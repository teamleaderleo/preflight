package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    }

    @Test
    void publishesOnlySemanticTransitionsAndStopsOrderly() throws Exception {
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
        String firstInteractiveTime =
                String.valueOf(RuntimeSemanticState.telemetry().get("mainMenuInteractiveAt"));
        RuntimeSemanticState.mainMenuInteractive();
        assertState(destination, "main-menu-interactive", 2L);
        assertEquals(firstInteractiveTime,
                String.valueOf(RuntimeSemanticState.telemetry().get("mainMenuInteractiveAt")));

        RuntimeSemanticState.campaignReady();
        assertState(destination, "campaign-ready", 3L);
        RuntimeSemanticState.combatReady();
        assertState(destination, "combat-ready", 4L);
        RuntimeSemanticState.campaignReady();
        assertState(destination, "campaign-ready", 5L);
        RuntimeSemanticState.stopped();
        assertState(destination, "stopped", 6L);
        assertTrue(Files.readString(destination).contains("\"mainMenuReadyAt\":"));
        assertTrue(Files.readString(destination).contains("\"mainMenuInteractiveAt\":"));
        assertTrue(RuntimeSemanticState.telemetry().get("writeProblem") == null);
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
