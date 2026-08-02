package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UninstallCommandTest {
    @TempDir
    Path home;

    @Test
    void nothingIsRemovedWithoutConfirmation() throws Exception {
        PreflightHome preflight = macHome();
        installIntegration(preflight);
        Files.createDirectories(preflight.root().resolve("cache"));
        Files.writeString(preflight.root().resolve("cache/artifact"), "prepared");

        String output = run(preflight, true, false);
        assertTrue(output.contains("Would remove:"), output);
        assertTrue(output.contains("Re-run with --yes"), output);
        assertTrue(Files.isDirectory(preflight.root()), "a dry run must not delete the cache");
        assertTrue(preflight.integrations().get(0).present(), "a dry run must not delete the app");
    }

    @Test
    void theDefaultScopeRemovesTheIntegrationAndKeepsTheCache() throws Exception {
        PreflightHome preflight = macHome();
        installIntegration(preflight);
        Files.createDirectories(preflight.root().resolve("cache"));
        Files.writeString(preflight.root().resolve("cache/artifact"), "prepared");

        assertEquals(0, UninstallCommand.run(preflight, false, true, quiet()));
        assertFalse(preflight.integrations().get(0).present(), "the app should be gone");
        assertTrue(Files.isRegularFile(preflight.root().resolve("cache/artifact")),
                "the cache is only removed by --purge");
    }

    @Test
    void purgeRemovesEverythingPreflightWrote() throws Exception {
        PreflightHome preflight = macHome();
        installIntegration(preflight);
        Files.createDirectories(preflight.root().resolve("runs/20260101-000000-000-abcdefgh"));
        Files.writeString(preflight.root().resolve("runs/20260101-000000-000-abcdefgh/run.json"), "{}");
        Files.createDirectories(preflight.root().resolve("cache/blobs/00"));
        Files.writeString(preflight.root().resolve("cache/blobs/00/blob"), "payload");

        assertEquals(0, UninstallCommand.run(preflight, true, true, quiet()));
        assertFalse(Files.exists(preflight.root()), "the home directory should be gone");
        assertFalse(preflight.integrations().get(0).present(), "the app should be gone");
    }

    @Test
    void theGameDirectoryIsNeverATarget() throws Exception {
        // Preflight has no undo to perform because it has nothing to undo: this pins that a purge
        // reaches only its own root, so a game install beside it survives untouched.
        PreflightHome preflight = macHome();
        Path game = home.resolve("Starsector.app");
        Files.createDirectories(game.resolve("Contents/Resources/Java"));
        Files.writeString(game.resolve("Contents/Resources/Java/starfarer_obf.jar"), "game");
        Files.createDirectories(preflight.root());
        Files.writeString(preflight.root().resolve("marker"), "preflight");

        assertEquals(0, UninstallCommand.run(preflight, true, true, quiet()));
        assertFalse(Files.exists(preflight.root()));
        assertTrue(Files.isRegularFile(game.resolve("Contents/Resources/Java/starfarer_obf.jar")),
                "the game install must be untouched");
    }

    @Test
    void everyIdTheInstallerWritesToIsOneTheUninstallerWillRemove() {
        // InstallCommand asks PreflightHome for these by id rather than rebuilding the paths, so
        // this pins that each platform actually defines the ids its installer reaches for. A new
        // integration added to the installer without a matching id fails to compile; one added to
        // PreflightHome without being installed shows up here.
        assertEquals(
                java.util.Set.of(PreflightHome.Id.MAC_APP),
                ids(Platform.MAC));
        assertEquals(
                java.util.Set.of(PreflightHome.Id.LINUX_COMMAND, PreflightHome.Id.LINUX_DESKTOP_ENTRY),
                ids(Platform.LINUX));
        assertEquals(
                java.util.Set.of(PreflightHome.Id.WINDOWS_COMMAND, PreflightHome.Id.WINDOWS_DIRECTORY),
                ids(Platform.WINDOWS));
        assertTrue(PreflightHome.resolve(Platform.OTHER, home, Map.of()).integrations().isEmpty(),
                "OTHER installs no integration, so it must claim none");
    }

    @Test
    void theWindowsLauncherFollowsLocalAppDataWhenTheEnvironmentSetsIt() {
        assertEquals(
                home.resolve("AppData/Local/Starsector Preflight/Starsector Preflight.cmd"),
                PreflightHome.resolve(Platform.WINDOWS, home, Map.of())
                        .pathOf(PreflightHome.Id.WINDOWS_COMMAND));
        assertEquals(
                Path.of("/local-app-data/Starsector Preflight/Starsector Preflight.cmd"),
                PreflightHome.resolve(Platform.WINDOWS, home, Map.of("LOCALAPPDATA", "/local-app-data"))
                        .pathOf(PreflightHome.Id.WINDOWS_COMMAND));
    }

    private java.util.Set<PreflightHome.Id> ids(Platform platform) {
        return PreflightHome.resolve(platform, home, Map.of()).integrations().stream()
                .map(PreflightHome.Integration::id)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void reportingStorageDoesNotRequireAnythingToBeInstalled() throws Exception {
        PreflightHome preflight = macHome();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            assertEquals(0, CacheCommand.report(preflight, null, out));
        }
        assertTrue(captured.toString(StandardCharsets.UTF_8).contains("storing nothing"),
                captured.toString(StandardCharsets.UTF_8));
    }

    @Test
    void theReportMarksTheProfileTheCurrentInstallResolvesTo() throws Exception {
        PreflightHome preflight = macHome();
        String current = "a".repeat(64);
        String stale = "b".repeat(64);
        Files.createDirectories(preflight.cache().resolve("resource-indexes"));
        Files.createDirectories(preflight.cache().resolve("manifests"));
        Files.writeString(preflight.cache().resolve("resource-indexes/" + current + ".spfi"), "index");
        Files.writeString(preflight.cache().resolve("manifests/" + current + ".spfm"), "manifest");
        Files.writeString(preflight.cache().resolve("resource-indexes/" + stale + ".spfi"), "index");

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            assertEquals(0, CacheCommand.report(preflight, current, out));
        }
        String output = captured.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Prepared profiles (2)"), output);
        assertTrue(output.contains(current.substring(0, 16) + "  <- current install"), output);
        assertFalse(output.contains(stale.substring(0, 16) + "  <- current"), output);
    }

    private PreflightHome macHome() {
        return PreflightHome.resolve(Platform.MAC, home, Map.of());
    }

    private static void installIntegration(PreflightHome preflight) throws Exception {
        Path app = preflight.integrations().get(0).path();
        Files.createDirectories(app.resolve("Contents/MacOS"));
        Files.writeString(app.resolve("Contents/MacOS/starsector-preflight"), "#!/bin/sh\n");
        Files.writeString(app.resolve("Contents/Info.plist"), "<plist/>");
    }

    private static String run(PreflightHome preflight, boolean purge, boolean confirmed)
            throws Exception {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            UninstallCommand.run(preflight, purge, confirmed, out);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static PrintStream quiet() {
        return new PrintStream(java.io.OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);
    }
}
