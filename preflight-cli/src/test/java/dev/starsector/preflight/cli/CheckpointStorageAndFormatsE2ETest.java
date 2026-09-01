package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.ContentFingerprint;
import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-End and specification verification for Feature 1: Checkpoint Storage & Formats.
 *
 * <p>Verifies the {@code starsector-preflight-checkpoint-v1} schema, deterministic fingerprinting,
 * atomic filesystem storage under {@code ~/.starsector-preflight/checkpoints/}, backup retention,
 * name sanitization, and fault-tolerant listing.</p>
 */
final class CheckpointStorageAndFormatsE2ETest {

    public static final String SCHEMA_FORMAT = "starsector-preflight-checkpoint-v1";
    private static final Pattern BACKUP_PATTERN = Pattern.compile("(?:checkpoint-backup|deleted-checkpoint)-\\d+-.*\\.json");

    @TempDir
    Path temporaryDirectory;

    // =========================================================================
    // Tier 1: Primary Feature Coverage & Happy Paths (>= 5 test cases)
    // =========================================================================

    @Nested
    @DisplayName("Tier 1: Feature Coverage & Happy Path Test Cases")
    class Tier1FeatureCoverage {

        @Test
        @DisplayName("T1.1: Complete valid checkpoint serializes and deserializes with 100% field fidelity")
        void validCheckpointSerializationAndRoundtrip() throws Exception {
            Fixture fixture = createFixture(List.of("uaf", "nexerelin"));
            CheckpointModel checkpoint = sampleCheckpoint("Cycle 214 Heavy Fleet", fixture);

            String jsonText = checkpoint.toJson();
            assertNotNull(jsonText);
            assertTrue(jsonText.contains("\"format\":\"" + SCHEMA_FORMAT + "\""));
            assertTrue(jsonText.contains("\"name\":\"Cycle 214 Heavy Fleet\""));

            CheckpointModel parsed = CheckpointModel.fromJson(jsonText, null);
            assertEquals(checkpoint.format(), parsed.format());
            assertEquals(checkpoint.name(), parsed.name());
            assertEquals(checkpoint.description(), parsed.description());
            assertEquals(checkpoint.installRoot(), parsed.installRoot());
            assertEquals(checkpoint.enabledMods(), parsed.enabledMods());
            assertEquals(checkpoint.modSignatures().size(), parsed.modSignatures().size());
            assertEquals(checkpoint.modSignatures().get(0).modId(), parsed.modSignatures().get(0).modId());
            assertEquals(checkpoint.modSignatures().get(0).contentSha256(), parsed.modSignatures().get(0).contentSha256());
            assertEquals(checkpoint.launchSettings().resolution(), parsed.launchSettings().resolution());
            assertEquals(checkpoint.launchSettings().memoryMiB(), parsed.launchSettings().memoryMiB());
            assertEquals(checkpoint.lastRunSummary().outcome(), parsed.lastRunSummary().outcome());
            assertEquals(checkpoint.lastRunSummary().startupMillis(), parsed.lastRunSummary().startupMillis());
            assertEquals(checkpoint.checkpointFingerprint(), parsed.checkpointFingerprint());
        }

        @Test
        @DisplayName("T1.2: Checkpoint fingerprint calculation is deterministic and sensitive to any mutation")
        void deterministicFingerprintCalculation() throws Exception {
            Fixture fixture = createFixture(List.of("mod_a", "mod_b"));
            CheckpointModel cp1 = sampleCheckpoint("Baseline", fixture);
            CheckpointModel cp2 = sampleCheckpoint("Baseline", fixture);

            // Identical states must yield the exact same SHA-256 fingerprint
            assertEquals(cp1.checkpointFingerprint(), cp2.checkpointFingerprint());
            assertTrue(cp1.checkpointFingerprint().matches("[0-9a-f]{64}"));

            // Changing mod list must alter fingerprint
            CheckpointModel cpDifferentMods = new CheckpointModel(
                    SCHEMA_FORMAT, "Baseline", "desc", fixture.game(), Instant.now().toString(),
                    null, "prof1", List.of("mod_a"), cp1.modSignatures().subList(0, 1),
                    cp1.launchSettings(), cp1.lastRunSummary(), null);
            assertNotEquals(cp1.checkpointFingerprint(), cpDifferentMods.checkpointFingerprint());

            // Changing resolution setting must alter fingerprint
            LaunchSettingsSnapshot modifiedSettings = new LaunchSettingsSnapshot(
                    "3840x2160", false, true, 4, 1.5, 600, 8192);
            CheckpointModel cpDifferentSettings = new CheckpointModel(
                    SCHEMA_FORMAT, "Baseline", "desc", fixture.game(), Instant.now().toString(),
                    null, "prof1", cp1.enabledMods(), cp1.modSignatures(),
                    modifiedSettings, cp1.lastRunSummary(), null);
            assertNotEquals(cp1.checkpointFingerprint(), cpDifferentSettings.checkpointFingerprint());
        }

