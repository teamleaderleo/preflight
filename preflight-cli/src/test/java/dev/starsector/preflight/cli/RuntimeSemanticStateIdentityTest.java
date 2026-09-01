package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void acceptsAnInteractiveMenuTimeOnlyAfterStartupReadiness() throws Exception {
        Instant startedAt = Instant.parse("2026-08-16T12:00:00Z");
        Instant readyAt = startedAt.plusSeconds(15);
        Path valid = write(42L, startedAt, "main-menu-interactive", 2L,
                readyAt, readyAt.plusSeconds(3), readyAt.plusSeconds(4));
        assertTrue(RuntimeSemanticStateIdentity.read(valid).is("main-menu-interactive"));

        Path advanced = write(42L, startedAt, "simulation-ready", 3L,
                readyAt, readyAt.plusSeconds(3), readyAt.plusSeconds(4));
        RuntimeSemanticStateIdentity advancedState = RuntimeSemanticStateIdentity.read(advanced);
        assertTrue(advancedState.is("simulation-ready"));
        assertTrue(advancedState.reached("main-menu-ready"));
        assertTrue(!advancedState.reached("main-menu-interactive"));

        Path impossible = write(42L, startedAt, "main-menu-interactive", 2L,
                readyAt, readyAt.minusMillis(1), readyAt.plusSeconds(4));
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeSemanticStateIdentity.read(impossible));
    }

    @Test
    void ignoresBoundedExtensionsButRejectsUnknownStates() throws Exception {
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
        assertTrue(RuntimeSemanticStateIdentity.read(unknown, target).is("starting"));
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
        return write(pid, startedAt, state, sequence, mainMenuReadyAt, null, observedAt);
    }

    private Path write(
            long pid,
            Instant startedAt,
            String state,
            long sequence,
            Instant mainMenuReadyAt,
            Instant mainMenuInteractiveAt,
            Instant observedAt) throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("format", "starsector-preflight-runtime-state-v1");
        values.put("pid", pid);
        values.put("processStartedAt", startedAt);
        if (mainMenuReadyAt != null) values.put("mainMenuReadyAt", mainMenuReadyAt);
        if (mainMenuInteractiveAt != null) {
            values.put("mainMenuInteractiveAt", mainMenuInteractiveAt);
        }
        values.put("state", state);
        values.put("sequence", sequence);
        values.put("observedAt", observedAt);
        Path file = temporaryDirectory.resolve("state-" + System.nanoTime() + ".json");
        Files.writeString(file, Json.object(values));
        return file;
    }
}
