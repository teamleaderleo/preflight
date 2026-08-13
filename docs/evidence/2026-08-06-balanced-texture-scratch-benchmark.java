package dev.starsector.preflight.core;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Fresh-process replay of trusted reads over the installed lossless-LZ4 texture corpus. */
final class BalancedTextureScratchBenchmark {
    public static void main(String[] args) throws Exception {
        Path blobs = Path.of(args[0]);
        int limit = args.length > 1 ? Integer.parseInt(args[1]) : Integer.MAX_VALUE;
        List<Path> files;
        try (var paths = Files.walk(blobs)) {
            files = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith("-identity-lz4.spft"))
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(limit)
                    .toList();
        }
        long fileBytes = 0;
        long pixelBytes = 0;
        long checksum = 1;
        long started = System.nanoTime();
        for (Path file : files) {
            PreparedTexture texture = PreparedTextureIO.readTrusted(file);
            ByteBuffer pixels = texture.pixelsView();
            fileBytes += Files.size(file);
            pixelBytes += pixels.remaining();
            checksum = checksum * 31 + texture.sourceSha256().hashCode();
            checksum = checksum * 31 + texture.uploadWidth();
            checksum = checksum * 31 + texture.uploadHeight();
            checksum = checksum * 31 + texture.channels();
            if (pixels.hasRemaining()) {
                checksum = checksum * 31 + pixels.get(0);
                checksum = checksum * 31 + pixels.get(pixels.remaining() / 2);
                checksum = checksum * 31 + pixels.get(pixels.remaining() - 1);
            }
        }
        double millis = (System.nanoTime() - started) / 1e6;
        System.out.printf(
                "files=%d fileBytes=%d pixelBytes=%d millis=%.3f checksum=%d%n",
                files.size(), fileBytes, pixelBytes, millis, checksum);
    }
}
