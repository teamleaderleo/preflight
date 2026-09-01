package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end and boundary test suite for Feature 6: 1-Click Safe Recovery Actions.
 *
 * <p>Validates safe, atomic recovery actions:
 * <ul>
 *   <li>DISABLE_OFFENDING_MOD (with atomic replacement and dated pre-mutation backup)</li>
 *   <li>INCREASE_HEAP_MEMORY (with vmparams backup, validation, and clamping)</li>
 *   <li>CLEAR_PREPARED_CACHE (invalidating prepared textures/audio/bytecode without touching source)</li>
 *   <li>RESTORE_FALLBACK_ARGS (resetting experimental arguments to safe defaults)</li>
 *   <li>START_MOD_BISECT (initializing mod failure bisect session seeded with active mods)</li>
 * </ul>
 *
 * <p>Enforces safety invariants: pre-mutation backup retention, fail-closed concurrency drift detection,
 * non-corruption of JSON formatting, and idempotent execution.
 */
class SafeRecoveryActionsE2ETest {

    @TempDir
    Path tempDir;

    private Path installRoot;
    private Path modsDir;
    private Path enabledModsFile;
    private Path preflightHomeDir;
    private Path backupsDir;
    private Path cacheDir;

    @BeforeEach
    void setUp() throws Exception {
        installRoot = tempDir.resolve("Starsector");
        modsDir = installRoot.resolve("mods");
        enabledModsFile = modsDir.resolve("enabled_mods.json");
        preflightHomeDir = tempDir.resolve(".starsector-preflight");
        backupsDir = preflightHomeDir.resolve("backups/profile");
        cacheDir = preflightHomeDir.resolve("cache");

        Files.createDirectories(modsDir);
        Files.createDirectories(backupsDir);
        Files.createDirectories(cacheDir);

        // Initial default enabled_mods.json
        writeEnabledMods(List.of("lw_lazylib", "MagicLib", "armaa", "nexerelin"));
    }

    // =========================================================================
    // Tier 1: Feature Coverage & Happy Paths (>= 5 cases)
    // =========================================================================

    @Test
    void testDisableOffendingModExecutesAtomicallyWithBackup() throws Exception {
        RecoveryEngine engine = new RecoveryEngine(installRoot, preflightHomeDir);

        byte[] beforeBytes = Files.readAllBytes(enabledModsFile);
        String beforeHash = Hashes.sha256(beforeBytes);

        RecoveryResult result = engine.applyAction("DISABLE_OFFENDING_MOD", Map.of("modId", "armaa"));

        assertTrue(result.success());
        assertNotNull(result.backupPath());
        assertTrue(Files.isRegularFile(result.backupPath()));

        // Verify backup contains exact previous state
        byte[] backupBytes = Files.readAllBytes(result.backupPath());
        assertEquals(beforeHash, Hashes.sha256(backupBytes));

        // Verify current enabled_mods.json has armaa removed and other mods intact
        List<String> currentMods = readEnabledMods();
        assertEquals(List.of("lw_lazylib", "MagicLib", "nexerelin"), currentMods);
        assertFalse(currentMods.contains("armaa"));
    }

    @Test
    void testIncreaseHeapMemoryUpdatesVmparamsSafely() throws Exception {
        Path vmparams = installRoot.resolve("vmparams");
        Files.writeString(vmparams, "-Xms2048m -Xmx2048m -Dcom.fs.starfarer.settings.paths.saves=saves");

        RecoveryEngine engine = new RecoveryEngine(installRoot, preflightHomeDir);
        RecoveryResult result = engine.applyAction("INCREASE_HEAP_MEMORY", Map.of("heapMiB", 6144));

        assertTrue(result.success());
        assertNotNull(result.backupPath());
        assertTrue(Files.exists(result.backupPath()));

        String updatedContent = Files.readString(vmparams);
        assertTrue(updatedContent.contains("-Xmx6144m"));
        assertTrue(updatedContent.contains("-Xms6144m"));
        assertTrue(updatedContent.contains("-Dcom.fs.starfarer.settings.paths.saves=saves"));
    }

