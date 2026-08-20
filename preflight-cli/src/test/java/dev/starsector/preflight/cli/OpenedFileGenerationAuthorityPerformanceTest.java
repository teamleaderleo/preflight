package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Hashes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenedFileGenerationAuthorityPerformanceTest {
    private static final int FILES = 256;
    private static final int BYTES_PER_FILE = 16 * 1024;

    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsGenerationMetadataSeparatelyFromPayloadHashing() throws Exception {
        byte[] payload = new byte[BYTES_PER_FILE];
        for (int index = 0; index < payload.length; index++) {
            payload[index] = (byte) (index * 31 + 7);
        }
        List<Path> files = new ArrayList<>(FILES);
        for (int index = 0; index < FILES; index++) {
            files.add(Files.write(temporaryDirectory.resolve("source-" + index + ".bin"), payload));
        }

        // Warm native/JNA linkage and filesystem metadata caches outside all measurements.
        OpenedFileGenerationAuthority.Generation warm =
                OpenedFileGenerationAuthority.capture(files.get(0));
        OpenedFileGenerationAuthority.requireCurrent(files.get(0), warm);
        Hashes.sha256(files.get(0));

        List<OpenedFileGenerationAuthority.Generation> generations = new ArrayList<>(FILES);
        long captureStarted = System.nanoTime();
        for (Path file : files) {
            generations.add(OpenedFileGenerationAuthority.capture(file));
        }
        long captureNanos = System.nanoTime() - captureStarted;

        long recheckStarted = System.nanoTime();
        for (int index = 0; index < files.size(); index++) {
            OpenedFileGenerationAuthority.requireCurrent(files.get(index), generations.get(index));
        }
        long recheckNanos = System.nanoTime() - recheckStarted;

        long hashStarted = System.nanoTime();
        for (Path file : files) {
            Hashes.sha256(file);
        }
        long hashNanos = System.nanoTime() - hashStarted;

        double captureMillis = captureNanos / 1_000_000.0;
        double recheckMillis = recheckNanos / 1_000_000.0;
        double hashMillis = hashNanos / 1_000_000.0;
        double captureMicrosPerFile = captureNanos / 1_000.0 / FILES;
        double recheckMicrosPerFile = recheckNanos / 1_000.0 / FILES;
        double hashMicrosPerFile = hashNanos / 1_000.0 / FILES;
        double hashMegabytesPerSecond = (FILES * (double) BYTES_PER_FILE / (1024.0 * 1024.0))
                / (hashNanos / 1_000_000_000.0);
        System.out.printf(
                java.util.Locale.ROOT,
                "RC832_GENERATION_EVIDENCE files=%d bytesPerFile=%d generationCaptureMs=%.3f generationCaptureMicrosPerFile=%.3f generationRecheckMs=%.3f generationRecheckMicrosPerFile=%.3f payloadHashMs=%.3f payloadHashMicrosPerFile=%.3f payloadHashMiBps=%.3f%n",
                FILES,
                BYTES_PER_FILE,
                captureMillis,
                captureMicrosPerFile,
                recheckMillis,
                recheckMicrosPerFile,
                hashMillis,
                hashMicrosPerFile,
                hashMegabytesPerSecond);
    }
}
