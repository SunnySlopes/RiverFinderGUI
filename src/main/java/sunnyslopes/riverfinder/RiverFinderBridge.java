package sunnyslopes.riverfinder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * JNI bridge to native riverfinder library.
 * Single-phase river search at y=-60, river + dripstone_caves count toward area.
 */
public final class RiverFinderBridge {

    private static boolean loaded = false;

    static {
        loadNativeLibrary();
    }

    private static void loadNativeLibrary() {
        if (loaded) return;
        try {
            System.loadLibrary("libriverFinderLibJ");
            loaded = true;
            return;
        } catch (UnsatisfiedLinkError e) {
            UnsatisfiedLinkError firstError = e;
            // Try loading from native library packaged inside the jar.
            if (tryLoadFromBundledResources()) {
                loaded = true;
                return;
            }

            // Fallback: try build output directory
            Path base = Paths.get(System.getProperty("user.dir", "."));
            List<String> libNames = getNativeLibNamesForCurrentOs();
            Path[] candidates = {
                base.resolve("native"),
                base.resolve("native").resolve("riverfinder").resolve("build"),
                base.resolve("build").resolve("libs").resolve("native"),
            };
            for (Path dir : candidates) {
                for (String libName : libNames) {
                    File f = dir.resolve(libName).toFile();
                    if (f.exists()) {
                        System.load(f.getAbsolutePath());
                        loaded = true;
                        return;
                    }
                }
            }
            throw new UnsatisfiedLinkError("riverfinder native library not found. Build with: gradle buildNative. " + firstError.getMessage());
        }
    }

    private static boolean tryLoadFromBundledResources() {
        String osFolder = getOsResourceFolder();
        if (osFolder == null) return false;

        for (String libName : getNativeLibNamesForCurrentOs()) {
            String resourcePath = "/native/" + osFolder + "/" + libName;
            if (tryLoadSingleBundledResource(resourcePath, libName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean tryLoadSingleBundledResource(String resourcePath, String libName) {
        try (InputStream is = RiverFinderBridge.class.getResourceAsStream(resourcePath)) {
            if (is == null) return false;
            Path tempDir = Files.createTempDirectory("riverfinder-native-");
            Path tempLib = tempDir.resolve(libName);
            Files.copy(is, tempLib, StandardCopyOption.REPLACE_EXISTING);
            tempLib.toFile().deleteOnExit();
            tempDir.toFile().deleteOnExit();
            System.load(tempLib.toAbsolutePath().toString());
            return true;
        } catch (IOException | UnsatisfiedLinkError ex) {
            return false;
        }
    }

    private static String getOsResourceFolder() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("mac")) return "macos";
        if (os.contains("nix") || os.contains("nux") || os.contains("aix") || os.contains("linux")) return "linux";
        return null;
    }

    private static List<String> getNativeLibNamesForCurrentOs() {
        String os = System.getProperty("os.name", "").toLowerCase();
        List<String> names = new ArrayList<>();
        if (os.contains("win")) {
            names.add("libriverFinderLibJ.dll");
            names.add("riverFinderLibJ.dll");
        } else if (os.contains("mac")) {
            names.add("libriverfinder.dylib");
            names.add("libriverFinderLibJ.dylib");
        } else {
            names.add("libriverfinder.so");
            names.add("libriverFinderLibJ.so");
        }
        return names;
    }

    /**
     * Single-phase river search.
     * @param seed world seed
     * @param startX region start X
     * @param startZ region start Z
     * @param width region width
     * @param height region height
     * @param y search height (use -60)
     * @param minArea minimum river area
     * @param preserveRange only data greater than the best value * preserveRange coefficient can be preserved.
     * @param numThreads thread count ({@code <= 0} uses hardware concurrency in native)
     * @return [x1, z1, area1, x2, z2, area2, ...] or null
     */
    public static native int[] riverSearch(long seed, int startX, int startZ,
        int width, int height, int y, int minArea, float preserveRange, int numThreads);


    /**
     * Progress query. Returns {@code [current, total, phase, status, tryPause, tryStop]} or {@code null} if idle.
     * {@code phase}: 1 = phase-1 scan, 2 = refinement, -1 = finishing.
     * {@code status}: phase 1: 0 = chunks active, 1 = no chunk in flight; phase 2: 1 = refining, 2 = refinement step done.
     * {@code tryPause} / {@code tryStop}: 1 if native pause/stop was requested (async; use with {@code status} for settled pause).
     */
    public static native int[] getSearchProgress();


    /**
     * return now results.
     * Reserved: returns [x1, z1, area1,x2...] (sorted), or null if not implemented.
     */
    public static native int[] getNowResult();

    /**
     * Try pause task.
     */
    public static native boolean pause();

    /**
     * Try resume task.
     */
    public static native boolean resume();

    /**
     *Try to stop the task.
     */
    public static native boolean stop();



    private RiverFinderBridge() {}
}
