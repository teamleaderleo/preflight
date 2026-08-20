package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Privacy-safe, read-only provenance for the Starsector settings Preflight already understands. */
final class GameConfigurationAuthority {
    private static final String EVIDENCE_KIND = "starsector-configuration-authority-v1";
    private static final String PREFERENCE_AUTHORITY = "java-preferences";
    private static final String HEAP_AUTHORITY = "launcher-heap-flags";
    private static final String PREFERENCE_GENERATION_SCHEMA =
            "starsector-launcher-preference-generation-v1";
    private static final String HEAP_OBSERVATION_BINDING = "unbound-current-observation";
    private static final List<String> PREFERENCE_KEYS = List.of(
            GameLaunchPreferences.RESOLUTION,
            GameLaunchPreferences.FULLSCREEN,
            GameLaunchPreferences.SOUND,
            GameLaunchPreferences.AA_SAMPLES,
            GameLaunchPreferences.SCREEN_SCALE,
            GameLaunchPreferences.GAMEPLAY_SETTINGS);

    private GameConfigurationAuthority() {
    }

    static Result project(
            Path installRoot,
            GameLaunchPreferences.Generation generation,
            JvmMemorySettings.Snapshot memory) {
        if (installRoot == null) {
            throw new IllegalArgumentException("Installation root is required");
        }
        if (generation == null) {
            throw new IllegalArgumentException("Launcher preference generation is required");
        }
        if (memory == null) {
            throw new IllegalArgumentException("Heap settings snapshot is required");
        }

        GameLaunchPreferences.Snapshot launch = GameLaunchPreferences.read(generation);
        List<Map<String, Object>> settings = new ArrayList<>();
        settings.add(preference(
                "resolution",
                launch.resolution(),
                generation,
                GameLaunchPreferences.RESOLUTION,
                generation.get(GameLaunchPreferences.RESOLUTION) == null ? "unset" : "stored-value",
                null));
        settings.add(preference(
                "fullscreen",
                launch.fullscreen(),
                generation,
                GameLaunchPreferences.FULLSCREEN,
                generation.get(GameLaunchPreferences.FULLSCREEN) == null
                        ? "vanilla-default-false"
                        : "stored-value",
                null));
        settings.add(preference(
                "sound",
                launch.sound(),
                generation,
                GameLaunchPreferences.SOUND,
                generation.get(GameLaunchPreferences.SOUND) == null
                        ? "vanilla-default-true"
                        : "stored-value",
                null));
        settings.add(preference(
                "antialiasingSamples",
                launch.antialiasingSamples(),
                generation,
                GameLaunchPreferences.AA_SAMPLES,
                generation.get(GameLaunchPreferences.AA_SAMPLES) == null ? "unset" : "stored-value",
                null));
        settings.add(preference(
                "uiScale",
                launch.uiScale(),
                generation,
                GameLaunchPreferences.SCREEN_SCALE,
                generation.get(GameLaunchPreferences.SCREEN_SCALE) == null ? "unset" : "stored-value",
                null));
        settings.add(battleSizePreference(launch.battleSize(), generation));
        settings.add(heap("maxHeapMiB", memory.maxHeapMiB(), installRoot, memory));
        settings.add(heap("initialHeapMiB", memory.initialHeapMiB(), installRoot, memory));

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("evidenceKind", EVIDENCE_KIND);
        values.put("composition", "independent-supplied-snapshots");
        values.put("freshness", "preference-generation-bound-heap-observation-unbound");
        values.put("combinedCurrentnessEstablished", false);
        values.put("preferenceNode", DirectLaunchSettings.PREFERENCES_NODE);
        values.put("preferenceGenerationBinding", "exact-supplied-generation-fingerprint");
        values.put("preferenceGenerationFingerprint", preferenceGenerationFingerprint(generation));
        values.put("heapObservationBinding", HEAP_OBSERVATION_BINDING);
        values.put("heapObservationGenerationBound", false);
        values.put("settings", List.copyOf(settings));
        values.put("launcherPreferenceDiagnostics", launch.diagnostics());
        values.put("heapObservationAvailable", memory.available());
        values.put("memoryDiagnosticCount", memory.diagnostics().size());
        values.put("notes", List.of(
                "Launcher settings use the same Java Preferences node and keys as Starsector's own launcher.",
                "The preference fingerprint identifies the exact supplied values for the launcher-setting keys in this projection.",
                "Heap values come from a separately supplied JvmMemorySettings observation with no shared generation token; combined currentness is not established.",
                "Heap source paths are only projected lexically relative to the selected installation; this projector does not perform a new filesystem containment check.",
                "Nested preference fields report parent-key presence separately from field-path presence and typed derivation.",
                "Registration credentials and unrelated launcher preferences are outside this projection.",
                "This report is read-only and does not apply or stage configuration changes."));
        return new Result(values);
    }