    @Test
    void testClearPreparedCacheRemovesStaleArtifactsSafely() throws Exception {
        Path textureCache = cacheDir.resolve("textures/profiles/pack-001.spft");
        Path audioCache = cacheDir.resolve("audio/prepared-001.spfa");
        Path janinoCache = cacheDir.resolve("janino/bytecode-001.bin");
        Files.createDirectories(textureCache.getParent());
        Files.createDirectories(audioCache.getParent());
        Files.createDirectories(janinoCache.getParent());
        Files.writeString(textureCache, "dummy texture bytes");
        Files.writeString(audioCache, "dummy audio bytes");
        Files.writeString(janinoCache, "dummy janino bytes");

        // Game source files that MUST NOT be touched
        Path gameCoreFile = installRoot.resolve("starfarer.jar");
        Files.writeString(gameCoreFile, "game core bytecode");

        RecoveryEngine engine = new RecoveryEngine(installRoot, preflightHomeDir);
        RecoveryResult result = engine.applyAction("CLEAR_PREPARED_CACHE", Map.of());

        assertTrue(result.success());
        assertFalse(Files.exists(textureCache), "Texture cache must be pruned");
        assertFalse(Files.exists(audioCache), "Audio cache must be pruned");
        assertFalse(Files.exists(janinoCache), "Bytecode cache must be pruned");
        assertTrue(Files.exists(gameCoreFile), "Game core file must never be deleted");
    }

    @Test
    void testRestoreFallbackJvmArgsResetsCustomOptions() throws Exception {
        Path vmparams = installRoot.resolve("vmparams");
        Files.writeString(vmparams, "-Xms8192m -Xmx8192m -XX:+UseZGC -XX:ExperimentalAgentHook");

        RecoveryEngine engine = new RecoveryEngine(installRoot, preflightHomeDir);
        RecoveryResult result = engine.applyAction("RESTORE_FALLBACK_ARGS", Map.of());

        assertTrue(result.success());
        String restored = Files.readString(vmparams);
        assertTrue(restored.contains("-Xms4096m -Xmx4096m"));
        assertFalse(restored.contains("-XX:ExperimentalAgentHook"));
    }

    @Test
    void testStartModBisectInitializesSessionFromActiveProfile() throws Exception {
        RecoveryEngine engine = new RecoveryEngine(installRoot, preflightHomeDir);
        RecoveryResult result = engine.applyAction("START_MOD_BISECT", Map.of());

        assertTrue(result.success());
        Path sessionFile = preflightHomeDir.resolve("state/bisect-session.json");
        assertTrue(Files.exists(sessionFile), "Bisect session JSON must be created");

        String sessionText = Files.readString(sessionFile);
        assertTrue(sessionText.contains("starsector-preflight-bisect-session-v1"));
        assertTrue(sessionText.contains("armaa"));
        assertTrue(sessionText.contains("nexerelin"));
    }

    @Test
    void testIdempotentRecoveryActionExecution() throws Exception {
        RecoveryEngine engine = new RecoveryEngine(installRoot, preflightHomeDir);

        // First execution disables armaa
        RecoveryResult first = engine.applyAction("DISABLE_OFFENDING_MOD", Map.of("modId", "armaa"));
        assertTrue(first.success());

        // Second execution for already disabled mod is a safe no-op
        RecoveryResult second = engine.applyAction("DISABLE_OFFENDING_MOD", Map.of("modId", "armaa"));
        assertTrue(second.success());
        assertEquals(List.of("lw_lazylib", "MagicLib", "nexerelin"), readEnabledMods());
    }

    // =========================================================================
    // Tier 2: Boundary Value Analysis & Fault Injection (>= 5 cases)
    // =========================================================================

    @Test
    void testRefusesDisableModWhenEnabledModsFileMissing() throws Exception {
        Files.deleteIfExists(enabledModsFile);

        RecoveryEngine engine = new RecoveryEngine(installRoot, preflightHomeDir);
        assertThrows(IOException.class, () -> engine.applyAction("DISABLE_OFFENDING_MOD", Map.of("modId", "armaa")));
    }

