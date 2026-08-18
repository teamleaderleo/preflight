package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * The user-visible settings Starsector already keeps in its launcher preference node.
 *
 * <p>This is intentionally not another configuration system. The vanilla launcher reads and writes
 * these exact keys, and the game reads {@code gameplaySettings} from the same node. Preflight only
 * gives the existing values a typed, versioned CLI/desktop surface.</p>
 */
final class GameLaunchPreferences {
    static final String RESOLUTION = "resolution";
    static final String FULLSCREEN = "fullscreen";
    static final String SOUND = "sound";
    static final String AA_SAMPLES = "numAASamples";
    static final String SCREEN_SCALE = "screenScale";
    static final String GAMEPLAY_SETTINGS = "gameplaySettings";
    static final String BATTLE_SIZE = "battleSize";
    static final List<Integer> AA_CHOICES = List.of(0, 2, 4, 8, 12, 16, 24, 32);
    static final String SETTINGS_CHANGED =
            "The launch settings changed while they were being applied; review the current values and try again.";

    private GameLaunchPreferences() {
    }

    interface Store extends DirectLaunchSettings.Lookup {
        void put(String key, String value);

        void remove(String key);

        void flush() throws BackingStoreException;
    }

    /** Exact raw values of every launcher preference Preflight is authorized to mutate. */
    record Generation(Map<String, String> values) {
        Generation {
            values = Map.copyOf(values);
        }

        String get(String key) {
            return values.get(key);
        }

        Backup backup() {
            return new Backup(values);
        }
    }

