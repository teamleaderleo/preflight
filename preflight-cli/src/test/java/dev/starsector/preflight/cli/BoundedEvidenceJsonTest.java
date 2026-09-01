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
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedEvidenceJsonTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void exactEncodedByteLimitIsInclusive() throws Exception {
        byte[] bytes = "{\"value\":1}".getBytes(StandardCharsets.UTF_8);

        Map<String, Object> result = BoundedEvidenceJson.readObject(
                new java.io.ByteArrayInputStream(bytes), bytes.length, "exact.json", "Evidence JSON");

        assertEquals(1L, result.get("value"));
    }

    @Test
    void initialSizePrefilterRejectsAnAlreadyOversizedPath() throws Exception {
        byte[] bytes = "{\"value\":1}".getBytes(StandardCharsets.UTF_8);
        Path file = temporaryDirectory.resolve("oversized.json");
        Files.write(file, bytes);

        IOException error = assertThrows(
                IOException.class,
                () -> BoundedEvidenceJson.readObject(file, bytes.length - 1L, "Evidence JSON"));

        assertTrue(error.getMessage().contains("exceeds"), error.getMessage());
    }

    @Test
    void pathReaderRefusesASymbolicLink() throws Exception {
        Path target = temporaryDirectory.resolve("target.json");
        Files.writeString(target, "{\"value\":1}");
        Path link = temporaryDirectory.resolve("linked.json");
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (IOException | UnsupportedOperationException error) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable: " + error.getMessage());
        }

        IOException error = assertThrows(
                IOException.class,
                () -> BoundedEvidenceJson.readObject(link, 1024, "Evidence JSON"));

        assertTrue(error.getMessage().contains("not a regular file"), error.getMessage());
    }

    @Test
    void growthDuringTheActualStreamReadIsRejected() throws Exception {
        byte[] bytes = "{\"value\":1}".getBytes(StandardCharsets.UTF_8);
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
                    () -> BoundedEvidenceJson.readObject(
                            growing, bytes.length, file.toString(), "Evidence JSON"));
            assertTrue(appended[0]);
            assertTrue(error.getMessage().contains("exceeds"), error.getMessage());
        }
        assertEquals(bytes.length + 1L, Files.size(file));
    }

    @Test
    void malformedUtf8IsRejectedBeforeJsonParsing() {
        byte[] bytes = {'{', '"', 'x', '"', ':', '"', (byte) 0x80, '"', '}'};

        IOException error = assertThrows(
                IOException.class,
                () -> BoundedEvidenceJson.readObject(
                        new java.io.ByteArrayInputStream(bytes), bytes.length, "bad.json", "Evidence JSON"));

        assertTrue(error.getMessage().contains("UTF-8"), error.getMessage());
    }
}
