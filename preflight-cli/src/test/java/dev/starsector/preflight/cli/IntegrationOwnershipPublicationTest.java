package dev.starsector.preflight.cli;

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

class IntegrationOwnershipPublicationTest {
    @Test
    void stableOwnedReinstallRemainsSupportedOnAllPlatforms(@TempDir Path tempDir) throws Exception {
        Path jar = jar(tempDir);
        Path firstGame = tempDir.resolve("first-game");
        Path secondGame = tempDir.resolve("second-game");

        PreflightHome mac = home(Platform.MAC, tempDir.resolve("mac-home"));
        assertEquals(0, InstallCommand.installMac(mac, jar, firstGame));
        assertEquals(0, InstallCommand.installMac(mac, jar, secondGame));
        assertTrue(mac.integration(PreflightHome.Id.MAC_APP).isOwned());
        assertTrue(Files.readString(mac.pathOf(PreflightHome.Id.MAC_APP).resolve("Contents/MacOS/preflight"))
                .contains(secondGame.toString()));

        PreflightHome linux = home(Platform.LINUX, tempDir.resolve("linux-home"));
        assertEquals(0, InstallCommand.installLinux(linux, jar, firstGame));
        assertEquals(0, InstallCommand.installLinux(linux, jar, secondGame));
        assertTrue(linux.integration(PreflightHome.Id.LINUX_COMMAND).isOwned());
        assertTrue(linux.integration(PreflightHome.Id.LINUX_DESKTOP_ENTRY).isOwned());
        assertTrue(Files.readString(linux.pathOf(PreflightHome.Id.LINUX_COMMAND)).contains(secondGame.toString()));

        PreflightHome windows = home(Platform.WINDOWS, tempDir.resolve("windows-home"));
        assertEquals(0, InstallCommand.installWindows(windows, jar, firstGame));
        assertEquals(0, InstallCommand.installWindows(windows, jar, secondGame));
        assertTrue(windows.integration(PreflightHome.Id.WINDOWS_DIRECTORY).isOwned());
        assertTrue(Files.readString(windows.pathOf(PreflightHome.Id.WINDOWS_COMMAND)).contains(secondGame.toString()));
    }

    @Test
    void windowsMetadataOnlyDirectoryCannotBeClaimedForReplacement(@TempDir Path tempDir) throws Exception {
        PreflightHome home = home(Platform.WINDOWS, tempDir.resolve("home"));
        Path directory = home.pathOf(PreflightHome.Id.WINDOWS_DIRECTORY);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("desktop.ini"), "external metadata\n");

        assertFalse(home.integration(PreflightHome.Id.WINDOWS_DIRECTORY).isOwned());
        assertThrows(IOException.class, () -> InstallCommand.installWindows(home, jar(tempDir), tempDir.resolve("game")));
        assertEquals("external metadata\n", Files.readString(directory.resolve("desktop.ini")));
    }

    @Test
    void macBundleWithoutOwnedExecutableCannotBeClaimedForReplacement(@TempDir Path tempDir) throws Exception {
        PreflightHome home = home(Platform.MAC, tempDir.resolve("home"));
        Path app = home.pathOf(PreflightHome.Id.MAC_APP);
        Files.createDirectories(app.resolve("Contents/MacOS"));
        Files.writeString(app.resolve("Contents/Info.plist"),
                "<key>CFBundleIdentifier</key><string>dev.starsector.preflight.launcher</string>"
                        + "<key>CFBundleExecutable</key><string>preflight</string>");
        Files.writeString(app.resolve("Contents/MacOS/.DS_Store"), "external metadata\n");

        assertFalse(home.integration(PreflightHome.Id.MAC_APP).isOwned());
        assertThrows(IOException.class, () -> InstallCommand.installMac(home, jar(tempDir), tempDir.resolve("game")));
        assertEquals("external metadata\n", Files.readString(app.resolve("Contents/MacOS/.DS_Store")));
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
}
