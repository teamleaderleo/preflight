package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        Path file = write(RuntimeSemanticStateIdentity.FORMAT_V2,
                process.pid(), startedAt, "campaign-ready", 2L,
                null, null, null, Instant.now());

        RuntimeSemanticStateIdentity state = RuntimeSemanticStateIdentity.read(file, target);

        assertTrue(state.is("campaign-ready"));
        assertTrue(state.usesUsableMenuTiming());
        assertEquals(2L, state.sequence());
        assertNull(state.startupMillis());
        assertThrows(IllegalArgumentException.class, () -> RuntimeSemanticStateIdentity.read(
                file, new DesktopSmokeDriver.ProcessTarget(process.pid(), Instant.EPOCH)));
    }

    @Test
    void reportsOnlyAValidatedProcessToMainMenuReadyDuration() throws Exception {
        Instant startedAt = Instant.parse("2026-08-16T12:00:00Z");
        Path file = write(RuntimeSemanticStateIdentity.FORMAT_V2,
                42L, startedAt, "stopped", 4L,
                Instant.parse("2026-08-16T12:00:15.250Z"), null, null,
                Instant.parse("2026-08-16T14:00:00Z"));

        assertEquals(15_250L, RuntimeSemanticStateIdentity.read(file).startupMillis());
    }

    @Test
    void v2ExposesFirstUsabilityAndTheLaterOverlayRemovalSeparately() throws Exception {
        Instant startedAt = Instant.parse("2026-08-16T12:00:00Z");
        Instant readyAt = startedAt.plusSeconds(15);
        Instant interactiveAt = readyAt.plusSeconds(1);
        Instant overlayRemovedAt = readyAt.plusSeconds(10);
        Path valid = write(RuntimeSemanticStateIdentity.FORMAT_V2,
                42L, startedAt, "main-menu-interactive", 2L,
                readyAt, interactiveAt, overlayRemovedAt, overlayRemovedAt.plusSeconds(1));

        RuntimeSemanticStateIdentity state = RuntimeSemanticStateIdentity.read(valid);
        assertTrue(state.is("main-menu-interactive"));
        assertTrue(state.usesUsableMenuTiming());
        assertEquals(interactiveAt, state.firstUsableMainMenuAt());
        assertNull(state.legacyV1MainMenuOverlayRemovedAt());
        assertEquals(overlayRemovedAt, state.mainMenuOverlayRemovedAt());

        Path advanced = write(RuntimeSemanticStateIdentity.FORMAT_V2,
                42L, startedAt, "simulation-ready", 3L,
                readyAt, interactiveAt, overlayRemovedAt, overlayRemovedAt.plusSeconds(1));
        RuntimeSemanticStateIdentity advancedState = RuntimeSemanticStateIdentity.read(advanced);
        assertTrue(advancedState.reached("main-menu-ready"));
        assertTrue(advancedState.reached("main-menu-interactive"));

        Path interactiveBeforeReady = write(RuntimeSemanticStateIdentity.FORMAT_V2,
                42L, startedAt, "main-menu-interactive", 2L,
                readyAt, readyAt.minusMillis(1), null, readyAt.plusSeconds(4));
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeSemanticStateIdentity.read(interactiveBeforeReady));

        Path overlayBeforeUsability = write(RuntimeSemanticStateIdentity.FORMAT_V2,
                42L, startedAt, "main-menu-interactive", 2L,
                readyAt, interactiveAt, readyAt.plusMillis(500), readyAt.plusSeconds(4));
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeSemanticStateIdentity.read(overlayBeforeUsability));
    }

    @Test
    void v1RemainsReadableAndItsOldInteractiveFieldIsClassifiedAsOverlayRemoval() throws Exception {
        Instant startedAt = Instant.parse("2026-08-16T12:00:00Z");
        Instant readyAt = startedAt.plusSeconds(15);
        Instant oldInteractiveField = readyAt.plusSeconds(10);
        Path legacy = write(RuntimeSemanticStateIdentity.FORMAT_V1,
                42L, startedAt, "main-menu-interactive", 2L,
                readyAt, oldInteractiveField, null, oldInteractiveField.plusSeconds(1));

        RuntimeSemanticStateIdentity state = RuntimeSemanticStateIdentity.read(legacy);

        assertEquals(RuntimeSemanticStateIdentity.FORMAT_V1, state.format());
        assertTrue(state.reached("main-menu-interactive"));
        assertFalse(state.usesUsableMenuTiming());
        assertNull(state.firstUsableMainMenuAt());
        assertEquals(oldInteractiveField, state.legacyV1MainMenuOverlayRemovedAt());
        assertNull(state.mainMenuOverlayRemovedAt());
    }

    @Test
    void ignoresBoundedExtensionsButRejectsUnknownFormatsAndStates() throws Exception {
        ProcessHandle process = ProcessHandle.current();
        Instant startedAt = process.info().startInstant().orElseThrow();
        DesktopSmokeDriver.ProcessTarget target =
                new DesktopSmokeDriver.ProcessTarget(process.pid(), startedAt);
        Path unknownState = write(RuntimeSemanticStateIdentity.FORMAT_V2,
                process.pid(), startedAt, "inventory-ready", 1L,
                null, null, null, Instant.now());
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeSemanticStateIdentity.read(unknownState, target));

        Path unsupported = write("starsector-preflight-runtime-state-v3",
                process.pid(), startedAt, "starting", 0L,
                null, null, null, Instant.now());
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeSemanticStateIdentity.read(unsupported, target));

        String unknownField = Files.readString(write(RuntimeSemanticStateIdentity.FORMAT_V2,
                process.pid(), startedAt, "starting", 0L,
                null, null, null, Instant.now()))
                .replace("{", "{\"windowTitle\":\"Starsector\",");
        Path unknown = temporaryDirectory.resolve("unknown.json");
        Files.writeString(unknown, unknownField);
        assertTrue(RuntimeSemanticStateIdentity.read(unknown, target).is("starting"));
    }

    private Path write(
            String format,
            long pid,
            Instant startedAt,
            String state,
            long sequence,
            Instant mainMenuReadyAt,
            Instant mainMenuInteractiveAt,
            Instant mainMenuOverlayRemovedAt,
            Instant observedAt) throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("format", format);
        values.put("pid", pid);
        values.put("processStartedAt", startedAt);
        if (mainMenuReadyAt != null) values.put("mainMenuReadyAt", mainMenuReadyAt);
        if (mainMenuInteractiveAt != null) values.put("mainMenuInteractiveAt", mainMenuInteractiveAt);
        if (RuntimeSemanticStateIdentity.FORMAT_V2.equals(format)
                && mainMenuOverlayRemovedAt != null) {
            values.put("mainMenuOverlayRemovedAt", mainMenuOverlayRemovedAt);
        }
        values.put("state", state);
        values.put("sequence", sequence);
        values.put("observedAt", observedAt);
        Path file = temporaryDirectory.resolve("state-" + System.nanoTime() + ".json");
        Files.writeString(file, Json.object(values));
        return file;
    }
}