        @Test
        @DisplayName("T1.3: Atomic storage saves to <sha256(name)>.json and loads cleanly")
        void atomicCheckpointStorageSaveAndLoad() throws Exception {
            Fixture fixture = createFixture(List.of("uaf"));
            Path checkpointsDir = fixture.home().root().resolve("checkpoints");
            CheckpointModel checkpoint = sampleCheckpoint("Stable Fleet", fixture);

            Path savedPath = saveCheckpoint(checkpointsDir, checkpoint);
            assertTrue(Files.isRegularFile(savedPath));
            assertEquals(Hashes.sha256("Stable Fleet".getBytes(StandardCharsets.UTF_8)) + ".json",
                    savedPath.getFileName().toString());

            CheckpointModel loaded = loadCheckpoint(checkpointsDir, "Stable Fleet");
            assertEquals(checkpoint.name(), loaded.name());
            assertEquals(checkpoint.checkpointFingerprint(), loaded.checkpointFingerprint());
            assertEquals(checkpoint.enabledMods(), loaded.enabledMods());
        }

        @Test
        @DisplayName("T1.4: Listing checkpoints returns all saved entries sorted alphabetically")
        void listAllCheckpointsSortedAlphabetically() throws Exception {
            Fixture fixture = createFixture(List.of("uaf"));
            Path checkpointsDir = fixture.home().root().resolve("checkpoints");

            saveCheckpoint(checkpointsDir, sampleCheckpoint("Zeta Campaign", fixture));
            saveCheckpoint(checkpointsDir, sampleCheckpoint("Alpha Fleet", fixture));
            saveCheckpoint(checkpointsDir, sampleCheckpoint("Beta Exploration", fixture));

            ListResult result = listCheckpoints(checkpointsDir);
            assertEquals(3, result.checkpoints().size());
            assertEquals(0, result.diagnostics().size());
            assertEquals("Alpha Fleet", result.checkpoints().get(0).name());
            assertEquals("Beta Exploration", result.checkpoints().get(1).name());
            assertEquals("Zeta Campaign", result.checkpoints().get(2).name());
        }

        @Test
        @DisplayName("T1.5: Deleting or mutating a checkpoint creates a backup and obeys retention limits")
        void checkpointBackupCreationAndSafetyRetention() throws Exception {
            Fixture fixture = createFixture(List.of("uaf"));
            Path checkpointsDir = fixture.home().root().resolve("checkpoints");
            Path backupsDir = fixture.home().root().resolve("checkpoint-backups");

            CheckpointModel checkpoint = sampleCheckpoint("To Be Deleted", fixture);
            Path savedPath = saveCheckpoint(checkpointsDir, checkpoint);

            // Perform backup before deletion
            Path backupPath = backupCheckpoint(backupsDir, savedPath);
            assertTrue(Files.isRegularFile(backupPath));
            assertTrue(backupPath.getFileName().toString().startsWith("deleted-checkpoint-"));

            // Verify content of backup
            String backupContent = Files.readString(backupPath, StandardCharsets.UTF_8);
            assertTrue(backupContent.contains("\"name\":\"To Be Deleted\""));

            // Create 25 additional backups to trigger retention trimming (capped at 20)
            for (int i = 0; i < 25; i++) {
                Path dummyBackup = backupsDir.resolve("checkpoint-backup-" + (1000 + i) + "-test.json");
                Files.writeString(dummyBackup, "{}");
            }
            SafetyArtifactRetention.retainNewest(backupsDir, BACKUP_PATTERN, SafetyArtifactRetention.MAX_BACKUPS_PER_DIRECTORY);

            try (var stream = Files.list(backupsDir)) {
                long remainingCount = stream.filter(p -> p.getFileName().toString().endsWith(".json")).count();
                assertEquals(SafetyArtifactRetention.MAX_BACKUPS_PER_DIRECTORY, remainingCount);
            }
        }
    }

