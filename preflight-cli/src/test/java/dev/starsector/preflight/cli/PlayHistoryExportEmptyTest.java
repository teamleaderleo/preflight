package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayHistoryExportEmptyTest {
    @TempDir
    Path temp;

    @Test
    void emptyHistoryExportsAValidZeroSummary() throws Exception {
        PreflightHome home = new PreflightHome(temp.resolve("home"), List.of());
        Path output = temp.resolve("empty.json");

        PlayHistoryExport.export(home, output, null);

        Map<String, Object> document = StrictJson.object(Files.readString(output));
        assertEquals(List.of(), document.get("events"));
        Map<?, ?> summary = (Map<?, ?>) document.get("summary");
        assertEquals(0L, ((Number) summary.get("totalMillis")).longValue());
        assertEquals(0L, ((Number) summary.get("recordedEvents")).longValue());
    }
}
