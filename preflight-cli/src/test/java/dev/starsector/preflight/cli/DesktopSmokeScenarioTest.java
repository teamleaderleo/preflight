package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void checkedInMixedPauseProfileUsesOnlyRuntimeControl() throws Exception {
        DesktopSmokeScenario scenario = DesktopSmokeScenario.read(
                Path.of("..", "scripts", "scenarios", "campaign-profile-paused-unpaused.json"));
        DesktopSmokeScenario sampled = DesktopSmokeScenario.read(
                Path.of("..", "scripts", "scenarios", "campaign-sample-paused-unpaused.json"));

        assertTrue(scenario.usesOnlyRuntimeControl());
        assertTrue(sampled.usesOnlyRuntimeControl());
        assertTrue(sampled.sampleRecording());
        assertEquals(scenario.stepIds().subList(0, 10), sampled.stepIds());
        assertEquals(11, scenario.stepIds().size());
        assertEquals(10, sampled.stepIds().size());
        assertTrue(scenario.stepIds().contains("observe-initial-pause-state"));
        assertTrue(scenario.stepIds().contains("paused-settled"));
        assertTrue(scenario.stepIds().contains("unpaused-settled"));
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
