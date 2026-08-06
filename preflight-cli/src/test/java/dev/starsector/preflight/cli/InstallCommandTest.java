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
        assertArrayEquals(new String[] {
                "--game", "/resolved/game",
                "--texture-storage", "balanced",
                "--workers", "2",
                "--memory-mb", "128"
        }, options.preparationArguments(Path.of("/resolved/game")));
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
}
