package dev.starsector.preflight.core.checkpoints;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable launch checkpoint capturing active mods, content SHA-256 signatures,
 * launcher settings, and historical run outcomes.
 */
public record Checkpoint(
        String format,
        String name,
        String description,
        Path installRoot,
        String createdAt,
        String checkpointFingerprint,
        String profileFingerprint,
        List<String> enabledMods,
        List<ModSignature> modSignatures,
        LaunchSettingsSnapshot launchSettings,
        LastRunSummary lastRunSummary,
        Path file) {

    public static final String FORMAT = "starsector-preflight-checkpoint-v1";

    public Checkpoint {
        name = validateName(name);
        description = description != null ? description : "";
        enabledMods = List.copyOf(enabledMods != null ? enabledMods : List.of());
        modSignatures = List.copyOf(modSignatures != null ? modSignatures : List.of());
        if (format == null) {
            format = FORMAT;
        }
        if (checkpointFingerprint == null) {
            checkpointFingerprint = computeFingerprint(name, installRoot, enabledMods, modSignatures, launchSettings);
        }
    }

    public static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Checkpoint name must not be blank");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 100 || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Checkpoint name must be 1-100 printable characters");
        }
        return trimmed;
    }

    public record ModSignature(
            String modId,
            String name,
            String version,
            String contentSha256,
            int fileCount,
            long totalBytes) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("modId", modId);
            map.put("name", name != null ? name : modId);
            map.put("version", version != null ? version : "unknown");
            map.put("contentSha256", contentSha256);
            map.put("fileCount", fileCount);
            map.put("totalBytes", totalBytes);
            return map;
        }

        public static ModSignature fromMap(Map<String, Object> map) {
            if (map == null) return null;
            String modId = String.valueOf(map.get("modId"));
            String name = map.get("name") != null ? String.valueOf(map.get("name")) : modId;
            String version = map.get("version") != null ? String.valueOf(map.get("version")) : "unknown";
            String contentSha256 = String.valueOf(map.get("contentSha256"));
            int fileCount = map.get("fileCount") instanceof Number n ? n.intValue() : 0;
            long totalBytes = map.get("totalBytes") instanceof Number n ? n.longValue() : 0L;
            return new ModSignature(modId, name, version, contentSha256, fileCount, totalBytes);
        }
    }

    public record LaunchSettingsSnapshot(
            String resolution,
            Boolean fullscreen,
            Boolean sound,
            Integer antialiasingSamples,
            Double uiScale,
            Integer battleSize,
            Integer memoryMiB) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("resolution", resolution);
            map.put("fullscreen", fullscreen);
            map.put("sound", sound);
            map.put("antialiasingSamples", antialiasingSamples);
            map.put("uiScale", uiScale);
            map.put("battleSize", battleSize);
            map.put("memoryMiB", memoryMiB);
            return map;
        }

        public static LaunchSettingsSnapshot fromMap(Map<String, Object> map) {
            if (map == null) return null;
            String resolution = map.get("resolution") != null ? String.valueOf(map.get("resolution")) : null;
            Boolean fullscreen = map.get("fullscreen") instanceof Boolean b ? b : null;
            Boolean sound = map.get("sound") instanceof Boolean b ? b : null;
            Integer antialiasingSamples = map.get("antialiasingSamples") instanceof Number n ? n.intValue() : null;
            Double uiScale = map.get("uiScale") instanceof Number n ? n.doubleValue() : null;
            Integer battleSize = map.get("battleSize") instanceof Number n ? n.intValue() : null;
            Integer memoryMiB = map.get("memoryMiB") instanceof Number n ? n.intValue() : null;
            return new LaunchSettingsSnapshot(resolution, fullscreen, sound, antialiasingSamples, uiScale, battleSize, memoryMiB);
        }
    }

    public record LastRunSummary(
            String outcome,
            Long startupMillis,
            Long durationMillis,
            Long exitCode,
            String started) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("outcome", outcome);
            map.put("startupMillis", startupMillis);
            map.put("durationMillis", durationMillis);
            map.put("exitCode", exitCode);
            map.put("started", started);
            return map;
        }

        public static LastRunSummary fromMap(Map<String, Object> map) {
            if (map == null) return null;
            String outcome = map.get("outcome") != null ? String.valueOf(map.get("outcome")) : null;
            Long startupMillis = map.get("startupMillis") instanceof Number n ? n.longValue() : null;
            Long durationMillis = map.get("durationMillis") instanceof Number n ? n.longValue() : null;
            Long exitCode = map.get("exitCode") instanceof Number n ? n.longValue() : null;
            String started = map.get("started") != null ? String.valueOf(map.get("started")) : null;
            return new LastRunSummary(outcome, startupMillis, durationMillis, exitCode, started);
        }
    }

    public Map<String, Object> persisted() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("format", FORMAT);
        map.put("name", name);
        map.put("description", description != null ? description : "");
        map.put("installRoot", installRoot != null ? installRoot.toAbsolutePath().normalize().toString() : "");
        map.put("createdAt", createdAt);
        map.put("checkpointFingerprint", checkpointFingerprint);
        map.put("profileFingerprint", profileFingerprint != null ? profileFingerprint : "");
        map.put("enabledMods", enabledMods);
        map.put("modSignatures", modSignatures.stream().map(ModSignature::toMap).toList());
        map.put("launchSettings", launchSettings == null ? null : launchSettings.toMap());
        map.put("lastRunSummary", lastRunSummary == null ? null : lastRunSummary.toMap());
        return map;
    }

    public String toJson() {
        return Json.object(persisted());
    }

    public static Checkpoint fromJson(String json, Path file) throws IOException {
        Map<String, Object> map = JsonParser.parseObject(json);
        if (!FORMAT.equals(map.get("format"))) {
            throw new IOException("Unsupported checkpoint format: " + map.get("format"));
        }
        String name = String.valueOf(map.get("name"));
        String description = map.get("description") != null ? String.valueOf(map.get("description")) : "";
        String install = map.get("installRoot") != null ? String.valueOf(map.get("installRoot")) : "";
        String createdAt = map.get("createdAt") != null ? String.valueOf(map.get("createdAt")) : "";
        String checkpointFingerprint = map.get("checkpointFingerprint") != null ? String.valueOf(map.get("checkpointFingerprint")) : null;
        String profileFingerprint = map.get("profileFingerprint") != null ? String.valueOf(map.get("profileFingerprint")) : "";

        @SuppressWarnings("unchecked")
        List<Object> rawEnabled = (List<Object>) map.get("enabledMods");
        List<String> enabledMods = rawEnabled != null
                ? rawEnabled.stream().map(String::valueOf).toList()
                : List.of();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawSigs = (List<Map<String, Object>>) map.get("modSignatures");
        List<ModSignature> modSignatures = rawSigs != null
                ? rawSigs.stream().map(ModSignature::fromMap).toList()
                : List.of();

        @SuppressWarnings("unchecked")
        Map<String, Object> rawSettings = (Map<String, Object>) map.get("launchSettings");
        LaunchSettingsSnapshot settings = LaunchSettingsSnapshot.fromMap(rawSettings);

        @SuppressWarnings("unchecked")
        Map<String, Object> rawLastRun = (Map<String, Object>) map.get("lastRunSummary");
        LastRunSummary lastRun = LastRunSummary.fromMap(rawLastRun);

        Path installRoot = !install.isBlank() ? Path.of(install).toAbsolutePath().normalize() : null;

        return new Checkpoint(
                FORMAT,
                name,
                description,
                installRoot,
                createdAt,
                checkpointFingerprint,
                profileFingerprint,
                enabledMods,
                modSignatures,
                settings,
                lastRun,
                file != null ? file.toAbsolutePath().normalize() : null
        );
    }

    public static String computeFingerprint(
            String name,
            Path installRoot,
            List<String> enabledMods,
            List<ModSignature> modSignatures,
            LaunchSettingsSnapshot launchSettings) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, FORMAT);
            update(digest, name);
            if (installRoot != null) {
                update(digest, installRoot.toAbsolutePath().normalize().toString());
            }
            if (enabledMods != null) {
                for (String modId : enabledMods) {
                    update(digest, "mod:" + modId);
                }
            }
            if (modSignatures != null) {
                for (ModSignature sig : modSignatures) {
                    update(digest, sig.modId());
                    update(digest, sig.version());
                    update(digest, sig.contentSha256());
                    update(digest, Integer.toString(sig.fileCount()));
                    update(digest, Long.toString(sig.totalBytes()));
                }
            }
            if (launchSettings != null) {
                update(digest, String.valueOf(launchSettings.resolution()));
                update(digest, String.valueOf(launchSettings.fullscreen()));
                update(digest, String.valueOf(launchSettings.sound()));
                update(digest, String.valueOf(launchSettings.antialiasingSamples()));
                update(digest, String.valueOf(launchSettings.uiScale()));
                update(digest, String.valueOf(launchSettings.battleSize()));
                update(digest, String.valueOf(launchSettings.memoryMiB()));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        if (value != null) {
            digest.update(value.getBytes(StandardCharsets.UTF_8));
        }
        digest.update((byte) 0);
    }
}
