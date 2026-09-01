package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.combat.CombatEngine;
import dev.starsector.preflight.core.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        CombatStressFixtureRuntime.reset();
        FrameTimeRuntime.reset();
        Global.setSettings(null);
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
    void acceptsOnlyReviewedMacAndWindowsTitleClassNames() {
        assertTrue(InternalGameControlRuntime.supportedTitleClassName(
                MainMenuInteractivePlan.TARGET_CLASS.replace('/', '.')));
        assertTrue(InternalGameControlRuntime.supportedTitleClassName(
                MainMenuInteractivePlan.WINDOWS_TARGET_CLASS.replace('/', '.')));
        assertFalse(InternalGameControlRuntime.supportedTitleClassName(
                MainMenuInteractivePlan.LINUX_TARGET_CLASS.replace('/', '.')));
        assertFalse(InternalGameControlRuntime.supportedTitleClassName("java.lang.Object"));
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
    void campaignFrameWindowActionIsRecognizedButStillRequiresTheExactCampaignShape()
            throws Exception {
        System.setProperty("preflight.desktopSmoke", "true");
        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));
        RuntimeSemanticState.campaignReady();
        InternalGameControlRuntime.beginSession(temporaryDirectory.resolve("adapter.json"));
        Files.writeString(temporaryDirectory.resolve("runtime-action-request.json"),
                request(Instant.now().plusSeconds(30),
                        InternalGameControlRuntime.CAMPAIGN_BEGIN_FRAME_WINDOW_ACTION,
                        InternalGameControlRuntime.CAMPAIGN_STATE));

        InternalGameControlRuntime.campaignInput(new Object(), new ArrayList<>());

        String receipt = Files.readString(temporaryDirectory.resolve("runtime-action-receipt.json"));
        assertTrue(receipt.contains("\"status\":\"failed\""), receipt);
        assertTrue(receipt.contains("campaign-class-mismatch"), receipt);
        assertTrue(receipt.contains("campaign.begin-frame-window"), receipt);
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
    void stressFixtureRequestFailsBeforeCombatMutationOnAnUnknownEngineShape() throws Exception {
        System.setProperty("preflight.desktopSmoke", "true");
        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));
        RuntimeSemanticState.combatReady();
        InternalGameControlRuntime.beginSession(temporaryDirectory.resolve("adapter.json"));
        Files.writeString(temporaryDirectory.resolve("runtime-action-request.json"),
                request(Instant.now().plusSeconds(30),
                        CombatStressFixtureRuntime.ACTION,
                        InternalGameControlRuntime.COMBAT_STATE));

        InternalGameControlRuntime.combatAdvance(new Object(), new ArrayList<>());

        String receipt = Files.readString(temporaryDirectory.resolve("runtime-action-receipt.json"));
        assertTrue(receipt.contains("\"status\":\"failed\""), receipt);
        assertTrue(receipt.contains("combat-engine-class-mismatch"), receipt);
        assertTrue(receipt.contains(CombatStressFixtureRuntime.ACTION), receipt);
        assertFalse((Boolean) CombatStressFixtureRuntime.telemetry().get("attempted"));
    }

    @Test
    void reviewedCombatEngineExecutesPauseStressWindowAndViewportActions() throws Exception {
        System.setProperty("preflight.desktopSmoke", "true");
        FrameTimeRuntime.beginSession(true);
        installStressSettings();
        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));
        RuntimeSemanticState.combatReady();
        InternalGameControlRuntime.beginSession(temporaryDirectory.resolve("adapter.json"));
        CombatEngine engine = new CombatEngine();

        assertExecuted(combatAction(engine, 1, InternalGameControlRuntime.COMBAT_PAUSE_ACTION));
        assertTrue(engine.isPaused());
        assertExecuted(combatAction(engine, 2, InternalGameControlRuntime.COMBAT_UNPAUSE_ACTION));
        assertFalse(engine.isPaused());

        String stress = combatAction(engine, 3, CombatStressFixtureRuntime.ACTION);
        assertExecuted(stress);
        assertTrue(stress.contains(CombatStressFixtureRuntime.RECIPE_ID), stress);
        assertTrue(engine.isPaused());
        assertTrue((Boolean) CombatStressFixtureRuntime.telemetry().get("prepared"));

        assertExecuted(combatAction(
                engine, 4, InternalGameControlRuntime.COMBAT_BEGIN_FRAME_WINDOW_ACTION));
        engine.advanceTime(4.25f);
        engine.destroyOneNonFighter(1);
        String ended = combatAction(
                engine, 5, InternalGameControlRuntime.COMBAT_END_FRAME_WINDOW_ACTION);
        assertExecuted(ended);
        assertTrue(ended.contains("4.250 game seconds"), ended);

        String baseline = combatAction(
                engine, 6, InternalGameControlRuntime.COMBAT_CAPTURE_VIEWPORT_ACTION);
        assertExecuted(baseline);
        assertTrue(baseline.contains("viewMult 1.000"), baseline);
        String stressed = combatAction(
                engine, 7, InternalGameControlRuntime.COMBAT_SET_STRESS_VIEWPORT_ACTION);
        assertExecuted(stressed);
        assertTrue(stressed.contains("viewMult 4.000"), stressed);
        InternalGameControlRuntime.combatAdvanceEnd(engine);
        String verified = combatAction(
                engine, 8, InternalGameControlRuntime.COMBAT_VERIFY_ZOOM_OUT_ACTION);
        assertExecuted(verified);
        assertTrue(verified.contains("center (0.0,0.0)"), verified);
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

    @Test
    void combatZoomFailsClosedBeforeAddingInputToAnUnknownShape() throws Exception {
        System.setProperty("preflight.desktopSmoke", "true");
        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));
        RuntimeSemanticState.combatReady();
        InternalGameControlRuntime.beginSession(temporaryDirectory.resolve("adapter.json"));
        Files.writeString(temporaryDirectory.resolve("runtime-action-request.json"),
                request(Instant.now().plusSeconds(30),
                        InternalGameControlRuntime.COMBAT_ZOOM_OUT_ACTION,
                        InternalGameControlRuntime.COMBAT_STATE));
        ArrayList<Object> events = new ArrayList<>();

        InternalGameControlRuntime.combatInput(new Object(), events);

        assertTrue(events.isEmpty());
        String receipt = Files.readString(temporaryDirectory.resolve("runtime-action-receipt.json"));
        assertTrue(receipt.contains("\"status\":\"failed\""), receipt);
        assertTrue(receipt.contains("combat-state-class-mismatch"), receipt);
        assertTrue(receipt.contains("combat-state.input"), receipt);
    }

    private String combatAction(CombatEngine engine, long sequence, String action) throws Exception {
        Files.deleteIfExists(temporaryDirectory.resolve("runtime-action-receipt.json"));
        Files.writeString(temporaryDirectory.resolve("runtime-action-request.json"),
                request(sequence, Instant.now().plusSeconds(30), action,
                        InternalGameControlRuntime.COMBAT_STATE));
        // Production intentionally bounds request-file polling to 20 ms. Exercise the same cadence,
        // then prove that the input seam defers non-wheel actions to the engine seam in-frame.
        Thread.sleep(25L);
        InternalGameControlRuntime.combatInput(new Object(), new ArrayList<>());
        InternalGameControlRuntime.combatAdvance(engine, new ArrayList<>());
        return Files.readString(temporaryDirectory.resolve("runtime-action-receipt.json"));
    }

    private static void assertExecuted(String receipt) {
        assertTrue(receipt.contains("\"status\":\"executed\""), receipt);
    }

    private static void installStressSettings() {
        Global.setSettings(new SettingsAPI() {
            @Override
            public List<String> getAllVariantIds() {
                return CombatStressFixtureRuntime.recipe().stream()
                        .map(row -> (String) row.get("variantId"))
                        .distinct()
                        .toList();
            }

            @Override
            public ShipVariantAPI getVariant(String id) {
                return null;
            }

            @Override
            public boolean doesVariantExist(String id) {
                return getAllVariantIds().contains(id);
            }
        });
    }

    private static String request(Instant deadline) {
        return request(deadline, InternalGameControlRuntime.CONTINUE_ACTION,
                InternalGameControlRuntime.INTERACTIVE_STATE);
    }

    private static String request(Instant deadline, String action, String expectedState) {
        return request(1L, deadline, action, expectedState);
    }

    private static String request(
            long sequence, Instant deadline, String action, String expectedState) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("format", InternalGameControlRuntime.REQUEST_FORMAT);
        values.put("sequence", sequence);
        values.put("pid", ProcessHandle.current().pid());
        values.put("processStartedAt", RuntimeSemanticState.processStartedAt());
        values.put("action", action);
        values.put("expectedState", expectedState);
        values.put("deadline", deadline);
        return Json.object(values);
    }
}
