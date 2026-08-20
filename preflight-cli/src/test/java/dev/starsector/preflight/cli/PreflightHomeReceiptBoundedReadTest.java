package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreflightHomeReceiptBoundedReadTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void exactReadLimitIsInclusive() throws Exception {
        byte[] bytes = receiptBytes();

        Map<String, Object> receipt = PreflightHome.readIntegrationReceipt(
                new ByteArrayInputStream(bytes), bytes.length, "exact-limit-integrations.json");

        assertTrue(receipt.get("integrations") instanceof java.util.List<?>);
    }

    @Test
    void initialSizePrefilterRejectsBeforeTheBoundedRead() throws Exception {
        byte[] bytes = receiptBytes();
        Path file = temporaryDirectory.resolve("already-too-large.json");
        Files.write(file, bytes);

        IOException error = assertThrows(
                IOException.class,
                () -> PreflightHome.readIntegrationReceipt(file, bytes.length - 1));

        assertTrue(error.getMessage().contains("byte safety limit"), error.getMessage());
    }

    @Test
    void actualStreamReadRejectsGrowthPastTheLimit() throws Exception {
        byte[] bytes = receiptBytes();
        Path file = temporaryDirectory.resolve("growing.json");
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
            IOException error = assertThrows(
                    IOException.class,
                    () -> PreflightHome.readIntegrationReceipt(growing, bytes.length, file.toString()));
            assertTrue(appended[0]);
            assertTrue(error.getMessage().contains("byte safety limit"), error.getMessage());
        }
        assertEquals(bytes.length + 1L, Files.size(file));
    }

    @Test
    void malformedUtf8IsDiscardedBeforeJsonParsing() {
        byte[] malformed = {(byte) 0x80};

        IOException error = assertThrows(
                IOException.class,
                () -> PreflightHome.readIntegrationReceipt(
                        new ByteArrayInputStream(malformed), malformed.length, "malformed-integrations.json"));

        assertTrue(error.getMessage().contains("UTF-8"), error.getMessage());
    }

    @Test
    void resolveFallsBackToConventionalPathsForOversizedReceipt() throws Exception {
        Path root = temporaryDirectory.resolve(PreflightHome.DIRECTORY_NAME);
        Files.createDirectories(root);
        Files.write(root.resolve("integrations.json"), new byte[512 * 1024 + 1]);

        PreflightHome home = PreflightHome.resolve(Platform.LINUX, temporaryDirectory, Map.of());

        assertEquals(
                temporaryDirectory.resolve(".local").resolve("bin").resolve("preflight"),
                home.pathOf(PreflightHome.Id.LINUX_COMMAND));
    }

    private static byte[] receiptBytes() {
        return ("{\"format\":\"preflight-integrations-v1\",\"integrations\":[]}\n")
                .getBytes(StandardCharsets.UTF_8);
    }
}
