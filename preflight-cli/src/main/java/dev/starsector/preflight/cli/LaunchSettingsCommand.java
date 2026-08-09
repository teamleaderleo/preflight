package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reports and explicitly updates the same settings Starsector's own launcher persists. */
final class LaunchSettingsCommand {
    private static final String FORMAT = "starsector-preflight-launch-settings-v1";
    /**
     * Starsector's maxBattleSize controls its settings slider, while the battleSize preference is
     * the value consumed by the game. Keep an explicit, bounded extended range without rewriting
     * the installation's settings.json. Opening the vanilla slider may reset a value above its
     * own maximum, which the desktop explains next to the control.
     */
    static final int EXTENDED_BATTLE_SIZE_MAX = 2000;
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter
            .ofPattern("uuuuMMdd-HHmmss-SSS")
            .withZone(ZoneOffset.UTC);
    private static final Pattern INTEGER_SETTING = Pattern.compile(
            "[\\\"']?(minBattleSize|defaultBattleSize|maxBattleSize|maxAASamples)[\\\"']?\\s*:\\s*(\\d+)");

    private LaunchSettingsCommand() {
    }

    static int execute(String[] args, int offset) throws Exception {
        boolean set = offset < args.length && "set".equals(args[offset]);
        int index = set ? offset + 1 : offset;
        Path game = null;
        boolean json = false;
        String resolution = null;
        Boolean fullscreen = null;
        Boolean sound = null;
        Integer antialiasing = null;
        Double uiScale = null;
        Integer battleSize = null;
        Integer memoryMiB = null;
        while (index < args.length) {
            String argument = args[index++];
            switch (argument) {
                case "--game" -> game = Path.of(requireValue(args, index++, argument));
                case "--json" -> json = true;
                case "--resolution" -> resolution = requireValue(args, index++, argument);
                case "--fullscreen" -> fullscreen = strictBoolean(requireValue(args, index++, argument), argument);
                case "--sound" -> sound = strictBoolean(requireValue(args, index++, argument), argument);
                case "--antialiasing" -> antialiasing = integer(requireValue(args, index++, argument), argument);
                case "--ui-scale" -> uiScale = decimal(requireValue(args, index++, argument), argument);
                case "--battle-size" -> battleSize = integer(requireValue(args, index++, argument), argument);
                case "--memory-mb" -> memoryMiB = integer(requireValue(args, index++, argument), argument);
                default -> throw new IllegalArgumentException("Unknown option: " + argument);
            }
        }
        if (!set && (resolution != null || fullscreen != null || sound != null
                || antialiasing != null || uiScale != null || battleSize != null || memoryMiB != null)) {
            throw new IllegalArgumentException("Use `launch-settings set` to change settings");
        }
        Path installRoot = game == null ? null : InstallRoot.resolve(game);
        Limits limits = Limits.read(installRoot);
        GameLaunchPreferences.Store store = GameLaunchPreferences.installed();
        GameLaunchPreferences.Snapshot existing = GameLaunchPreferences.read(store);
        Path backup = null;
        JvmMemorySettings.UpdateResult memoryUpdate = null;
        if (set) {
            GameLaunchPreferences.Update update = new GameLaunchPreferences.Update(
                    resolution, fullscreen, sound, antialiasing, uiScale, battleSize);
            boolean preferenceChange = resolution != null || fullscreen != null || sound != null
                    || antialiasing != null || uiScale != null || battleSize != null;
            if (!preferenceChange && memoryMiB == null) {
                throw new IllegalArgumentException("Name at least one launch setting to change");
            }
            if (preferenceChange) {
                GameLaunchPreferences.validate(update);
                limits.validate(update, existing);
            }
            OperationLease.Acquisition ownership = OperationLease.acquire(
                    PreflightHome.current(), "changing-launch-settings", installRoot);
            try (OperationLease ignored = ownership.lease()) {
                UpdateOutcome outcome = applyUpdate(
                        store, update, preferenceChange, installRoot, memoryMiB);
                backup = outcome.preferenceBackup();
                memoryUpdate = outcome.memoryUpdate();
            }
        }

        DirectLaunchSettings.Availability availability = directAvailability();
        JvmMemorySettings.Snapshot memory = memoryUpdate == null
                ? JvmMemorySettings.inspect(installRoot)
                : memoryUpdate.snapshot();
        Map<String, Object> report = describe(
                availability,
                GameLaunchPreferences.read(store),
                limits,
                memory,
                set,
                backup,
                memoryUpdate == null ? null : memoryUpdate.backup());
        // This command has historically emitted JSON even without --json. Keep that stable while
        // accepting the flag explicitly for desktop callers and shell consistency.
        if (json || !report.isEmpty()) System.out.println(Json.object(report));
        return 0;
    }