    private static Map<String, Object> preference(
            String name,
            Object effectiveValue,
            GameLaunchPreferences.Generation generation,
            String rawKey,
            String derivation,
            String valuePath) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", name);
        value.put("effectiveValue", effectiveValue);
        value.put("authority", PREFERENCE_AUTHORITY);
        value.put("source", DirectLaunchSettings.PREFERENCES_NODE);
        value.put("rawKey", rawKey);
        value.put("rawPresent", generation.get(rawKey) != null);
        value.put("derivation", derivation);
        if (valuePath != null) {
            value.put("valuePath", valuePath);
        }
        return value;
    }

    private static Map<String, Object> battleSizePreference(
            Integer effectiveValue,
            GameLaunchPreferences.Generation generation) {
        String raw = generation.get(GameLaunchPreferences.GAMEPLAY_SETTINGS);
        boolean valuePathPresent = false;
        String derivation = "unset";
        if (raw != null && !raw.isBlank()) {
            try {
                Map<String, Object> gameplay = StrictJson.object(raw);
                valuePathPresent = gameplay.containsKey(GameLaunchPreferences.BATTLE_SIZE);
                if (valuePathPresent) {
                    derivation = effectiveValue == null
                            ? "stored-json-field-unresolved"
                            : "stored-json-field";
                }
            } catch (RuntimeException malformed) {
                derivation = "unreadable-json-container";
            }
        }
        Map<String, Object> value = preference(
                "battleSize",
                effectiveValue,
                generation,
                GameLaunchPreferences.GAMEPLAY_SETTINGS,
                derivation,
                GameLaunchPreferences.BATTLE_SIZE);
        value.put("valuePathPresent", valuePathPresent);
        return value;
    }

    private static Map<String, Object> heap(
            String name,
            Integer effectiveValue,
            Path installRoot,
            JvmMemorySettings.Snapshot memory) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", name);
        value.put("effectiveValue", effectiveValue);
        value.put("authority", HEAP_AUTHORITY);
        value.put("observationBinding", HEAP_OBSERVATION_BINDING);
        value.put("generationBound", false);
        value.put("available", memory.available());
        value.put("editable", memory.editable());
        value.put("sourceKind", memory.sourceKind());
        SafeSource source = safeSource(installRoot, memory.source());
        value.put("sourceRelativePath", source.relativePath());
        value.put("sourceFileName", source.fileName());
        value.put("sourceLexicallyInsideSelectedInstall", source.lexicallyInsideSelectedInstall());
        return value;
    }

    private static SafeSource safeSource(Path installRoot, Path source) {
        if (source == null) {
            return new SafeSource(null, null, false);
        }
        Path root = installRoot.toAbsolutePath().normalize();
        Path absolute = source.toAbsolutePath().normalize();
        String fileName = absolute.getFileName() == null ? null : absolute.getFileName().toString();
        if (!absolute.startsWith(root)) {
            return new SafeSource(null, fileName, false);
        }
        String relative = root.relativize(absolute).toString().replace(absolute.getFileSystem().getSeparator(), "/");
        return new SafeSource(relative, fileName, true);
    }

    private static String preferenceGenerationFingerprint(GameLaunchPreferences.Generation generation) {
        MessageDigest digest = sha256();
        update(digest, PREFERENCE_GENERATION_SCHEMA);
        for (String key : PREFERENCE_KEYS) {
            update(digest, key);
            String raw = generation.get(key);
            digest.update((byte) (raw == null ? 0 : 1));
            if (raw != null) {
                update(digest, raw);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    record Result(Map<String, Object> values) {
        Result {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        String toJson() {
            return Json.object(values);
        }
    }

    private record SafeSource(
            String relativePath,
            String fileName,
            boolean lexicallyInsideSelectedInstall) {
    }
}
