import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Separates the two things that make up a read: the fixed cost of touching a file at all, and the
 * per-byte cost once it is open. A texture load benchmark that scales one measured figure by size
 * conflates them, and gets the answer badly wrong for a cache made of few large files.
 */
public final class StorageProbe {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        Files.createDirectories(dir);

        // Per-file overhead: many tiny files, so time is dominated by open/stat/close.
        int smallCount = 400;
        int smallSize = 4096;
        Path smallDir = dir.resolve("small");
        Files.createDirectories(smallDir);
        byte[] smallData = new byte[smallSize];
        new Random(7).nextBytes(smallData);
        Path[] smalls = new Path[smallCount];
        for (int i = 0; i < smallCount; i++) {
            smalls[i] = smallDir.resolve("f" + i + ".bin");
            Files.write(smalls[i], smallData);
        }
        for (int warm = 0; warm < 2; warm++) {
            for (Path p : smalls) {
                Files.readAllBytes(p);
            }
        }
        long t0 = System.nanoTime();
        long smallBytes = 0;
        for (Path p : smalls) {
            smallBytes += Files.readAllBytes(p).length;
        }
        long smallNanos = System.nanoTime() - t0;

        // Sequential bandwidth: one large file, where per-file cost is negligible.
        int largeSize = 256 * 1024 * 1024;
        Path large = dir.resolve("large.bin");
        byte[] largeData = new byte[largeSize];
        new Random(11).nextBytes(largeData);
        Files.write(large, largeData);
        Files.readAllBytes(large);
        long t1 = System.nanoTime();
        byte[] readBack = Files.readAllBytes(large);
        long largeNanos = System.nanoTime() - t1;

        double perFileMicros = smallNanos / 1000.0 / smallCount;
        double seqGbPerSec = readBack.length / (double) largeNanos;
        System.out.printf("per-file overhead   %.1f us  (%d files x %d B in %.1f ms)%n",
                perFileMicros, smallCount, smallSize, smallNanos / 1e6);
        System.out.printf("sequential read     %.2f GB/s (%d MiB in %.1f ms)%n",
                seqGbPerSec, largeSize / 1048576, largeNanos / 1e6);
        System.out.printf("small-file rate     %.2f GB/s%n", smallBytes / (double) smallNanos);
    }
}
