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
                phase(Instant.parse("2026-08-09T00:00:00Z"), Instant.parse("2026-08-09T00:01:40Z")),
                phase(Instant.parse("2026-08-09T00:02:00Z"), Instant.parse("2026-08-09T00:03:00Z"))));
        assertEquals(100_000L, comparison.get("measurementOnlyElapsedMs"));
        assertEquals(60_000L, comparison.get("optimizedElapsedMs"));
        assertEquals(-40_000L, comparison.get("elapsedDeltaMs"));
        assertEquals(40.0, comparison.get("elapsedImprovementPercent"));
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

    private static Map<String, Object> phase(Instant start, Instant end) {
        return Map.of("launch", Map.of("evidence", Map.of(
                "startedAt", start,
                "completedAt", end)));
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
