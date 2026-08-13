package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopSmokeEvidenceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void sealsOnlyACompleteOrderedPassAndHashesItsArtifacts() throws Exception {
        DesktopSmokeScenario scenario = scenario();
        Path run = Files.createDirectory(temporaryDirectory.resolve("run"));
        Path screenshot = Files.writeString(run.resolve("campaign.png"), "pixels");
        Path request = writeRequest(run, fullSteps("campaign.png"), capabilities());
        Path destination = run.resolve("smoke-evidence.json");

        Map<String, Object> result = DesktopSmokeEvidence.collect(scenario, request, destination);

        assertEquals("starsector-preflight-smoke-evidence-v1", result.get("format"));
        assertEquals("passed", result.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> artifacts = (List<Map<String, Object>>) result.get("artifacts");
        assertEquals(1, artifacts.size());
        assertEquals(Files.size(screenshot), artifacts.get(0).get("bytes"));
        assertEquals(Hashes.sha256(screenshot), artifacts.get(0).get("sha256"));
        assertTrue(Files.readString(destination).contains("\"scenario\":\"campaign-roam\""));
        try (Stream<Path> files = Files.list(run)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().contains(".tmp-")));
        }
    }

    @Test
    void rejectsAFalsePassWithMissingCapabilitiesOrSteps() throws Exception {
        DesktopSmokeScenario scenario = scenario();
        Path run = Files.createDirectory(temporaryDirectory.resolve("incomplete"));
        Files.writeString(run.resolve("campaign.png"), "pixels");
        Path missingCapability = writeRequest(
                run, fullSteps("campaign.png"), List.of("process-control", "semantic-state"));
        assertThrows(IllegalArgumentException.class, () -> DesktopSmokeEvidence.collect(
                scenario, missingCapability, run.resolve("smoke-evidence.json")));

        Path missingStep = writeRequest(run, fullSteps("campaign.png").subList(0, 1), capabilities());
        assertThrows(IllegalArgumentException.class, () -> DesktopSmokeEvidence.collect(
                scenario, missingStep, run.resolve("smoke-evidence.json")));
    }

    @Test
    void refusesArtifactsOutsideTheRunDirectory() throws Exception {
        DesktopSmokeScenario scenario = scenario();
        Path run = Files.createDirectory(temporaryDirectory.resolve("contained"));
        Path outside = Files.writeString(temporaryDirectory.resolve("outside.png"), "pixels");
        Path request = writeRequest(run, fullSteps(outside.toString()), capabilities());

        assertThrows(IOException.class, () -> DesktopSmokeEvidence.collect(
                scenario, request, run.resolve("smoke-evidence.json")));
    }

    private Path writeRequest(Path run, List<Map<String, Object>> steps, List<String> capabilities)
            throws Exception {
        Map<String, Object> driver = new LinkedHashMap<>();
        driver.put("id", "test-driver");
        driver.put("version", "1.0");
        driver.put("platform", "mac");
        driver.put("capabilities", capabilities);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("format", DesktopSmokeEvidence.DRIVER_RESULT_FORMAT);
        request.put("status", "passed");
        request.put("startedAt", Instant.parse("2026-08-07T00:00:00Z"));
        request.put("completedAt", Instant.parse("2026-08-07T00:00:04Z"));
        request.put("driver", driver);
        request.put("runDirectory", run.toAbsolutePath().toString());
        request.put("steps", steps);
        request.put("diagnostics", List.of());
        Path path = temporaryDirectory.resolve("request-" + System.nanoTime() + ".json");
        Files.writeString(path, Json.object(request));
        return path;
    }

    private static List<Map<String, Object>> fullSteps(String artifactPath) {
        return List.of(
                step("menu", "passed", "2026-08-07T00:00:00Z", "2026-08-07T00:00:02Z", List.of()),
                step("capture", "passed", "2026-08-07T00:00:02Z", "2026-08-07T00:00:04Z",
                        List.of(Map.of("kind", "screenshot", "path", artifactPath))));
    }

    private static Map<String, Object> step(
            String id, String status, String startedAt, String completedAt,
            List<Map<String, Object>> artifacts) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", id);
        step.put("status", status);
        step.put("startedAt", startedAt);
        step.put("completedAt", completedAt);
        step.put("detail", "test");
        step.put("artifacts", artifacts);
        return step;
    }

    private static List<String> capabilities() {
        return List.of("process-control", "semantic-state", "screen-capture");
    }

    private static DesktopSmokeScenario scenario() {
        return DesktopSmokeScenario.parse("""
                {
                  "format": "starsector-preflight-smoke-v1",
                  "name": "campaign-roam",
                  "timeoutSeconds": 60,
                  "launch": {"preset": "fast", "textureStorage": "balanced", "profile": null},
                  "steps": [
                    {"id": "menu", "kind": "wait-state", "state": "main-menu-ready", "timeoutSeconds": 30},
                    {"id": "capture", "kind": "capture", "artifacts": ["screenshot"]}
                  ]
                }
                """);
    }
}
