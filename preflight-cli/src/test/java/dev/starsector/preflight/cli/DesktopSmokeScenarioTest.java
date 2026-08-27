package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class DesktopSmokeScenarioTest {
    @Test
    @SuppressWarnings("unchecked")
    void validatesTheCheckedInCampaignScenarioAndDerivesDriverCapabilities() throws Exception {
        DesktopSmokeScenario scenario = DesktopSmokeScenario.read(
                Path.of("..", "scripts", "scenarios", "campaign-roam.json"));

        Map<String, Object> view = scenario.view();
        assertEquals("starsector-preflight-smoke-v1", view.get("format"));
        assertEquals("campaign-roam", view.get("name"));
        assertEquals(8, ((List<?>) view.get("steps")).size());
        Set<String> capabilities = (Set<String>) view.get("requiredCapabilities");
        assertTrue(capabilities.contains("process-control"));
        assertTrue(capabilities.contains("semantic-state"));
        assertTrue(capabilities.contains("semantic-control"));
        assertTrue(capabilities.contains("window-control"));
        assertTrue(capabilities.contains("screen-capture"));
        assertTrue(capabilities.contains("evidence-read"));
        assertTrue(view.get("steps").toString().contains("main-menu-interactive"));
        assertTrue(view.get("steps").toString().contains("warmup"));
        assertTrue(view.get("steps").toString().contains("settle-buffer"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void checkedInBenchmarkScenariosKeepTheSameSemanticIdentity() throws Exception {
        DesktopSmokeScenario optimized = DesktopSmokeScenario.read(
                Path.of("..", "scripts", "scenarios", "campaign-roam.json"));
        DesktopSmokeScenario measurement = DesktopSmokeScenario.read(
                Path.of("..", "scripts", "scenarios", "campaign-roam-measurement-only.json"));

        assertEquals(measurement.benchmarkIdentity(), optimized.benchmarkIdentity());
        assertEquals("measurement-only", measurement.launchPreset());
        assertEquals("fast", optimized.launchPreset());
        assertEquals(480, optimized.view().get("timeoutSeconds"));
        Map<String, Object> menu = (Map<String, Object>) ((List<?>) optimized.view().get("steps")).get(0);
        assertEquals("menu", menu.get("id"));
        assertEquals(180, menu.get("timeoutSeconds"));
    }

    @Test
    void startupScenarioRequiresOnlyOwnedProcessAndSemanticState() throws Exception {
        DesktopSmokeScenario scenario = DesktopSmokeScenario.read(
                Path.of("..", "scripts", "scenarios", "startup.json"));

        assertTrue(scenario.usesOnlyRuntimeState());
        assertEquals(Set.of("process-control", "semantic-state"), scenario.requiredCapabilities());
        assertEquals(List.of("menu"), scenario.stepIds());
    }

    @Test
    void acceptsMinimalDiskAsABenchmarkIdentity() {
        DesktopSmokeScenario scenario = DesktopSmokeScenario.parse("""
                {
                  "format":"starsector-preflight-smoke-v1",
                  "name":"minimal",
                  "timeoutSeconds":60,
                  "launch":{"preset":"fast","textureStorage":"minimal","profile":null},
                  "steps":[{"id":"menu","kind":"wait-state","state":"main-menu-ready",
                    "timeoutSeconds":30}]
                }
                """);

        assertEquals("minimal", scenario.textureStorage());
    }

    @Test
    void runtimeContinueScenarioMayDwellWithoutDesktopAutomation() {
        DesktopSmokeScenario scenario = DesktopSmokeScenario.parse("""
                {
                  "format":"starsector-preflight-smoke-v1",
                  "name":"continue-dwell",
                  "timeoutSeconds":60,
                  "launch":{"preset":"fast","textureStorage":"balanced","profile":null},
                  "steps":[
                    {"id":"menu","kind":"wait-state","state":"main-menu-interactive",
                      "timeoutSeconds":30},
                    {"id":"continue","kind":"click","target":"main-menu.continue"},
                    {"id":"visible","kind":"wait-duration","durationMillis":1000}
                  ]
                }
                """);

        assertTrue(scenario.usesOnlyRuntimeControl());
        assertEquals(Set.of("process-control", "semantic-state", "semantic-control"),
                scenario.requiredCapabilities());
    }

    @Test
    void runtimeCampaignPauseActionsRemainInternalOnly() {
        DesktopSmokeScenario scenario = DesktopSmokeScenario.parse("""
                {
                  "format":"starsector-preflight-smoke-v1",
                  "name":"pause-cycle",
                  "timeoutSeconds":60,
                  "launch":{"preset":"fast","textureStorage":"balanced","profile":null},
                  "steps":[
                    {"id":"unpause","kind":"click","target":"campaign.unpause"},
                    {"id":"dwell","kind":"wait-duration","durationMillis":1000},
                    {"id":"pause","kind":"click","target":"campaign.pause"}
                  ]
                }
                """);

        assertTrue(scenario.usesOnlyRuntimeControl());
        assertEquals(Set.of("process-control", "semantic-control"),
                scenario.requiredCapabilities());
    }

    @Test
    void runtimeSimulationDeploymentActionsRemainInternalOnly() {
        DesktopSmokeScenario scenario = DesktopSmokeScenario.parse("""
                {
                  "format":"starsector-preflight-smoke-v1",
                  "name":"simulation-deployment",
                  "timeoutSeconds":60,
                  "launch":{"preset":"fast","textureStorage":"balanced","profile":null},
                  "steps":[
                    {"id":"opponents-all","kind":"click","target":"simulation.opponents.all"},
                    {"id":"opponents-deploy","kind":"click","target":"simulation.opponents.deploy"},
                    {"id":"allies","kind":"click","target":"simulation.allies.select"},
                    {"id":"allies-all","kind":"click","target":"simulation.allies.all"},
                    {"id":"allies-deploy","kind":"click","target":"simulation.allies.deploy"},
                    {"id":"engage","kind":"click","target":"simulation.engage"}
                  ]
                }
                """);

        assertTrue(scenario.usesOnlyRuntimeControl());
        assertEquals(Set.of("process-control", "semantic-control"),
                scenario.requiredCapabilities());
    }

    @Test
    void checkedInCombatFixtureUsesOnlyRuntimeControl() throws Exception {
        DesktopSmokeScenario scenario = DesktopSmokeScenario.read(
                Path.of("..", "scripts", "scenarios", "campaign-prepare-combat-fixture.json"));

        assertTrue(scenario.usesOnlyRuntimeControl());
        assertTrue(scenario.stepIds().contains("prepare-combat-fixture"));
        assertTrue(scenario.stepIds().contains("fixture-settle"));
        assertTrue(scenario.stepIds().contains("verify-combat-fixture"));
        assertEquals(Set.of("process-control", "semantic-state", "semantic-control"),
                scenario.requiredCapabilities());
    }

    @Test
    void checkedInSimulationCombatUsesTheReviewedFleetAndDeploymentRoute() throws Exception {
        DesktopSmokeScenario scenario = DesktopSmokeScenario.read(
                Path.of("..", "scripts", "scenarios", "campaign-simulation-combat.json"));

        assertEquals("campaign-simulation-combat", scenario.view().get("name"));
        assertTrue(scenario.sampleRecording());
        assertTrue(scenario.stepIds().contains("prepare-simulation-fleet"));
        assertTrue(scenario.stepIds().contains("verify-simulation-fleet"));
        assertTrue(scenario.stepIds().contains("simulation"));
        assertTrue(scenario.stepIds().contains("opponents-deploy"));
        assertTrue(scenario.stepIds().contains("allies-deploy"));
        assertTrue(scenario.stepIds().contains("autopilot"));
        assertTrue(scenario.stepIds().contains("close-command-map"));
        assertTrue(scenario.stepIds().contains("ensure-combat-unpaused"));
        assertTrue(scenario.stepIds().contains("capture-viewport"));
        assertTrue(scenario.stepIds().contains("zoom-out"));
        assertTrue(scenario.stepIds().contains("verify-zoom-out"));
        assertTrue(scenario.stepIds().contains("begin-frame-window"));
        assertTrue(scenario.stepIds().contains("combat-sample"));
        assertEquals(Set.of("process-control", "semantic-state", "semantic-control",
                        "window-control", "screen-capture", "evidence-read"),
                scenario.requiredCapabilities());
    }

    @Test
    void checkedInAcceleratedSimulationKeepsSpeedupExplicit() throws Exception {
        DesktopSmokeScenario scenario = DesktopSmokeScenario.read(
                Path.of("..", "scripts", "scenarios", "campaign-simulation-combat-speedup.json"));

        assertEquals("campaign-simulation-combat-speedup", scenario.view().get("name"));
        assertTrue(scenario.stepIds().contains("close-command-map"));
        assertTrue(scenario.stepIds().contains("enable-two-times-speed"));
        assertTrue(scenario.stepIds().contains("ensure-combat-unpaused"));
        assertTrue(scenario.stepIds().contains("capture-viewport"));
        assertTrue(scenario.stepIds().contains("zoom-out"));
        assertTrue(scenario.stepIds().contains("verify-zoom-out"));
        assertTrue(scenario.stepIds().contains("begin-frame-window"));
        assertTrue(scenario.stepIds().contains("combat-sample-2x"));
    }

    @Test
    void checkedInThousandDpSimulationReplacesBothSidesBeforeMeasurement() throws Exception {
        DesktopSmokeScenario scenario = DesktopSmokeScenario.read(
                Path.of("..", "scripts", "scenarios", "campaign-simulation-combat-1000dp.json"));

        assertEquals("campaign-simulation-combat-1000dp", scenario.view().get("name"));
        assertTrue(scenario.sampleRecording());
        assertTrue(scenario.stepIds().contains("prepare-symmetric-stress"));
        assertTrue(scenario.stepIds().contains("stress-settle"));
        assertTrue(scenario.stepIds().contains("combat-sample-1040dp"));
        assertFalse(scenario.stepIds().contains("activate-startup"));
        assertFalse(scenario.stepIds().contains("activate-game"));
        assertEquals(Set.of("process-control", "semantic-state", "semantic-control",
                        "window-control", "evidence-read"),
                scenario.requiredCapabilities());
    }

    @Test
    void checkedInThinThousandDpSimulationBoundsAndFingerprintsTheFrameWindow()
            throws Exception {
        DesktopSmokeScenario scenario = DesktopSmokeScenario.read(
                Path.of("..", "scripts", "scenarios",
                        "campaign-simulation-combat-1000dp-thin.json"));

        assertEquals("campaign-simulation-combat-1000dp-thin", scenario.view().get("name"));
        assertFalse(scenario.sampleRecording());
        assertTrue(scenario.stepIds().contains("begin-frame-window"));
        assertTrue(scenario.stepIds().contains("combat-sample-1040dp"));
        assertTrue(scenario.stepIds().contains("end-frame-window"));
        assertTrue(scenario.stepIds().contains("fingerprint-flush"));
        assertEquals(Set.of("process-control", "semantic-state", "semantic-control",
                        "window-control", "evidence-read"),
                scenario.requiredCapabilities());
    }

    @Test
    @SuppressWarnings("unchecked")
    void combatZoomUsesTheClosedGameInputAction() throws Exception {
        DesktopSmokeScenario scenario = DesktopSmokeScenario.read(
                Path.of("..", "scripts", "scenarios", "campaign-simulation-combat.json"));

        Map<String, Object> zoom = (Map<String, Object>) scenario.stepViews().stream()
                .filter(step -> "zoom-out".equals(step.get("id")))
                .findFirst()
                .orElseThrow();
        assertEquals("click", zoom.get("kind"));
        assertEquals("combat.zoom-out", zoom.get("target"));
    }

    @Test
    void nativeScrollStepsRemainBoundedWhenUsed() {
        assertThrows(IllegalArgumentException.class, () -> DesktopSmokeScenario.parse("""
                {"format":"starsector-preflight-smoke-v1","name":"bad-scroll",
                 "timeoutSeconds":60,
                 "launch":{"preset":"fast","textureStorage":"balanced","profile":null},
                 "steps":[{"id":"zoom","kind":"scroll-wheel","direction":"out","clicks":25}]}
                """));
    }

    @Test
    void checkedInMixedPauseProfileUsesOnlyRuntimeControl() throws Exception {
        DesktopSmokeScenario scenario = DesktopSmokeScenario.read(
                Path.of("..", "scripts", "scenarios", "campaign-profile-paused-unpaused.json"));
        DesktopSmokeScenario sampled = DesktopSmokeScenario.read(
                Path.of("..", "scripts", "scenarios", "campaign-sample-paused-unpaused.json"));

        assertTrue(scenario.usesOnlyRuntimeControl());
        assertTrue(sampled.usesOnlyRuntimeControl());
        assertTrue(sampled.sampleRecording());
        assertEquals(scenario.stepIds(), sampled.stepIds());
        assertEquals(10, scenario.stepIds().size());
        assertEquals(10, sampled.stepIds().size());
        assertTrue(scenario.stepIds().contains("observe-initial-pause-state"));
        assertTrue(scenario.stepIds().contains("paused-settled"));
        assertTrue(scenario.stepIds().contains("unpaused-settled"));
    }

    @Test
    void checkedInPausedUnpausedBenchmarkPairHasOneExactForegroundedRoute() throws Exception {
        DesktopSmokeScenario baseline = DesktopSmokeScenario.read(Path.of(
                "..", "scripts", "scenarios",
                "campaign-paused-unpaused-measurement-only.json"));
        DesktopSmokeScenario candidate = DesktopSmokeScenario.read(Path.of(
                "..", "scripts", "scenarios", "campaign-paused-unpaused-optimized.json"));

        assertEquals("measurement-only", baseline.launchPreset());
        assertEquals("fast", candidate.launchPreset());
        assertEquals(baseline.benchmarkIdentity(), candidate.benchmarkIdentity());
        assertEquals(baseline.stepIds(), candidate.stepIds());
        assertTrue(baseline.stepIds().contains("activate-game"));
        assertTrue(baseline.stepIds().contains("paused-settled"));
        assertTrue(baseline.stepIds().contains("begin-unpaused-frame-window"));
        assertTrue(baseline.stepIds().contains("unpaused-settled"));
        assertEquals("quit", baseline.stepIds().get(baseline.stepIds().size() - 1));
    }

    @Test
    void launchMayOptIntoDeepCampaignTimingAndSmoothFramePacing() {
        DesktopSmokeScenario scenario = DesktopSmokeScenario.parse("""
                {
                  "format":"starsector-preflight-smoke-v1",
                  "name":"profile",
                  "timeoutSeconds":60,
                  "launch":{"preset":"fast","textureStorage":"balanced","profile":null,
                    "campaignTimes":true,"smoothFramePacing":true},
                  "steps":[{"id":"menu","kind":"wait-state","state":"main-menu-ready",
                    "timeoutSeconds":30}]
                }
                """);

        assertTrue(scenario.campaignTimes());
        assertTrue(scenario.smoothFramePacing());
        assertTrue(scenario.benchmarkIdentity().toString().contains("campaignTimes=true"));
    }

    @Test
    void launchMayOptIntoSamplingWithoutDeepCampaignTimers() {
        DesktopSmokeScenario scenario = DesktopSmokeScenario.parse("""
                {
                  "format":"starsector-preflight-smoke-v1",
                  "name":"sample",
                  "timeoutSeconds":60,
                  "launch":{"preset":"fast","textureStorage":"balanced","profile":null,
                    "recording":"sample"},
                  "steps":[{"id":"menu","kind":"wait-state","state":"main-menu-ready",
                    "timeoutSeconds":30}]
                }
                """);

        assertTrue(scenario.sampleRecording());
        assertTrue(scenario.benchmarkIdentity().toString().contains("recording=sample"));
    }

    @Test
    void rejectsUnknownRecordingModes() {
        String invalid = """
                {
                  "format":"starsector-preflight-smoke-v1",
                  "name":"sample",
                  "timeoutSeconds":60,
                  "launch":{"preset":"fast","textureStorage":"balanced","profile":null,
                    "recording":"continuous"},
                  "steps":[{"id":"menu","kind":"wait-state","state":"main-menu-ready",
                    "timeoutSeconds":30}]
                }
                """;

        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> DesktopSmokeScenario.parse(invalid)).getMessage().contains("none or sample"));
    }

    @Test
    void rejectsUnknownTargetsAndDuplicateStepIds() {
        String invalidTarget = scenario("""
                {"id":"click","kind":"click","target":"main-menu.destroy-save"}
                """);
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> DesktopSmokeScenario.parse(invalidTarget)).getMessage().contains("unsupported"));

        String duplicate = scenario("""
                {"id":"same","kind":"activate-window"},
                {"id":"same","kind":"quit"}
                """);
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> DesktopSmokeScenario.parse(duplicate)).getMessage().contains("Duplicate step id"));
    }

    @Test
    void rejectsUnknownFieldsInsteadOfSilentlyIgnoringTypos() {
        String typo = """
                {
                  "format":"starsector-preflight-smoke-v1",
                  "name":"typo",
                  "timeoutSeconds":60,
                  "launch":{"preset":"fast","textureStorage":"balanced","profile":null},
                  "steps":[{"id":"menu","kind":"wait-state","state":"main-menu-ready",
                    "timeoutSeconds":30,"timeoutSecond":30}]
                }
                """;
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> DesktopSmokeScenario.parse(typo)).getMessage().contains("unknown field"));
    }

    @Test
    void rejectsADwellLongerThanTheWholeScenario() {
        String tooLong = scenario("""
                {"id":"visible","kind":"wait-duration","durationMillis":60001}
                """);

        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> DesktopSmokeScenario.parse(tooLong)).getMessage().contains("scenario timeout"));
    }

    private static String scenario(String steps) {
        return """
                {
                  "format":"starsector-preflight-smoke-v1",
                  "name":"test",
                  "timeoutSeconds":60,
                  "launch":{"preset":"fast","textureStorage":"balanced","profile":null},
                  "steps":[%s]
                }
                """.formatted(steps);
    }
}