    private static UpdateOutcome applyUpdate(
            GameLaunchPreferences.Store store,
            GameLaunchPreferences.Update update,
            boolean preferenceChange,
            Path installRoot,
            Integer memoryMiB) throws Exception {
        GameLaunchPreferences.Backup before = preferenceChange ? GameLaunchPreferences.backup(store) : null;
        Path backup = before == null ? null : writeBackup(before);
        try {
            if (preferenceChange) GameLaunchPreferences.apply(store, update);
            JvmMemorySettings.UpdateResult memoryUpdate = memoryMiB == null
                    ? null : JvmMemorySettings.update(installRoot, memoryMiB);
            return new UpdateOutcome(backup, memoryUpdate);
        } catch (Exception failed) {
            if (before != null) {
                try {
                    GameLaunchPreferences.restore(store, before);
                } catch (Exception rollbackFailed) {
                    failed.addSuppressed(rollbackFailed);
                }
            }
            throw failed;
        }
    }

    static Map<String, Object> describe(DirectLaunchSettings.Availability availability) {
        return describe(
                availability,
                GameLaunchPreferences.read(key -> null),
                Limits.unknown(),
                JvmMemorySettings.Snapshot.unavailable("No installation was selected"),
                false,
                null,
                null);
    }

    static Map<String, Object> describe(
            DirectLaunchSettings.Availability availability,
            GameLaunchPreferences.Snapshot preferences,
            Limits limits,
            JvmMemorySettings.Snapshot memory,
            boolean changed,
            Path backup,
            Path memoryBackup) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", FORMAT);
        document.put("directLaunchAvailable", availability.available());
        document.put("reason", availability.reason());
        // Preserve the original report field for benchmark callers that only care whether the
        // direct-launch properties are complete. The broader editable surface is separate.
        document.put("settings", availability.settings() == null
                ? null : availability.settings().toReportValues());
        document.put("preferences", preferences.toMap());
        document.put("limits", limits.toMap());
        Map<String, Object> memoryValues = memory.toMap();
        memoryValues.put("backup", memoryBackup);
        document.put("memory", memoryValues);
        document.put("changed", changed);
        document.put("backup", backup);
        return document;
    }

    private record UpdateOutcome(
            Path preferenceBackup,
            JvmMemorySettings.UpdateResult memoryUpdate) {
    }

    private static DirectLaunchSettings.Availability directAvailability() {
        return DirectLaunchSettings.preferencesReadable()
                ? DirectLaunchSettings.resolve(DirectLaunchSettings.installedPreferences())
                : DirectLaunchSettings.Availability.unavailable(
                        "The game's launcher preferences (" + DirectLaunchSettings.PREFERENCES_NODE
                                + ") do not exist yet. Start the game once through its own"
                                + " launcher first.");
    }

    private static Path writeBackup(GameLaunchPreferences.Backup backup) throws IOException {
        Path directory = PreflightHome.current().launcherPreferenceBackups();
        Files.createDirectories(directory);
        Path destination = directory.resolve(
                BACKUP_TIME.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8) + ".json");
        Files.writeString(
                destination,
                Json.object(backup.toMap()) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        return destination.toAbsolutePath().normalize();
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) throw new IllegalArgumentException("Missing value for " + option);
        return args[index];
    }

    private static Boolean strictBoolean(String value, String option) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException(option + " must be true or false");
    }

    private static Integer integer(String value, String option) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException(option + " must be an integer", malformed);
        }
    }

    private static Double decimal(String value, String option) {
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException(option + " must be a number", malformed);
        }
    }

    record Limits(
            Integer minBattleSize,
            Integer defaultBattleSize,
            Integer maxBattleSize,
            Integer maxAntialiasingSamples,
            List<String> diagnostics) {
        Limits {
            diagnostics = List.copyOf(diagnostics);
        }

        static Limits unknown() {
            return new Limits(null, null, null, null, List.of());
        }

        static Limits read(Path installRoot) {
            if (installRoot == null) return unknown();
            for (Path settings : settingsCandidates(installRoot)) {
                if (!Files.isRegularFile(settings)) continue;
                try {
                    String text = Files.readString(settings, StandardCharsets.UTF_8);
                    Map<String, Integer> found = new LinkedHashMap<>();
                    Matcher matcher = INTEGER_SETTING.matcher(text);
                    while (matcher.find()) found.putIfAbsent(matcher.group(1), Integer.valueOf(matcher.group(2)));
                    if (found.keySet().containsAll(List.of(
                            "minBattleSize", "defaultBattleSize", "maxBattleSize"))) {
                        return new Limits(
                                found.get("minBattleSize"),
                                found.get("defaultBattleSize"),
                                found.get("maxBattleSize"),
                                found.get("maxAASamples"),
                                List.of());
                    }
                    return new Limits(null, null, null, found.get("maxAASamples"),
                            List.of("Could not read all battle-size bounds from " + settings));
                } catch (IOException unreadable) {
                    return new Limits(null, null, null, null,
                            List.of("Could not read battle-size bounds: " + unreadable.getMessage()));
                }
            }
            return new Limits(null, null, null, null,
                    List.of("Could not locate data/config/settings.json under " + installRoot));
        }

        void validate(GameLaunchPreferences.Update update) {
            validate(update, new GameLaunchPreferences.Snapshot(
                    null, false, true, null, null, null, List.of()));
        }

        void validate(
                GameLaunchPreferences.Update update,
                GameLaunchPreferences.Snapshot existing) {
            if (update.antialiasingSamples() != null && maxAntialiasingSamples != null
                    && update.antialiasingSamples() > maxAntialiasingSamples) {
                throw new IllegalArgumentException(
                        "Antialiasing exceeds the installed game's " + maxAntialiasingSamples
                                + "-sample limit");
            }
            String effectiveResolution = update.resolution() != null
                    ? update.resolution() : existing.resolution();
            Double effectiveUiScale = update.uiScale() != null
                    ? update.uiScale() : existing.uiScale();
            if (effectiveResolution != null && effectiveUiScale != null) {
                double maximum = maximumUiScale(effectiveResolution);
                if (effectiveUiScale > maximum) {
                    throw new IllegalArgumentException(
                            "UI scale cannot exceed " + maximum + " at " + effectiveResolution);
                }
            }
            if (update.battleSize() == null || minBattleSize == null || maxBattleSize == null) return;
            int effectiveMaximum = Math.max(maxBattleSize, EXTENDED_BATTLE_SIZE_MAX);
            if (update.battleSize() < minBattleSize || update.battleSize() > effectiveMaximum) {
                throw new IllegalArgumentException(
                        "Battle size must be between " + minBattleSize + " and " + effectiveMaximum
                                + "; the installed game's settings slider ends at " + maxBattleSize);
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("antialiasingSamples", maxAntialiasingSamples == null
                    ? GameLaunchPreferences.AA_CHOICES
                    : GameLaunchPreferences.AA_CHOICES.stream()
                            .filter(value -> value <= maxAntialiasingSamples)
                            .toList());
            values.put("uiScaleMin", 1.0);
            values.put("uiScaleMax", 3.0);
            values.put("uiScaleStep", 0.05);
            values.put("battleSizeMin", minBattleSize);
            values.put("battleSizeDefault", defaultBattleSize);
            values.put("battleSizeMax", maxBattleSize);
            values.put("battleSizeExtendedMax", maxBattleSize == null
                    ? EXTENDED_BATTLE_SIZE_MAX
                    : Math.max(maxBattleSize, EXTENDED_BATTLE_SIZE_MAX));
            values.put("diagnostics", diagnostics);
            return values;
        }

        static double maximumUiScale(String resolution) {
            String[] axes = resolution.split("x", -1);
            if (axes.length != 2) return 1d;
            try {
                double width = Integer.parseInt(axes[0]);
                double height = Integer.parseInt(axes[1]);
                return Math.max(1d, Math.floor(Math.min(height / 768d, width / 1280d) * 20d) / 20d);
            } catch (NumberFormatException malformed) {
                return 1d;
            }
        }

        private static List<Path> settingsCandidates(Path root) {
            return List.of(
                    root.resolve("data/config/settings.json"),
                    root.resolve("starsector-core/data/config/settings.json"),
                    root.resolve("Contents/Resources/Java/data/config/settings.json"),
                    root.resolve("Contents/Resources/data/config/settings.json"),
                    root.resolve("Contents/Java/data/config/settings.json"));
        }
    }
}
