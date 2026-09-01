package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Executes safe, atomic 1-click recovery actions for diagnosed Starsector crashes.
 */
final class RecoveryActionEngine {
    static final String FORMAT = "starsector-preflight-recovery-result-v1";
    private static final Pattern PROFILE_BACKUP_PATTERN = Pattern.compile(
            "enabled_mods-\\d+-.*\\.json");

    private RecoveryActionEngine() {
    }

    record ExecutionResult(
            String format,
            String actionId,
            boolean applied,
            boolean success,
            String summary,
            String details,
            Path backupPath,
            boolean relaunchReady
    ) {
        ExecutionResult {
            if (format == null) format = FORMAT;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("format", format);
            map.put("actionId", actionId);
            map.put("applied", applied);
            map.put("success", success);
            map.put("summary", summary);
            map.put("details", details);
            map.put("backupPath", backupPath == null ? null : backupPath.toString());
            map.put("relaunchReady", relaunchReady);
            return Collections.unmodifiableMap(map);
        }

        String toJson() {
            return Json.object(toMap());
        }
    }

    static ExecutionResult execute(
            PreflightHome home,
            Path installRoot,
            String actionId,
            Map<String, Object> parameters,
            boolean confirmed) throws Exception {

        if (actionId == null || actionId.isBlank()) {
            throw new IllegalArgumentException("Recovery action ID must not be blank");
        }

        if (!confirmed) {
            return new ExecutionResult(
                    FORMAT,
                    actionId,
                    false,
                    true,
                    "Preview for recovery action '" + actionId + "' (not applied without --yes / confirmation)",
                    "Pass --yes or confirmed=true to execute this action.",
                    null,
                    false
            );
        }

        OperationLease.Acquisition ownership = OperationLease.acquire(home, "recovery-" + actionId.toLowerCase().replace('_', '-'), installRoot);
        try (OperationLease ignored = ownership.lease()) {
            return switch (actionId) {
                case "DISABLE_OFFENDING_MOD", "disable-mod" -> disableMod(home, installRoot, parameters);
                case "INCREASE_HEAP_MEMORY", "increase-heap" -> increaseHeap(home, installRoot, parameters);
                case "CLEAR_SHADER_CACHE", "clear-shader-cache" -> clearShaderCache(installRoot);
                case "CLEAR_PREPARED_CACHE", "clear-prepared-cache" -> clearPreparedCache(home);
                case "RESTORE_FALLBACK_ARGS", "restore-fallback-args" -> restoreFallbackArgs(home, installRoot);
                case "EXPORT_DIAGNOSTICS", "export-diagnostics" -> exportDiagnostics(home);
                default -> throw new IllegalArgumentException("Unknown recovery action: " + actionId);
            };
        }
    }

