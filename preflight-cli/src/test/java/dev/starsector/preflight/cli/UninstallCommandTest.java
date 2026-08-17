package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void theDefaultScopeAlsoRemovesTheInstalledCommandEngine() throws Exception {
        PreflightHome preflight = macHome();
        installIntegration(preflight);
        Files.createDirectories(preflight.installedJar().getParent());
        Files.writeString(preflight.installedJar(), "engine");
        Files.createDirectories(preflight.root().resolve("cache"));
        Files.writeString(preflight.root().resolve("cache/artifact"), "prepared");

        assertEquals(0, UninstallCommand.run(preflight, false, true, quiet()));
        assertFalse(Files.exists(preflight.installedJar().getParent()));
        assertTrue(Files.isRegularFile(preflight.root().resolve("cache/artifact")));
    }

    @Test
    void jsonPreviewNamesTheScopeAndPinsTheGameBoundary() throws Exception {
        PreflightHome preflight = macHome();
        installIntegration(preflight);

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            assertEquals(0, UninstallCommand.run(
                    preflight, UninstallCommand.Scope.LAUNCHER, false, true, out));
        }
        String json = captured.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"format\":\"preflight-removal-v1\""), json);
        assertTrue(json.contains("\"scope\":\"launcher\""), json);
        assertTrue(json.contains("\"applied\":false"), json);
        assertTrue(json.contains("\"Starsector installation\""), json);
        assertTrue(preflight.integrations().get(0).present(), "preview must not remove anything");
    }

    @Test
    void anInterruptedAllDataRemovalBlocksOtherMutationsButCanResume() throws Exception {
        PreflightHome preflight = macHome();
        Files.createDirectories(preflight.state());
        Files.writeString(OperationLease.removalMarker(preflight), "interrupted\n");
        Files.createDirectories(preflight.root().resolve("cache"));
        Files.writeString(preflight.root().resolve("cache/artifact"), "prepared");

        OperationLease.BusyException refused = assertThrows(OperationLease.BusyException.class,
                () -> OperationLease.acquire(preflight, "launching", null));
        assertTrue(refused.getMessage().contains("uninstall --purge --yes"));

        assertEquals(0, UninstallCommand.run(preflight, true, true, quiet()));
        assertFalse(Files.exists(preflight.root()), "the explicit purge should resume and finish");
    }

    @Test
    void theDefaultScopeAlsoRemovesTheLegacyBrandedLauncher() throws Exception {
        PreflightHome preflight = macHome();
        Path legacyApp = preflight.pathOf(PreflightHome.Id.LEGACY_MAC_APP);
        Files.createDirectories(legacyApp.resolve("Contents/MacOS"));
        Files.writeString(legacyApp.resolve("Contents/MacOS/starsector-preflight"), "#!/bin/sh\n");

        assertEquals(0, UninstallCommand.run(preflight, false, true, quiet()));
        assertFalse(Files.exists(legacyApp), "the old branded launcher should be gone");
    }

    @Test
    void installingTheRenamedLauncherRetiresOnlyTheLegacyBrand() throws Exception {
        PreflightHome preflight = macHome();
        installIntegration(preflight);
        Path currentApp = preflight.pathOf(PreflightHome.Id.MAC_APP);
        Path legacyApp = preflight.pathOf(PreflightHome.Id.LEGACY_MAC_APP);
        Files.createDirectories(legacyApp.resolve("Contents/MacOS"));
        Files.writeString(legacyApp.resolve("Contents/MacOS/starsector-preflight"), "#!/bin/sh\n");

        InstallCommand.retireLegacyLaunchers(preflight);

        assertTrue(Files.isDirectory(currentApp), "the newly installed launcher should remain");
        assertFalse(Files.exists(legacyApp), "the renamed launcher should be retired");
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
        // this pins that each platform defines the ids its installer reaches for and the legacy
        // branding paths that uninstall still cleans up.
        assertEquals(
                java.util.Set.of(PreflightHome.Id.MAC_APP, PreflightHome.Id.LEGACY_MAC_APP),
                ids(Platform.MAC));
        assertEquals(
                java.util.Set.of(
                        PreflightHome.Id.LINUX_COMMAND,
                        PreflightHome.Id.LINUX_DESKTOP_ENTRY,
                        PreflightHome.Id.LEGACY_LINUX_COMMAND,
                        PreflightHome.Id.LEGACY_LINUX_DESKTOP_ENTRY),
                ids(Platform.LINUX));
        assertEquals(
                java.util.Set.of(
                        PreflightHome.Id.WINDOWS_COMMAND,
                        PreflightHome.Id.WINDOWS_DIRECTORY,
                        PreflightHome.Id.LEGACY_WINDOWS_COMMAND,
                        PreflightHome.Id.LEGACY_WINDOWS_DIRECTORY),
                ids(Platform.WINDOWS));
        assertTrue(PreflightHome.resolve(Platform.OTHER, home, Map.of()).integrations().isEmpty(),
                "OTHER installs no integration, so it must claim none");
    }

    @Test
    void theWindowsLauncherFollowsLocalAppDataWhenTheEnvironmentSetsIt() {
        assertEquals(
                home.resolve("AppData/Local/Preflight/Preflight.cmd"),
                PreflightHome.resolve(Platform.WINDOWS, home, Map.of())
                        .pathOf(PreflightHome.Id.WINDOWS_COMMAND));
        assertEquals(
                Path.of("/local-app-data/Preflight/Preflight.cmd"),
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
        String output = captured.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("storing nothing"), output);
        assertFalse(output.contains("legacy"), "absent migration paths should stay out of reports");
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

    @Test
    void allDataRemovalRefusesSymlinkedHomeAndPreservesTargetContents() throws Exception {
        Path outside = home.resolve("outside-unrelated");
        Files.createDirectories(outside.resolve("important-data"));
        Path sentinel = outside.resolve("important-data/document.txt");
        Files.writeString(sentinel, "do not delete");

        PreflightHome preflight = macHome();
        Files.createSymbolicLink(preflight.root(), outside);

        // Dry-run preview
        ByteArrayOutputStream previewCaptured = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(previewCaptured, true, StandardCharsets.UTF_8)) {
            int exitCode = UninstallCommand.run(preflight, UninstallCommand.Scope.ALL_DATA, false, false, out);
            assertEquals(1, exitCode, "preview on symlinked root must return refusal code");
        }
        String previewOutput = previewCaptured.toString(StandardCharsets.UTF_8);
        assertTrue(previewOutput.contains("symlink or alias"), previewOutput);
        assertTrue(previewOutput.contains("All-data removal is refused"), previewOutput);
        assertTrue(Files.isRegularFile(sentinel), "preview must not delete target files");

        // Confirmed apply
        ByteArrayOutputStream applyCaptured = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(applyCaptured, true, StandardCharsets.UTF_8)) {
            int exitCode = UninstallCommand.run(preflight, UninstallCommand.Scope.ALL_DATA, true, false, out);
            assertEquals(1, exitCode, "confirmed apply on symlinked root must be refused");
        }
        String applyOutput = applyCaptured.toString(StandardCharsets.UTF_8);
        assertTrue(applyOutput.contains("symlink or alias"), applyOutput);
        assertTrue(Files.isRegularFile(sentinel), "refused purge must not delete target files");
        assertTrue(Files.isSymbolicLink(preflight.root()), "symlink itself must survive on refusal");
    }

    @Test
    void symlinkToStarsectorInstallIsRefusedAndPreservesGameFiles() throws Exception {
        Path game = home.resolve("Starsector.app");
        Files.createDirectories(game.resolve("Contents/Resources/Java"));
        Path gameJar = game.resolve("Contents/Resources/Java/starfarer_obf.jar");
        Files.writeString(gameJar, "original game bytes");

        PreflightHome preflight = macHome();
        Files.createSymbolicLink(preflight.root(), game);

        int exitCode = UninstallCommand.run(preflight, UninstallCommand.Scope.ALL_DATA, true, false, quiet());
        assertEquals(1, exitCode);
        assertTrue(Files.isRegularFile(gameJar), "game files must be preserved");
        assertEquals("original game bytes", Files.readString(gameJar));
    }

    @Test
    void nestedSymlinkInsideRealHomeDeletesOnlyTheLinkEntry() throws Exception {
        Path outside = home.resolve("outside-target");
        Files.createDirectories(outside);
        Path externalFile = outside.resolve("preserved.txt");
        Files.writeString(externalFile, "keep me");

        PreflightHome preflight = macHome();
        Files.createDirectories(preflight.root().resolve("cache"));
        Files.writeString(preflight.root().resolve("cache/artifact"), "delete me");
        Files.createSymbolicLink(preflight.root().resolve("linked-external"), outside);

        int exitCode = UninstallCommand.run(preflight, UninstallCommand.Scope.ALL_DATA, true, false, quiet());
        assertEquals(0, exitCode);
        assertFalse(Files.exists(preflight.root()), "preflight home should be gone");
        assertTrue(Files.isRegularFile(externalFile), "external target of nested symlink must survive untouched");
        assertEquals("keep me", Files.readString(externalFile));
    }

    @Test
    void allDataJsonPreviewReportsRefusalForSymlinkedHome() throws Exception {
        Path outside = home.resolve("outside");
        Files.createDirectories(outside);
        PreflightHome preflight = macHome();
        Files.createSymbolicLink(preflight.root(), outside);

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            int exitCode = UninstallCommand.run(preflight, UninstallCommand.Scope.ALL_DATA, false, true, out);
            assertEquals(1, exitCode);
        }
        String json = captured.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"safe\":false"), json);
        assertTrue(json.contains("symlink or alias"), json);
    }

    private PreflightHome macHome() {
        return PreflightHome.resolve(Platform.MAC, home, Map.of());
    }

    private static void installIntegration(PreflightHome preflight) throws Exception {
        Path app = preflight.integrations().get(0).path();
        Files.createDirectories(app.resolve("Contents/MacOS"));
        Files.writeString(app.resolve("Contents/MacOS/preflight"), "#!/bin/sh\n");
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
