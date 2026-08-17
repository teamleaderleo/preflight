package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallCommandTest {
    @Test
    void preparationDefaultsToBalancedAndForwardsOnlyPreparationOptions() {
        InstallCommand.Options options = InstallCommand.Options.parse(new String[] {
                "install", "--game", "/game", "--prepare", "--workers", "2", "--memory-mb", "128"
        }, 1);

        assertEquals(Path.of("/game"), options.game());
        assertEquals(TextureStoragePolicy.BALANCED, options.textureStorage());
        // Forwarded as the platform spells it, which is "\resolved\game" on Windows.
        Path resolved = Path.of("/resolved/game");
        assertArrayEquals(new String[] {
                "--game", resolved.toString(),
                "--texture-storage", "balanced",
                "--workers", "2",
                "--memory-mb", "128"
        }, options.preparationArguments(resolved));
    }

    @Test
    void fastestCanBeSelectedExplicitly() {
        InstallCommand.Options options = InstallCommand.Options.parse(new String[] {
                "install", "--prepare", "--texture-storage", "fastest"
        }, 1);

        assertEquals(TextureStoragePolicy.FASTEST, options.textureStorage());
    }

    @Test
    void rejectsPreparationControlsWhenPreparationWasNotRequested() {
        assertThrows(IllegalArgumentException.class, () -> InstallCommand.Options.parse(new String[] {
                "install", "--workers", "2"
        }, 1));
        assertThrows(IllegalArgumentException.class, () -> InstallCommand.Options.parse(new String[] {
                "install", "--texture-storage", "fastest"
        }, 1));
    }

    @Test
    void boundsPreparationResourcesBeforeInstallingAnything() {
        assertThrows(IllegalArgumentException.class, () -> InstallCommand.Options.parse(new String[] {
                "install", "--prepare", "--workers", "0"
        }, 1));
        assertThrows(IllegalArgumentException.class, () -> InstallCommand.Options.parse(new String[] {
                "install", "--prepare", "--memory-mb", "15"
        }, 1));
    }

    @Test
    void linuxDesktopLauncherQuotesSpacesAndReservedFieldCharacters() {
        assertEquals(
                "\"/home/Space User/.local/bin/preflight\"",
                InstallCommand.desktopExecArgument("/home/Space User/.local/bin/preflight"));
        assertEquals(
                "\"/home/\\\\$cash/%%f/\\\\`tick/\\\\\\\"quote/\\\\\\\\slash\"",
                InstallCommand.desktopExecArgument("/home/$cash/%f/`tick/\"quote/\\slash"));
    }

    @Test
    void linuxDesktopLauncherRejectsNul() {
        assertThrows(
                IllegalArgumentException.class,
                () -> InstallCommand.desktopExecArgument("/home/user/\0preflight"));
    }

    @Test
    void windowsLauncherPreservesLiteralPercentInPaths() {
        assertEquals(
                "C:\\Users\\100%% Real\\Preflight\\preflight.jar",
                InstallCommand.windowsBatchLiteral("C:\\Users\\100% Real\\Preflight\\preflight.jar"));
    }

    @Test
    void windowsLauncherRejectsLineBreakingControlCharacters() {
        assertThrows(
                IllegalArgumentException.class,
                () -> InstallCommand.windowsBatchLiteral("C:\\Preflight\r\nmalicious-command"));
    }

    @Test
    void refusesInstallingMacAppWhenAppIsSymlinkAndPreservesTarget(@TempDir Path tempDir) throws Exception {
        Path home = tempDir.resolve("home");
        Path outside = tempDir.resolve("outside-target");
        Files.createDirectories(outside);
        Path sentinel = outside.resolve("preserved.txt");
        Files.writeString(sentinel, "do not overwrite");

        PreflightHome preflight = PreflightHome.resolve(Platform.MAC, home, Map.of());
        Files.createDirectories(preflight.pathOf(PreflightHome.Id.MAC_APP).getParent());
        Files.createSymbolicLink(preflight.pathOf(PreflightHome.Id.MAC_APP), outside);

        Path jar = tempDir.resolve("bin/preflight.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar-content");
        Path game = tempDir.resolve("Starsector.app");

        IOException error = assertThrows(
                IOException.class,
                () -> InstallCommand.installMac(preflight, jar, game));
        assertTrue(error.getMessage().contains("symlink or alias"), error.getMessage());
        assertTrue(Files.isRegularFile(sentinel));
        assertEquals("do not overwrite", Files.readString(sentinel));
        assertFalse(Files.exists(outside.resolve("Contents")), "no Contents directory should be written to symlink target");
    }

    @Test
    void refusesInstallingLinuxCommandWhenTargetIsSymlinkAndPreservesTarget(@TempDir Path tempDir) throws Exception {
        Path home = tempDir.resolve("home");
        Path outside = tempDir.resolve("outside-file.sh");
        Files.writeString(outside, "original script");

        PreflightHome preflight = PreflightHome.resolve(Platform.LINUX, home, Map.of());
        Path launcher = preflight.pathOf(PreflightHome.Id.LINUX_COMMAND);
        Files.createDirectories(launcher.getParent());
        Files.createSymbolicLink(launcher, outside);

        Path jar = tempDir.resolve("bin/preflight.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar-content");
        Path game = tempDir.resolve("starsector");

        IOException error = assertThrows(
                IOException.class,
                () -> InstallCommand.installLinux(preflight, jar, game));
        assertTrue(error.getMessage().contains("symlink or alias"), error.getMessage());
        assertEquals("original script", Files.readString(outside));
    }

    @Test
    void refusesInstallingWindowsLauncherWhenDirectoryIsSymlink(@TempDir Path tempDir) throws Exception {
        Path home = tempDir.resolve("home");
        Path outside = tempDir.resolve("outside-win-dir");
        Files.createDirectories(outside);
        Path sentinel = outside.resolve("data.txt");
        Files.writeString(sentinel, "original win data");

        PreflightHome preflight = PreflightHome.resolve(Platform.WINDOWS, home, Map.of());
        Path winDir = preflight.pathOf(PreflightHome.Id.WINDOWS_DIRECTORY);
        Files.createDirectories(winDir.getParent());
        Files.createSymbolicLink(winDir, outside);

        Path jar = tempDir.resolve("bin/preflight.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar-content");
        Path game = tempDir.resolve("starsector");

        IOException error = assertThrows(
                IOException.class,
                () -> InstallCommand.installWindows(preflight, jar, game));
        assertTrue(error.getMessage().contains("symlink or alias"), error.getMessage());
        assertEquals("original win data", Files.readString(sentinel));
    }

    @Test
    void refusesInstallingWhenParentDirectoryIsSymlink(@TempDir Path tempDir) throws Exception {
        Path home = tempDir.resolve("home");
        Path outside = tempDir.resolve("outside-bin");
        Files.createDirectories(outside);

        PreflightHome preflight = PreflightHome.resolve(Platform.LINUX, home, Map.of());
        Path binDir = preflight.pathOf(PreflightHome.Id.LINUX_COMMAND).getParent();
        Files.createDirectories(binDir.getParent());
        Files.createSymbolicLink(binDir, outside);

        Path jar = tempDir.resolve("bin/preflight.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar-content");
        Path game = tempDir.resolve("starsector");

        IOException error = assertThrows(
                IOException.class,
                () -> InstallCommand.installLinux(preflight, jar, game));
        assertTrue(error.getMessage().contains("symlink or alias"), error.getMessage());
    }

    @Test
    void normalInstallationSucceedsOnAllPlatforms(@TempDir Path tempDir) throws Exception {
        Path home = tempDir.resolve("home");
        Path jar = tempDir.resolve("bin/preflight.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar-content");
        Path game = tempDir.resolve("Starsector");

        PreflightHome macHome = PreflightHome.resolve(Platform.MAC, home.resolve("mac"), Map.of());
        assertEquals(0, InstallCommand.installMac(macHome, jar, game));
        assertTrue(Files.isRegularFile(macHome.pathOf(PreflightHome.Id.MAC_APP).resolve("Contents/MacOS/preflight")));

        PreflightHome linuxHome = PreflightHome.resolve(Platform.LINUX, home.resolve("linux"), Map.of());
        assertEquals(0, InstallCommand.installLinux(linuxHome, jar, game));
        assertTrue(Files.isRegularFile(linuxHome.pathOf(PreflightHome.Id.LINUX_COMMAND)));
        assertTrue(Files.isRegularFile(linuxHome.pathOf(PreflightHome.Id.LINUX_DESKTOP_ENTRY)));

        PreflightHome winHome = PreflightHome.resolve(Platform.WINDOWS, home.resolve("win"), Map.of());
        assertEquals(0, InstallCommand.installWindows(winHome, jar, game));
        assertTrue(Files.isRegularFile(winHome.pathOf(PreflightHome.Id.WINDOWS_COMMAND)));
    }
}