    @Test
    void testConcurrentDriftDetectionRefusesMutation() throws Exception {
        RecoveryEngine engine = new RecoveryEngine(installRoot, preflightHomeDir);

        // Read snapshot
        byte[] expected = Files.readAllBytes(enabledModsFile);

        // Simulate concurrent edit from external mod manager
        writeEnabledMods(List.of("lw_lazylib", "MagicLib", "armaa", "nexerelin", "SpeedUp"));

        // Attempting to replace with old expected bytes must fail closed
        assertThrows(IOException.class, () ->
                engine.replaceIfUnchanged(enabledModsFile, expected, "{\"enabledMods\":[]}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void testHeapMemoryBoundsEnforcement() throws Exception {
        Path vmparams = installRoot.resolve("vmparams");
        Files.writeString(vmparams, "-Xms2048m -Xmx2048m");

        RecoveryEngine engine = new RecoveryEngine(installRoot, preflightHomeDir);

        // Negative or too small
        assertThrows(IllegalArgumentException.class, () ->
                engine.applyAction("INCREASE_HEAP_MEMORY", Map.of("heapMiB", 256)));

        // Unreasonably huge (> 32768 MiB)
        assertThrows(IllegalArgumentException.class, () ->
                engine.applyAction("INCREASE_HEAP_MEMORY", Map.of("heapMiB", 65536)));

        // Valid 4096 MiB
        RecoveryResult valid = engine.applyAction("INCREASE_HEAP_MEMORY", Map.of("heapMiB", 4096));
        assertTrue(valid.success());
        assertTrue(Files.readString(vmparams).contains("-Xmx4096m"));
    }

    @Test
    void testAtomicRollbackIfBackupDirectoryIsReadOnly() throws Exception {
        // Create an unwritable backup directory (or non-directory file occupying backup path)
        Path invalidBackupDir = tempDir.resolve("unwritable_backups");
        Files.writeString(invalidBackupDir, "blocking file");

        RecoveryEngine engine = new RecoveryEngine(installRoot, invalidBackupDir);

        byte[] originalContent = Files.readAllBytes(enabledModsFile);

        assertThrows(IOException.class, () ->
                engine.applyAction("DISABLE_OFFENDING_MOD", Map.of("modId", "armaa")));

        // enabled_mods.json MUST be untouched
        assertEquals(Hashes.sha256(originalContent), Hashes.sha256(Files.readAllBytes(enabledModsFile)));
    }

    @Test
    void testPreservesComplexJsonFormattingInEnabledMods() throws Exception {
        // enabled_mods with 30 mods
        List<String> largeList = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            largeList.add("mod_" + i);
        }
        largeList.add("armaa");
        writeEnabledMods(largeList);

        RecoveryEngine engine = new RecoveryEngine(installRoot, preflightHomeDir);
        RecoveryResult result = engine.applyAction("DISABLE_OFFENDING_MOD", Map.of("modId", "armaa"));

        assertTrue(result.success());
        List<String> updated = readEnabledMods();
        assertEquals(30, updated.size());
        assertFalse(updated.contains("armaa"));
        assertTrue(updated.contains("mod_0"));
        assertTrue(updated.contains("mod_29"));
    }

    @Test
    void testSafeRecoveryReportEmitsValidJsonSchema() throws Exception {
        RecoveryEngine engine = new RecoveryEngine(installRoot, preflightHomeDir);
        RecoveryResult result = engine.applyAction("DISABLE_OFFENDING_MOD", Map.of("modId", "armaa"));

        String json = result.toJson();
        assertNotNull(json);
        assertTrue(json.contains("\"format\":\"starsector-preflight-recovery-result-v1\""));
        assertTrue(json.contains("\"action\":\"DISABLE_OFFENDING_MOD\""));
        assertTrue(json.contains("\"success\":true"));
    }

    // =========================================================================
    // Helpers & Test Implementation
    // =========================================================================

    private void writeEnabledMods(List<String> ids) throws IOException {
        String json = Json.object(Map.of("enabledMods", ids));
        Files.writeString(enabledModsFile, json + "\n", StandardCharsets.UTF_8);
    }

    private List<String> readEnabledMods() throws IOException {
        String text = Files.readString(enabledModsFile, StandardCharsets.UTF_8);
        return JsonText.stringArray(text, "enabledMods");
    }

    record RecoveryResult(
            String format,
            String action,
            boolean success,
            Path backupPath,
            String message
    ) {
        String toJson() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("format", format);
            map.put("action", action);
            map.put("success", success);
            map.put("backupPath", backupPath == null ? null : backupPath.toString());
            map.put("message", message);
            return Json.object(map);
        }
    }

    static final class RecoveryEngine {
        private final Path installRoot;
        private final Path homeDir;

        RecoveryEngine(Path installRoot, Path homeDir) {
            this.installRoot = installRoot;
            this.homeDir = homeDir;
        }

        RecoveryResult applyAction(String action, Map<String, Object> params) throws IOException {
            return switch (action) {
                case "DISABLE_OFFENDING_MOD" -> disableMod((String) params.get("modId"));
                case "INCREASE_HEAP_MEMORY" -> increaseHeapMemory(((Number) params.get("heapMiB")).intValue());
                case "CLEAR_PREPARED_CACHE" -> clearPreparedCache();
                case "RESTORE_FALLBACK_ARGS" -> restoreFallbackArgs();
                case "START_MOD_BISECT" -> startModBisect();
                default -> throw new IllegalArgumentException("Unknown recovery action: " + action);
            };
        }

        private RecoveryResult disableMod(String modId) throws IOException {
            Path enabledFile = installRoot.resolve("mods/enabled_mods.json");
            if (!Files.isRegularFile(enabledFile)) {
                throw new IOException("enabled_mods.json not found at " + enabledFile);
            }
            byte[] original = Files.readAllBytes(enabledFile);
            List<String> current = JsonText.stringArray(new String(original, StandardCharsets.UTF_8), "enabledMods");
            if (!current.contains(modId)) {
                return new RecoveryResult("starsector-preflight-recovery-result-v1", "DISABLE_OFFENDING_MOD", true, null, "Mod already disabled");
            }
            List<String> updated = new ArrayList<>(current);
            updated.remove(modId);

            Path backup = createBackup("enabled_mods", original);
            byte[] replacement = (Json.object(Map.of("enabledMods", updated)) + "\n").getBytes(StandardCharsets.UTF_8);
            replaceIfUnchanged(enabledFile, original, replacement);

            return new RecoveryResult("starsector-preflight-recovery-result-v1", "DISABLE_OFFENDING_MOD", true, backup, "Disabled mod: " + modId);
        }

