package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Json;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void snapshotCarriesTheCompactRunSummaryOfTheLatestRun() throws Exception {
        Path home = Files.createDirectories(temporaryDirectory.resolve("run-summary-home"));
        Path game = Files.createDirectories(temporaryDirectory.resolve("run-summary-game"));
        Files.writeString(game.resolve("starsector.command"), "#!/bin/sh\n");
        Path run = Files.createDirectories(home.resolve(".starsector-preflight/runs/run-1"));
        Files.writeString(run.resolve("run.json"), Json.object(Map.of(
                "started", "2026-08-16T12:00:00Z",
                "ended", "2026-08-16T12:00:15.300Z",
                "outcome", "COMPLETED",
                "exitCode", 0)));

        Map<String, Object> snapshot = DesktopBridgeCommand.snapshot(
                Platform.MAC, home, temporaryDirectory, Map.of(), game, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> lastRun = (Map<String, Object>) snapshot.get("lastRun");

        assertEquals("2026-08-16T12:00:00Z", lastRun.get("started"));
        assertEquals("2026-08-16T12:00:15.300Z", lastRun.get("ended"));
        assertEquals(15300L, lastRun.get("durationMillis"));
        assertEquals("COMPLETED", lastRun.get("outcome"));
        assertEquals(0L, lastRun.get("exitCode"));
    }

    @Test
    void snapshotIgnoresUnrecognisedAdapterHealth() throws Exception {
        Path home = Files.createDirectories(temporaryDirectory.resolve("unknown-health-home"));
        Path game = Files.createDirectories(temporaryDirectory.resolve("unknown-health-game"));
        Files.writeString(game.resolve("starsector.command"), "#!/bin/sh\n");
        Path run = Files.createDirectories(home.resolve(".starsector-preflight/runs/run-1"));
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
