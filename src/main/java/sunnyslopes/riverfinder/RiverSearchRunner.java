package sunnyslopes.riverfinder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * River search via JNI (RiverFinderBridge). Progress from {@link RiverFinderBridge#getSearchProgress()}.
 */
public class RiverSearchRunner {

    public static final int RING_INNER = 24;
    public static final int RING_OUTER = 128;
    /** Used by UI for search area validation. */
    public static final int PHASE1_GRID_STEP = 16;

    /** Exact number of blocks between the two AFK rings (inner radius 24, outer 128). */
    public static final int RIVER_AREA_BLOCKS = 49_640;

    /** Same block count as a double, for percentage calculations. */
    public static final double RIVER_AREA_FULL = RIVER_AREA_BLOCKS;

    /** Max search area (blocks). */
    public static final long MAX_SEARCH_AREA_BLOCKS = 30_000_000L * 30_000_000L;

    /** Matches native refinement threshold coefficient (opV). */
    public static final float DEFAULT_PRESERVE_RANGE = 0.9f;

    /** Bucket size for "one maximum per region" output filtering. */
    public static final int OUTPUT_REGION_SIZE = 64;

    private static final long PROGRESS_POLL_MS = 100L;

    /** Result stride: x, z, riverArea, caveArea. */
    public static final int RESULT_STRIDE = 4;

    static int combinedScore(int riverArea, int caveArea, float caveFactor) {
        return riverArea + Math.round(caveArea * caveFactor);
    }

    /**
     * Deduplicate identical (x,z), then keep one point with maximum combined score per OUTPUT_REGION_SIZE square.
     * Each entry: [x, z, riverArea, caveArea].
     */
    static List<int[]> filterResultsForOutput(int[] raw, float caveFactor) {
        if (raw == null || raw.length < RESULT_STRIDE) {
            return List.of();
        }
        Map<Long, int[]> byXZ = new HashMap<>();
        for (int i = 0; i + RESULT_STRIDE - 1 < raw.length; i += RESULT_STRIDE) {
            int x = raw[i], z = raw[i + 1], river = raw[i + 2], cave = raw[i + 3];
            long pk = packXZ(x, z);
            int[] quad = { x, z, river, cave };
            byXZ.merge(pk, quad, (a, b) ->
                combinedScore(a[2], a[3], caveFactor) >= combinedScore(b[2], b[3], caveFactor) ? a : b);
        }
        Map<Long, int[]> byCell = new HashMap<>();
        for (int[] h : byXZ.values()) {
            int bx = Math.floorDiv(h[0], OUTPUT_REGION_SIZE);
            int bz = Math.floorDiv(h[1], OUTPUT_REGION_SIZE);
            long ck = packXZ(bx, bz);
            byCell.merge(ck, h, (a, b) ->
                combinedScore(a[2], a[3], caveFactor) >= combinedScore(b[2], b[3], caveFactor) ? a : b);
        }
        List<int[]> out = new ArrayList<>(byCell.values());
        out.sort(Comparator.comparingInt((int[] t) -> combinedScore(t[2], t[3], caveFactor)).reversed());
        return out;
    }

    private static long packXZ(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /**
     * @param done when true, search has finished (success, stop, or error); UI should re-enable controls.
     * @param nativePauseSettled when {@code true}, native pause has taken effect (from {@link RiverFinderBridge#getSearchProgress()}).
     * @param nativeStopRequested native stop flag is set (search may still be winding down until {@code done}).
     */
    public record ProgressInfo(int phase, long processed, long total, double percentage, long elapsedMs, long remainingMs,
                               int currentSeedIndex, int totalSeeds, boolean done, boolean refiningResults,
                               boolean nativePauseSettled, boolean nativeStopRequested) {
        public static ProgressInfo of(int phase, long processed, long total, long elapsedMs, long remainingMs) {
            return of(phase, processed, total, elapsedMs, remainingMs, false, false, false);
        }

        /** @param refiningResults native phase 2 (post-scan refinement); UI may show non-numeric message. */
        public static ProgressInfo of(int phase, long processed, long total, long elapsedMs, long remainingMs,
                                      boolean refiningResults) {
            return of(phase, processed, total, elapsedMs, remainingMs, refiningResults, false, false);
        }

        public static ProgressInfo of(int phase, long processed, long total, long elapsedMs, long remainingMs,
                                      boolean refiningResults, boolean nativePauseSettled, boolean nativeStopRequested) {
            double pct = total > 0 ? (processed * 100.0 / total) : 0;
            return new ProgressInfo(phase, processed, total, pct, elapsedMs, remainingMs, 0, 0, false, refiningResults,
                nativePauseSettled, nativeStopRequested);
        }

        /** Final callback after native {@code riverSearch} returns. */
        public static ProgressInfo terminal(long elapsedMs, long processed, long total) {
            long t = Math.max(1, total);
            long p = Math.min(processed, t);
            double pct = t > 0 ? (p * 100.0 / t) : 100.0;
            return new ProgressInfo(0, p, t, pct, elapsedMs, 0, 0, 0, true, false, false, false);
        }
    }

    /**
     * Whether native code has finished winding down into pause (async pause). Uses {@link RiverFinderBridge#getSearchProgress()}.
     */
    public static boolean isNativePauseSettled(int[] progress) {
        if (progress == null || progress.length < 4) {
            return false;
        }
        boolean tryPause = progress.length > 4 && progress[4] != 0;
        if (!tryPause) {
            return false;
        }
        int phase = progress[2];
        int status = progress[3];
        if (phase == 1) {
            return status == 1;
        }
        if (phase == 2) {
            return true;
        }
        return true;
    }

    private volatile boolean isRunning = false;
    private volatile boolean isPaused = false;
    private volatile long runStartTimeMs = 0;
    private volatile long totalPausedMs = 0;
    private volatile long pauseStartMs = 0;

    public boolean isRunning() { return isRunning; }
    public boolean isPaused() { return isPaused; }

    public void pause() {
        if (!isPaused) {
            isPaused = true;
            pauseStartMs = System.currentTimeMillis();
            try {
                RiverFinderBridge.pause();
            } catch (Throwable ignored) {
            }
        }
    }

    public void resume() {
        if (isPaused) {
            if (pauseStartMs > 0) {
                totalPausedMs += System.currentTimeMillis() - pauseStartMs;
                pauseStartMs = 0;
            }
            isPaused = false;
            try {
                RiverFinderBridge.resume();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Requests native stop; {@link #isRunning()} stays true until {@code riverSearch} returns on the search thread. */
    public void stop() {
        try {
            RiverFinderBridge.stop();
        } catch (Throwable ignored) {
        }
    }

    private long getElapsedMs() {
        long base = System.currentTimeMillis() - runStartTimeMs;
        long paused = totalPausedMs;
        if (pauseStartMs > 0) {
            paused += System.currentTimeMillis() - pauseStartMs;
        }
        return Math.max(0, base - paused);
    }

    /** Start river search for one seed. Runs in background thread. */
    public void startRiverSearch(long seed, int mc, int minX, int maxX, int minZ, int maxZ, int minArea,
                                 int threadCount, int biomeY, float caveFactor,
                                 Consumer<ProgressInfo> progressCallback, Consumer<String> resultCallback) {
        if (isRunning) return;
        Thread t = new Thread(() -> runRiverSearch(seed, mc, minX, maxX, minZ, maxZ, minArea, threadCount,
            biomeY, caveFactor, progressCallback, resultCallback),
            "riverfinder-search");
        t.setDaemon(true);
        t.start();
    }

    /** Run river search for one seed on the current thread (for list search). */
    public void runRiverSearchBlocking(long seed, int mc, int minX, int maxX, int minZ, int maxZ, int minArea,
                                       int threadCount, int biomeY, float caveFactor,
                                       Consumer<ProgressInfo> progressCallback, Consumer<String> resultCallback) {
        if (isRunning) return;
        runRiverSearch(seed, mc, minX, maxX, minZ, maxZ, minArea, threadCount, biomeY, caveFactor,
            progressCallback, resultCallback);
    }

    private void runRiverSearch(long seed, int mc, int minX, int maxX, int minZ, int maxZ, int minArea,
                               int threadCount, int biomeY, float caveFactor,
                               Consumer<ProgressInfo> progressCallback, Consumer<String> resultCallback) {
        isRunning = true;
        isPaused = false;
        totalPausedMs = 0;
        pauseStartMs = 0;
        runStartTimeMs = System.currentTimeMillis();
        int threads = Math.max(1, Math.min(threadCount, Runtime.getRuntime().availableProcessors()));

        long searchWidth = (long) (maxX - minX) + 1;
        long searchHeight = (long) (maxZ - minZ) + 1;
        long searchArea = searchWidth * searchHeight;
        if (searchArea <= 0 || searchArea > MAX_SEARCH_AREA_BLOCKS) {
            isRunning = false;
            if (resultCallback != null && searchArea > MAX_SEARCH_AREA_BLOCKS) {
                resultCallback.accept("[Error] Search area too large (" + searchArea + " blocks). Must not exceed "
                    + MAX_SEARCH_AREA_BLOCKS + " (400M blocks).");
            }
            if (progressCallback != null) {
                progressCallback.accept(ProgressInfo.terminal(getElapsedMs(), 0, 1));
            }
            return;
        }

        int startX = minX - RING_OUTER;
        int startZ = minZ - RING_OUTER;
        int width = (maxX - minX + 1) + 2 * RING_OUTER;
        int height = (maxZ - minZ + 1) + 2 * RING_OUTER;

        AtomicInteger phase1TotalMax = new AtomicInteger(0);
        AtomicLong lastProcessed = new AtomicLong(0);
        AtomicLong lastTotal = new AtomicLong(1);

        ScheduledExecutorService progressScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "riverfinder-progress");
            t.setDaemon(true);
            return t;
        });

        if (progressCallback != null) {
            progressCallback.accept(ProgressInfo.of(0, 0, 1, getElapsedMs(), 0));
        }

        progressScheduler.scheduleAtFixedRate(() -> {
            if (progressCallback == null) {
                return;
            }
            try {
                int[] arr = RiverFinderBridge.getSearchProgress();
                if (arr == null || arr.length < 4) {
                    if (!isRunning) {
                        return;
                    }
                    return;
                }
                boolean tryStop = arr.length > 5 && arr[5] != 0;
                boolean pauseSettled = isNativePauseSettled(arr);

                if (!isRunning) {
                    return;
                }
                int cur = arr[0];
                int tot = arr[1];
                int nPhase = arr[2];

                long processed;
                long total;
                if (nPhase == 1) {
                    phase1TotalMax.set(Math.max(phase1TotalMax.get(), tot));
                    processed = cur;
                    total = Math.max(tot, 1L);
                } else if (nPhase == 2) {
                    int p1 = phase1TotalMax.get();
                    if (p1 <= 0) {
                        processed = cur;
                        total = Math.max(tot, 1L);
                    } else {
                        processed = (long) p1 + cur;
                        total = (long) p1 + Math.max(tot, 1);
                    }
                } else {
                    processed = cur;
                    total = Math.max(tot, 1L);
                }

                lastProcessed.set(processed);
                lastTotal.set(total);

                long elapsed = getElapsedMs();
                long remaining = 0L;
                if (!isPaused && total > processed && processed > 0) {
                    remaining = elapsed * (total - processed) / processed;
                }
                boolean refining = (nPhase == 2);
                progressCallback.accept(ProgressInfo.of(0, processed, total, elapsed, remaining, refining, pauseSettled, tryStop));
            } catch (Throwable ignored) {
            }
        }, 0, PROGRESS_POLL_MS, TimeUnit.MILLISECONDS);

        try {
            int[] raw = RiverFinderBridge.riverSearchEx(seed, mc, startX, startZ, width, height, biomeY, minArea,
                DEFAULT_PRESERVE_RANGE, threads, caveFactor);

            if (raw != null && resultCallback != null) {
                int fullAreaInt = RIVER_AREA_BLOCKS;
                StringBuilder sb = new StringBuilder();
                for (int[] quad : filterResultsForOutput(raw, caveFactor)) {
                    int x = quad[0], z = quad[1], river = quad[2], cave = quad[3];
                    int score = combinedScore(river, cave, caveFactor);
                    double ratioPercent = score / RIVER_AREA_FULL * 100.0;
                    sb.append(String.format("/tp %d 64 %d s=%d/%d = %.2f%%", x, z, score, fullAreaInt, ratioPercent))
                        .append('\n');
                }
                if (sb.length() > 0) {
                    resultCallback.accept(sb.toString());
                }
            }
        } catch (UnsatisfiedLinkError e) {
            if (resultCallback != null) {
                resultCallback.accept("[Error] Native library not loaded. Build with: gradle buildNative. " + e.getMessage());
            }
        } finally {
            progressScheduler.shutdownNow();
            try {
                progressScheduler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            isRunning = false;
            if (pauseStartMs > 0) {
                totalPausedMs += System.currentTimeMillis() - pauseStartMs;
                pauseStartMs = 0;
            }
            isPaused = false;
            if (progressCallback != null) {
                long p = lastProcessed.get();
                long t = lastTotal.get();
                if (t <= 0) {
                    t = 1;
                }
                if (p < t) {
                    p = t;
                }
                progressCallback.accept(ProgressInfo.terminal(getElapsedMs(), p, t));
            }
        }
    }
}
