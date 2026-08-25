package dev.starsector.preflight.cli;

import dev.starsector.preflight.agent.FrameTimeTelemetry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopBridgeCommandTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void snapshotExposesOnlyTheDesktopContract() throws Exception {
        Path home = Files.createDirectories(temporaryDirectory.resolve("home"));
        Path game = Files.createDirectories(temporaryDirectory.resolve("Starsector"));
        Path launcher = Files.writeString(game.resolve("starsector.command"), "#!/bin/sh\n");

        Map<String, Object> snapshot = DesktopBridgeCommand.snapshot(
                Platform.MAC, home, temporaryDirectory, Map.of(), game, null);

        assertEquals(1, snapshot.get("protocol"));
        assertEquals("mac", snapshot.get("platform"));
        assertEquals(true, snapshot.get("ready"));
        @SuppressWarnings("unchecked")
        Map<String, Object> selected = (Map<String, Object>) snapshot.get("selected");
        assertNotNull(selected);
        assertEquals(game.toAbsolutePath().normalize(), selected.get("installRoot"));
        assertEquals(launcher.toAbsolutePath().normalize(), selected.get("launcher"));
        assertFalse(selected.containsKey("command"), selected.toString());
        assertNull(snapshot.get("lastRun"));
        @SuppressWarnings("unchecked")
        Map<String, Object> playtime = (Map<String, Object>) snapshot.get("playtime");
        assertEquals(true, playtime.get("readable"));
        assertEquals(0, playtime.get("launches"));
    }

    @Test
    void snapshotCarriesTheDurablePlaytimeTotal() throws Exception {
        Path home = Files.createDirectories(temporaryDirectory.resolve("history-home"));
        Path game = Files.createDirectories(temporaryDirectory.resolve("history-game"));
        Files.writeString(game.resolve("starsector.command"), "#!/bin/sh\n");
        PreflightHome preflightHome = PreflightHome.resolve(Platform.MAC, home, Map.of());
        assertNull(LaunchLedger.record(preflightHome, new LaunchLedger.Entry(
                "launch-1",
                Instant.parse("2026-08-16T00:00:00Z"),
                90 * 60_000L,
                "COMPLETED",
                0,
                false,
                "recommended",
                List.of(),
                "run-directory",
                "profile")));

        Map<String, Object> snapshot = DesktopBridgeCommand.snapshot(
                Platform.MAC, home, temporaryDirectory, Map.of(), game, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> playtime = (Map<String, Object>) snapshot.get("playtime");

        assertEquals(true, playtime.get("readable"));
        assertEquals(90 * 60_000L, playtime.get("totalMillis"));
        assertEquals(1, playtime.get("launches"));
        assertEquals("2026-08-16T00:00:00Z", playtime.get("first").toString());
    }

    @Test
    void snapshotCarriesOnlyTheCompactHealthOfTheLatestRun() throws Exception {
        Path home = Files.createDirectories(temporaryDirectory.resolve("health-home"));
        Path game = Files.createDirectories(temporaryDirectory.resolve("health-game"));
        Files.writeString(game.resolve("starsector.command"), "#!/bin/sh\n");
        Path run = Files.createDirectories(home.resolve(".starsector-preflight/runs/run-1"));
        Files.writeString(run.resolve("run.json"), Json.object(Map.of(
                "installRoot", game,
                "textureProfileFingerprint", "a".repeat(64))));
        Files.writeString(run.resolve("adapter-health.json"), Json.object(Map.ofEntries(
                Map.entry("format", AdapterHealthReport.FORMAT),
                Map.entry("status", "PARTIAL"),
                Map.entry("summary", "A deliberately long engine sentence that the desktop does not need."),
                Map.entry("accelerationsActive", true),
                Map.entry("originalCodeRetained", true),
                Map.entry("reviewRecommended", true),
                Map.entry("transformationsApplied", 31),
                Map.entry("registryTargets", 32),
                Map.entry("containedFailures", 0),
                Map.entry("evidenceKinds", List.of("VERSION_OR_TARGET_MISMATCH")),
                Map.entry("suggestedActions", List.of("Keep playing if the game is healthy.")),
                Map.entry("adapterReport", "/private/source/path/adapter.json"))));

        Map<String, Object> snapshot = DesktopBridgeCommand.snapshot(
                Platform.MAC, home, temporaryDirectory, Map.of(), game, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> lastRun = (Map<String, Object>) snapshot.get("lastRun");
        @SuppressWarnings("unchecked")
        Map<String, Object> health = (Map<String, Object>) lastRun.get("adapterHealth");

        assertEquals("PARTIAL", health.get("status"));
        assertEquals(31L, health.get("transformationsApplied"));
        assertEquals(List.of("VERSION_OR_TARGET_MISMATCH"), health.get("evidenceKinds"));
        assertFalse(health.containsKey("summary"), health.toString());
        assertFalse(health.containsKey("adapterReport"), health.toString());
    }

    @Test
    void snapshotCarriesExactStartupTimingInsteadOfWholeSessionDuration() throws Exception {
        Path home = Files.createDirectories(temporaryDirectory.resolve("run-summary-home"));
        Path game = Files.createDirectories(temporaryDirectory.resolve("run-summary-game"));
        Files.writeString(game.resolve("starsector.command"), "#!/bin/sh\n");
        Path run = Files.createDirectories(home.resolve(".starsector-preflight/runs/run-1"));
        Files.writeString(run.resolve("run.json"), Json.object(Map.of(
                "wrapperPid", 42,
                "wrapperStartedAt", "2026-08-16T11:59:59Z",
                "started", "2026-08-16T12:00:00Z",
                "ended", "2026-08-16T12:00:15.300Z",
                "outcome", "COMPLETED",
                "exitCode", 0,
                "installRoot", game,
                "textureProfileFingerprint", "b".repeat(64))));
        Files.writeString(run.resolve("runtime-state.json"), Json.object(Map.of(
                "format", "starsector-preflight-runtime-state-v1",
                "pid", 42,
                "processStartedAt", "2026-08-16T12:00:00Z",
                "mainMenuReadyAt", "2026-08-16T12:00:15.250Z",
                "state", "stopped",
                "sequence", 4,
                "observedAt", "2026-08-16T14:00:15.300Z")));

        Map<String, Object> snapshot = DesktopBridgeCommand.snapshot(
                Platform.MAC, home, temporaryDirectory, Map.of(), game, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> lastRun = (Map<String, Object>) snapshot.get("lastRun");

        assertEquals("2026-08-16T12:00:00Z", lastRun.get("started"));
        assertEquals("2026-08-16T12:00:15.300Z", lastRun.get("ended"));
        assertEquals(42L, lastRun.get("wrapperPid"));
        assertEquals("2026-08-16T11:59:59Z", lastRun.get("wrapperStartedAt"));
        assertEquals(15250L, lastRun.get("startupMillis"));
        assertFalse(lastRun.containsKey("durationMillis"), lastRun.toString());
        assertEquals("COMPLETED", lastRun.get("outcome"));
        assertEquals(0L, lastRun.get("exitCode"));
        assertEquals(game.toAbsolutePath().normalize(), lastRun.get("installRoot"));
        assertEquals("b".repeat(64), lastRun.get("profileFingerprint"));
    }

    @Test
    void snapshotCarriesOnlyBoundedFramePacingSummariesFromTheLatestRun() throws Exception {
        Path home = Files.createDirectories(temporaryDirectory.resolve("frame-home"));
        Path game = Files.createDirectories(temporaryDirectory.resolve("frame-game"));
        Files.writeString(game.resolve("starsector.command"), "#!/bin/sh\n");
        Path run = Files.createDirectories(home.resolve(".starsector-preflight/runs/run-1"));
        Files.writeString(run.resolve("run.json"), Json.object(Map.of(
                "installRoot", game,
                "textureProfileFingerprint", "9".repeat(64))));
        Map<String, Object> initialCampaign = Map.of(
                "frames", 1383,
                "averageFps", 46.10,
                "onePercentLowFps", 9.15,
                "p95Micros", 44500,
                "p99Micros", 109300);
        Map<String, Object> allCampaign = Map.of(
                "frames", 5474,
                "averageFps", 52.76,
                "onePercentLowFps", 15.06,
                "p95Micros", 31200,
                "p99Micros", 66400,
                "worstFrames", List.of(Map.of("timestamp", "private-detail")));
        Map<String, Object> settledCampaign = Map.of(
                "frames", 4091,
                "averageFps", 55.47,
                "onePercentLowFps", 20.45,
                "p95Micros", 27100,
                "p99Micros", 48900);
        Files.writeString(run.resolve("adapter.json"), Json.object(Map.of(
                FrameTimeTelemetry.REPORT, Map.of(
                        FrameTimeTelemetry.ENABLED, true,
                        FrameTimeTelemetry.CAMPAIGN_ACTIVE, allCampaign,
                        FrameTimeTelemetry.CAMPAIGN_FIRST_30_SECONDS_ACTIVE, initialCampaign,
                        FrameTimeTelemetry.CAMPAIGN_AFTER_30_SECONDS_ACTIVE, settledCampaign,
                        FrameTimeTelemetry.COMBAT_AFTER_CAMPAIGN_ACTIVE, Map.of("frames", 0),
                        FrameTimeTelemetry.MEASUREMENT_OVERHEAD,
                        Map.of(FrameTimeTelemetry.AVERAGE_MICROS, 1.78)))));

        Map<String, Object> snapshot = DesktopBridgeCommand.snapshot(
                Platform.MAC, home, temporaryDirectory, Map.of(), game, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> lastRun = (Map<String, Object>) snapshot.get("lastRun");
        @SuppressWarnings("unchecked")
        Map<String, Object> framePacing = (Map<String, Object>) lastRun.get("framePacing");
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) framePacing.get("campaign");
        @SuppressWarnings("unchecked")
        Map<String, Object> initial = (Map<String, Object>) framePacing.get("initialCampaign");
        @SuppressWarnings("unchecked")
        Map<String, Object> settled = (Map<String, Object>) framePacing.get("settledCampaign");

        assertEquals("starsector-preflight-frame-pacing-summary-v1", framePacing.get("format"));
        assertEquals(5474L, summary.get("frames"));
        assertEquals(52.76, summary.get("averageFps"));
        assertEquals(15.06, summary.get("onePercentLowFps"));
        assertEquals(1383L, initial.get("frames"));
        assertEquals(9.15, initial.get("onePercentLowFps"));
        assertEquals(4091L, settled.get("frames"));
        assertEquals(20.45, settled.get("onePercentLowFps"));
        assertEquals(1.78, framePacing.get("measurementAverageMicros"));
        assertFalse(summary.containsKey("worstFrames"), summary.toString());
        assertNull(framePacing.get("combat"));
    }

    @Test
    void snapshotRejectsAnOversizedFramePacingReport() throws Exception {
        Path home = Files.createDirectories(temporaryDirectory.resolve("oversized-frame-home"));
        Path game = Files.createDirectories(temporaryDirectory.resolve("oversized-frame-game"));
        Files.writeString(game.resolve("starsector.command"), "#!/bin/sh\n");
        Path run = Files.createDirectories(home.resolve(".starsector-preflight/runs/run-1"));
        Files.writeString(run.resolve("run.json"), Json.object(Map.of("installRoot", game)));
        Files.write(run.resolve("adapter.json"), new byte[512 * 1024 + 1]);

        Map<String, Object> snapshot = DesktopBridgeCommand.snapshot(
                Platform.MAC, home, temporaryDirectory, Map.of(), game, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> lastRun = (Map<String, Object>) snapshot.get("lastRun");

        assertNull(lastRun.get("framePacing"));
    }

    @Test
    void snapshotUsesTheNewestRunBoundToTheSelectedInstallation() throws Exception {
        Path home = Files.createDirectories(temporaryDirectory.resolve("two-install-home"));
        Path selectedGame = Files.createDirectories(temporaryDirectory.resolve("selected-game"));
        Path otherGame = Files.createDirectories(temporaryDirectory.resolve("other-game"));
        Files.writeString(selectedGame.resolve("starsector.command"), "#!/bin/sh\n");
        Files.writeString(otherGame.resolve("starsector.command"), "#!/bin/sh\n");
        Path runs = Files.createDirectories(home.resolve(".starsector-preflight/runs"));
        Path selectedRun = Files.createDirectories(runs.resolve("selected-run"));
        Files.writeString(selectedRun.resolve("run.json"), Json.object(Map.of(
                "installRoot", selectedGame,
                "textureProfileFingerprint", "c".repeat(64),
                "started", "2026-08-16T12:00:00Z")));
        Files.setLastModifiedTime(selectedRun, FileTime.from(Instant.parse("2026-08-16T12:00:00Z")));
        Path foreignRun = Files.createDirectories(runs.resolve("foreign-run"));
        Files.writeString(foreignRun.resolve("run.json"), Json.object(Map.of(
                "installRoot", otherGame,
                "textureProfileFingerprint", "d".repeat(64),
                "started", "2026-08-17T12:00:00Z")));
        Files.setLastModifiedTime(foreignRun, FileTime.from(Instant.parse("2026-08-17T12:00:00Z")));

        Map<String, Object> snapshot = DesktopBridgeCommand.snapshot(
                Platform.MAC, home, temporaryDirectory, Map.of(), selectedGame, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> lastRun = (Map<String, Object>) snapshot.get("lastRun");

        assertNotNull(lastRun);
        assertEquals(selectedRun.toAbsolutePath().normalize(), lastRun.get("directory"));
        assertEquals("c".repeat(64), lastRun.get("profileFingerprint"));
    }

    @Test
    void snapshotDoesNotGuessTheInstallationOfLegacyRunMetadata() throws Exception {
        Path home = Files.createDirectories(temporaryDirectory.resolve("legacy-run-home"));
        Path game = Files.createDirectories(temporaryDirectory.resolve("legacy-run-game"));
        Files.writeString(game.resolve("starsector.command"), "#!/bin/sh\n");
        Path run = Files.createDirectories(home.resolve(".starsector-preflight/runs/run-1"));
        Files.writeString(run.resolve("run.json"), Json.object(Map.of(
                "started", "2026-08-16T12:00:00Z",
                "textureProfileFingerprint", "e".repeat(64))));

        Map<String, Object> snapshot = DesktopBridgeCommand.snapshot(
                Platform.MAC, home, temporaryDirectory, Map.of(), game, null);

        assertNull(snapshot.get("lastRun"));
    }

    @Test
    void snapshotIgnoresUnrecognisedAdapterHealth() throws Exception {
        Path home = Files.createDirectories(temporaryDirectory.resolve("unknown-health-home"));
        Path game = Files.createDirectories(temporaryDirectory.resolve("unknown-health-game"));
        Files.writeString(game.resolve("starsector.command"), "#!/bin/sh\n");
        Path run = Files.createDirectories(home.resolve(".starsector-preflight/runs/run-1"));
        Files.writeString(run.resolve("run.json"), Json.object(Map.of(
                "installRoot", game,
                "textureProfileFingerprint", "f".repeat(64))));
        Files.writeString(run.resolve("adapter-health.json"),
                "{\"format\":\"future-format\",\"status\":\"ACTIVE\"}");

        Map<String, Object> snapshot = DesktopBridgeCommand.snapshot(
                Platform.MAC, home, temporaryDirectory, Map.of(), game, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> lastRun = (Map<String, Object>) snapshot.get("lastRun");

        assertNull(lastRun.get("adapterHealth"));
    }

    @Test
    void snapshotWithoutAnInstallIsAValidSetupState() throws Exception {
        Path home = Files.createDirectories(temporaryDirectory.resolve("empty-home"));
        Path current = Files.createDirectories(temporaryDirectory.resolve("empty-current"));

        Map<String, Object> snapshot =
                DesktopBridgeCommand.snapshot(Platform.LINUX, home, current, Map.of(), null, null);

        assertEquals(false, snapshot.get("ready"));
        assertNull(snapshot.get("selected"));
        assertTrue(snapshot.get("diagnostics").toString().contains("No launcher found"));
    }

    @Test
    void bootstrapCommandKeepsSetupUsableWithoutAnInstallation() throws Exception {
        Path home = Files.createDirectories(temporaryDirectory.resolve("bootstrap-home"));
        Path current = Files.createDirectories(temporaryDirectory.resolve("bootstrap-current"));

        Map<String, Object> bootstrap = DesktopBridgeCommand.bootstrap(
                Platform.LINUX, home, current, Map.of(), null, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) bootstrap.get("snapshot");

        assertEquals("starsector-preflight-desktop-bootstrap-v1", bootstrap.get("format"));
        assertEquals(false, snapshot.get("ready"));
        assertNull(snapshot.get("selected"));
        assertNull(bootstrap.get("homeState"));
        assertNull(bootstrap.get("homeStateError"));
    }

    @Test
    void bootstrapCommandReturnsHomeStateForTheSelectedInstallation() throws Exception {
        Path home = Files.createDirectories(temporaryDirectory.resolve("selected-home"));
        Path game = Files.createDirectories(temporaryDirectory.resolve("selected-game"));
        Files.writeString(game.resolve("starsector.command"), "#!/bin/sh\n");
        Path mods = Files.createDirectories(game.resolve("mods"));
        Files.writeString(mods.resolve("enabled_mods.json"), "{\"enabledMods\":[]}");

        Map<String, Object> bootstrap = DesktopBridgeCommand.bootstrap(
                Platform.MAC, home, temporaryDirectory, Map.of(), game, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> homeState = (Map<String, Object>) bootstrap.get("homeState");

        assertNotNull(homeState);
        assertEquals("starsector-preflight-desktop-home-state-v1", homeState.get("format"));
        assertNotNull(homeState.get("cacheInspection"));
        assertNotNull(homeState.get("profiles"));
        assertNotNull(homeState.get("launchSettings"));
        assertNull(bootstrap.get("homeStateError"));
    }

    @Test
    void desktopSmokeStatusesHaveScriptableExitCodes() {
        assertEquals(0, DesktopBridgeCommand.statusExitCode("passed"));
        assertEquals(3, DesktopBridgeCommand.statusExitCode("skipped"));
        assertEquals(1, DesktopBridgeCommand.statusExitCode("failed"));
        assertEquals(1, DesktopBridgeCommand.statusExitCode(null));
    }

    @Test
    void benchmarkFailuresKeepTheActionableEngineReason() {
        java.io.IOException failure = DesktopBridgeCommand.launchFailure(
                "Desktop benchmark launch failed",
                new IllegalStateException("runtime marker didn't appear"));

        assertTrue(failure.getMessage().contains("runtime marker didn't appear"));
    }
}