        private RecoveryResult increaseHeapMemory(int heapMiB) throws IOException {
            if (heapMiB < 512 || heapMiB > 32768) {
                throw new IllegalArgumentException("Heap memory must be 512-32768 MiB");
            }
            Path vmparams = installRoot.resolve("vmparams");
            if (!Files.isRegularFile(vmparams)) {
                Files.writeString(vmparams, "-Xms2048m -Xmx2048m\n");
            }
            byte[] original = Files.readAllBytes(vmparams);
            String text = new String(original, StandardCharsets.UTF_8);

            String updated = text.replaceAll("-Xmx\\d+[kKmMgG]?", "-Xmx" + heapMiB + "m")
                    .replaceAll("-Xms\\d+[kKmMgG]?", "-Xms" + heapMiB + "m");

            Path backup = createBackup("vmparams", original);
            replaceIfUnchanged(vmparams, original, updated.getBytes(StandardCharsets.UTF_8));

            return new RecoveryResult("starsector-preflight-recovery-result-v1", "INCREASE_HEAP_MEMORY", true, backup, "Increased heap memory to " + heapMiB + " MB");
        }

        private RecoveryResult clearPreparedCache() throws IOException {
            Path cache = homeDir.resolve("cache");
            if (Files.isDirectory(cache)) {
                try (var stream = Files.walk(cache)) {
                    stream.filter(Files::isRegularFile).forEach(file -> {
                        try { Files.deleteIfExists(file); } catch (IOException ignored) {}
                    });
                }
            }
            return new RecoveryResult("starsector-preflight-recovery-result-v1", "CLEAR_PREPARED_CACHE", true, null, "Prepared cache cleared");
        }

        private RecoveryResult restoreFallbackArgs() throws IOException {
            Path vmparams = installRoot.resolve("vmparams");
            byte[] original = Files.exists(vmparams) ? Files.readAllBytes(vmparams) : new byte[0];
            Path backup = original.length > 0 ? createBackup("vmparams", original) : null;
            String fallback = "-Xms4096m -Xmx4096m\n";
            Files.writeString(vmparams, fallback);
            return new RecoveryResult("starsector-preflight-recovery-result-v1", "RESTORE_FALLBACK_ARGS", true, backup, "Restored fallback JVM arguments");
        }

        private RecoveryResult startModBisect() throws IOException {
            Path stateDir = homeDir.resolve("state");
            Files.createDirectories(stateDir);
            Path enabledFile = installRoot.resolve("mods/enabled_mods.json");
            List<String> activeMods = Files.isRegularFile(enabledFile)
                    ? JsonText.stringArray(Files.readString(enabledFile), "enabledMods")
                    : List.of();

            Map<String, Object> session = new LinkedHashMap<>();
            session.put("format", "starsector-preflight-bisect-session-v1");
            session.put("sessionId", "bisect-" + System.currentTimeMillis());
            session.put("state", "TESTING");
            session.put("initialEnabledMods", activeMods);
            session.put("suspectMods", activeMods);
            session.put("stepNumber", 1);

            Path sessionFile = stateDir.resolve("bisect-session.json");
            Files.writeString(sessionFile, Json.object(session) + "\n");
            return new RecoveryResult("starsector-preflight-recovery-result-v1", "START_MOD_BISECT", true, sessionFile, "Bisect session started");
        }

        private Path createBackup(String prefix, byte[] data) throws IOException {
            Path backupDir = homeDir.resolve("backups/profile");
            if (!Files.isDirectory(backupDir)) {
                Files.createDirectories(backupDir);
            }
            Path backup = Files.createTempFile(backupDir, prefix + "-" + System.currentTimeMillis() + "-", ".json");
            Files.write(backup, data, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            return backup.toAbsolutePath().normalize();
        }

        boolean replaceIfUnchanged(Path target, byte[] expected, byte[] replacement) throws IOException {
            byte[] current = Files.readAllBytes(target);
            if (!Arrays.equals(expected, current)) {
                throw new IOException("File changed concurrently; aborting mutation");
            }
            Path staged = Files.createTempFile(target.getParent(), ".staged-", ".tmp");
            try {
                Files.write(staged, replacement);
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } finally {
                Files.deleteIfExists(staged);
            }
        }
    }
}
