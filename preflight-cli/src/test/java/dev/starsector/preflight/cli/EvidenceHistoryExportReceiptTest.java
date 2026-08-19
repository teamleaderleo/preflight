package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvidenceHistoryExportReceiptTest {
    @Test
    void jsonReceiptIsMachineReadableAndNamesBothOutputs() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PlayHistoryExport.Result result = new PlayHistoryExport.Result(
                Path.of("history.json"), Path.of("history.csv"), 12, 3_600_000L);

        assertEquals(0, EvidenceCommand.historyExported(
                result, true, new PrintStream(bytes, true, StandardCharsets.UTF_8)));

        Map<String, Object> receipt = StrictJson.object(bytes.toString(StandardCharsets.UTF_8));
        assertEquals("starsector-preflight-play-history-export-v1", receipt.get("format"));
        assertEquals(12L, ((Number) receipt.get("events")).longValue());
        assertEquals(3_600_000L, ((Number) receipt.get("totalMillis")).longValue());
        assertEquals("history.json", receipt.get("output"));
        assertEquals("history.csv", receipt.get("csv"));
    }

    @Test
    void humanReceiptStatesThatTheLedgerWasNotChanged() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PlayHistoryExport.Result result = new PlayHistoryExport.Result(
                Path.of("history.json"), null, 2, 90_000L);

        assertEquals(0, EvidenceCommand.historyExported(
                result, false, new PrintStream(bytes, true, StandardCharsets.UTF_8)));

        String text = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("2 events"));
        assertTrue(text.contains("1 minute"));
        assertTrue(text.contains("local launch ledger was not changed"));
    }
}
