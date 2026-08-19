package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayHistoryExportTest {
    @TempDir
    Path temp;

    @Test
    void jsonIsCanonicalPortableAndOmitsRunDirectories() throws Exception {
        PreflightHome home = home();
        record(home, launch("later", "2026-08-02T10:00:00Z", 30_000L, "COMPLETED", "profile-b", "/Users/alice/private/run-b"));
        record(home, launch("earlier", "2026-08-01T10:00:00Z", 60_000L, "COMPLETED", "profile-a", "C:\\Users\\alice\\private\\run-a"));
        record(home, launch("unknown-end", "2026-08-03T10:00:00Z", null, "INTERRUPTED", null, "/private/run-c"));

        Path first = temp.resolve("first.json");
        Path second = temp.resolve("second.json");
        PlayHistoryExport.export(home, first, null);
        PlayHistoryExport.export(home, second, null);

        String text = Files.readString(first);
        assertEquals(text, Files.readString(second), "the same history should export byte-identically");
        assertFalse(text.contains("/Users/alice"));
        assertFalse(text.contains("C:\\\\Users"));
        assertFalse(text.contains("runDirectory"));

        Map<String, Object> document = StrictJson.object(text);
        assertEquals(PlayHistoryExport.FORMAT, document.get("format"));
        List<?> events = (List<?>) document.get("events");
        assertEquals(3, events.size());
        assertEquals("earlier", ((Map<?, ?>) events.get(0)).get("launchId"));
        assertEquals("later", ((Map<?, ?>) events.get(1)).get("launchId"));
        assertEquals("observed", ((Map<?, ?>) events.get(2)).get("provenance"));
        assertEquals(null, ((Map<?, ?>) events.get(2)).get("elapsedMillis"));

        Map<?, ?> summary = (Map<?, ?>) document.get("summary");
        assertEquals(90_000L, ((Number) summary.get("totalMillis")).longValue());
        assertEquals(2L, ((Number) summary.get("countedSessions")).longValue());
        assertEquals(1L, ((Number) summary.get("sessionsWithoutDuration")).longValue());
    }

    @Test
    void csvNeutralizesSpreadsheetPrefixesAndKeepsPrivatePathsOut() throws Exception {
        PreflightHome home = home();
        record(home, launch(
                "=HYPERLINK(\"https://example.invalid\")",
                "2026-08-01T10:00:00Z",
                60_000L,
                "COMPLETED",
                "@profile",
                "/Users/alice/private/run"));

        Path json = temp.resolve("history.json");
        Path csv = temp.resolve("history.csv");
        PlayHistoryExport.export(home, json, csv);

        String text = Files.readString(csv);
        assertTrue(text.startsWith("launchId,started,elapsedMillis,outcome,profileFingerprint,provenance\n"));
        assertTrue(text.contains("\"'=HYPERLINK(\"\"https://example.invalid\"\")\""));
        assertTrue(text.contains("\"'@profile\""));
        assertFalse(text.contains("/Users/alice"));
    }

    @Test
    void existingOutputIsNeverOverwritten() throws Exception {
        PreflightHome home = home();
        record(home, launch("one", "2026-08-01T10:00:00Z", 1_000L, "COMPLETED", null, "private"));
        Path output = temp.resolve("history.json");
        Files.writeString(output, "keep me");

        assertThrows(FileAlreadyExistsException.class, () -> PlayHistoryExport.export(home, output, null));
        assertEquals("keep me", Files.readString(output));
    }

    @Test
    void jsonAndCsvCannotAliasTheSameOutput() {
        PreflightHome home = home();
        Path output = temp.resolve("history.out");

        assertThrows(IllegalArgumentException.class, () -> PlayHistoryExport.export(home, output, output));
    }

    private PreflightHome home() {
        return new PreflightHome(temp.resolve("preflight-home"), List.of());
    }

    private static void record(PreflightHome home, LaunchLedger.Entry entry) {
        assertEquals(null, LaunchLedger.record(home, entry));
    }

    private static LaunchLedger.Entry launch(
            String launchId,
            String started,
            Long elapsedMillis,
            String outcome,
            String profileFingerprint,
            String runDirectory) {
        return new LaunchLedger.Entry(
                launchId,
                Instant.parse(started),
                elapsedMillis,
                outcome,
                0,
                false,
                "recommended",
                List.of(),
                runDirectory,
                profileFingerprint);
    }
}
