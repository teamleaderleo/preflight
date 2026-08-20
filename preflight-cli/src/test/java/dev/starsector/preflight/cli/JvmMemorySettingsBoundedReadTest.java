package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JvmMemorySettingsBoundedReadTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void exactConfiguredReadLimitIsInclusive() throws Exception {
        byte[] bytes = "-Xms512m -Xmx1024m\n".getBytes(StandardCharsets.UTF_8);
        Path file = temporaryDirectory.resolve("exact.vmparams");
        Files.write(file, bytes);

        String text = JvmMemorySettings.readBoundedSettingsText(file, bytes.length);

        assertEquals(new String(bytes, StandardCharsets.UTF_8), text);
    }

    @Test
    void initiallyOversizedPathIsRejectedByTheCheapPrefilter() throws Exception {
        byte[] bytes = "-Xms512m -Xmx1024m\n".getBytes(StandardCharsets.UTF_8);
        Path file = temporaryDirectory.resolve("oversized.vmparams");
        Files.write(file, bytes);

        IOException error = assertThrows(
                IOException.class,
                () -> JvmMemorySettings.readBoundedSettingsText(file, bytes.length - 1));

        assertTrue(error.getMessage().contains("byte safety limit"), error.getMessage());
    }

    @Test
    void actualOpenedStreamRejectsGrowthPastTheLimit() throws Exception {
        byte[] bytes = "-Xmx1024m\n".getBytes(StandardCharsets.UTF_8);
        Path file = temporaryDirectory.resolve("growing.vmparams");
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
                    () -> JvmMemorySettings.readBoundedSettingsBytes(
                            growing, bytes.length, file.toString()));
            assertTrue(appended[0]);
            assertTrue(error.getMessage().contains("byte safety limit"), error.getMessage());
        }

        assertEquals(bytes.length + 1L, Files.size(file));
    }

    @Test
    void finalComponentReplacementWithSymlinkIsRejectedAtActualOpen() throws Exception {
        byte[] reviewed = "-Xmx1024m\n".getBytes(StandardCharsets.UTF_8);
        Path file = temporaryDirectory.resolve("reviewed.vmparams");
        Path replacement = temporaryDirectory.resolve("replacement.vmparams");
        Files.write(file, reviewed);
        Files.writeString(replacement, "-Xmx8192m\n", StandardCharsets.UTF_8);

        Path probe = temporaryDirectory.resolve("symlink-probe");
        try {
            Files.createSymbolicLink(probe, replacement.getFileName());
        } catch (UnsupportedOperationException | IOException unavailable) {
            assumeTrue(false, "Symbolic links are unavailable: " + unavailable.getMessage());
        }
        Files.delete(probe);

        assertThrows(
                IOException.class,
                () -> JvmMemorySettings.readBoundedSettingsText(file, reviewed.length, path -> {
                    Files.delete(path);
                    Files.createSymbolicLink(path, replacement.getFileName());
                }));
        assertTrue(Files.isSymbolicLink(file));
    }

    @Test
    void malformedUtf8IsRejectedInsteadOfReplacementDecoded() throws Exception {
        Path file = temporaryDirectory.resolve("malformed.vmparams");
        Files.write(file, new byte[] {(byte) 0x80});

        IOException error = assertThrows(
                IOException.class,
                () -> JvmMemorySettings.readBoundedSettingsText(file, 1));

        assertTrue(error.getMessage().contains("UTF-8"), error.getMessage());
    }
}
