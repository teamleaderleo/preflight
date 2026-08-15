package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
