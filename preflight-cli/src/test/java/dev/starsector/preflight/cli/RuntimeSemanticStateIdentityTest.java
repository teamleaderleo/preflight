package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RuntimeSemanticStateIdentityTest {
    private static final int MAX_BYTES = 64 * 1024;

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsOnlyStateFromTheExactTargetProcessLifetime() throws Exception {
        ProcessHandle process = ProcessHandle.current();
        Instant startedAt = process.info().startInstant().orElseThrow();
        DesktopSmokeDriver.ProcessTarget target =
                new DesktopSmokeDriver.ProcessTarget(process.pid(), startedAt);
        Path file = write(process.pid(), startedAt, "campaign-ready", 2L);

        RuntimeSemanticStateIdentity state = RuntimeSemanticStateIdentity.read(file, target);

        assertTrue(state.is("campaign-ready"));
        assertEquals(2L, state.sequence());
        assertNull(state.startupMillis());
        assertThrows(IllegalArgumentException.class, () -> RuntimeSemanticStateIdentity.read(
                file, new DesktopSmokeDriver.ProcessTarget(process.pid(), Instant.EPOCH)));
    }

    @Test
    void reportsOnlyAValidatedProcessToMainMenuDuration() throws Exception {
        Instant startedAt = Instant.parse("2026-08-16T12:00:00Z");
        Path file = write(42L, startedAt, "stopped", 4L,
                Instant.parse("2026-08-16T12:00:15.250Z"),
                Instant.parse("2026-08-16T14:00:00Z"));

        assertEquals(15_250L, RuntimeSemanticStateIdentity.read(file).startupMillis());
    }

    @Test
    void rejectsUnknownFieldsAndStates() throws Exception {
        ProcessHandle process = ProcessHandle.current();
        Instant startedAt = process.info().startInstant().orElseThrow();
        DesktopSmokeDriver.ProcessTarget target =
                new DesktopSmokeDriver.ProcessTarget(process.pid(), startedAt);
        Path unknownState = write(process.pid(), startedAt, "inventory-ready", 1L);
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeSemanticStateIdentity.read(unknownState, target));

        String unknownField = Files.readString(write(process.pid(), startedAt, "starting", 0L))
                .replace("{", "{\"windowTitle\":\"Starsector\",");
        Path unknown = temporaryDirectory.resolve("unknown.json");
        Files.writeString(unknown, unknownField);
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeSemanticStateIdentity.read(unknown, target));
    }

    @Test
    void exactEncodedByteLimitIsAccepted() throws Exception {
        Path file = write(42L, Instant.parse("2026-08-16T12:00:00Z"), "starting", 0L);
        padToSize(file, MAX_BYTES);

        RuntimeSemanticStateIdentity state = RuntimeSemanticStateIdentity.read(file);

        assertEquals(MAX_BYTES, Files.size(file));
        assertTrue(state.is("starting"));
    }

    @Test
    void initiallyOversizedStateIsRejected() throws Exception {
        Path file = write(42L, Instant.parse("2026-08-16T12:00:00Z"), "starting", 0L);
        padToSize(file, MAX_BYTES + 1);

        java.io.IOException error = assertThrows(
                java.io.IOException.class,
                () -> RuntimeSemanticStateIdentity.read(file));

        assertTrue(error.getMessage().contains("exceeds " + MAX_BYTES + " bytes"), error.getMessage());
    }

    @Test
    void malformedUtf8RemainsAReadFailure() throws Exception {
        Path file = temporaryDirectory.resolve("malformed-utf8.json");
        Files.write(file, new byte[] {(byte) 0x80});

        java.io.IOException error = assertThrows(
                java.io.IOException.class,
                () -> RuntimeSemanticStateIdentity.read(file));

        assertTrue(error.getMessage().contains("UTF-8"), error.getMessage());
    }

    @Test
    void malformedJsonRemainsASchemaParseFailure() throws Exception {
        Path file = temporaryDirectory.resolve("malformed-json.json");
        Files.writeString(file, "{");

        assertThrows(IllegalArgumentException.class, () -> RuntimeSemanticStateIdentity.read(file));
    }

    private Path write(long pid, Instant startedAt, String state, long sequence) throws Exception {
        return write(pid, startedAt, state, sequence, null, Instant.now());
    }

    private Path write(
            long pid,
            Instant startedAt,
            String state,
            long sequence,
            Instant mainMenuReadyAt,
            Instant observedAt) throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("format", "starsector-preflight-runtime-state-v1");
        values.put("pid", pid);
        values.put("processStartedAt", startedAt);
        if (mainMenuReadyAt != null) values.put("mainMenuReadyAt", mainMenuReadyAt);
        values.put("state", state);
        values.put("sequence", sequence);
        values.put("observedAt", observedAt);
        Path file = temporaryDirectory.resolve("state-" + System.nanoTime() + ".json");
        Files.writeString(file, Json.object(values));
        return file;
    }

    private static void padToSize(Path file, int size) throws Exception {
        long current = Files.size(file);
        if (current > size) {
            throw new IllegalArgumentException("Runtime-state fixture already exceeds requested size");
        }
        byte[] padding = new byte[Math.toIntExact(size - current)];
        Arrays.fill(padding, (byte) ' ');
        Files.write(file, padding, StandardOpenOption.APPEND);
    }
}
