package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    private GameLaunchPreferences() {
    }

    interface Store extends DirectLaunchSettings.Lookup {
        void put(String key, String value);

        void remove(String key);

        void flush() throws BackingStoreException;
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

    static Snapshot read(DirectLaunchSettings.Lookup store) {
        Objects.requireNonNull(store, "store");
        List<String> diagnostics = new ArrayList<>();
        String resolution = trimToNull(store.get(RESOLUTION));
        Integer aa = integer(store.get(AA_SAMPLES), AA_SAMPLES, diagnostics);
        Double scale = decimal(store.get(SCREEN_SCALE), SCREEN_SCALE, diagnostics);
        Integer battleSize = battleSize(store.get(GAMEPLAY_SETTINGS), diagnostics);
        return new Snapshot(
                resolution,
                Boolean.parseBoolean(store.get(FULLSCREEN)),
                booleanOrDefault(store.get(SOUND), true),
                aa,
                scale,
                battleSize,
                diagnostics);
    }

    static Backup backup(DirectLaunchSettings.Lookup store) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : mutableKeys()) {
            String value = store.get(key);
            if (value != null) {
                values.put(key, value);
            }
        }
        return new Backup(values);
    }

    static void apply(Store store, Update update) throws BackingStoreException {
        Objects.requireNonNull(store, "store");
        validate(update);
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
