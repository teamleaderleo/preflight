package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopHomeStateCommandTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void combinesTheThreeIndependentHomeReads() throws Exception {
        Path game = Files.createDirectories(temporaryDirectory.resolve("game"));
        Path mods = Files.createDirectories(game.resolve("mods"));
        Files.writeString(
                mods.resolve("enabled_mods.json"),
                Json.object(Map.of("enabledMods", List.of("alpha"))));
        Path alpha = Files.createDirectories(mods.resolve("alpha"));
        Files.writeString(alpha.resolve("mod_info.json"), "{\"id\":\"alpha\"}");
        PreflightHome home = new PreflightHome(
                temporaryDirectory.resolve("preflight-home").toAbsolutePath().normalize(),
                List.of());

        Map<String, Object> state = DesktopHomeStateCommand.read(home, game);

        assertEquals("starsector-preflight-desktop-home-state-v1", state.get("format"));
        assertNotNull(state.get("cacheInspection"));
        assertNotNull(state.get("profiles"));
        assertNotNull(state.get("launchSettings"));
        assertEquals(Map.of(), state.get("errors"));
    }

    @Test
    void oneUnreadableFamilyDoesNotDiscardTheOtherHomeData() throws Exception {
        Path game = Files.createDirectories(temporaryDirectory.resolve("incomplete-game"));
        PreflightHome home = new PreflightHome(
                temporaryDirectory.resolve("preflight-home").toAbsolutePath().normalize(),
                List.of());

        Map<String, Object> state = DesktopHomeStateCommand.read(home, game);

        assertNotNull(state.get("cacheInspection"));
        assertNull(state.get("profiles"));
        assertNotNull(state.get("launchSettings"));
        @SuppressWarnings("unchecked")
        Map<String, Object> errors = (Map<String, Object>) state.get("errors");
        assertTrue(errors.containsKey("profiles"));
        assertEquals(1, errors.size());
    }
}