    record Snapshot(
            String resolution,
            boolean fullscreen,
            boolean sound,
            Integer antialiasingSamples,
            Double uiScale,
            Integer battleSize,
            List<String> diagnostics) {
        Snapshot {
            diagnostics = List.copyOf(diagnostics);
        }

        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("resolution", resolution);
            values.put("fullscreen", fullscreen);
            values.put("sound", sound);
            values.put("antialiasingSamples", antialiasingSamples);
            values.put("uiScale", uiScale);
            values.put("battleSize", battleSize);
            values.put("diagnostics", diagnostics);
            return values;
        }
    }

    record Update(
            String resolution,
            Boolean fullscreen,
            Boolean sound,
            Integer antialiasingSamples,
            Double uiScale,
            Integer battleSize) {
        boolean empty() {
            return resolution == null
                    && fullscreen == null
                    && sound == null
                    && antialiasingSamples == null
                    && uiScale == null
                    && battleSize == null;
        }
    }

    /** Raw old values, deliberately excluding the registration serial and unrelated preferences. */
    record Backup(Map<String, String> values) {
        Backup {
            values = Map.copyOf(values);
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("format", "starsector-preflight-launch-settings-backup-v1");
            out.put("values", values);
            return out;
        }
    }

    /**
     * One observed Preflight publication. {@code expectedAfter} names the raw values Preflight
     * intended to own for the keys it touched; {@code observedAfter} is the actual readback after
     * the preference backend flushed.
     */
    record AppliedChange(
            Generation before,
            Generation expectedAfter,
            Generation observedAfter,
            Set<String> touchedKeys) {
        AppliedChange {
            touchedKeys = Set.copyOf(touchedKeys);
        }

        boolean publishedAsRequested() {
            for (String key : touchedKeys) {
                if (!Objects.equals(expectedAfter.get(key), observedAfter.get(key))) return false;
            }
            return true;
        }
    }

    static final class PreferenceStateChangedException extends BackingStoreException {
        PreferenceStateChangedException() {
            super(SETTINGS_CHANGED);
        }

        PreferenceStateChangedException(String message) {
            super(message);
        }
    }

    static Store installed() {
        Preferences preferences = Preferences.userRoot().node(DirectLaunchSettings.PREFERENCES_NODE);
        return new Store() {
            @Override
            public String get(String key) {
                return preferences.get(key, null);
            }

            @Override
            public void put(String key, String value) {
                preferences.put(key, value);
            }

            @Override
            public void remove(String key) {
                preferences.remove(key);
            }

            @Override
            public void flush() throws BackingStoreException {
                preferences.flush();
            }
        };
    }

    static Generation generation(DirectLaunchSettings.Lookup store) {
        Objects.requireNonNull(store, "store");
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : mutableKeys()) {
            String value = store.get(key);
            if (value != null) values.put(key, value);
        }
        return new Generation(values);
    }

    static Snapshot read(DirectLaunchSettings.Lookup store) {
        return read(generation(store));
    }

    static Snapshot read(Generation generation) {
        Objects.requireNonNull(generation, "generation");
        List<String> diagnostics = new ArrayList<>();
        String resolution = trimToNull(generation.get(RESOLUTION));
        Integer aa = integer(generation.get(AA_SAMPLES), AA_SAMPLES, diagnostics);
        Double scale = decimal(generation.get(SCREEN_SCALE), SCREEN_SCALE, diagnostics);
        Integer battleSize = battleSize(generation.get(GAMEPLAY_SETTINGS), diagnostics);
        return new Snapshot(
                resolution,
                Boolean.parseBoolean(generation.get(FULLSCREEN)),
                booleanOrDefault(generation.get(SOUND), true),
                aa,
                scale,
                battleSize,
                diagnostics);
    }

    static Backup backup(DirectLaunchSettings.Lookup store) {
        return generation(store).backup();
    }

    static void apply(Store store, Update update) throws BackingStoreException {
        Objects.requireNonNull(store, "store");
        validate(update);
        applyValues(store, update);
        store.flush();
    }

    /**
     * Applies a partial update only if the mutable preference set still matches {@code expected} at
     * the final application boundary exposed by the Java Preferences API. The returned raw
     * generations let the caller validate publication and later perform a guarded compensation.
     *
     * <p>This deliberately does not treat the Preflight operation lease as an external lock. A
     * caller that observed a newer generation should revalidate the requested effective settings
     * against that generation before calling this method.</p>
     */
    static AppliedChange applyIfUnchanged(Store store, Generation expected, Update update)
            throws BackingStoreException {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(expected, "expected");
        validate(update);
        Generation current = generation(store);
        if (!current.equals(expected)) throw new PreferenceStateChangedException();

        Set<String> touched = touchedKeys(update);
        Generation expectedAfter = expectedAfter(expected, update);
        applyValues(store, update);
        store.flush();
        Generation observedAfter = generation(store);
        return new AppliedChange(expected, expectedAfter, observedAfter, touched);
    }

    /**
     * Restores only keys whose raw values still equal the exact values Preflight published. A newer
     * external value on any touched key makes that key ineligible for rollback. Unrelated launcher
     * preferences are never restored from the broad recovery backup.
     */
    static void restoreIfStillPublished(Store store, AppliedChange change) throws BackingStoreException {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(change, "change");
        if (!change.publishedAsRequested()) {
            throw new PreferenceStateChangedException(
                    "The launch settings changed while Preflight was publishing them; the current values were kept."
                            + " Review the current settings before applying another change.");
        }

        Generation current = generation(store);
        for (String key : change.touchedKeys()) {
            if (!Objects.equals(current.get(key), change.expectedAfter().get(key))) {
                throw new PreferenceStateChangedException(
                        "The launch settings changed after Preflight updated them; the newer values were kept."
                                + " Review the current settings before applying another change.");
            }
        }

        for (String key : change.touchedKeys()) {
            String value = change.before().get(key);
            if (value == null) store.remove(key);
            else store.put(key, value);
        }
        store.flush();
    }

    static void restore(Store store, Backup backup) throws BackingStoreException {
        for (String key : mutableKeys()) {
            String value = backup.values().get(key);
            if (value == null) store.remove(key);
            else store.put(key, value);
        }
        store.flush();
    }

    static void validate(Update update) {
        Objects.requireNonNull(update, "update");
        if (update.empty()) {
            throw new IllegalArgumentException("Choose at least one launch setting to change");
        }
        if (update.resolution() != null && !validResolution(update.resolution())) {
            throw new IllegalArgumentException("Resolution must be WIDTHxHEIGHT using values from 1 to 65535");
        }
        if (update.antialiasingSamples() != null
                && !AA_CHOICES.contains(update.antialiasingSamples())) {
            throw new IllegalArgumentException("Antialiasing samples must be one of " + AA_CHOICES);
        }
        if (update.uiScale() != null) {
            double scale = update.uiScale();
            if (!Double.isFinite(scale) || scale < 1d || scale > 3d
                    || Math.abs(scale * 20d - Math.rint(scale * 20d)) > 0.000_001d) {
                throw new IllegalArgumentException("UI scale must be from 1.00 to 3.00 in 0.05 steps");
            }
        }
        if (update.battleSize() != null && update.battleSize() <= 0) {
            throw new IllegalArgumentException("Battle size must be positive");
        }
    }

    private static void applyValues(Store store, Update update) {
        if (update.resolution() != null) store.put(RESOLUTION, update.resolution());
        if (update.fullscreen() != null) store.put(FULLSCREEN, update.fullscreen().toString());
        if (update.sound() != null) store.put(SOUND, update.sound().toString());
        if (update.antialiasingSamples() != null) {
            store.put(AA_SAMPLES, update.antialiasingSamples().toString());
        }
        if (update.uiScale() != null) store.put(SCREEN_SCALE, decimalText(update.uiScale()));
        if (update.battleSize() != null) {
            Map<String, Object> gameplay = gameplayObject(store.get(GAMEPLAY_SETTINGS));
            gameplay.put(BATTLE_SIZE, update.battleSize());
            store.put(GAMEPLAY_SETTINGS, Json.object(gameplay));
        }
    }

    private static Generation expectedAfter(Generation before, Update update) {
        Map<String, String> values = new LinkedHashMap<>(before.values());
        if (update.resolution() != null) values.put(RESOLUTION, update.resolution());
        if (update.fullscreen() != null) values.put(FULLSCREEN, update.fullscreen().toString());
        if (update.sound() != null) values.put(SOUND, update.sound().toString());
        if (update.antialiasingSamples() != null) values.put(AA_SAMPLES, update.antialiasingSamples().toString());
        if (update.uiScale() != null) values.put(SCREEN_SCALE, decimalText(update.uiScale()));
        if (update.battleSize() != null) {
            Map<String, Object> gameplay = gameplayObject(before.get(GAMEPLAY_SETTINGS));
            gameplay.put(BATTLE_SIZE, update.battleSize());
            values.put(GAMEPLAY_SETTINGS, Json.object(gameplay));
        }
        return new Generation(values);
    }

    private static Set<String> touchedKeys(Update update) {
        Set<String> keys = new LinkedHashSet<>();
        if (update.resolution() != null) keys.add(RESOLUTION);
        if (update.fullscreen() != null) keys.add(FULLSCREEN);
        if (update.sound() != null) keys.add(SOUND);
        if (update.antialiasingSamples() != null) keys.add(AA_SAMPLES);
        if (update.uiScale() != null) keys.add(SCREEN_SCALE);
        if (update.battleSize() != null) keys.add(GAMEPLAY_SETTINGS);
        return Set.copyOf(keys);
    }

    private static List<String> mutableKeys() {
        return List.of(RESOLUTION, FULLSCREEN, SOUND, AA_SAMPLES, SCREEN_SCALE, GAMEPLAY_SETTINGS);
    }

    private static Map<String, Object> gameplayObject(String raw) {
        if (raw == null || raw.isBlank()) return new LinkedHashMap<>();
        return new LinkedHashMap<>(StrictJson.object(raw));
    }

    private static Integer battleSize(String raw, List<String> diagnostics) {
        if (raw == null || raw.isBlank()) return null;
        try {
            Object value = gameplayObject(raw).get(BATTLE_SIZE);
            if (value == null) return null;
            if (value instanceof Number number) {
                long exact = number.longValue();
                if (exact > 0 && exact <= Integer.MAX_VALUE && number.doubleValue() == exact) {
                    return (int) exact;
                }
            }
            diagnostics.add("The saved gameplaySettings battleSize is not a positive integer.");
        } catch (IllegalArgumentException malformed) {
            diagnostics.add("The saved gameplaySettings preference is not valid JSON; it was left untouched.");
        }
        return null;
    }

    private static Integer integer(String raw, String name, List<String> diagnostics) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException malformed) {
            diagnostics.add("The saved " + name + " preference is not an integer.");
            return null;
        }
    }

    private static Double decimal(String raw, String name, List<String> diagnostics) {
        if (raw == null || raw.isBlank()) return null;
        try {
            double value = Double.parseDouble(raw.trim());
            if (Double.isFinite(value)) return value;
        } catch (NumberFormatException ignored) {
            // Described once below.
        }
        diagnostics.add("The saved " + name + " preference is not a finite number.");
        return null;
    }

    private static boolean validResolution(String value) {
        String[] axes = value.split("x", -1);
        return axes.length == 2 && boundedPositive(axes[0]) && boundedPositive(axes[1]);
    }

    private static boolean boundedPositive(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 && parsed <= 65_535 && Integer.toString(parsed).equals(value);
        } catch (NumberFormatException malformed) {
            return false;
        }
    }

    private static String decimalText(double value) {
        if (value == Math.rint(value)) return Integer.toString((int) value) + ".0";
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static boolean booleanOrDefault(String value, boolean fallback) {
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value);
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
