import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Exact bundled-JVM comparison for LoadingUtils.super(InputStream). */
class LoadingUtilsReaderBenchmark {
    private static final String[] EXTENSIONS = {
        ".json", ".csv", ".ship", ".variant", ".wpn", ".faction", ".system", ".skin", ".proj"
    };
    private static long sink;

    public static void main(String[] args) throws Exception {
        Path root = Path.of("/Applications/Starsector.app");
        List<Path> files = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile).filter(LoadingUtilsReaderBenchmark::included)
                    .forEach(files::add);
        }
        Collections.sort(files);
        long bytes = 0;
        for (Path file : files) bytes += Files.size(file);
        for (Path file : files) Files.readAllBytes(file);

        for (Path file : files) {
            String expected = vanilla(Files.newInputStream(file));
            String actual = optimized(Files.newInputStream(file));
            if (!expected.equals(actual)) throw new AssertionError("mismatch: " + file);
        }

        double[] vanilla = new double[6];
        double[] optimized = new double[6];
        for (int round = 0; round < 6; round++) {
            if ((round & 1) == 0) {
                vanilla[round] = pass(files, false);
                optimized[round] = pass(files, true);
            } else {
                optimized[round] = pass(files, true);
                vanilla[round] = pass(files, false);
            }
        }
        Arrays.sort(vanilla);
        Arrays.sort(optimized);
        double oldMedian = (vanilla[2] + vanilla[3]) / 2;
        double newMedian = (optimized[2] + optimized[3]) / 2;
        System.out.printf("jvm=%s arch=%s files=%d bytes=%d equality=exact%n",
                System.getProperty("java.version"), System.getProperty("os.arch"),
                files.size(), bytes);
        System.out.printf("vanillaMedianMs=%.3f optimizedMedianMs=%.3f savedMs=%.3f speedup=%.2fx sink=%d%n",
                oldMedian, newMedian, oldMedian - newMedian, oldMedian / newMedian, sink);
    }

    private static double pass(List<Path> files, boolean fast) throws Exception {
        long started = System.nanoTime();
        for (Path file : files) {
            String value = fast ? optimized(Files.newInputStream(file))
                    : vanilla(Files.newInputStream(file));
            sink += value.length();
        }
        return (System.nanoTime() - started) / 1_000_000.0;
    }

    private static String vanilla(InputStream input) throws Exception {
        byte[] buffer = new byte[1_048_576];
        StringBuffer text = new StringBuffer();
        try {
            int count;
            while ((count = input.read(buffer)) != -1) {
                text.append(new String(buffer, 0, count, "UTF-8"));
            }
        } finally {
            input.close();
        }
        return text.toString().replaceAll("\\r", "");
    }

    private static String optimized(InputStream input) throws Exception {
        try {
            byte[] bytes = input.readAllBytes();
            int output = 0;
            for (byte value : bytes) if (value != '\r') bytes[output++] = value;
            return new String(bytes, 0, output, StandardCharsets.UTF_8);
        } finally {
            input.close();
        }
    }

    private static boolean included(Path path) {
        String value = path.toString();
        for (String extension : EXTENSIONS) if (value.endsWith(extension)) return true;
        return false;
    }
}
