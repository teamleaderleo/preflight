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
        assertEquals(6, ((List<?>) view.get("steps")).size());
        Set<String> capabilities = (Set<String>) view.get("requiredCapabilities");
        assertTrue(capabilities.contains("process-control"));
        assertTrue(capabilities.contains("semantic-state"));
        assertTrue(capabilities.contains("window-control"));
        assertTrue(capabilities.contains("screen-capture"));
        assertTrue(capabilities.contains("evidence-read"));
    }

    @Test
    void checkedInBenchmarkScenariosKeepTheSameSemanticIdentity() throws Exception {
        DesktopSmokeScenario optimized = DesktopSmokeScenario.read(
                Path.of("..", "scripts", "scenarios", "campaign-roam.json"));
        DesktopSmokeScenario measurement = DesktopSmokeScenario.read(
                Path.of("..", "scripts", "scenarios", "campaign-roam-measurement-only.json"));

        assertEquals(measurement.benchmarkIdentity(), optimized.benchmarkIdentity());
        assertEquals("measurement-only", measurement.launchPreset());
        assertEquals("fast", optimized.launchPreset());
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
