package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.prefs.BackingStoreException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LaunchSettingsPublicationFailureTest {
    @TempDir
    Path temporary;

    @Test
    void flushFailureCompensatesOnlyWhenTheRequestedPublicationIsStillOwned() {
        FailingFlushStore store = new FailingFlushStore(Map.of(
                GameLaunchPreferences.RESOLUTION, "1920x1080",
                GameLaunchPreferences.SCREEN_SCALE, "1.0"),
                current -> {});

        GameLaunchPreferences.PreferencePublicationException failure = assertThrows(
                GameLaunchPreferences.PreferencePublicationException.class,
                () -> applyScale(store));

        assertEquals("1.0", store.get(GameLaunchPreferences.SCREEN_SCALE));
        assertTrue(failure.observedChange().publishedAsRequested());
        assertEquals(2, store.flushes, "first flush fails; guarded compensation flushes once more");
    }

    @Test
    void flushFailureNeverRollsBackANewerExternalValueOnATouchedKey() {
        FailingFlushStore store = new FailingFlushStore(Map.of(
                GameLaunchPreferences.RESOLUTION, "1920x1080",
                GameLaunchPreferences.SCREEN_SCALE, "1.0"),
                current -> current.put(GameLaunchPreferences.SCREEN_SCALE, "1.3"));

        GameLaunchPreferences.PreferencePublicationException failure = assertThrows(
                GameLaunchPreferences.PreferencePublicationException.class,
                () -> applyScale(store));

        assertEquals("1.3", store.get(GameLaunchPreferences.SCREEN_SCALE));
        assertFalse(failure.observedChange().publishedAsRequested());
        assertEquals(1, store.flushes, "ambiguous/external touched state must not be compensated");
    }

    @Test
    void flushFailurePreservesExternalUnrelatedValuesWhileCompensatingOwnedKey() {
        FailingFlushStore store = new FailingFlushStore(Map.of(
                GameLaunchPreferences.RESOLUTION, "1920x1080",
                GameLaunchPreferences.SCREEN_SCALE, "1.0",
                GameLaunchPreferences.SOUND, "true"),
                current -> current.put(GameLaunchPreferences.SOUND, "false"));

        assertThrows(GameLaunchPreferences.PreferencePublicationException.class, () -> applyScale(store));

        assertEquals("1.0", store.get(GameLaunchPreferences.SCREEN_SCALE));
        assertEquals("false", store.get(GameLaunchPreferences.SOUND));
        assertEquals(2, store.flushes);
    }

    private void applyScale(FailingFlushStore store) throws Exception {
        LaunchSettingsMutation.apply(
                store,
                new GameLaunchPreferences.Update(null, null, null, null, 1.25, null),
                true,
                LaunchSettingsCommand.Limits.unknown(),
                temporary,
                null,
                ignored -> temporary.resolve("backup.json"),
                ignored -> {},
                (root, memory) -> null);
    }

    @FunctionalInterface
    private interface FailureHook {
        void run(FailingFlushStore store);
    }

    private static final class FailingFlushStore implements GameLaunchPreferences.Store {
        private final Map<String, String> values = new LinkedHashMap<>();
        private final FailureHook failureHook;
        private int flushes;

        private FailingFlushStore(Map<String, String> values, FailureHook failureHook) {
            this.values.putAll(values);
            this.failureHook = failureHook;
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
            if (flushes == 1) {
                failureHook.run(this);
                throw new BackingStoreException("synthetic flush failure");
            }
        }
    }
}
