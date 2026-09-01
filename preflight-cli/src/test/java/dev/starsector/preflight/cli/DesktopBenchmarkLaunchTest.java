package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopBenchmarkLaunchTest {
    @Test
    void aStuckMonitorIsSuppressedOntoTheFailureThatAbortedThePhase() throws Exception {
        IllegalStateException launchFailure = new IllegalStateException("launch failed");
        StubbornThread mirror = StubbornThread.started();
        try {
            DesktopBenchmarkLaunch.stopCancellationMirror(
                    "baseline", mirror, new AtomicBoolean(), launchFailure);

            assertEquals(1, launchFailure.getSuppressed().length);
            assertEquals(
                    "The baseline cancellation monitor didn't stop",
                    launchFailure.getSuppressed()[0].getMessage());
        } finally {
            mirror.release();
        }
    }

    @Test
    void aStuckMonitorFailsThePhaseWhenTheLaunchItselfSucceeded() throws Exception {
        StubbornThread mirror = StubbornThread.started();
        try {
            IOException thrown = assertThrows(IOException.class, () ->
                    DesktopBenchmarkLaunch.stopCancellationMirror(
                            "candidate", mirror, new AtomicBoolean(), null));

            assertEquals("The candidate cancellation monitor didn't stop", thrown.getMessage());
        } finally {
            mirror.release();
        }
    }

    @Test
    void aMonitorThatStopsMarksThePhaseFinishedAndReportsNothing() throws Exception {
        AtomicBoolean finished = new AtomicBoolean();
        Thread mirror = new Thread(() -> { });
        mirror.start();

        DesktopBenchmarkLaunch.stopCancellationMirror("baseline", mirror, finished, null);

        assertTrue(finished.get());
    }

    @Test
    void interruptionWhileWaitingKeepsTheInterruptStatusAndReportsNothing() throws Exception {
        StubbornThread mirror = StubbornThread.started();
        AtomicBoolean interruptRetained = new AtomicBoolean();
        try {
            Thread caller = new Thread(() -> {
                Thread.currentThread().interrupt();
                try {
                    DesktopBenchmarkLaunch.stopCancellationMirror(
                            "baseline", mirror, new AtomicBoolean(), null);
                    interruptRetained.set(Thread.currentThread().isInterrupted());
                } catch (IOException unexpected) {
                    interruptRetained.set(false);
                }
            });
            caller.start();
            caller.join(5_000L);

            assertTrue(interruptRetained.get(), "a cut-short wait must not look like a clean stop");
        } finally {
            mirror.release();
        }
    }

    /** A mirror thread that ignores interruption, standing in for one that will not stop. */
    private static final class StubbornThread extends Thread {
        private final AtomicBoolean released = new AtomicBoolean();

        static StubbornThread started() {
            StubbornThread thread = new StubbornThread();
            thread.setDaemon(true);
            thread.start();
            return thread;
        }

        @Override
        public void run() {
            while (!released.get()) {
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException ignored) {
                    // Deliberately unresponsive to interruption.
                }
            }
        }

        void release() throws InterruptedException {
            released.set(true);
            join(5_000L);
        }
    }

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
    void comparisonRanksRecurringStutterAheadOfSupportingFpsMetrics() {
        Map<String, Object> baseline = Map.of(
                "stutterBurdenMillisPerSecond", 80.0,
                "repeatedSlowFramesPercent", 5.0,
                "slowFramesPerMinute", 180.0,
                "longestSlowFrameClusterMillis", 600.0,
                "onePercentLowFps", 14.0,
                "averageFps", 52.0);
        Map<String, Object> optimized = Map.of(
                "stutterBurdenMillisPerSecond", 40.0,
                "repeatedSlowFramesPercent", 2.5,
                "slowFramesPerMinute", 90.0,
                "longestSlowFrameClusterMillis", 300.0,
                "onePercentLowFps", 16.0,
                "averageFps", 53.0);

        Map<String, Object> comparison = DesktopBenchmarkLaunch.comparison(List.of(
                Map.of("summary", baseline), Map.of("summary", optimized)));
        @SuppressWarnings("unchecked")
        List<String> priority = (List<String>) comparison.get("smoothnessPriority");
        assertEquals("stutterBurdenMillisPerSecond", priority.get(0));
        assertTrue(priority.indexOf("repeatedSlowFramesPercent")
                < priority.indexOf("onePercentLowFps"));
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) comparison.get("metrics");
        @SuppressWarnings("unchecked")
        Map<String, Object> burden =
                (Map<String, Object>) metrics.get("stutterBurdenMillisPerSecond");
        assertEquals(50.0, burden.get("improvementPercent"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void comparisonKeepsPausedAndUnpausedDistributionsIndependent() {
        Map<String, Object> baseline = Map.of("campaignStateWindows", Map.of(
                "paused", Map.of(
                        "stutterBurdenMillisPerSecond", 30.0,
                        "onePercentLowFps", 30.0),
                "unpaused", Map.of(
                        "stutterBurdenMillisPerSecond", 90.0,
                        "onePercentLowFps", 15.0)));
        Map<String, Object> optimized = Map.of("campaignStateWindows", Map.of(
                "paused", Map.of(
                        "stutterBurdenMillisPerSecond", 15.0,
                        "onePercentLowFps", 40.0),
                "unpaused", Map.of(
                        "stutterBurdenMillisPerSecond", 60.0,
                        "onePercentLowFps", 18.0)));

        Map<String, Object> comparison = DesktopBenchmarkLaunch.comparison(List.of(
                Map.of("summary", baseline), Map.of("summary", optimized)));
        Map<String, Object> states =
                (Map<String, Object>) comparison.get("campaignStateWindows");
        Map<String, Object> paused = (Map<String, Object>) states.get("paused");
        Map<String, Object> pausedMetrics = (Map<String, Object>) paused.get("metrics");
        Map<String, Object> pausedBurden =
                (Map<String, Object>) pausedMetrics.get("stutterBurdenMillisPerSecond");
        Map<String, Object> unpaused = (Map<String, Object>) states.get("unpaused");
        Map<String, Object> unpausedMetrics = (Map<String, Object>) unpaused.get("metrics");
        Map<String, Object> unpausedLow =
                (Map<String, Object>) unpausedMetrics.get("onePercentLowFps");

        assertEquals(true, comparison.get("available"));
        assertEquals(50.0, pausedBurden.get("improvementPercent"));
        assertEquals(20.0, unpausedLow.get("improvementPercent"));
    }

    @Test
    void eachStateWindowMustMeetTheFullCoverageGate() {
        IOException missing = assertThrows(IOException.class, () ->
                DesktopBenchmarkLaunch.campaignFrameSummary(null, "paused campaign"));
        IOException shortWindow = assertThrows(IOException.class, () ->
                DesktopBenchmarkLaunch.campaignFrameSummary(Map.of(
                        "frames", 99,
                        "totalActiveNanos", 29_999_999_999L), "unpaused campaign"));

        assertTrue(missing.getMessage().contains("lacks settled paused campaign frames"));
        assertTrue(shortWindow.getMessage().contains(
                "settled unpaused campaign coverage requires at least 100 frames and 30 active seconds"));
    }

    @Test
    void sealsMeasurementOnlyIdentityAfterTimingWithoutProfileMetadata(@TempDir Path temporary)
            throws Exception {
        Path install = temporary.resolve("game");
        Path mod = install.resolve("mods/Example");
        Files.createDirectories(mod);
        Files.writeString(mod.resolve("mod_info.json"), "{\"id\":\"example\"}");
        Files.writeString(
                install.resolve("mods/enabled_mods.json"),
                "{\"enabledMods\":[\"example\"]}");

        Path runDirectory = temporary.resolve("run");
        Files.createDirectories(runDirectory);
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("installRoot", install.toString());
        run.put("launcher", install.resolve("starsector.sh").toString());
        run.put("launcherKind", "shell-script");
        run.put("platform", "MAC");
        run.put("wrapperRuntime", Map.of("javaVersion", "17", "osArch", "x86_64"));
        run.put("directLaunchSettings", Map.of("width", 1440, "height", 932));
        run.put("preflightJarSha256", "cd".repeat(32));
        Files.writeString(runDirectory.resolve("run.json"), Json.object(run));

        Map<String, Object> identity = DesktopBenchmarkLaunch.measuredIdentity(runDirectory);

        assertEquals(
                ProfileCensus.scan(install).values().get("profileFingerprint"),
                identity.get("profileFingerprint"));
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
                "frameTimes", Map.of(
                        "campaignAfter30SecondsActive", Map.ofEntries(
                                Map.entry("frames", 1_800),
                                Map.entry("totalActiveNanos", 31_000_000_000L),
                                Map.entry("averageFps", 60.0),
                                Map.entry("medianFps", 62.0),
                                Map.entry("onePercentLowFps", 35.0),
                                Map.entry("pointOnePercentLowFps", 22.0),
                                Map.entry("p95Micros", 20_000),
                                Map.entry("p99Micros", 28_571),
                                Map.entry("framesMeeting60FpsPercent", 88.0),
                                Map.entry("over33_33Millis", 12),
                                Map.entry("over50Millis", 5),
                                Map.entry("over100Millis", 1),
                                Map.entry("stutterProfile", Map.of(
                                        "slowFramesPerMinute", 12.0,
                                        "stutterBurdenMillisPerSecond", 3.5,
                                        "repeatedSlowFramesPercent", 0.4,
                                        "longestSlowFrameClusterMillis", 80.0))),
                        "campaignPausedAfter30SecondsActive", Map.ofEntries(
                                Map.entry("frames", 900),
                                Map.entry("totalActiveNanos", 31_000_000_000L),
                                Map.entry("averageFps", 58.0),
                                Map.entry("medianFps", 60.0),
                                Map.entry("onePercentLowFps", 32.0),
                                Map.entry("pointOnePercentLowFps", 20.0),
                                Map.entry("p95Micros", 21_000),
                                Map.entry("p99Micros", 31_250),
                                Map.entry("framesMeeting60FpsPercent", 78.0),
                                Map.entry("over33_33Millis", 8),
                                Map.entry("over50Millis", 3),
                                Map.entry("over100Millis", 1),
                                Map.entry("stutterProfile", Map.of(
                                        "slowFramesPerMinute", 9.0,
                                        "stutterBurdenMillisPerSecond", 2.5,
                                        "repeatedSlowFramesPercent", 0.3,
                                        "longestSlowFrameClusterMillis", 70.0))),
                        "campaignUnpausedAfter30SecondsActive", Map.ofEntries(
                                Map.entry("frames", 850),
                                Map.entry("totalActiveNanos", 31_500_000_000L),
                                Map.entry("averageFps", 52.0),
                                Map.entry("medianFps", 55.0),
                                Map.entry("onePercentLowFps", 24.0),
                                Map.entry("pointOnePercentLowFps", 14.0),
                                Map.entry("p95Micros", 25_000),
                                Map.entry("p99Micros", 41_667),
                                Map.entry("framesMeeting60FpsPercent", 62.0),
                                Map.entry("over33_33Millis", 18),
                                Map.entry("over50Millis", 7),
                                Map.entry("over100Millis", 2),
                                Map.entry("stutterProfile", Map.of(
                                        "slowFramesPerMinute", 22.0,
                                        "stutterBurdenMillisPerSecond", 6.5,
                                        "repeatedSlowFramesPercent", 0.8,
                                        "longestSlowFrameClusterMillis", 130.0))),
                        "measurementOverhead", Map.of(
                                "samples", 1_200,
                                "totalNanos", 12_000_000,
                                "averageMicros", 10.0,
                                "maximumMicros", 80.0)))));
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
                Map.of("id", "campaign", "completedAt", processStart.plusSeconds(20)),
                Map.of("id", "paused-settled", "completedAt", processStart.plusSeconds(21)),
                Map.of("id", "unpaused-settled", "completedAt", processStart.plusSeconds(22))));
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
        assertEquals(12L, summary.get("over33_33Millis"));
        assertEquals(5L, summary.get("over50Millis"));
        assertEquals(1L, summary.get("over100Millis"));
        assertEquals(12.0, summary.get("slowFramesPerMinute"));
        assertEquals(3.5, summary.get("stutterBurdenMillisPerSecond"));
        assertEquals(0.4, summary.get("repeatedSlowFramesPercent"));
        assertEquals(80.0, summary.get("longestSlowFrameClusterMillis"));
        assertEquals("after-first-30-seconds", summary.get("campaignWindow"));
        assertEquals(1_800L, summary.get("campaignFrames"));
        assertEquals(31_000_000_000L, summary.get("campaignActiveNanos"));
        Map<String, Object> coverage = (Map<String, Object>) summary.get("campaignCoverage");
        assertEquals(100L, coverage.get("minimumFrames"));
        assertEquals(30_000_000_000L, coverage.get("minimumActiveNanos"));
        assertEquals(true, coverage.get("accepted"));
        Map<String, Object> overhead = (Map<String, Object>) summary.get("measurementOverhead");
        assertEquals(1_200L, overhead.get("samples"));
        assertEquals(0.05, overhead.get("routeSharePercent"));
        assertEquals(true, overhead.get("withinBudget"));
        Map<String, Object> selected = (Map<String, Object>) summary.get("selectedSave");
        assertEquals("save_Test_123", selected.get("directory"));
        assertTrue(selected.get("descriptorSha256").toString().matches("[0-9a-f]{64}"));
        Map<String, Object> context = (Map<String, Object>) summary.get("runtimeContext");
        assertEquals(27L, context.get("cacheHits"));
        assertEquals(8L, context.get("cacheMisses"));
        assertEquals(3L, context.get("fallbacks"));
        assertEquals(61L, context.get("memoryAvailablePercent"));
        Map<String, Object> states =
                (Map<String, Object>) summary.get("campaignStateWindows");
        Map<String, Object> paused = (Map<String, Object>) states.get("paused");
        Map<String, Object> unpaused = (Map<String, Object>) states.get("unpaused");
        assertEquals(900L, paused.get("frames"));
        assertEquals(32.0, paused.get("onePercentLowFps"));
        assertEquals(850L, unpaused.get("frames"));
        assertEquals(6.5, unpaused.get("stutterBurdenMillisPerSecond"));
    }

    @Test
    void refusesCampaignMetricsWithoutRepresentativeSettledCoverage(@TempDir Path temporary)
            throws Exception {
        Instant processStart = Instant.parse("2026-08-09T00:00:00Z");
        Path game = temporary.resolve("game");
        Path save = game.resolve("saves/save_Test_123");
        Files.createDirectories(save);
        Path descriptor = save.resolve("descriptor.xml");
        Files.writeString(descriptor, "<save/>");
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
                "frameTimes", Map.of(
                        "campaignAfter30SecondsActive", Map.of(
                                "frames", 99,
                                "totalActiveNanos", 29_999_999_999L),
                        "measurementOverhead", Map.of(
                                "samples", 1_200,
                                "totalNanos", 12_000_000,
                                "averageMicros", 10.0,
                                "maximumMicros", 80.0)))));
        Path log = run.resolve("desktop-smoke-log-tail.txt");
        Files.writeString(log, "Reading save data from [" + descriptor + "]\n");
        Path health = run.resolve("desktop-smoke-adapter-health.json");
        Files.writeString(health, Json.object(Map.of(
                "format", "starsector-preflight-runtime-adapter-health-v1",
                "adapterMode", "enabled")));
        Map<String, Object> evidence = Map.of(
                "startedAt", processStart.plusSeconds(1),
                "completedAt", processStart.plusSeconds(25),
                "steps", List.of(
                        Map.of("id", "menu", "completedAt", processStart.plusSeconds(10)),
                        Map.of("id", "campaign", "completedAt", processStart.plusSeconds(20))),
                "artifacts", List.of(
                        Map.of("kind", "frame-report", "path", frames),
                        Map.of("kind", "log-tail", "path", log),
                        Map.of("kind", "adapter-health", "path", health)));

        Map<String, Object> phase = DesktopBenchmarkLaunch.phase("optimized", Map.of(
                "status", "passed",
                "runtimeProcess", runtime,
                "runDirectory", run,
                "evidence", evidence));

        assertEquals("failed", phase.get("status"));
        assertTrue(phase.get("summaryError").toString().contains(
                "at least 100 frames and 30 active seconds"), phase.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sealsAStartupOnlyComparisonWithoutSaveOrDesktopArtifacts(@TempDir Path temporary)
            throws Exception {
        Instant processStart = Instant.parse("2026-08-09T00:00:00Z");
        Path run = Files.createDirectories(temporary.resolve("startup"));
        Path runtime = run.resolve("runtime-process.json");
        Map<String, Object> runtimeIdentity = new LinkedHashMap<>();
        runtimeIdentity.put("format", "starsector-preflight-runtime-process-v1");
        runtimeIdentity.put("pid", ProcessHandle.current().pid());
        runtimeIdentity.put("parentPid", null);
        runtimeIdentity.put("startedAt", processStart);
        runtimeIdentity.put("observedAt", processStart.plusSeconds(20));
        runtimeIdentity.put("state", "stopped");
        runtimeIdentity.put("stoppedAt", processStart.plusSeconds(20));
        Files.writeString(runtime, Json.object(runtimeIdentity));
        Map<String, Object> evidence = Map.of(
                "steps", List.of(Map.of(
                        "id", "menu", "completedAt", processStart.plusSeconds(15))),
                "artifacts", List.of());

        Map<String, Object> phase = DesktopBenchmarkLaunch.phase("optimized", Map.of(
                "status", "passed",
                "runtimeProcess", runtime,
                "runDirectory", run,
                "evidence", evidence));

        assertEquals("passed", phase.get("status"));
        Map<String, Object> summary = (Map<String, Object>) phase.get("summary");
        assertEquals(Map.of("processToMainMenuMs", 15_000L), summary);
        Map<String, Object> comparison = DesktopBenchmarkLaunch.comparison(List.of(
                Map.of("summary", Map.of("processToMainMenuMs", 88_000L)),
                Map.of("summary", Map.of("processToMainMenuMs", 15_880L))));
        assertEquals(true, comparison.get("available"));
        Map<String, Object> metrics = (Map<String, Object>) comparison.get("metrics");
        assertEquals(Set.of("processToMainMenuMs"), metrics.keySet());
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