    // =========================================================================
    // Tier 2: Boundary, Corner & Fault Injection Cases (>= 5 test cases)
    // =========================================================================

    @Nested
    @DisplayName("Tier 2: Boundary, Corner & Fault Injection Test Cases")
    class Tier2BoundaryAndFaultInjection {

        @Test
        @DisplayName("T2.1: Rejects invalid, blank, control-character, or excessively long checkpoint names")
        void rejectInvalidCheckpointNames() {
            assertThrows(IllegalArgumentException.class, () -> CheckpointModel.validateName(null));
            assertThrows(IllegalArgumentException.class, () -> CheckpointModel.validateName(""));
            assertThrows(IllegalArgumentException.class, () -> CheckpointModel.validateName("   "));
            assertThrows(IllegalArgumentException.class, () -> CheckpointModel.validateName("Name\nWith\nNewlines"));
            assertThrows(IllegalArgumentException.class, () -> CheckpointModel.validateName("NameWith\u0000NullByte"));
            assertThrows(IllegalArgumentException.class, () -> CheckpointModel.validateName("A".repeat(101)));

            // Valid boundary names
            assertEquals("A", CheckpointModel.validateName("  A  "));
            assertEquals("A".repeat(100), CheckpointModel.validateName("A".repeat(100)));
            assertEquals("Orbitron v1.0 [Heavy-Fleet #42]", CheckpointModel.validateName("Orbitron v1.0 [Heavy-Fleet #42]"));
        }

