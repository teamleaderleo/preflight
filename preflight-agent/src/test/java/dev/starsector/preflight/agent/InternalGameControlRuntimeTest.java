package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class InternalGameControlRuntimeTest {
    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void reset() {
        System.clearProperty("preflight.desktopSmoke");
        InternalGameControlRuntime.reset();
        RuntimeSemanticState.reset();
    }

    @Test
    void staysOffOutsideExplicitDesktopSmokeRuns() throws Exception {
        Path report = temporaryDirectory.resolve("adapter.json");
        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));
        InternalGameControlRuntime.beginSession(report);

        assertFalse(InternalGameControlRuntime.enabled());
        InternalGameControlRuntime.titleAdvance(new Object());
        assertFalse(Files.exists(temporaryDirectory.resolve("runtime-action-receipt.json")));
    }

    @Test
    void rejectsExpiredPidBoundRequestsWithoutInvokingTheTitle() throws Exception {
        System.setProperty("preflight.desktopSmoke", "true");
        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));
        RuntimeSemanticState.mainMenuReady();
        RuntimeSemanticState.mainMenuInteractive();
        InternalGameControlRuntime.beginSession(temporaryDirectory.resolve("adapter.json"));
        Files.writeString(temporaryDirectory.resolve("runtime-action-request.json"),
                request(Instant.EPOCH));

        InternalGameControlRuntime.titleAdvance(new Object());

        String receipt = Files.readString(temporaryDirectory.resolve("runtime-action-receipt.json"));
        assertTrue(receipt.contains("\"status\":\"rejected\""), receipt);
        assertTrue(receipt.contains("deadline-expired"), receipt);
    }

    @Test
    void aCanonicalLiveRequestReachesOnlyTheExactTitleShape() throws Exception {
        System.setProperty("preflight.desktopSmoke", "true");
        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));
        RuntimeSemanticState.mainMenuReady();
        RuntimeSemanticState.mainMenuInteractive();
        InternalGameControlRuntime.beginSession(temporaryDirectory.resolve("adapter.json"));
        Files.writeString(temporaryDirectory.resolve("runtime-action-request.json"),
                request(Instant.now().plusSeconds(30)));

        InternalGameControlRuntime.titleAdvance(new Object());

        String receipt = Files.readString(temporaryDirectory.resolve("runtime-action-receipt.json"));
        assertTrue(receipt.contains("\"status\":\"failed\""), receipt);
        assertTrue(receipt.contains("title-class-mismatch"), receipt);
    }

    @Test
    void campaignActionsFailClosedBeforeAddingInputToAnUnknownShape() throws Exception {
        System.setProperty("preflight.desktopSmoke", "true");
        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));
        RuntimeSemanticState.campaignReady();
        InternalGameControlRuntime.beginSession(temporaryDirectory.resolve("adapter.json"));
        Files.writeString(temporaryDirectory.resolve("runtime-action-request.json"),
                request(Instant.now().plusSeconds(30),
                        InternalGameControlRuntime.CAMPAIGN_UNPAUSE_ACTION,
                        InternalGameControlRuntime.CAMPAIGN_STATE));
        ArrayList<Object> events = new ArrayList<>();

        InternalGameControlRuntime.campaignInput(new Object(), events);

        assertTrue(events.isEmpty());
        String receipt = Files.readString(temporaryDirectory.resolve("runtime-action-receipt.json"));
        assertTrue(receipt.contains("\"status\":\"failed\""), receipt);
        assertTrue(receipt.contains("campaign-class-mismatch"), receipt);
    }

    @Test
    void combatFixtureRequestFailsBeforeConsoleMutationOnAnUnknownCampaignShape() throws Exception {
        System.setProperty("preflight.desktopSmoke", "true");
        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));
        RuntimeSemanticState.campaignReady();
        InternalGameControlRuntime.beginSession(temporaryDirectory.resolve("adapter.json"));
        Files.writeString(temporaryDirectory.resolve("runtime-action-request.json"),
                request(Instant.now().plusSeconds(30),
                        ConsoleCombatFixtureRuntime.ACTION,
                        InternalGameControlRuntime.CAMPAIGN_STATE));

        InternalGameControlRuntime.campaignInput(new Object(), new ArrayList<>());

        String receipt = Files.readString(temporaryDirectory.resolve("runtime-action-receipt.json"));
        assertTrue(receipt.contains("\"status\":\"failed\""), receipt);
        assertTrue(receipt.contains("campaign-class-mismatch"), receipt);
        assertTrue(receipt.contains(ConsoleCombatFixtureRuntime.ACTION), receipt);
        assertFalse((Boolean) ConsoleCombatFixtureRuntime.telemetry().get("attempted"));
    }

    @Test
    void simulationActionsFailClosedBeforeTouchingAnUnknownDialogShape() throws Exception {
        System.setProperty("preflight.desktopSmoke", "true");
        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));
        RuntimeSemanticState.simulationReady();
        InternalGameControlRuntime.beginSession(temporaryDirectory.resolve("adapter.json"));
        Files.writeString(temporaryDirectory.resolve("runtime-action-request.json"),
                request(Instant.now().plusSeconds(30),
                        InternalGameControlRuntime.SIMULATION_OPPONENTS_ALL,
                        InternalGameControlRuntime.SIMULATION_STATE));

        InternalGameControlRuntime.simulationDialog(new Object());

        String receipt = Files.readString(temporaryDirectory.resolve("runtime-action-receipt.json"));
        assertTrue(receipt.contains("\"status\":\"failed\""), receipt);
        assertTrue(receipt.contains("simulation-dialog-class-mismatch"), receipt);
        assertTrue(receipt.contains("simulation-dialog.advance"), receipt);
    }

    @Test
    void simulatorSessionStartsWithoutAnEngagedLatch() throws Exception {
        System.setProperty("preflight.desktopSmoke", "true");
        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));
        InternalGameControlRuntime.beginSession(temporaryDirectory.resolve("adapter.json"));

        assertFalse(InternalGameControlRuntime.simulationEngaged());
    }

    @Test
    void combatActionsFailClosedBeforeTouchingAnUnknownEngineShape() throws Exception {
        System.setProperty("preflight.desktopSmoke", "true");
        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));
        RuntimeSemanticState.combatReady();
        InternalGameControlRuntime.beginSession(temporaryDirectory.resolve("adapter.json"));
        Files.writeString(temporaryDirectory.resolve("runtime-action-request.json"),
                request(Instant.now().plusSeconds(30),
                        InternalGameControlRuntime.COMBAT_UNPAUSE_ACTION,
                        InternalGameControlRuntime.COMBAT_STATE));

        InternalGameControlRuntime.combatAdvance(new Object(), new ArrayList<>());

        String receipt = Files.readString(temporaryDirectory.resolve("runtime-action-receipt.json"));
        assertTrue(receipt.contains("\"status\":\"failed\""), receipt);
        assertTrue(receipt.contains("combat-engine-class-mismatch"), receipt);
        assertTrue(receipt.contains("combat-engine.advance"), receipt);
    }

    private static String request(Instant deadline) {
        return request(deadline, InternalGameControlRuntime.CONTINUE_ACTION,
                InternalGameControlRuntime.INTERACTIVE_STATE);
    }

    private static String request(Instant deadline, String action, String expectedState) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("format", InternalGameControlRuntime.REQUEST_FORMAT);
        values.put("sequence", 1L);
        values.put("pid", ProcessHandle.current().pid());
        values.put("processStartedAt", RuntimeSemanticState.processStartedAt());
        values.put("action", action);
        values.put("expectedState", expectedState);
        values.put("deadline", deadline);
        return Json.object(values);
    }
}
