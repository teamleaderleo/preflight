package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.BackingStoreException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LaunchSettingsMutationTest {
    @TempDir
    Path temporary;

    @Test
    void externalResolutionChangeRevalidatesPendingUiScaleAgainstCurrentCombination() {
        MemoryStore store = store(
                GameLaunchPreferences.RESOLUTION, "1920x1080",
                GameLaunchPreferences.SCREEN_SCALE, "1.0",
                GameLaunchPreferences.SOUND, "true");
        GameLaunchPreferences.Update update = new GameLaunchPreferences.Update(
                null, null, null, null, 1.25, null);
        AtomicReference<GameLaunchPreferences.Backup> backup = new AtomicReference<>();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                LaunchSettingsMutation.apply(
                        store,
                        update,
                        true,
                        LaunchSettingsCommand.Limits.unknown(),
                        temporary,
                        null,
                        value -> {
                            backup.set(value);
                            return temporary.resolve("backup.json");
                        },
                        current -> current.put(GameLaunchPreferences.RESOLUTION, "1280x768"),
                        (root, memory) -> null));

        assertTrue(failure.getMessage().contains("UI scale"), failure::getMessage);
        assertEquals("1280x768", store.get(GameLaunchPreferences.RESOLUTION));
        assertEquals("1.0", store.get(GameLaunchPreferences.SCREEN_SCALE));
        assertNull(backup.get(), "an invalid effective pair must be refused before backup publication");
    }

    @Test
    void externalUiScaleChangeRevalidatesPendingResolutionAgainstCurrentCombination() {
        MemoryStore store = store(
                GameLaunchPreferences.RESOLUTION, "1920x1080",
                GameLaunchPreferences.SCREEN_SCALE, "1.0");
        GameLaunchPreferences.Update update = new GameLaunchPreferences.Update(
                "1280x768", null, null, null, null, null);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                LaunchSettingsMutation.apply(
                        store,
                        update,
                        true,
                        LaunchSettingsCommand.Limits.unknown(),
                        temporary,
                        null,
                        ignored -> temporary.resolve("backup.json"),
                        current -> current.put(GameLaunchPreferences.SCREEN_SCALE, "1.25"),
                        (root, memory) -> null));

        assertTrue(failure.getMessage().contains("UI scale"), failure::getMessage);
        assertEquals("1920x1080", store.get(GameLaunchPreferences.RESOLUTION));
        assertEquals("1.25", store.get(GameLaunchPreferences.SCREEN_SCALE));
    }

    @Test
    void externalUnrelatedChangeBeforeCommitIsPreservedAndIncludedInTheExactBackup() throws Exception {
        MemoryStore store = store(
                GameLaunchPreferences.RESOLUTION, "1920x1080",
                GameLaunchPreferences.SCREEN_SCALE, "1.0",
                GameLaunchPreferences.SOUND, "true");
        GameLaunchPreferences.Update update = new GameLaunchPreferences.Update(
                null, null, null, null, 1.25, null);
        AtomicReference<GameLaunchPreferences.Backup> backup = new AtomicReference<>();

        LaunchSettingsMutation.Outcome outcome = LaunchSettingsMutation.apply(
                store,
                update,
                true,
                LaunchSettingsCommand.Limits.unknown(),
                temporary,
                null,
                value -> {
                    backup.set(value);
                    return temporary.resolve("backup.json");
                },
                current -> current.put(GameLaunchPreferences.SOUND, "false"),
                (root, memory) -> null);

        assertEquals(temporary.resolve("backup.json"), outcome.preferenceBackup());
        assertEquals("false", store.get(GameLaunchPreferences.SOUND));
        assertEquals("1.25", store.get(GameLaunchPreferences.SCREEN_SCALE));
        assertEquals("false", backup.get().values().get(GameLaunchPreferences.SOUND));
        assertEquals("1.0", backup.get().values().get(GameLaunchPreferences.SCREEN_SCALE));
    }

    @Test
    void memoryFailurePreservesANewerTouchedValueAndRollsBackOtherStillOwnedKeys() throws Exception {
        MemoryStore store = store(
                GameLaunchPreferences.RESOLUTION, "1920x1080",
                GameLaunchPreferences.SCREEN_SCALE, "1.0");
        GameLaunchPreferences.Update update = new GameLaunchPreferences.Update(
                null, false, null, null, 1.25, null);

        IOException failure = assertThrows(IOException.class, () -> LaunchSettingsMutation.apply(
                store,
                update,
                true,
                LaunchSettingsCommand.Limits.unknown(),
                temporary,
                6144,
                ignored -> temporary.resolve("backup.json"),
                ignored -> {},
                (root, memory) -> {
                    store.put(GameLaunchPreferences.SCREEN_SCALE, "1.3");
                    store.flush();
                    throw new IOException("synthetic memory failure");
                }));

        assertEquals("synthetic memory failure", failure.getMessage());
        assertEquals("1.3", store.get(GameLaunchPreferences.SCREEN_SCALE));
        assertNull(store.get(GameLaunchPreferences.FULLSCREEN),
                "the other touched key is still Preflight-owned and should be compensated");
        assertEquals(1, failure.getSuppressed().length);
        assertInstanceOf(GameLaunchPreferences.PreferenceStateChangedException.class, failure.getSuppressed()[0]);
        assertTrue(failure.getSuppressed()[0].getMessage().contains("newer values were kept"));
    }

    @Test
    void memoryFailureRollsBackOnlyPreflightsStillOwnedKeysAndPreservesExternalUnrelatedEdit() throws Exception {
        MemoryStore store = store(
                GameLaunchPreferences.RESOLUTION, "1920x1080",
                GameLaunchPreferences.SCREEN_SCALE, "1.0",
                GameLaunchPreferences.SOUND, "true");
        GameLaunchPreferences.Update update = new GameLaunchPreferences.Update(
                null, null, null, null, 1.25, null);

        IOException failure = assertThrows(IOException.class, () -> LaunchSettingsMutation.apply(
                store,
                update,
                true,
                LaunchSettingsCommand.Limits.unknown(),
                temporary,
                6144,
                ignored -> temporary.resolve("backup.json"),
                ignored -> {},
                (root, memory) -> {
                    store.put(GameLaunchPreferences.SOUND, "false");
                    store.flush();
                    throw new IOException("synthetic memory failure");
                }));

        assertEquals("synthetic memory failure", failure.getMessage());
        assertEquals("1.0", store.get(GameLaunchPreferences.SCREEN_SCALE));
        assertEquals("false", store.get(GameLaunchPreferences.SOUND));
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test
    void ordinaryPreferenceAndMemoryFailureRestoresTheExactPreflightPreferenceGeneration() throws Exception {
        MemoryStore store = store(
                GameLaunchPreferences.RESOLUTION, "1920x1080",
                GameLaunchPreferences.SCREEN_SCALE, "1.0",
                GameLaunchPreferences.SOUND, "true");
        GameLaunchPreferences.Update update = new GameLaunchPreferences.Update(
                null, false, null, null, 1.25, null);

        assertThrows(IOException.class, () -> LaunchSettingsMutation.apply(
                store,
                update,
                true,
                LaunchSettingsCommand.Limits.unknown(),
                temporary,
                6144,
                ignored -> temporary.resolve("backup.json"),
                ignored -> {},
                (root, memory) -> { throw new IOException("synthetic memory failure"); }));

        assertEquals("1.0", store.get(GameLaunchPreferences.SCREEN_SCALE));
        assertNull(store.get(GameLaunchPreferences.FULLSCREEN));
        assertEquals("true", store.get(GameLaunchPreferences.SOUND));
    }

    @Test
    void successfulPartialUpdateReadsBackTheAuthoritativeEffectivePair() throws Exception {
        MemoryStore store = store(
                GameLaunchPreferences.RESOLUTION, "2560x1440",
                GameLaunchPreferences.SCREEN_SCALE, "1.0");
        GameLaunchPreferences.Update update = new GameLaunchPreferences.Update(
                null, null, null, null, 1.5, null);

        LaunchSettingsMutation.apply(
                store,
                update,
                true,
                LaunchSettingsCommand.Limits.unknown(),
                temporary,
                null,
                ignored -> temporary.resolve("backup.json"),
                ignored -> {},
                (root, memory) -> null);

        GameLaunchPreferences.Snapshot readback = GameLaunchPreferences.read(store);
        assertEquals("2560x1440", readback.resolution());
        assertEquals(1.5, readback.uiScale());
        assertFalse(readback.fullscreen());
    }

    private static MemoryStore store(String... pairs) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put(pairs[index], pairs[index + 1]);
        }
        return new MemoryStore(values);
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
