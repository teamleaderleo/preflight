package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

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
}
