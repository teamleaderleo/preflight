package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IntegrationRemovalRaceTest {
    @Test
    void linuxRacedCommandIsPreservedWhileDesktopRemovalIsReported(@TempDir Path tempDir) throws Exception {
        PreflightHome home = home(Platform.LINUX, tempDir.resolve("home"));
        assertEquals(0, InstallCommand.installLinux(home, jar(tempDir), tempDir.resolve("game")));
        Path command = home.pathOf(PreflightHome.Id.LINUX_COMMAND);
        Path desktop = home.pathOf(PreflightHome.Id.LINUX_DESKTOP_ENTRY);
        AtomicInteger reviews = new AtomicInteger();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();

        try (IntegrationMutation.TestHookScope ignored = IntegrationMutation.installTestHook((event, integration, path) -> {
            if (event == IntegrationMutation.Event.AFTER_REMOVAL_REVIEW
                    && integration.id() == PreflightHome.Id.LINUX_COMMAND
                    && reviews.incrementAndGet() == 2) {
                Files.delete(command);
                Files.writeString(command, "external command generation\n");
            }
        }); PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            assertEquals(1, UninstallCommand.run(home, false, true, out));
        }

        assertEquals("external command generation\n", Files.readString(command));
        assertFalse(Files.exists(desktop, LinkOption.NOFOLLOW_LINKS));
        String output = captured.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Could not remove " + command), output);
        assertTrue(output.contains("Removed:"), output);
        assertTrue(output.contains(desktop.toString()), output);
        assertFalse(output.contains("  command                  " + command), output);
        String receipt = Files.readString(home.root().resolve("integrations.json"));
        assertFalse(receipt.contains("LINUX_COMMAND"), receipt);
        assertFalse(receipt.contains("LINUX_DESKTOP_ENTRY"), receipt);
    }

    @Test
    void sameSizeSameMtimeLinuxReplacementWinsRemovalRace(@TempDir Path tempDir) throws Exception {
        PreflightHome home = home(Platform.LINUX, tempDir.resolve("home"));
        assertEquals(0, InstallCommand.installLinux(home, jar(tempDir), tempDir.resolve("game")));
        Path command = home.pathOf(PreflightHome.Id.LINUX_COMMAND);
        byte[] original = Files.readAllBytes(command);
        byte[] external = original.clone();
        external[external.length / 2] ^= 1;
        FileTime mtime = Files.getLastModifiedTime(command, LinkOption.NOFOLLOW_LINKS);
        AtomicInteger reviews = new AtomicInteger();

        try (IntegrationMutation.TestHookScope ignored = IntegrationMutation.installTestHook((event, integration, path) -> {
            if (event == IntegrationMutation.Event.AFTER_REMOVAL_REVIEW
                    && integration.id() == PreflightHome.Id.LINUX_COMMAND
                    && reviews.incrementAndGet() == 2) {
                Files.delete(command);
                Files.write(command, external);
                Files.setLastModifiedTime(command, mtime);
            }
        })) {
            assertEquals(1, UninstallCommand.run(home, false, true, quiet()));
        }

        assertTrue(java.util.Arrays.equals(external, Files.readAllBytes(command)));
        assertEquals(original.length, Files.size(command));
        assertEquals(mtime, Files.getLastModifiedTime(command, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void symlinkThatAppearsAfterLinuxOwnershipProofIsPreserved(@TempDir Path tempDir) throws Exception {
        PreflightHome home = home(Platform.LINUX, tempDir.resolve("home"));
        assertEquals(0, InstallCommand.installLinux(home, jar(tempDir), tempDir.resolve("game")));
        Path command = home.pathOf(PreflightHome.Id.LINUX_COMMAND);
        Path outside = tempDir.resolve("outside");
        Files.writeString(outside, "outside bytes\n");
        AtomicInteger reviews = new AtomicInteger();

        try (IntegrationMutation.TestHookScope ignored = IntegrationMutation.installTestHook((event, integration, path) -> {
            if (event == IntegrationMutation.Event.AFTER_REMOVAL_REVIEW
                    && integration.id() == PreflightHome.Id.LINUX_COMMAND
                    && reviews.incrementAndGet() == 2) {
                Files.delete(command);
                Files.createSymbolicLink(command, outside);
            }
        })) {
            assertEquals(1, UninstallCommand.run(home, false, true, quiet()));
        }

        assertTrue(Files.isSymbolicLink(command));
        assertEquals("outside bytes\n", Files.readString(outside));
    }

    @Test
    void macBundleChangedAfterCurrentPlanIsPreserved(@TempDir Path tempDir) throws Exception {
        PreflightHome home = home(Platform.MAC, tempDir.resolve("home"));
        assertEquals(0, InstallCommand.installMac(home, jar(tempDir), tempDir.resolve("game")));
        Path app = home.pathOf(PreflightHome.Id.MAC_APP);
        Path executable = app.resolve("Contents/MacOS/preflight");
        AtomicInteger reviews = new AtomicInteger();

        try (IntegrationMutation.TestHookScope ignored = IntegrationMutation.installTestHook((event, integration, path) -> {
            if (event == IntegrationMutation.Event.AFTER_REMOVAL_REVIEW
                    && integration.id() == PreflightHome.Id.MAC_APP
                    && reviews.incrementAndGet() == 2) {
                Files.writeString(executable, "external executable generation\n");
            }
        })) {
            assertEquals(1, UninstallCommand.run(home, false, true, quiet()));
        }

        assertEquals("external executable generation\n", Files.readString(executable));
    }

    @Test
    void externalBundleFileIntroducedDuringCleanupSurvivesInCommittedQuarantine(@TempDir Path tempDir)
            throws Exception {
        PreflightHome home = home(Platform.MAC, tempDir.resolve("home"));
        assertEquals(0, InstallCommand.installMac(home, jar(tempDir), tempDir.resolve("game")));
        Path app = home.pathOf(PreflightHome.Id.MAC_APP);
        AtomicReference<Path> quarantine = new AtomicReference<>();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();

        try (IntegrationMutation.TestHookScope ignored = IntegrationMutation.installTestHook((event, integration, path) -> {
            if (event == IntegrationMutation.Event.BEFORE_REMOVE_CLEANUP
                    && integration.id() == PreflightHome.Id.MAC_APP) {
                quarantine.set(path);
                Files.writeString(path.resolve("external.txt"), "external bundle content\n");
            }
        }); PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            assertEquals(0, UninstallCommand.run(home, false, true, out));
        }

        assertFalse(Files.exists(app, LinkOption.NOFOLLOW_LINKS));
        assertEquals("external bundle content\n", Files.readString(quarantine.get().resolve("external.txt")));
        assertTrue(Files.isRegularFile(quarantine.get().resolve("Contents/Info.plist")));
        assertTrue(captured.toString(StandardCharsets.UTF_8).contains("Removed:"));
    }

    @Test
    void windowsDirectoryMutationAfterProofIsPreservedAsAUnit(@TempDir Path tempDir) throws Exception {
        PreflightHome home = home(Platform.WINDOWS, tempDir.resolve("home"));
        assertEquals(0, InstallCommand.installWindows(home, jar(tempDir), tempDir.resolve("game")));
        Path directory = home.pathOf(PreflightHome.Id.WINDOWS_DIRECTORY);
        AtomicInteger reviews = new AtomicInteger();

        try (IntegrationMutation.TestHookScope ignored = IntegrationMutation.installTestHook((event, integration, path) -> {
            if (event == IntegrationMutation.Event.AFTER_REMOVAL_REVIEW
                    && integration.id() == PreflightHome.Id.WINDOWS_DIRECTORY
                    && reviews.incrementAndGet() == 2) {
                Files.writeString(directory.resolve("external.txt"), "external directory content\n");
            }
        })) {
            assertEquals(1, UninstallCommand.run(home, false, true, quiet()));
        }

        assertEquals("external directory content\n", Files.readString(directory.resolve("external.txt")));
        assertTrue(Files.isRegularFile(directory.resolve("Preflight.cmd")));
    }

    @Test
    void legacyReplacementBetweenReviewAndRemovalSurvivesRetirement(@TempDir Path tempDir) throws Exception {
        PreflightHome home = home(Platform.MAC, tempDir.resolve("home"));
        Path legacy = home.pathOf(PreflightHome.Id.LEGACY_MAC_APP);
        installLegacyMac(legacy);
        Path executable = legacy.resolve("Contents/MacOS/starsector-preflight");

        try (IntegrationMutation.TestHookScope ignored = IntegrationMutation.installTestHook((event, integration, path) -> {
            if (event == IntegrationMutation.Event.AFTER_REMOVAL_REVIEW
                    && integration.id() == PreflightHome.Id.LEGACY_MAC_APP) {
                Files.writeString(executable, "external legacy generation\n");
            }
        })) {
            InstallCommand.retireLegacyLaunchers(home);
        }

        assertEquals("external legacy generation\n", Files.readString(executable));
    }

    @Test
    void metadataAloneNeverProvesMacOrWindowsDirectoryOwnership(@TempDir Path tempDir) throws Exception {
        PreflightHome mac = home(Platform.MAC, tempDir.resolve("mac-home"));
        Path app = mac.pathOf(PreflightHome.Id.MAC_APP);
        Files.createDirectories(app.resolve("Contents/MacOS"));
        Files.writeString(
                app.resolve("Contents/Info.plist"),
                "<key>CFBundleIdentifier</key><string>dev.starsector.preflight.launcher</string>"
                        + "<key>CFBundleExecutable</key><string>preflight</string>");
        Files.writeString(app.resolve("Contents/MacOS/.DS_Store"), "metadata");
        assertFalse(mac.integration(PreflightHome.Id.MAC_APP).isOwned());

        PreflightHome windows = home(Platform.WINDOWS, tempDir.resolve("win-home"));
        Path directory = windows.pathOf(PreflightHome.Id.WINDOWS_DIRECTORY);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("desktop.ini"), "metadata");
        assertFalse(windows.integration(PreflightHome.Id.WINDOWS_DIRECTORY).isOwned());
    }

    private static PreflightHome home(Platform platform, Path home) {
        return PreflightHome.resolve(platform, home, Map.of());
    }

    private static Path jar(Path tempDir) throws IOException {
        Path jar = tempDir.resolve("bin/preflight.jar");
        Files.createDirectories(jar.getParent());
        if (!Files.exists(jar)) Files.writeString(jar, "jar-content");
        return jar;
    }

    private static PrintStream quiet() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    private static void installLegacyMac(Path app) throws IOException {
        Path macos = app.resolve("Contents/MacOS");
        Files.createDirectories(macos);
        Files.writeString(macos.resolve("starsector-preflight"),
                "#!/bin/sh\n" + IntegrationOwnership.POSIX_MARKER
                        + "\nexec 'java' -jar 'preflight.jar' run --fast --game 'game' \"$@\"\n");
        Files.writeString(app.resolve("Contents/Info.plist"),
                "<key>CFBundleIdentifier</key><string>dev.starsector.preflight.launcher</string>"
                        + "<key>CFBundleExecutable</key><string>starsector-preflight</string>");
    }
}
