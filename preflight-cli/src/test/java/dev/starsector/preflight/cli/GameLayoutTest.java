package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GameLayoutTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void locatesModsInsideMacApplicationBundle() throws Exception {
        Path app = temporaryDirectory.resolve("Starsector.app");
        Path mods = app.resolve("Contents/Resources/Java/mods");
        Files.createDirectories(mods);
        Path enabled = Files.writeString(mods.resolve("enabled_mods.json"), "{\"enabledMods\":[]}");

        GameLayout layout = GameLayout.locate(app);

        assertEquals(mods.toAbsolutePath().normalize(), layout.modsDirectory());
        assertEquals(enabled.toAbsolutePath().normalize(), layout.enabledModsFile());
        assertEquals(mods.toRealPath(), layout.requireMutationSafe().modsDirectory());
    }

    @Test
    void refusesMutationThroughModsDirectorySymlinkOutsideInstall() throws Exception {
        Path game = Files.createDirectories(temporaryDirectory.resolve("game"));
        Path outsideMods = Files.createDirectories(temporaryDirectory.resolve("outside-mods"));
        Files.writeString(outsideMods.resolve("enabled_mods.json"), "{\"enabledMods\":[]}");
        try {
            Files.createSymbolicLink(game.resolve("mods"), outsideMods);
        } catch (UnsupportedOperationException | IOException unavailable) {
            return;
        }

        GameLayout layout = GameLayout.locate(game);
        IOException error = assertThrows(IOException.class, layout::requireMutationSafe);

        assertTrue(error.getMessage().contains("outside the selected install"), error.getMessage());
    }

    @Test
    void refusesMutationWhenEnabledModsFileIsASymlink() throws Exception {
        Path game = Files.createDirectories(temporaryDirectory.resolve("game"));
        Path mods = Files.createDirectories(game.resolve("mods"));
        Path outside = Files.writeString(
                temporaryDirectory.resolve("enabled_mods.json"), "{\"enabledMods\":[]}");
        try {
            Files.createSymbolicLink(mods.resolve("enabled_mods.json"), outside);
        } catch (UnsupportedOperationException | IOException unavailable) {
            return;
        }

        GameLayout layout = GameLayout.locate(game);
        IOException error = assertThrows(IOException.class, layout::requireMutationSafe);

        assertTrue(error.getMessage().contains("symlink or non-file"), error.getMessage());
    }
}