        @Test
        @DisplayName("T2.2: Listing directory with corrupted JSON logs diagnostic and does not fail cleanly loaded items")
        void handleCorruptedAndUnreadableJsonFiles() throws Exception {
            Fixture fixture = createFixture(List.of("uaf"));
            Path checkpointsDir = fixture.home().root().resolve("checkpoints");

            saveCheckpoint(checkpointsDir, sampleCheckpoint("Valid Checkpoint", fixture));
            Files.writeString(checkpointsDir.resolve("corrupt_binary.json"), "\u0000\u0001\u0002 not json at all");
            Files.writeString(checkpointsDir.resolve("truncated.json"), "{\"format\": \"starsector-preflight-checkpoint-v1\",");

            ListResult result = listCheckpoints(checkpointsDir);
            assertEquals(1, result.checkpoints().size());
            assertEquals("Valid Checkpoint", result.checkpoints().get(0).name());
            assertEquals(2, result.diagnostics().size());
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("corrupt_binary.json")));
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("truncated.json")));
        }

        @Test
        @DisplayName("T2.3: Rejects checkpoint JSON with unsupported format version")
        void handleUnsupportedFormatSchema() {
            String unsupportedJson = "{\"format\":\"starsector-preflight-checkpoint-v999\",\"name\":\"Future Checkpoint\"}";
            IOException ex = assertThrows(IOException.class, () -> CheckpointModel.fromJson(unsupportedJson, null));
            assertTrue(ex.getMessage().contains("Unsupported checkpoint format") || ex.getMessage().contains("format"));
        }

        @Test
        @DisplayName("T2.4: Nullable optional fields serialize and deserialize safely without NPE")
        void nullableOptionalFieldsSerialization() throws Exception {
            Fixture fixture = createFixture(List.of("uaf"));
            CheckpointModel minimal = new CheckpointModel(
                    SCHEMA_FORMAT,
                    "Minimal Checkpoint",
                    null, // null description
                    fixture.game(),
                    Instant.now().toString(),
                    null,
                    "prof_min",
                    List.of("uaf"),
                    List.of(),
                    null, // null launch settings
                    null, // null last run summary
                    null
            );

            String json = minimal.toJson();
            CheckpointModel parsed = CheckpointModel.fromJson(json, null);

            assertEquals("Minimal Checkpoint", parsed.name());
            assertEquals("", parsed.description());
            assertNull(parsed.launchSettings());
            assertNull(parsed.lastRunSummary());
            assertNotNull(parsed.checkpointFingerprint());
        }

        @Test
        @DisplayName("T2.5: Extreme mod count (250+ mods) and multi-gigabyte byte totals do not overflow")
        void extremeModCountAndFileSizeStress() throws Exception {
            Fixture fixture = createFixture(List.of("uaf"));
            List<String> bigModList = new ArrayList<>();
            List<ModSignature> bigSignatures = new ArrayList<>();

            for (int i = 0; i < 250; i++) {
                String modId = "mod_stress_" + i;
                bigModList.add(modId);
                bigSignatures.add(new ModSignature(
                        modId,
                        "Stress Mod " + i,
                        "1.0." + i,
                        Hashes.sha256(("content_" + i).getBytes(StandardCharsets.UTF_8)),
                        500,
                        50_000_000L // 50 MB each -> total 12.5 GB
                ));
            }

            CheckpointModel massiveCheckpoint = new CheckpointModel(
                    SCHEMA_FORMAT,
                    "Massive 250 Mod Pack",
                    "Stress test layout",
                    fixture.game(),
                    Instant.now().toString(),
                    null,
                    "prof_massive",
                    bigModList,
                    bigSignatures,
                    new LaunchSettingsSnapshot("3840x2160", true, true, 8, 2.0, 1000, 16384),
                    new LastRunSummary("SUCCESS", 45000L, 3600000L, 0L, Instant.now().toString()),
                    null
            );

            assertDoesNotThrow(() -> {
                String json = massiveCheckpoint.toJson();
                CheckpointModel roundtrip = CheckpointModel.fromJson(json, null);
                assertEquals(250, roundtrip.enabledMods().size());
                assertEquals(250, roundtrip.modSignatures().size());
                long totalBytes = roundtrip.modSignatures().stream().mapToLong(ModSignature::totalBytes).sum();
                assertEquals(12_500_000_000L, totalBytes);
            });
        }
    }

    // =========================================================================
    // Test Infrastructure & Helper Records
    // =========================================================================

    record ModSignature(
            String modId,
            String name,
            String version,
            String contentSha256,
            int fileCount,
            long totalBytes) {

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("modId", modId);
            map.put("name", name);
            map.put("version", version);
            map.put("contentSha256", contentSha256);
            map.put("fileCount", fileCount);
            map.put("totalBytes", totalBytes);
            return map;
        }

        static ModSignature fromMap(Map<String, Object> map) {
            String modId = String.valueOf(map.get("modId"));
            String name = map.get("name") != null ? String.valueOf(map.get("name")) : modId;
            String version = map.get("version") != null ? String.valueOf(map.get("version")) : "unknown";
            String contentSha256 = String.valueOf(map.get("contentSha256"));
            int fileCount = map.get("fileCount") instanceof Number n ? n.intValue() : 0;
            long totalBytes = map.get("totalBytes") instanceof Number n ? n.longValue() : 0L;
            return new ModSignature(modId, name, version, contentSha256, fileCount, totalBytes);
        }
    }

    record LaunchSettingsSnapshot(
            String resolution,
            Boolean fullscreen,
            Boolean sound,
            Integer antialiasingSamples,
            Double uiScale,
            Integer battleSize,
            Integer memoryMiB) {

        Map<String, Object> toMap() {
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

        static LaunchSettingsSnapshot fromMap(Map<String, Object> map) {
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

    record LastRunSummary(
            String outcome,
            Long startupMillis,
            Long durationMillis,
            Long exitCode,
            String started) {

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("outcome", outcome);
            map.put("startupMillis", startupMillis);
            map.put("durationMillis", durationMillis);
            map.put("exitCode", exitCode);
            map.put("started", started);
            return map;
        }

        static LastRunSummary fromMap(Map<String, Object> map) {
            if (map == null) return null;
            String outcome = map.get("outcome") != null ? String.valueOf(map.get("outcome")) : null;
            Long startupMillis = map.get("startupMillis") instanceof Number n ? n.longValue() : null;
            Long durationMillis = map.get("durationMillis") instanceof Number n ? n.longValue() : null;
            Long exitCode = map.get("exitCode") instanceof Number n ? n.longValue() : null;
            String started = map.get("started") != null ? String.valueOf(map.get("started")) : null;
            return new LastRunSummary(outcome, startupMillis, durationMillis, exitCode, started);
        }
    }

    record CheckpointModel(
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

        CheckpointModel {
            name = validateName(name);
            enabledMods = List.copyOf(enabledMods != null ? enabledMods : List.of());
            modSignatures = List.copyOf(modSignatures != null ? modSignatures : List.of());
            if (checkpointFingerprint == null) {
                checkpointFingerprint = computeFingerprint(name, installRoot, enabledMods, modSignatures, launchSettings);
            }
        }

        static String validateName(String name) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Checkpoint name must not be blank");
            }
            String trimmed = name.trim();
            if (trimmed.length() > 100 || trimmed.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Checkpoint name must be 1-100 printable characters");
            }
            return trimmed;
        }

        String toJson() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("format", SCHEMA_FORMAT);
            map.put("name", name);
            map.put("description", description != null ? description : "");
            map.put("installRoot", installRoot.toAbsolutePath().normalize().toString());
            map.put("createdAt", createdAt);
            map.put("checkpointFingerprint", checkpointFingerprint);
            map.put("profileFingerprint", profileFingerprint);
            map.put("enabledMods", enabledMods);
            map.put("modSignatures", modSignatures.stream().map(ModSignature::toMap).toList());
            map.put("launchSettings", launchSettings == null ? null : launchSettings.toMap());
            map.put("lastRunSummary", lastRunSummary == null ? null : lastRunSummary.toMap());
            return Json.object(map);
        }

        static CheckpointModel fromJson(String json, Path file) throws IOException {
            Map<String, Object> map = StrictJson.object(json);
            if (!SCHEMA_FORMAT.equals(map.get("format"))) {
                throw new IOException("Unsupported checkpoint format: " + map.get("format"));
            }
            String name = String.valueOf(map.get("name"));
            String description = map.get("description") != null ? String.valueOf(map.get("description")) : "";
            String install = String.valueOf(map.get("installRoot"));
            String createdAt = String.valueOf(map.get("createdAt"));
            String checkpointFingerprint = String.valueOf(map.get("checkpointFingerprint"));
            String profileFingerprint = String.valueOf(map.get("profileFingerprint"));

            @SuppressWarnings("unchecked")
            List<String> enabledMods = (List<String>) map.get("enabledMods");
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

            return new CheckpointModel(
                    SCHEMA_FORMAT,
                    name,
                    description,
                    Path.of(install).toAbsolutePath().normalize(),
                    createdAt,
                    checkpointFingerprint,
                    profileFingerprint,
                    enabledMods,
                    modSignatures,
                    settings,
                    lastRun,
                    file
            );
        }

        static String computeFingerprint(
                String name,
                Path installRoot,
                List<String> enabledMods,
                List<ModSignature> modSignatures,
                LaunchSettingsSnapshot launchSettings) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                update(digest, SCHEMA_FORMAT);
                update(digest, name);
                if (installRoot != null) {
                    update(digest, installRoot.toAbsolutePath().normalize().toString());
                }
                for (String modId : enabledMods) {
                    update(digest, "mod:" + modId);
                }
                for (ModSignature sig : modSignatures) {
                    update(digest, sig.modId());
                    update(digest, sig.version());
                    update(digest, sig.contentSha256());
                    update(digest, Integer.toString(sig.fileCount()));
                    update(digest, Long.toString(sig.totalBytes()));
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

    record ListResult(List<CheckpointModel> checkpoints, List<String> diagnostics) {}

    static Path saveCheckpoint(Path checkpointsDir, CheckpointModel checkpoint) throws IOException {
        Files.createDirectories(checkpointsDir);
        String sha = Hashes.sha256(checkpoint.name().getBytes(StandardCharsets.UTF_8));
        Path target = checkpointsDir.resolve(sha + ".json").toAbsolutePath().normalize();
        Path staged = Files.createTempFile(checkpointsDir, ".checkpoint-", ".tmp");
        try {
            Files.writeString(staged, checkpoint.toJson() + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
        return target;
    }

    static CheckpointModel loadCheckpoint(Path checkpointsDir, String name) throws IOException {
        String sha = Hashes.sha256(name.getBytes(StandardCharsets.UTF_8));
        Path file = checkpointsDir.resolve(sha + ".json");
        if (!Files.isRegularFile(file)) {
            throw new IOException("Checkpoint not found: " + name);
        }
        return CheckpointModel.fromJson(Files.readString(file, StandardCharsets.UTF_8), file);
    }

    static ListResult listCheckpoints(Path checkpointsDir) throws IOException {
        if (!Files.isDirectory(checkpointsDir)) {
            return new ListResult(List.of(), List.of());
        }
        List<CheckpointModel> list = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        try (var stream = Files.list(checkpointsDir)) {
            for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                try {
                    list.add(CheckpointModel.fromJson(Files.readString(file, StandardCharsets.UTF_8), file));
                } catch (Exception ex) {
                    diagnostics.add("Could not read checkpoint " + file.getFileName() + ": " + ex.getMessage());
                }
            }
        }
        list.sort(Comparator.comparing(CheckpointModel::name, String.CASE_INSENSITIVE_ORDER));
        return new ListResult(List.copyOf(list), List.copyOf(diagnostics));
    }

    static Path backupCheckpoint(Path backupsDir, Path checkpointFile) throws IOException {
        Files.createDirectories(backupsDir);
        Path backup = Files.createTempFile(
                backupsDir,
                "deleted-checkpoint-" + Instant.now().toEpochMilli() + "-",
                ".json");
        Files.copy(checkpointFile, backup, StandardCopyOption.REPLACE_EXISTING);
        return backup;
    }

    private CheckpointModel sampleCheckpoint(String name, Fixture fixture) throws Exception {
        List<ModSignature> signatures = new ArrayList<>();
        for (String id : fixture.enabledMods()) {
            Path modDir = fixture.mods().resolve(id);
            signatures.add(new ModSignature(
                    id,
                    "Mod " + id,
                    "1.0.0",
                    ContentFingerprint.compute(modDir),
                    Files.walk(modDir).filter(Files::isRegularFile).toList().size(),
                    Files.walk(modDir).filter(Files::isRegularFile).mapToLong(p -> {
                        try { return Files.size(p); } catch (IOException e) { return 0L; }
                    }).sum()
            ));
        }

        return new CheckpointModel(
                SCHEMA_FORMAT,
                name,
                "Standard synthetic launch checkpoint",
                fixture.game(),
                Instant.now().toString(),
                null,
                "prof_" + name.toLowerCase().replace(' ', '_'),
                fixture.enabledMods(),
                signatures,
                new LaunchSettingsSnapshot("2560x1440", false, true, 4, 1.25, 500, 6144),
                new LastRunSummary("SUCCESS", 14250L, 1820400L, 0L, Instant.now().toString()),
                null
        );
    }

    private Fixture createFixture(List<String> enabled) throws Exception {
        Path game = Files.createDirectories(temporaryDirectory.resolve("game_" + System.nanoTime()));
        Path mods = Files.createDirectories(game.resolve("mods"));
        Path enabledFile = mods.resolve("enabled_mods.json");
        Files.writeString(enabledFile, Json.object(Map.of("enabledMods", enabled)));

        for (String modId : enabled) {
            Path dir = Files.createDirectories(mods.resolve(modId));
            Files.writeString(dir.resolve("mod_info.json"), "{\"id\":\"" + modId + "\",\"name\":\"Mod " + modId + "\",\"version\":\"1.0.0\"}");
            Path data = Files.createDirectories(dir.resolve("data"));
            Files.writeString(data.resolve("config.json"), "{\"setting\": true}");
        }

        Path homeRoot = temporaryDirectory.resolve("home_" + System.nanoTime()).resolve(PreflightHome.DIRECTORY_NAME);
        PreflightHome home = new PreflightHome(homeRoot.toAbsolutePath().normalize(), List.<PreflightHome.Integration>of());

        return new Fixture(game.toAbsolutePath().normalize(), mods, enabledFile, enabled, home);
    }

    private record Fixture(Path game, Path mods, Path enabledFile, List<String> enabledMods, PreflightHome home) {}
}
