package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
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

final class DesktopSmokeEvidenceBoundedReadTest {
    private static final int MAX_DRIVER_RESULT_BYTES = 1024 * 1024;

    @TempDir
    Path temporaryDirectory;

    @Test
    void exactEncodedByteLimitIsInclusive() throws Exception {
        byte[] bytes = exactLimitJson();

        Map<String, Object> result = DesktopSmokeEvidence.readResult(
                new ByteArrayInputStream(bytes), "exact.json");

        String value = (String) result.get("value");
        assertEquals(MAX_DRIVER_RESULT_BYTES, bytes.length);
        assertEquals(MAX_DRIVER_RESULT_BYTES
                - "{\"value\":\"".getBytes(StandardCharsets.UTF_8).length
                - "\"}".getBytes(StandardCharsets.UTF_8).length,
                value.length());
    }

    @Test
    void initiallyOversizedDriverResultIsRefusedBeforeParsing() throws Exception {
        Path file = temporaryDirectory.resolve("oversized.json");
        try (FileChannel channel = FileChannel.open(
                file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(MAX_DRIVER_RESULT_BYTES);
            channel.write(ByteBuffer.wrap(new byte[] {0}));
        }
        assertEquals(MAX_DRIVER_RESULT_BYTES + 1L, Files.size(file));

        IOException error = assertThrows(
                IOException.class,
                () -> DesktopSmokeEvidence.readResult(file));

        assertTrue(error.getMessage().contains("Desktop smoke driver result exceeds"), error.getMessage());
    }

    @Test
    void growthDuringTheOpenedReadIsRejectedAtLimitPlusOne() throws Exception {
        Path file = temporaryDirectory.resolve("growing.json");
        Files.write(file, exactLimitJson());
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
                    () -> DesktopSmokeEvidence.readResult(growing, file.toString()));
            assertTrue(appended[0]);
            assertTrue(error.getMessage().contains("exceeds"), error.getMessage());
        }

        assertEquals(MAX_DRIVER_RESULT_BYTES + 1L, Files.size(file));
    }

    @Test
    void finalComponentSymlinkReplacementIsRejectedAtActualOpen() throws Exception {
        Path file = temporaryDirectory.resolve("reviewed.json");
        Path replacement = temporaryDirectory.resolve("replacement.json");
        Files.writeString(file, "{\"value\":1}", StandardCharsets.UTF_8);
        Files.writeString(replacement, "{\"value\":2}", StandardCharsets.UTF_8);

        Path probe = temporaryDirectory.resolve("symlink-probe");
        try {
            Files.createSymbolicLink(probe, replacement.getFileName());
        } catch (UnsupportedOperationException | IOException unavailable) {
            assumeTrue(false, "Symbolic links are unavailable: " + unavailable.getMessage());
        }
        Files.delete(probe);

        assertThrows(
                IOException.class,
                () -> DesktopSmokeEvidence.readResult(file, path -> {
                    Files.delete(path);
                    Files.createSymbolicLink(path, replacement.getFileName());
                }));
        assertTrue(Files.isSymbolicLink(file));
    }

    @Test
    void malformedUtf8IsRejectedBeforeJsonParsing() {
        byte[] bytes = {'{', '"', 'x', '"', ':', '"', (byte) 0x80, '"', '}'};

        IOException error = assertThrows(
                IOException.class,
                () -> DesktopSmokeEvidence.readResult(
                        new ByteArrayInputStream(bytes), "malformed-utf8.json"));

        assertTrue(error.getMessage().contains("UTF-8"), error.getMessage());
    }

    @Test
    void malformedJsonKeepsTheExistingHardParseFailure() {
        byte[] bytes = "{".getBytes(StandardCharsets.UTF_8);

        assertThrows(
                RuntimeException.class,
                () -> DesktopSmokeEvidence.readResult(
                        new ByteArrayInputStream(bytes), "malformed.json"));
    }

    private static byte[] exactLimitJson() {
        byte[] prefix = "{\"value\":\"".getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\"}".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[MAX_DRIVER_RESULT_BYTES];
        System.arraycopy(prefix, 0, bytes, 0, prefix.length);
        Arrays.fill(bytes, prefix.length, bytes.length - suffix.length, (byte) 'a');
        System.arraycopy(suffix, 0, bytes, bytes.length - suffix.length, suffix.length);
        return bytes;
    }
}
