package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RuntimeSemanticStateIdentityTest {
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
        assertThrows(IllegalArgumentException.class, () -> RuntimeSemanticStateIdentity.read(
                file, new DesktopSmokeDriver.ProcessTarget(process.pid(), Instant.EPOCH)));
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

    private Path write(long pid, Instant startedAt, String state, long sequence) throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("format", "starsector-preflight-runtime-state-v1");
        values.put("pid", pid);
        values.put("processStartedAt", startedAt);
        values.put("state", state);
        values.put("sequence", sequence);
        values.put("observedAt", Instant.now());
        Path file = temporaryDirectory.resolve("state-" + System.nanoTime() + ".json");
        Files.writeString(file, Json.object(values));
        return file;
    }
}
