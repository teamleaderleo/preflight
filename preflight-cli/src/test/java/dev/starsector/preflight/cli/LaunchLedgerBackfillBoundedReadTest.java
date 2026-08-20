package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LaunchLedgerBackfillBoundedReadTest {
    @TempDir
    Path root;

    @Test
    void exactConfiguredReadLimitIsInclusiveForValidMetadata() throws Exception {
        String json = "{\"started\":\"2026-08-20T00:00:00Z\",\"outcome\":\"COMPLETED\"}";
        byte[] encoded = json.getBytes(StandardCharsets.UTF_8);
        Path file = root.resolve("exact-run.json");
        Files.write(file, encoded);

        String actual = LaunchLedgerBackfill.readBoundedMetadataText(file, encoded.length);

        assertEquals(json, actual);
        Map<String, Object> parsed = StrictJson.object(actual);
        assertEquals("2026-08-20T00:00:00Z", parsed.get("started"));
    }

    @Test
    void initiallyOversizedMetadataIsRejectedByTheCheapPrefilter() throws Exception {
        byte[] encoded = "{\"started\":\"2026-08-20T00:00:00Z\"}".getBytes(StandardCharsets.UTF_8);
        Path file = root.resolve("oversized-run.json");
        Files.write(file, encoded);

        IOException error = assertThrows(
                IOException.class,
                () -> LaunchLedgerBackfill.readBoundedMetadataText(file, encoded.length - 1));

        assertTrue(error.getMessage().contains("byte safety limit"), error.getMessage());
    }

    @Test
    void actualOpenedStreamRejectsGrowthPastTheLimit() throws Exception {
        byte[] encoded = "{\"started\":\"2026-08-20T00:00:00Z\"}".getBytes(StandardCharsets.UTF_8);
        Path file = root.resolve("growing-run.json");
        Files.write(file, encoded);
        boolean[] appended = {false};

        try (InputStream raw = Files.newInputStream(file);
             InputStream growing = new FilterInputStream(raw) {
                 @Override
                 public int read(byte[] buffer, int offset, int length) throws IOException {
                     int requested = appended[0] ? length : Math.min(1, length);
                     int read = super.read(buffer, offset, requested);
                     if (!appended[0] && read > 0) {
                         Files.write(file, new byte[] {' '}, StandardOpenOption.APPEND);
                         appended[0] = true;
                     }
                     return read;
                 }
             }) {
            IOException error = assertThrows(
                    IOException.class,
                    () -> LaunchLedgerBackfill.readBoundedMetadataBytes(
                            growing, encoded.length, file.toString()));
            assertTrue(appended[0]);
            assertTrue(error.getMessage().contains("byte safety limit"), error.getMessage());
        }

        assertEquals(encoded.length + 1L, Files.size(file));
    }

    @Test
    void malformedUtf8AndJsonCostOnlyTheirHistoricalRows() throws Exception {
        PreflightHome home = new PreflightHome(root.resolve("home"), List.of());
        Path runs = Files.createDirectories(home.runs());
        writeValidRun(runs.resolve("valid"));

        Path badUtf8 = Files.createDirectories(runs.resolve("bad-utf8"));
        Files.write(badUtf8.resolve("run.json"), new byte[] {(byte) 0x80});

        Path badJson = Files.createDirectories(runs.resolve("bad-json"));
        Files.writeString(badJson.resolve("run.json"), "{not json", StandardCharsets.UTF_8);

        assertEquals(1, LaunchLedgerBackfill.runOnce(home));
        List<LaunchLedger.Entry> entries = LaunchLedger.read(home);
        assertEquals(1, entries.size());
        assertEquals("valid", entries.get(0).runDirectory());
        assertEquals(60_000L, entries.get(0).elapsedMillis());
        assertEquals("COMPLETED", entries.get(0).outcome());
    }

    private static void writeValidRun(Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("run.json"),
                "{\"started\":\"2026-08-20T00:00:00Z\","
                        + "\"ended\":\"2026-08-20T00:01:00Z\","
                        + "\"outcome\":\"COMPLETED\","
                        + "\"exitCode\":0,"
                        + "\"disabledOptimizationDomains\":[]}",
                StandardCharsets.UTF_8);
    }
}
