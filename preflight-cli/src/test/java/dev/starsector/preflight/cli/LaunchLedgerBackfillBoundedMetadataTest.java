package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LaunchLedgerBackfillBoundedMetadataTest {
    @TempDir
    Path root;

    @Test
    void exactEncodedByteLimitIsAcceptedForValidMetadata() throws Exception {
        byte[] bytes = "{\"started\":\"2026-08-20T00:00:00Z\"}"
                .getBytes(StandardCharsets.UTF_8);
        Path file = root.resolve("run.json");
        Files.write(file, bytes);

        Map<String, Object> values = LaunchLedgerBackfill.readMetadataFile(
                file, bytes.length, input -> input);

        assertNotNull(values);
        assertEquals("2026-08-20T00:00:00Z", values.get("started"));
    }

    @Test
    void initiallyOversizedMetadataIsRefusedBeforeOpen() throws Exception {
        byte[] bytes = "{\"started\":\"2026-08-20T00:00:00Z\"}"
                .getBytes(StandardCharsets.UTF_8);
        Path file = root.resolve("oversized.json");
        Files.write(file, bytes);
        AtomicBoolean opened = new AtomicBoolean();

        Map<String, Object> values = LaunchLedgerBackfill.readMetadataFile(
                file,
                bytes.length - 1,
                input -> {
                    opened.set(true);
                    return input;
                });

        assertNull(values);
        assertFalse(opened.get());
    }

    @Test
    void actualOpenedReadRejectsGrowthPastTheLimit() throws Exception {
        byte[] bytes = "{\"started\":\"2026-08-20T00:00:00Z\"}"
                .getBytes(StandardCharsets.UTF_8);
        Path file = root.resolve("growing.json");
        Files.write(file, bytes);
        AtomicBoolean appended = new AtomicBoolean();

        Map<String, Object> values = LaunchLedgerBackfill.readMetadataFile(
                file,
                bytes.length,
                raw -> new FilterInputStream(raw) {
                    @Override
                    public int read(byte[] buffer, int offset, int length) throws IOException {
                        int requested = appended.get() ? length : Math.min(1, length);
                        int read = super.read(buffer, offset, requested);
                        if (!appended.get() && read > 0) {
                            Files.write(file, new byte[] {' '}, StandardOpenOption.APPEND);
                            appended.set(true);
                        }
                        return read;
                    }
                });

        assertNull(values);
        assertTrue(appended.get());
        assertEquals(bytes.length + 1L, Files.size(file));
    }

    @Test
    void malformedUtf8AndJsonRemainSkippedMetadata() throws Exception {
        Path file = root.resolve("malformed.json");
        Files.write(file, new byte[] {(byte) 0x80});

        assertNull(LaunchLedgerBackfill.readMetadataFile(file, 1, input -> input));

        byte[] malformedJson = "{not json".getBytes(StandardCharsets.UTF_8);
        Files.write(file, malformedJson, StandardOpenOption.TRUNCATE_EXISTING);
        assertNull(LaunchLedgerBackfill.readMetadataFile(
                file, malformedJson.length, input -> input));
    }

    @Test
    void ordinaryValidHistoricalBackfillStillImports() throws Exception {
        PreflightHome home = new PreflightHome(root.resolve("home"), List.of());
        Path directory = Files.createDirectories(home.runs().resolve("past"));
        Files.writeString(
                directory.resolve("run.json"),
                "{\"started\":\"2026-08-19T00:00:00Z\","
                        + "\"ended\":\"2026-08-19T00:01:00Z\","
                        + "\"exitCode\":0,\"outcome\":\"COMPLETED\"}",
                StandardCharsets.UTF_8);

        assertEquals(1, LaunchLedgerBackfill.runOnce(home));
        assertEquals(1, LaunchLedger.read(home).size());
    }
}
