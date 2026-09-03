package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RuntimeSemanticStateTest {
    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void reset() {
        RuntimeSemanticState.reset();
        FrameTimeRuntime.reset();
    }

    @Test
    void v2StartsWithoutMenuTimestamps() throws Exception {
        Path destination = temporaryDirectory.resolve("runtime-state.json");
        RuntimeSemanticState.beginSession(destination);

        Map<String, Object> telemetry = RuntimeSemanticState.telemetry();
        assertEquals("starsector-preflight-runtime-state-v2", telemetry.get("format"));
        assertNull(telemetry.get("mainMenuReadyAt"));
        assertNull(telemetry.get("mainMenuInteractiveAt"));
        assertNull(telemetry.get("mainMenuOverlayRemovedAt"));
        String json = Files.readString(destination);
        assertTrue(json.contains("\"format\":\"starsector-preflight-runtime-state-v2\""), json);
        assertTrue(json.contains("\"mainMenuInteractiveAt\":null"), json);
        assertTrue(json.contains("\"mainMenuOverlayRemovedAt\":null"), json);
    }

    @Test
    void interactiveTransitionAlsoMarksTheFrameTelemetryBoundary() throws Exception {
        FrameTimeRuntime.beginSession(true);
        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));
        RuntimeSemanticState.mainMenuReady();

        RuntimeSemanticState.mainMenuInteractive();

        assertEquals(true, FrameTimeRuntime.telemetry().get("mainMenuInteractive"));
    }

    @Test
    void usabilityAndOverlayRemovalAreIndependentIdempotentClocks() throws Exception {
        Path destination = temporaryDirectory.resolve("runtime-state.json");
        RuntimeSemanticState.beginSession(destination);
        RuntimeSemanticState.mainMenuReady();
        RuntimeSemanticState.mainMenuInteractive();

        assertState(destination, "main-menu-interactive", 2L);
        String firstInteractive =
                String.valueOf(RuntimeSemanticState.telemetry().get("mainMenuInteractiveAt"));
        assertNull(RuntimeSemanticState.telemetry().get("mainMenuOverlayRemovedAt"));

        RuntimeSemanticState.mainMenuInteractive();
        assertState(destination, "main-menu-interactive", 2L);
        assertEquals(firstInteractive,
                String.valueOf(RuntimeSemanticState.telemetry().get("mainMenuInteractiveAt")));

        RuntimeSemanticState.mainMenuOverlayRemoved();
        assertState(destination, "main-menu-interactive", 2L);
        String firstOverlayRemoved =
                String.valueOf(RuntimeSemanticState.telemetry().get("mainMenuOverlayRemovedAt"));
        assertTrue(!"null".equals(firstOverlayRemoved));
        assertEquals(firstInteractive,
                String.valueOf(RuntimeSemanticState.telemetry().get("mainMenuInteractiveAt")));

        RuntimeSemanticState.mainMenuOverlayRemoved();
        assertState(destination, "main-menu-interactive", 2L);
        assertEquals(firstOverlayRemoved,
                String.valueOf(RuntimeSemanticState.telemetry().get("mainMenuOverlayRemovedAt")));
    }

    @Test
    void laterTelemetryCannotRegressCampaignSimulationOrCombatState() throws Exception {
        Path destination = temporaryDirectory.resolve("runtime-state.json");
        RuntimeSemanticState.beginSession(destination);
        assertState(destination, "starting", 0L);

        RuntimeSemanticState.mainMenuReady();
        assertState(destination, "main-menu-ready", 1L);
        String firstMainMenuTime = String.valueOf(RuntimeSemanticState.telemetry().get("mainMenuReadyAt"));
        RuntimeSemanticState.mainMenuReady();
        assertState(destination, "main-menu-ready", 1L);
        assertEquals(firstMainMenuTime,
                String.valueOf(RuntimeSemanticState.telemetry().get("mainMenuReadyAt")));

        RuntimeSemanticState.mainMenuInteractive();
        assertState(destination, "main-menu-interactive", 2L);
        RuntimeSemanticState.combatReady();
        assertState(destination, "main-menu-interactive", 2L);

        RuntimeSemanticState.campaignReady();
        assertState(destination, "campaign-ready", 3L);
        RuntimeSemanticState.mainMenuInteractive();
        RuntimeSemanticState.mainMenuOverlayRemoved();
        assertState(destination, "campaign-ready", 3L);

        RuntimeSemanticState.simulationReady();
        assertState(destination, "simulation-ready", 4L);
        RuntimeSemanticState.combatReady();
        assertState(destination, "simulation-ready", 4L);
        RuntimeSemanticState.campaignReady();
        assertState(destination, "campaign-ready", 5L);
        RuntimeSemanticState.combatReady();
        assertState(destination, "combat-ready", 6L);
        RuntimeSemanticState.stopped();
        assertState(destination, "stopped", 7L);
        assertTrue(Files.readString(destination).contains("\"mainMenuReadyAt\":"));
        assertTrue(Files.readString(destination).contains("\"mainMenuInteractiveAt\":"));
        assertTrue(Files.readString(destination).contains("\"mainMenuOverlayRemovedAt\":"));
        assertNull(RuntimeSemanticState.telemetry().get("writeProblem"));
    }

    private static void assertState(Path path, String state, long sequence) throws Exception {
        String json = Files.readString(path);
        assertTrue(json.contains("\"state\":\"" + state + "\""), json);
        assertTrue(json.contains("\"sequence\":" + sequence), json);
        Map<String, Object> telemetry = RuntimeSemanticState.telemetry();
        assertEquals(state, telemetry.get("state"));
        assertEquals(sequence, telemetry.get("sequence"));
    }
}
