package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopBenchmarkLaunchTest {
    @Test
    void acceptsOnlyTheSameSemanticRouteWithMeasurementFirst() {
        Map<String, Object> identity = DesktopBenchmarkLaunch.validatePair(
                scenario("baseline", "measurement-only", 3_000),
                scenario("candidate", "fast", 3_000));

        assertEquals("starsector-preflight-smoke-v1", identity.get("format"));
        assertEquals("balanced", identity.get("textureStorage"));
        assertTrue(identity.get("sha256").toString().matches("[0-9a-f]{64}"));
    }

    @Test
    void rejectsReversedModesBeforeEitherGameCanLaunch() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> DesktopBenchmarkLaunch.validatePair(
                        scenario("candidate", "fast", 3_000),
                        scenario("baseline", "measurement-only", 3_000)));

        assertTrue(failure.getMessage().contains("first benchmark scenario"));
    }

    @Test
    void rejectsAQuietlyDifferentInteractionSequence() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> DesktopBenchmarkLaunch.validatePair(
                        scenario("baseline", "measurement-only", 3_000),
                        scenario("candidate", "fast", 4_000)));

        assertTrue(failure.getMessage().contains("identical settings and interaction steps"));
    }

    @Test
    void sealsTheActualRunIdentityAndElapsedComparison(@TempDir Path temporary) throws Exception {
        Path baseline = temporary.resolve("baseline");
        Path optimized = temporary.resolve("optimized");
        writeRunIdentity(baseline, "ab".repeat(32));
        writeRunIdentity(optimized, "ab".repeat(32));

        assertEquals(
                DesktopBenchmarkLaunch.measuredIdentity(baseline),
                DesktopBenchmarkLaunch.measuredIdentity(optimized));

        Map<String, Object> comparison = DesktopBenchmarkLaunch.comparison(List.of(
                phase(100_000L, 30.0), phase(60_000L, 45.0)));
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) comparison.get("metrics");
        @SuppressWarnings("unchecked")
        Map<String, Object> elapsed = (Map<String, Object>) metrics.get("routeElapsedMs");
        assertEquals(100_000L, elapsed.get("measurementOnly"));
        assertEquals(60_000L, elapsed.get("optimized"));
        assertEquals(-40_000.0, elapsed.get("delta"));
        assertEquals(40.0, elapsed.get("improvementPercent"));
        @SuppressWarnings("unchecked")
        Map<String, Object> fps = (Map<String, Object>) metrics.get("averageFps");
        assertEquals(50.0, fps.get("improvementPercent"));
    }

    @Test
    void mirrorsAnEarlySessionCancellationAfterThePhaseDirectoryAppears(@TempDir Path temporary)
            throws Exception {
        Path sessionCancellation = temporary.resolve("cancel.requested");
        Path phaseCancellation = temporary.resolve("phase").resolve("cancel.requested");
        Files.writeString(sessionCancellation, "cancel\n");
        AtomicBoolean finished = new AtomicBoolean();
        Thread mirror = DesktopBenchmarkLaunch.cancellationMirror(
                sessionCancellation, phaseCancellation, finished);

        Files.createDirectory(phaseCancellation.getParent());
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (!DesktopBenchmarkLaunch.cancellationRequested(phaseCancellation)
                && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        finished.set(true);
        mirror.interrupt();
        mirror.join(1_000L);

        assertTrue(DesktopBenchmarkLaunch.cancellationRequested(phaseCancellation));
        assertTrue(!mirror.isAlive());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sealsStartupAndCampaignFrameMetricsFromValidatedPhaseEvidence(@TempDir Path temporary)
            throws Exception {
        Instant processStart = Instant.parse("2026-08-09T00:00:00Z");
        Path game = temporary.resolve("game");
        Path save = game.resolve("saves/save_Test_123");
        Files.createDirectories(save);
        Path descriptor = save.resolve("descriptor.xml");
        Files.writeString(descriptor, "<save><timestamp>123</timestamp></save>");
        Path run = temporary.resolve("run");
        Files.createDirectories(run);
        Files.writeString(run.resolve("run.json"), Json.object(Map.of(
                "installRoot", game.toString())));
        Path runtime = run.resolve("runtime-process.json");
        Map<String, Object> runtimeIdentity = new LinkedHashMap<>();
        runtimeIdentity.put("format", "starsector-preflight-runtime-process-v1");
        runtimeIdentity.put("pid", ProcessHandle.current().pid());
        runtimeIdentity.put("parentPid", null);
        runtimeIdentity.put("startedAt", processStart);
        runtimeIdentity.put("observedAt", processStart.plusSeconds(30));
        runtimeIdentity.put("state", "stopped");
        runtimeIdentity.put("stoppedAt", processStart.plusSeconds(30));
        Files.writeString(runtime, Json.object(runtimeIdentity));
        Path frames = run.resolve("desktop-smoke-frame-report.json");
        Files.writeString(frames, Json.object(Map.of(
                "format", "starsector-preflight-runtime-frame-report-v1",
                "frameTimes", Map.of("campaignActive", Map.of(
                        "frames", 180,
                        "averageFps", 60.0,
                        "medianFps", 62.0,
                        "onePercentLowFps", 35.0,
                        "pointOnePercentLowFps", 22.0,
                        "p95Micros", 20_000,
                        "p99Micros", 28_571,
                        "framesMeeting60FpsPercent", 88.0)))));
        Path log = run.resolve("desktop-smoke-log-tail.txt");
        Files.writeString(log, "123 [main] INFO CampaignGameManager - Reading save data from ["
                + descriptor + "]\n");
        Path health = run.resolve("desktop-smoke-adapter-health.json");
        Files.writeString(health, Json.object(Map.of(
                "format", "starsector-preflight-runtime-adapter-health-v1",
                "adapterMode", "enabled",
                "adapterTransformationCache", Map.of("hits", 4, "misses", 1),
                "mergedReadCache", Map.of("hits", 11, "misses", 2),
                "preparedTextures", Map.of(
                        "hits", 7, "misses", 3, "fallbacks", 1,
                        "corruptions", 0, "internalErrors", 0, "packFailures", 0),
                "preparedAudio", Map.of(
                        "servedFromCache", 5, "decodedByTheGame", 2, "failures", 0),
                "memoryPressure", Map.of("sessionAvailablePercent", 61))));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("startedAt", processStart.plusSeconds(1));
        evidence.put("completedAt", processStart.plusSeconds(25));
        evidence.put("steps", List.of(
                Map.of("id", "menu", "completedAt", processStart.plusSeconds(10)),
                Map.of("id", "campaign", "completedAt", processStart.plusSeconds(20))));
        evidence.put("artifacts", List.of(
                Map.of("kind", "frame-report", "path", frames),
                Map.of("kind", "log-tail", "path", log),
                Map.of("kind", "adapter-health", "path", health)));
        Map<String, Object> phase = DesktopBenchmarkLaunch.phase("optimized", Map.of(
                "status", "passed",
                "runtimeProcess", runtime,
                "runDirectory", run,
                "evidence", evidence));

        assertEquals("passed", phase.get("status"));
        Map<String, Object> summary = (Map<String, Object>) phase.get("summary");
        assertEquals(10_000L, summary.get("processToMainMenuMs"));
        assertEquals(20_000L, summary.get("processToCampaignReadyMs"));
        assertEquals(60.0, summary.get("averageFps"));
        assertEquals(28_571L, summary.get("p99FrameMicros"));
        Map<String, Object> selected = (Map<String, Object>) summary.get("selectedSave");
        assertEquals("save_Test_123", selected.get("directory"));
        assertTrue(selected.get("descriptorSha256").toString().matches("[0-9a-f]{64}"));
        Map<String, Object> context = (Map<String, Object>) summary.get("runtimeContext");
        assertEquals(27L, context.get("cacheHits"));
        assertEquals(8L, context.get("cacheMisses"));
        assertEquals(3L, context.get("fallbacks"));
        assertEquals(61L, context.get("memoryAvailablePercent"));
    }

    @Test
    void refusesASaveDescriptorOutsideTheInstallation(@TempDir Path temporary) throws Exception {
        Path game = temporary.resolve("game");
        Files.createDirectories(game.resolve("saves"));
        Path outside = temporary.resolve("outside/descriptor.xml");
        Files.createDirectories(outside.getParent());
        Files.writeString(outside, "<save/>");
        Path run = temporary.resolve("run");
        Files.createDirectories(run);
        Files.writeString(run.resolve("run.json"), Json.object(Map.of(
                "installRoot", game.toString())));
        Path log = run.resolve("desktop-smoke-log-tail.txt");
        Files.writeString(log, "Reading save data from [" + outside + "]\n");
        Map<String, Object> evidence = Map.of(
                "artifacts", List.of(Map.of("kind", "log-tail", "path", log)));

        java.io.IOException failure = assertThrows(
                java.io.IOException.class,
                () -> DesktopBenchmarkLaunch.selectedSave(
                        evidence, Map.of("runDirectory", run)));
        assertTrue(failure.getMessage().contains("inside this installation"));
    }

    private static void writeRunIdentity(Path directory, String profile) throws Exception {
        Files.createDirectories(directory);
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("installRoot", "/game");
        run.put("launcher", "/game/starsector.sh");
        run.put("launcherKind", "shell-script");
        run.put("platform", "MAC");
        run.put("wrapperRuntime", Map.of("javaVersion", "17", "osArch", "x86_64"));
        run.put("directLaunchSettings", Map.of("width", 1440, "height", 932));
        run.put("preflightJarSha256", "cd".repeat(32));
        Files.writeString(directory.resolve("run.json"), Json.object(run));
        Files.writeString(directory.resolve("profile.json"),
                Json.object(Map.of("profileFingerprint", profile)));
    }

    private static Map<String, Object> phase(long elapsedMillis, double fps) {
        return Map.of("summary", Map.of(
                "processToMainMenuMs", elapsedMillis / 2,
                "processToCampaignReadyMs", elapsedMillis,
                "routeElapsedMs", elapsedMillis,
                "averageFps", fps));
    }

    private static DesktopSmokeScenario scenario(String name, String preset, int roamMillis) {
        return DesktopSmokeScenario.parse("""
                {
                  "format":"starsector-preflight-smoke-v1",
                  "name":"%s",
                  "timeoutSeconds":240,
                  "launch":{"preset":"%s","textureStorage":"balanced","profile":null},
                  "steps":[
                    {"id":"menu","kind":"wait-state","state":"main-menu-ready","timeoutSeconds":90},
                    {"id":"roam","kind":"hold-key","key":"w","durationMillis":%d},
                    {"id":"quit","kind":"quit"}
                  ]
                }
                """.formatted(name, preset, roamMillis));
    }
}
