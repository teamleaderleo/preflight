package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.prefs.BackingStoreException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GameLaunchPreferencesTest {
    @TempDir
    Path temporary;

    @Test
    void readsTheVanillaLauncherAndGameplayPreferenceKeys() {
        MemoryStore store = new MemoryStore(Map.of(
                "resolution", "1920x1080",
                "fullscreen", "true",
                "sound", "false",
                "numAASamples", "12",
                "screenScale", "1.25",
                "gameplaySettings", "{\"battleSize\":350,\"tooltipDelay\":0.4}"));

        GameLaunchPreferences.Snapshot snapshot = GameLaunchPreferences.read(store);

        assertEquals("1920x1080", snapshot.resolution());
        assertTrue(snapshot.fullscreen());
        assertEquals(false, snapshot.sound());
        assertEquals(12, snapshot.antialiasingSamples());
        assertEquals(1.25, snapshot.uiScale());
        assertEquals(350, snapshot.battleSize());
        assertTrue(snapshot.diagnostics().isEmpty());
    }

    @Test
    void updatesOnlyRequestedKeysAndPreservesOtherGameplaySettings() throws Exception {
        MemoryStore store = new MemoryStore(Map.of(
                "resolution", "1280x720",
                "fullscreen", "false",
                "gameplaySettings", "{\"tooltipDelay\":0.4,\"battleSize\":200}"));

        GameLaunchPreferences.apply(store, new GameLaunchPreferences.Update(
                "2560x1440", true, null, 8, 1.5, 400));

        assertEquals("2560x1440", store.get("resolution"));
        assertEquals("true", store.get("fullscreen"));
        assertNull(store.get("sound"));
        assertEquals("8", store.get("numAASamples"));
        assertEquals("1.5", store.get("screenScale"));
        Map<String, Object> gameplay = StrictJson.object(store.get("gameplaySettings"));
        assertEquals(0.4, gameplay.get("tooltipDelay"));
        assertEquals(400L, gameplay.get("battleSize"));
        assertEquals(1, store.flushes);
    }

    @Test
    void backupAndRestoreCoverOnlyTheMutablePreferenceSet() throws Exception {
        MemoryStore store = new MemoryStore(Map.of(
                "resolution", "1440x900",
                "serial", "must-never-enter-backup"));
        GameLaunchPreferences.Backup backup = GameLaunchPreferences.backup(store);

        GameLaunchPreferences.apply(store, new GameLaunchPreferences.Update(
                "1920x1080", true, true, 0, 1.0, 300));
        GameLaunchPreferences.restore(store, backup);

        assertEquals("1440x900", store.get("resolution"));
        assertEquals("must-never-enter-backup", store.get("serial"));
        assertNull(store.get("fullscreen"));
        assertNull(backup.values().get("serial"));
    }

    @Test
    void malformedOptionalPreferencesAreReportedWithoutBlockingTheSnapshot() {
        MemoryStore store = new MemoryStore(Map.of(
                "numAASamples", "many",
                "screenScale", "NaN",
                "gameplaySettings", "not-json"));

        GameLaunchPreferences.Snapshot snapshot = GameLaunchPreferences.read(store);

        assertNull(snapshot.antialiasingSamples());
        assertNull(snapshot.uiScale());
        assertNull(snapshot.battleSize());
        assertEquals(3, snapshot.diagnostics().size());
    }

    @Test
    void validationMatchesTheVanillaLauncherChoices() {
        assertThrows(IllegalArgumentException.class, () -> GameLaunchPreferences.validate(
                new GameLaunchPreferences.Update(null, null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> GameLaunchPreferences.validate(
                new GameLaunchPreferences.Update("1920 by 1080", null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> GameLaunchPreferences.validate(
                new GameLaunchPreferences.Update(null, null, null, 6, null, null)));
        assertThrows(IllegalArgumentException.class, () -> GameLaunchPreferences.validate(
                new GameLaunchPreferences.Update(null, null, null, null, 1.03, null)));
    }

    @Test
    void readsAndEnforcesBattleBoundsFromTheInstalledSettingsDialect() throws Exception {
        Path settings = temporary.resolve("Contents/Resources/Java/data/config/settings.json");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, """
                {
                  "minBattleSize":200, # vanilla comments are accepted by the game
                  "defaultBattleSize":400,
                  "maxBattleSize":600,
                  "maxAASamples":16,
                }
                """);

        LaunchSettingsCommand.Limits limits = LaunchSettingsCommand.Limits.read(temporary);

        assertEquals(200, limits.minBattleSize());
        assertEquals(400, limits.defaultBattleSize());
        assertEquals(600, limits.maxBattleSize());
        assertEquals(16, limits.maxAntialiasingSamples());
        limits.validate(new GameLaunchPreferences.Update(null, null, null, null, null, 600));
        assertThrows(IllegalArgumentException.class, () -> limits.validate(
                new GameLaunchPreferences.Update(null, null, null, null, null, 700)));
        assertThrows(IllegalArgumentException.class, () -> limits.validate(
                new GameLaunchPreferences.Update(null, null, null, 24, null, null)));
        assertThrows(IllegalArgumentException.class, () -> limits.validate(
                new GameLaunchPreferences.Update("1280x768", null, null, null, 1.05, null)));
        assertThrows(IllegalArgumentException.class, () -> limits.validate(
                new GameLaunchPreferences.Update("1280x768", null, null, null, null, null),
                new GameLaunchPreferences.Snapshot(
                        "1920x1080", false, true, 0, 1.25, 400, java.util.List.of())));
        assertThrows(IllegalArgumentException.class, () -> limits.validate(
                new GameLaunchPreferences.Update(null, null, null, null, 1.5, null),
                new GameLaunchPreferences.Snapshot(
                        "1920x1080", false, true, 0, 1.0, 400, java.util.List.of())));
        assertEquals(1.4, LaunchSettingsCommand.Limits.maximumUiScale("1920x1080"));
    }

    private static final class MemoryStore implements GameLaunchPreferences.Store {
        private final Map<String, String> values = new LinkedHashMap<>();
        private int flushes;

        private MemoryStore(Map<String, String> values) {
            this.values.putAll(values);
        }

        @Override
        public String get(String key) {
            return values.get(key);
        }

        @Override
        public void put(String key, String value) {
            values.put(key, value);
        }

        @Override
        public void remove(String key) {
            values.remove(key);
        }

        @Override
        public void flush() throws BackingStoreException {
            flushes++;
        }
    }
}
