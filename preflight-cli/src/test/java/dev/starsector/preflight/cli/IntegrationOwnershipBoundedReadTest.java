package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IntegrationOwnershipBoundedReadTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void exactEncodedByteLimitIsInclusive() throws Exception {
        byte[] bytes = "exact-boundary".getBytes(StandardCharsets.UTF_8);

        String text = IntegrationOwnership.readBounded(
                new ByteArrayInputStream(bytes), bytes.length, "exact");

        assertEquals("exact-boundary", text);
    }

    @Test
    void initiallyOversizedInputReturnsUnavailableBeforeOpen() throws Exception {
        Path file = temporaryDirectory.resolve("oversized.sh");
        Files.writeString(file, "abc", StandardCharsets.UTF_8);
        AtomicBoolean opened = new AtomicBoolean();

        String text = IntegrationOwnership.readBounded(
                file, 2, ignored -> opened.set(true));

        assertEquals("", text);
        assertFalse(opened.get());
    }

    @Test
    void growthAfterFirstConsumedByteIsRefusedAtLimitPlusOne() throws Exception {
        Path file = temporaryDirectory.resolve("growing.sh");
        byte[] bytes = "launcher".getBytes(StandardCharsets.UTF_8);
        Files.write(file, bytes);
        AtomicBoolean appended = new AtomicBoolean();

        String text;
        try (InputStream raw = Files.newInputStream(file);
                InputStream growing = new FilterInputStream(raw) {
                    @Override
                    public int read(byte[] buffer, int offset, int length) throws IOException {
                        int requested = appended.get() ? length : Math.min(1, length);
                        int read = super.read(buffer, offset, requested);
                        if (!appended.get() && read > 0) {
                            Files.write(file, new byte[] {'!'}, StandardOpenOption.APPEND);
                            appended.set(true);
                        }
                        return read;
                    }
                }) {
            text = IntegrationOwnership.readBounded(growing, bytes.length, file.toString());
        }

        assertEquals("", text);
        assertTrue(appended.get());
        assertEquals(bytes.length + 1L, Files.size(file));
    }

    @Test
    void finalComponentSymlinkReplacementIsRejectedAtActualOpen() throws Exception {
        Path reviewed = temporaryDirectory.resolve("reviewed.sh");
        Path replacement = temporaryDirectory.resolve("replacement.sh");
        Files.writeString(reviewed, "reviewed", StandardCharsets.UTF_8);
        Files.writeString(replacement, "replacement", StandardCharsets.UTF_8);

        Path probe = temporaryDirectory.resolve("symlink-probe");
        try {
            Files.createSymbolicLink(probe, replacement.getFileName());
        } catch (UnsupportedOperationException | IOException unavailable) {
            assumeTrue(false, "Symbolic links are unavailable: " + unavailable.getMessage());
        }
        Files.delete(probe);

        assertThrows(
                IOException.class,
                () -> IntegrationOwnership.readBounded(reviewed, 64, path -> {
                    Files.delete(path);
                    Files.createSymbolicLink(path, replacement.getFileName());
                }));
        assertTrue(Files.isSymbolicLink(reviewed));
    }

    @Test
    void malformedUtf8RemainsUnreadable() {
        IOException error = assertThrows(
                IOException.class,
                () -> IntegrationOwnership.readBounded(
                        new ByteArrayInputStream(new byte[] {(byte) 0x80}), 1, "malformed"));

        assertTrue(error.getMessage().contains("UTF-8"), error.getMessage());
    }

    @Test
    void ordinaryLauncherMarkerSemanticsStayUnchanged() throws Exception {
        String script = "#!/bin/sh\n"
                + IntegrationOwnership.POSIX_MARKER
                + "\nexec java -jar preflight.jar run --fast --game /game \"$@\"\n";
        Path file = temporaryDirectory.resolve("preflight");
        Files.writeString(file, script, StandardCharsets.UTF_8);

        assertTrue(IntegrationOwnership.isOwnedLinuxCommand(file));
    }
}
