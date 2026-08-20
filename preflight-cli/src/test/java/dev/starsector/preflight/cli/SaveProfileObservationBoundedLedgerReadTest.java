package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.starsector.preflight.core.Json;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SaveProfileObservationBoundedLedgerReadTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void exactEncodedByteLimitIsInclusive() throws Exception {
        byte[] bytes = exactLimitJson(256);
        Path file = temporaryDirectory.resolve("exact.json");
        Files.write(file, bytes);

        Map<String, Object> root = SaveProfileObservation.readLedgerRoot(
                file, bytes.length, ignored -> {});

        assertNotNull(root);
        assertEquals(100_663_296L, SaveProfileObservation.MAX_LEDGER_BYTES);
        assertEquals(
                bytes.length
                        - "{\"value\":\"".getBytes(StandardCharsets.UTF_8).length
                        - "\"}".getBytes(StandardCharsets.UTF_8).length,
                ((String) root.get("value")).length());
    }

    @Test
    void initiallyOversizedLedgerIsUnavailableBeforeOpen() throws Exception {
        byte[] bytes = "{\"value\":1}".getBytes(StandardCharsets.UTF_8);
        Path file = temporaryDirectory.resolve("oversized.json");
        Files.write(file, bytes);
        AtomicBoolean opened = new AtomicBoolean();

        Map<String, Object> root = SaveProfileObservation.readLedgerRoot(
                file,
                bytes.length - 1L,
                ignored -> opened.set(true));

        assertNull(root);
        assertFalse(opened.get());
    }

    @Test
    void growthAfterTheFirstConsumedByteIsUnavailableAtLimitPlusOne() throws Exception {
        byte[] bytes = exactLimitJson(128);
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
                    () -> SaveProfileObservation.readLedgerText(
                            growing, bytes.length, file.toString()));
            assertTrue(error.getMessage().contains("exceeds " + bytes.length), error.getMessage());
        }

        assertTrue(appended[0]);
        assertEquals(bytes.length + 1L, Files.size(file));
    }

    @Test
    void malformedUtf8RemainsUnavailableHistory() throws Exception {
        Path file = temporaryDirectory.resolve("malformed-utf8.json");
        Files.write(file, new byte[] {'{', '"', 'x', '"', ':', '"', (byte) 0x80, '"', '}'});

        assertNull(SaveProfileObservation.readLedgerRoot(file, 64, ignored -> {}));
    }

    @Test
    void malformedJsonRemainsUnavailableHistory() throws Exception {
        Path file = temporaryDirectory.resolve("malformed-json.json");
        Files.writeString(file, "{");

        assertNull(SaveProfileObservation.readLedgerRoot(file, 64, ignored -> {}));
    }

    @Test
    void genuineReadIoFailureStillPropagates() {
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("synthetic read failure");
            }
        };

        IOException error = assertThrows(
                IOException.class,
                () -> SaveProfileObservation.readLedgerText(failing, 64, "synthetic"));

        assertEquals("synthetic read failure", error.getMessage());
    }

    @Test
    void finalComponentSymlinkReplacementIsRejectedAtActualOpen() throws Exception {
        Path reviewed = temporaryDirectory.resolve("reviewed.json");
        Path replacement = temporaryDirectory.resolve("replacement.json");
        Files.writeString(reviewed, "{\"value\":1}");
        Files.writeString(replacement, "{\"value\":2}");

        Path probe = temporaryDirectory.resolve("symlink-probe");
        try {
            Files.createSymbolicLink(probe, replacement.getFileName());
        } catch (UnsupportedOperationException | IOException unavailable) {
            assumeTrue(false, "Symbolic links are unavailable: " + unavailable.getMessage());
        }
        Files.delete(probe);

        assertThrows(
                IOException.class,
                () -> SaveProfileObservation.readLedgerRoot(reviewed, 64, path -> {
                    Files.delete(path);
                    Files.createSymbolicLink(path, replacement.getFileName());
                }));
        assertTrue(Files.isSymbolicLink(reviewed));
    }

    @Test
    void ordinaryCanonicalLedgerStillProducesObservations() throws Exception {
        PreflightHome home = new PreflightHome(temporaryDirectory.resolve("home"), List.of());
        Path store = SaveProfileObservation.storeFile(home);
        Files.createDirectories(store.getParent());

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("installationKey", "install");
        record.put("saveKey", "manual");
        record.put("saveName", "Manual");
        record.put("stateToken", "token");
        record.put("observedAt", "2026-08-20T00:00:00Z");
        record.put("missingSince", null);
        record.put("profileFingerprint", "profile");
        record.put("profileDisplayName", "Profile");
        record.put("gameBuild", "build");

        Files.writeString(store, Json.object(Map.of(
                "format", SaveProfileObservation.FORMAT,
                "records", List.of(record))));

        List<SaveProfileObservation.Observation> observations = SaveProfileObservation.observations(home);

        assertEquals(1, observations.size());
        assertEquals("manual", observations.get(0).saveKey());
        assertEquals("profile", observations.get(0).profileFingerprint());
    }

    private static byte[] exactLimitJson(int bytes) {
        byte[] prefix = "{\"value\":\"".getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\"}".getBytes(StandardCharsets.UTF_8);
        if (bytes < prefix.length + suffix.length) {
            throw new IllegalArgumentException("Exact-limit fixture is too small");
        }
        byte[] result = new byte[bytes];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        Arrays.fill(result, prefix.length, result.length - suffix.length, (byte) 'a');
        System.arraycopy(suffix, 0, result, result.length - suffix.length, suffix.length);
        return result;
    }
}