    private static ExecutionResult disableMod(
            PreflightHome home,
            Path installRoot,
            Map<String, Object> parameters) throws Exception {

        String modId = parameters != null && parameters.get("modId") instanceof String s ? s : null;
        if (modId == null || modId.isBlank()) {
            throw new IllegalArgumentException("modId parameter is required for DISABLE_OFFENDING_MOD");
        }

        GameLayout layout = GameLayout.locate(installRoot);
        Path enabledModsFile = layout.enabledModsFile();

        if (!Files.isRegularFile(enabledModsFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("enabled_mods.json file not found at " + enabledModsFile);
        }

        byte[] currentBytes = Files.readAllBytes(enabledModsFile);
        List<String> current = JsonText.stringArray(new String(currentBytes, StandardCharsets.UTF_8), "enabledMods");

        if (!current.contains(modId)) {
            return new ExecutionResult(
                    FORMAT,
                    "DISABLE_OFFENDING_MOD",
                    true,
                    true,
                    "Mod '" + modId + "' was already disabled in enabled_mods.json.",
                    null,
                    null,
                    true
            );
        }

        List<String> updated = new ArrayList<>(current);
        updated.remove(modId);

        // Pre-mutation backup
        Path backup = backupEnabledMods(home, currentBytes);

        // Atomic update
        byte[] replacement = (Json.object(Map.of("enabledMods", updated)) + System.lineSeparator())
                .getBytes(StandardCharsets.UTF_8);

        atomicReplaceFile(enabledModsFile, currentBytes, replacement);

        return new ExecutionResult(
                FORMAT,
                "DISABLE_OFFENDING_MOD",
                true,
                true,
                "Successfully disabled mod '" + modId + "' (" + updated.size() + " mods remaining).",
                "Created safety backup at " + backup,
                backup,
                true
        );
    }

    private static ExecutionResult increaseHeap(
            PreflightHome home,
            Path installRoot,
            Map<String, Object> parameters) throws IOException {

        int targetHeapMiB = 6144;
        if (parameters != null && parameters.get("targetHeapMiB") instanceof Number n) {
            targetHeapMiB = n.intValue();
        } else if (parameters != null && parameters.get("heapMiB") instanceof Number n) {
            targetHeapMiB = n.intValue();
        }

        Path vmparams = installRoot != null ? installRoot.resolve("vmparams") : null;
        try {
            JvmMemorySettings.UpdateResult result = JvmMemorySettings.update(installRoot, targetHeapMiB);
            return new ExecutionResult(
                    FORMAT,
                    "INCREASE_HEAP_MEMORY",
                    true,
                    true,
                    "Updated JVM maximum heap allocation to " + targetHeapMiB + " MiB in launcher vmparams.",
                    result.backup() != null ? "Backup saved to " + result.backup() : null,
                    result.backup(),
                    true
            );
        } catch (Exception failure) {
            if (vmparams != null && Files.isRegularFile(vmparams)) {
                return updateStandaloneVmparams(home, vmparams, "INCREASE_HEAP_MEMORY", targetHeapMiB);
            }
            throw failure;
        }
    }

    private static ExecutionResult clearShaderCache(Path installRoot) throws IOException {
        long deletedCount = 0;
        if (installRoot != null) {
            Path[] candidateDirs = new Path[] {
                    installRoot.resolve("shaders/cache"),
                    installRoot.resolve("starsector-core/shaders/cache")
            };
            for (Path dir : candidateDirs) {
                if (Files.isDirectory(dir)) {
                    try (Stream<Path> stream = Files.walk(dir)) {
                        for (Path p : stream.filter(Files::isRegularFile).toList()) {
                            try {
                                Files.deleteIfExists(p);
                                deletedCount++;
                            } catch (IOException ignored) {
                            }
                        }
                    }
                }
            }
        }

        return new ExecutionResult(
                FORMAT,
                "CLEAR_SHADER_CACHE",
                true,
                true,
                "Cleared " + deletedCount + " cached shader files from shaders/cache.",
                null,
                null,
                true
        );
    }

    private static ExecutionResult clearPreparedCache(PreflightHome home) throws IOException {
        long deletedCount = 0;
        Path cacheDir = home.cache();
        if (Files.isDirectory(cacheDir)) {
            try (Stream<Path> stream = Files.walk(cacheDir)) {
                for (Path p : stream.sorted((a, b) -> b.compareTo(a)).toList()) {
                    if (!p.equals(cacheDir)) {
                        try {
                            Files.deleteIfExists(p);
                            deletedCount++;
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        }

        return new ExecutionResult(
                FORMAT,
                "CLEAR_PREPARED_CACHE",
                true,
                true,
                "Purged " + deletedCount + " prepared cache artifacts from " + cacheDir + ".",
                null,
                null,
                true
        );
    }

    private static ExecutionResult restoreFallbackArgs(PreflightHome home, Path installRoot) throws IOException {
        int targetHeapMiB = 4096;
        Path vmparams = installRoot != null ? installRoot.resolve("vmparams") : null;
        try {
            JvmMemorySettings.UpdateResult result = JvmMemorySettings.update(installRoot, targetHeapMiB);
            return new ExecutionResult(
                    FORMAT,
                    "RESTORE_FALLBACK_ARGS",
                    true,
                    true,
                    "Restored safe fallback JVM heap allocation (4096 MiB).",
                    result.backup() != null ? "Backup saved to " + result.backup() : null,
                    result.backup(),
                    true
            );
        } catch (Exception failure) {
            if (vmparams != null && Files.isRegularFile(vmparams)) {
                return updateStandaloneVmparams(home, vmparams, "RESTORE_FALLBACK_ARGS", targetHeapMiB);
            }
            throw failure;
        }
    }

    private static ExecutionResult updateStandaloneVmparams(
            PreflightHome home,
            Path vmparams,
            String actionId,
            int targetHeapMiB) throws IOException {
        byte[] original = Files.readAllBytes(vmparams);
        String text = new String(original, StandardCharsets.UTF_8);
        String updated = text.replaceAll("-Xmx\\d+[kKmMgG]?", "-Xmx" + targetHeapMiB + "m")
                .replaceAll("-Xms\\d+[kKmMgG]?", "-Xms" + targetHeapMiB + "m");
        if (!updated.contains("-Xmx")) {
            updated = "-Xms" + targetHeapMiB + "m -Xmx" + targetHeapMiB + "m\n" + updated;
        }

        Path backup = backupFile(home, "vmparams", original);
        atomicReplaceFile(vmparams, original, updated.getBytes(StandardCharsets.UTF_8));

        return new ExecutionResult(
                FORMAT,
                actionId,
                true,
                true,
                "Updated JVM maximum heap allocation to " + targetHeapMiB + " MiB in vmparams.",
                "Backup saved to " + backup,
                backup,
                true
        );
    }

    private static Path backupFile(PreflightHome home, String prefix, byte[] content) throws IOException {
        Path directory = SafetyArtifactRetention.requireRealDirectory(home.launcherFileBackups());
        Path target = Files.createTempFile(
                directory,
                prefix + "-" + Instant.now().toEpochMilli() + "-",
                ".bak");
        Files.write(target, content);
        return target.toAbsolutePath().normalize();
    }

    private static ExecutionResult exportDiagnostics(PreflightHome home) {
        return new ExecutionResult(
                FORMAT,
                "EXPORT_DIAGNOSTICS",
                true,
                true,
                "Support diagnostics bundle ready for export.",
                "Use `preflight evidence export` or desktop export button.",
                null,
                false
        );
    }

    private static Path backupEnabledMods(PreflightHome home, byte[] content) throws IOException {
        Path directory = SafetyArtifactRetention.requireRealDirectory(home.profileBackups());
        Path target = Files.createTempFile(
                directory,
                "enabled_mods-" + Instant.now().toEpochMilli() + "-",
                ".json");
        Files.write(target, content);
        SafetyArtifactRetention.retainNewest(
                home.profileBackups(),
                PROFILE_BACKUP_PATTERN,
                SafetyArtifactRetention.MAX_BACKUPS_PER_DIRECTORY);
        return target.toAbsolutePath().normalize();
    }

    private static void atomicReplaceFile(Path destination, byte[] expected, byte[] replacement) throws IOException {
        byte[] current = Files.readAllBytes(destination);
        if (!java.util.Arrays.equals(expected, current)) {
            throw new IOException("File changed concurrently before recovery action could be applied");
        }

        Path parent = destination.toAbsolutePath().normalize().getParent();
        Path staged = Files.createTempFile(parent, ".preflight-recovery-", ".tmp");
        try {
            Files.write(staged, replacement, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(destination);
                Files.setPosixFilePermissions(staged, permissions);
            } catch (UnsupportedOperationException ignored) {
            }

            try {
                Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(staged, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
    }
}
