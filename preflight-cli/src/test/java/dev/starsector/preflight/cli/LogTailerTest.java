package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogTailerTest {

    @TempDir
    Path tempDir;

    @Test
    void tailFileReturnsEmptyWhenNullOrMissing() {
        LogTailer.TailResult resultNull = LogTailer.tailFile(null, 1024, 1024);
        assertNotNull(resultNull);
        assertTrue(resultNull.lines().isEmpty());
        assertFalse(resultNull.problems().isEmpty());

        LogTailer.TailResult resultMissing = LogTailer.tailFile(tempDir.resolve("nonexistent.log"), 1024, 1024);
        assertNotNull(resultMissing);
        assertTrue(resultMissing.lines().isEmpty());
        assertFalse(resultMissing.problems().isEmpty());
    }

    @Test
    void tailFileBoundedMemoryOnHugeLog() throws IOException {
        Path log = tempDir.resolve("huge.log");
        byte[] lineBytes = "123456 [Thread-1] INFO com.fs.starfarer.loading.SpecStore - Loaded weapon spec line padding\n"
                .getBytes(StandardCharsets.UTF_8);

        // Write ~2 MiB
        try (var out = Files.newOutputStream(log, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            for (int i = 0; i < 20000; i++) {
                out.write(lineBytes);
            }
            out.write("FATAL ERROR: Crash at end of log\n".getBytes(StandardCharsets.UTF_8));
        }

        int maxMemory = 8 * 1024; // 8 KiB budget
        LogTailer.TailResult result = LogTailer.tailFile(log, 512 * 1024, maxMemory);

        assertNotNull(result);
        assertFalse(result.lines().isEmpty());
        assertTrue(result.truncated());
        assertTrue(result.lines().get(result.lines().size() - 1).contains("FATAL ERROR: Crash at end of log"));

        int totalRetainedBytes = result.lines().stream().mapToInt(s -> s.length() * 2).sum();
        assertTrue(totalRetainedBytes <= maxMemory + 1024, "Retained lines must be within memory budget");
    }

    @Test
    void streamLinesHandlesMalformedUtf8Replacement() throws IOException {
        Path log = tempDir.resolve("malformed.log");
        // Write invalid UTF-8 bytes (e.g. 0xFF, 0xFE)
        byte[] raw = new byte[] {
                'H', 'e', 'l', 'l', 'o', ' ', (byte) 0xFF, (byte) 0xFE, ' ', 'W', 'o', 'r', 'l', 'd', '\n'
        };
        Files.write(log, raw);

        List<String> lines = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        long read = LogTailer.streamLines(log, 0, raw.length, lines::add, problems);

        assertEquals(raw.length, read);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("Hello"));
        assertTrue(lines.get(0).contains("World"));
        assertTrue(problems.isEmpty());
    }

    @Test
    void discoverLogCandidatesFindsHsErrAndRotatedLogs() throws IOException {
        Path installRoot = tempDir.resolve("Starsector");
        Path logsDir = installRoot.resolve("logs");
        Files.createDirectories(logsDir);

        Path hsErr = installRoot.resolve("hs_err_pid1234.log");
        Files.writeString(hsErr, "# JVM fatal crash");

        Path mainLog = logsDir.resolve("starsector.log");
        Files.writeString(mainLog, "Main log");

        Path rotated1 = logsDir.resolve("starsector.log.1");
        Files.writeString(rotated1, "Rotated log 1");

        Path rotated2 = logsDir.resolve("starsector.log.2");
        Files.writeString(rotated2, "Rotated log 2");

        Path runDir = tempDir.resolve("runs/run-01");
        Files.createDirectories(runDir);
        Path console = runDir.resolve("console.txt");
        Files.writeString(console, "Console output");

        List<Path> candidates = LogTailer.discoverLogCandidates(installRoot, runDir);

        assertNotNull(candidates);
        assertTrue(candidates.contains(hsErr));
        assertTrue(candidates.contains(console));
        assertTrue(candidates.contains(mainLog));
        assertTrue(candidates.contains(rotated1));
        assertTrue(candidates.contains(rotated2));
    }

    @Test
    void boundedTailCollectorEvictsWhenCapacityExceeded() {
        LogTailer.BoundedTailCollector collector = new LogTailer.BoundedTailCollector(200, 5);
        for (int i = 1; i <= 10; i++) {
            collector.accept("Line " + i);
        }

        assertTrue(collector.isEvicted());
        List<String> retained = collector.getLines();
        assertTrue(retained.size() <= 5);
        assertEquals("Line 10", retained.get(retained.size() - 1));
    }
}
