package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopModReadinessCommandTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void returnsTheSharedBoundedFindingsWithoutPaths() throws Exception {
        Path game = Files.createDirectories(temporaryDirectory.resolve("game"));
        Path mods = Files.createDirectories(game.resolve("mods"));
        Files.writeString(
                mods.resolve("enabled_mods.json"),
                Json.object(Map.of("enabledMods", List.of("alpha"))));
        Path alpha = Files.createDirectories(mods.resolve("alpha"));
        Files.writeString(
                alpha.resolve("mod_info.json"),
                "{\"id\":\"alpha\",\"dependencies\":[{\"id\":\"missing\"}]}");

        Map<String, Object> result = DesktopModReadinessCommand.read(game);

        assertEquals(ModMetadataCheck.FORMAT, result.get("format"));
        assertEquals(false, result.get("ready"));
        assertTrue(((Number) result.get("metadataBytes")).longValue() > 0);
        assertTrue(((Number) result.get("elapsedMillis")).longValue() >= 0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) result.get("findings");
        assertEquals(1, findings.size());
        assertEquals("mod-metadata.required-dependency-missing", findings.get(0).get("code"));
        assertFalse(Json.object(result).contains(temporaryDirectory.toString()));
    }
}
