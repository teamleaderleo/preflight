package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LaunchLedgerBackfillBoundedMetadataTest {
    private static final int MAX_RUN_METADATA_BYTES = 1024 * 1024;

    @TempDir
    Path temporaryDirectory;

    @Test
    void exactEncodedByteLimitIsInclusive() throws Exception {
        Path run = Files.createDirectories(temporaryDirectory.resolve("exact"));
        byte[] bytes = exactLimitJson();
        Files.write(run.resolve("run.json"), bytes);

        Map<String, Object> metadata = LaunchLedgerBackfill.metadata(run);

        assertNotNull(metadata);
        assertEquals(MAX_RUN_METADATA_BYTES, bytes.length);
        assertEquals(
                MAX_RUN_METADATA_BYTES
                        - "{\"value\":\"".getBytes(StandardCharsets.UTF_8).length
                        - "\"}".getBytes(StandardCharsets.UTF_8).length,
                ((String) metadata.get("value")).length());
    }

    @Test
    void initiallyOversizedMetadataIsRefusedByTheCheapPrefilter() throws Exception {
        Path run = Files.createDirectories(temporaryDirectory.resolve("oversized"));
        Path file = run.resolve("run.json");
        try (FileChannel channel = FileChannel.open(
                file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(MAX_RUN_METADATA_BYTES);
            channel.write(ByteBuffer.wrap(new byte[] {0}));
        }

        assertEquals(MAX_RUN_METADATA_BYTES + 1L, Files.size(file));
        assertNull(LaunchLedgerBackfill.metadata(run));
    }

    @Test
    void growthDuringTheOpenedReadIsRejectedAtLimitPlusOne() throws Exception {
        Path file = temporaryDirectory.resolve("growing-run.json");
        byte[] bytes = exactLimitJson();
        Files.write(file, bytes);
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
            assertNull(LaunchLedgerBackfill.metadata(growing, file.toString()));
        }

        assertTrue(appended[0]);
        assertEquals(MAX_RUN_METADATA_BYTES + 1L, Files.size(file));
    }

    @Test
    void malformedUtf8RemainsUnavailableHistory() {
        byte[] bytes = {'{', '"', 'x', '"', ':', '"', (byte) 0x80, '"', '}'};

        assertNull(LaunchLedgerBackfill.metadata(
                new java.io.ByteArrayInputStream(bytes), "malformed-utf8-run.json"));
    }

    @Test
    void malformedJsonRemainsUnavailableHistory() {
        byte[] bytes = "{".getBytes(StandardCharsets.UTF_8);

        assertNull(LaunchLedgerBackfill.metadata(
                new java.io.ByteArrayInputStream(bytes), "malformed-run.json"));
    }

    @Test
    void ordinaryMetadataStillParses() throws Exception {
        Path run = Files.createDirectories(temporaryDirectory.resolve("ordinary"));
        Files.writeString(
                run.resolve("run.json"),
                "{\"started\":\"2026-08-20T10:00:00Z\",\"outcome\":\"COMPLETED\"}",
                StandardCharsets.UTF_8);

        Map<String, Object> metadata = LaunchLedgerBackfill.metadata(run);

        assertNotNull(metadata);
        assertEquals("2026-08-20T10:00:00Z", metadata.get("started"));
        assertEquals("COMPLETED", metadata.get("outcome"));
    }

    private static byte[] exactLimitJson() {
        byte[] prefix = "{\"value\":\"".getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\"}".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[MAX_RUN_METADATA_BYTES];
        System.arraycopy(prefix, 0, bytes, 0, prefix.length);
        Arrays.fill(bytes, prefix.length, bytes.length - suffix.length, (byte) 'a');
        System.arraycopy(suffix, 0, bytes, bytes.length - suffix.length, suffix.length);
        return bytes;
    }
}
