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
 * JNI bridge to native riverfinder library (Gradle subproject :riverfinder → RiverFinderBase).
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
            if (tryLoadFromBundledResources()) {
                loaded = true;
                return;
            }

            Path base = Paths.get(System.getProperty("user.dir", "."));
            List<String> libNames = getNativeLibNamesForCurrentOs();
            Path[] candidates = {
                base.resolve("native"),
                base.resolve("build").resolve("libs").resolve("native"),
                // Gradle subproject / git submodule output (RiverFinderBase)
                base.resolve("RiverFinderBase").resolve("build").resolve("native"),
                base.resolve("build").resolve("generated").resolve("native-resources")
                    .resolve("native").resolve(getOsResourceFolder() != null ? getOsResourceFolder() : "windows"),
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
            throw new UnsatisfiedLinkError(
                "riverfinder native library not found. Build with: gradle :riverfinder:copyNativeArtifacts (or buildMainJar). "
                    + firstError.getMessage());
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
            names.add("libriverFinderLibJ.dylib");
            names.add("libriverfinder.dylib");
        } else {
            names.add("libriverFinderLibJ.so");
            names.add("libriverfinder.so");
        }
        return names;
    }

    /**
     * River / cave search at biome sample height {@code y}.
     * @param caveFactor cave area weight for ranking score = river + cave * caveFactor
     * @return [x1, z1, riverArea1, caveArea1, ...] or null
     */
    public static native int[] riverSearch(long seed, int startX, int startZ,
        int width, int height, int y, int minArea, float preserveRange, int numThreads,
        float caveFactor);

    /**
     * Progress query. Returns {@code [current, total, phase, status, tryPause, tryStop]} or {@code null} if idle.
     */
    public static native int[] getSearchProgress();

    /**
     * Current results: [x, z, riverArea, caveArea, ...] sorted by score, or null.
     */
    public static native int[] getNowResult();

    public static native boolean pause();

    public static native boolean resume();

    public static native boolean stop();

    private RiverFinderBridge() {}
}
