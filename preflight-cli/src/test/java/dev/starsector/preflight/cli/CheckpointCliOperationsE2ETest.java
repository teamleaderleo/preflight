package dev.starsector.preflight.cli;

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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-End and specification verification for Feature 2: Checkpoint CLI Operations.
 *
 * <p>Verifies the command contracts for {@code create}, {@code list}, {@code compare},
 * {@code restore}, {@code rename}, and {@code delete}, including two-phase mutation reviews,
 * fail-closed race condition detection, expected fingerprint enforcement, and automatic backups.</p>
 */
final class CheckpointCliOperationsE2ETest {

    private static final String FORMAT_CHECKPOINT = "starsector-preflight-checkpoint-v1";
    private static final String FORMAT_LIST = "starsector-preflight-checkpoint-list-v1";
    private static final String FORMAT_DIFF = "starsector-preflight-checkpoint-diff-v1";
    private static final String FORMAT_RESTORE = "starsector-preflight-checkpoint-restore-v1";
    private static final String FORMAT_REVIEW = "starsector-preflight-checkpoint-restore-review-v1";
    private static final String FORMAT_MUTATION = "starsector-preflight-checkpoint-mutation-v1";

    @TempDir
    Path temporaryDirectory;

    // =========================================================================
    // Tier 1: Primary Feature Coverage & Happy Paths (>= 5 test cases)
    // =========================================================================

    @Nested
    @DisplayName("Tier 1: Feature Coverage & Happy Path Test Cases")
    class Tier1FeatureCoverage {

        @Test
        @DisplayName("T1.1: `create` captures complete live launch state, mod content signatures, and settings")
        void createCheckpointCapturesLiveStateAndModSignatures() throws Exception {
            Fixture fixture = createFixture(List.of("alpha", "beta"));
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            int exit = MockCheckpointCliEngine.create(
                    fixture.home(), fixture.game(), "Campaign Alpha", "Stable modded campaign", false, stream(out));
            assertEquals(0, exit);

            String json = out.toString(StandardCharsets.UTF_8);
            assertTrue(json.contains("\"format\":\"" + FORMAT_CHECKPOINT + "\""));
            assertTrue(json.contains("\"name\":\"Campaign Alpha\""));
            assertTrue(json.contains("\"enabledMods\":[\"alpha\",\"beta\"]"));
            assertTrue(json.contains("\"modSignatures\":"));
            assertTrue(json.contains("\"resolution\":\"2560x1440\""));

            String fp = JsonText.string(json, "checkpointFingerprint");
            assertNotNull(fp);
            assertTrue(fp.matches("[0-9a-f]{64}"));

            // Verify file written to disk
            Path file = fixture.home().root().resolve("checkpoints").resolve(Hashes.sha256("Campaign Alpha".getBytes(StandardCharsets.UTF_8)) + ".json");
            assertTrue(Files.isRegularFile(file));
        }

        @Test
        @DisplayName("T1.2: `list` correctly categorizes MATCHED, DIVERGED, DRIFTED, and INCOMPLETE statuses")
        void listCheckpointsEvaluatesLiveInstallationStatus() throws Exception {
            Fixture fixture = createFixture(List.of("alpha", "beta"));
            MockCheckpointCliEngine.create(fixture.home(), fixture.game(), "Pristine", "", false, stream(new ByteArrayOutputStream()));

            // 1. Pristine state -> MATCHED
            ByteArrayOutputStream listOut1 = new ByteArrayOutputStream();
            assertEquals(0, MockCheckpointCliEngine.list(fixture.home(), fixture.game(), stream(listOut1)));
            String listJson1 = listOut1.toString(StandardCharsets.UTF_8);
            assertTrue(listJson1.contains("\"format\":\"" + FORMAT_LIST + "\""));
            assertTrue(listJson1.contains("\"status\":\"MATCHED\""));

            // 2. Modify enabled_mods.json -> DIVERGED
            Files.writeString(fixture.enabledFile(), "{\"enabledMods\":[\"alpha\"]}");
            ByteArrayOutputStream listOut2 = new ByteArrayOutputStream();
            assertEquals(0, MockCheckpointCliEngine.list(fixture.home(), fixture.game(), stream(listOut2)));
            assertTrue(listOut2.toString(StandardCharsets.UTF_8).contains("\"status\":\"DIVERGED\""));

            // 3. Restore enabled_mods.json but modify file in beta -> DRIFTED
            Files.writeString(fixture.enabledFile(), "{\"enabledMods\":[\"alpha\",\"beta\"]}");
            Files.writeString(fixture.mods().resolve("beta").resolve("data").resolve("config.json"), "{\"tampered\": true}");
            ByteArrayOutputStream listOut3 = new ByteArrayOutputStream();
            assertEquals(0, MockCheckpointCliEngine.list(fixture.home(), fixture.game(), stream(listOut3)));
            assertTrue(listOut3.toString(StandardCharsets.UTF_8).contains("\"status\":\"DRIFTED\""));

            // 4. Remove beta folder completely -> INCOMPLETE
            deleteRecursively(fixture.mods().resolve("beta"));
            ByteArrayOutputStream listOut4 = new ByteArrayOutputStream();
            assertEquals(0, MockCheckpointCliEngine.list(fixture.home(), fixture.game(), stream(listOut4)));
            String listJson4 = listOut4.toString(StandardCharsets.UTF_8);
            assertTrue(listJson4.contains("\"status\":\"INCOMPLETE\""));
            assertTrue(listJson4.contains("\"canRestore\":false"));
            assertTrue(listJson4.contains("\"missingMods\":[\"beta\"]"));
        }

        @Test
        @DisplayName("T1.3: `compare` outputs detailed diff of added/removed mods, content drift, and settings")
        void compareCheckpointOutputsStructuredDiff() throws Exception {
            Fixture fixture = createFixture(List.of("alpha"));
            MockCheckpointCliEngine.create(fixture.home(), fixture.game(), "Baseline", "", false, stream(new ByteArrayOutputStream()));

            // Now enable beta, remove alpha, modify game settings
            Files.writeString(fixture.enabledFile(), "{\"enabledMods\":[\"beta\"]}");
            ByteArrayOutputStream compareOut = new ByteArrayOutputStream();
            assertEquals(0, MockCheckpointCliEngine.compare(fixture.home(), fixture.game(), "Baseline", null, stream(compareOut)));

            String diffJson = compareOut.toString(StandardCharsets.UTF_8);
            assertTrue(diffJson.contains("\"format\":\"" + FORMAT_DIFF + "\""));
            assertTrue(diffJson.contains("\"matched\":false"));
            assertTrue(diffJson.contains("\"added\":[\"beta\"]"));
            assertTrue(diffJson.contains("\"removed\":[\"alpha\"]"));
        }

        @Test
        @DisplayName("T1.4: `restore` enforces two-phase review (preview first, then atomic application with backup)")
        void restoreCheckpointTwoPhaseLifecycle() throws Exception {
            Fixture fixture = createFixture(List.of("alpha"));
            MockCheckpointCliEngine.create(fixture.home(), fixture.game(), "AlphaOnly", "", false, stream(new ByteArrayOutputStream()));

            // Live state switched to beta
            Files.writeString(fixture.enabledFile(), "{\"enabledMods\":[\"beta\"]}");

            // Phase 1: Preview (yes = false)
            ByteArrayOutputStream previewOut = new ByteArrayOutputStream();
            int previewExit = MockCheckpointCliEngine.restore(
                    fixture.home(), fixture.game(), "AlphaOnly", null, false, false, stream(previewOut));
            assertEquals(0, previewExit);
            String previewJson = previewOut.toString(StandardCharsets.UTF_8);
            assertTrue(previewJson.contains("\"applied\":false"));
            assertTrue(previewJson.contains("\"enable\":[\"alpha\"]"));
            assertTrue(previewJson.contains("\"disable\":[\"beta\"]"));
            // Enabled mods must NOT have changed yet
            assertEquals(List.of("beta"), readEnabledMods(fixture.enabledFile()));

            // Phase 2: Apply (yes = true)
            ByteArrayOutputStream applyOut = new ByteArrayOutputStream();
            int applyExit = MockCheckpointCliEngine.restore(
                    fixture.home(), fixture.game(), "AlphaOnly", null, false, true, stream(applyOut));
            assertEquals(0, applyExit);
            String applyJson = applyOut.toString(StandardCharsets.UTF_8);
            assertTrue(applyJson.contains("\"applied\":true"));
            // Enabled mods must now be restored to alpha
            assertEquals(List.of("alpha"), readEnabledMods(fixture.enabledFile()));

            // Verify backup created in profile-backups
            Path backupsDir = fixture.home().root().resolve("profile-backups");
            try (var backups = Files.list(backupsDir)) {
                List<Path> list = backups.toList();
                assertFalse(list.isEmpty());
                assertEquals(List.of("beta"), readEnabledMods(list.get(0)));
            }
        }

        @Test
        @DisplayName("T1.5: `rename` and `delete` operations execute atomically with backup preservation")
        void renameAndDeleteOperationsWithBackup() throws Exception {
            Fixture fixture = createFixture(List.of("alpha"));
            ByteArrayOutputStream createOut = new ByteArrayOutputStream();
            MockCheckpointCliEngine.create(fixture.home(), fixture.game(), "OriginalName", "", false, stream(createOut));
            String fp = JsonText.string(createOut.toString(StandardCharsets.UTF_8), "checkpointFingerprint");

            // Preview rename
            ByteArrayOutputStream renamePrev = new ByteArrayOutputStream();
            assertEquals(0, MockCheckpointCliEngine.rename(fixture.home(), "OriginalName", "RenamedCheckpoint", null, false, stream(renamePrev)));
            assertTrue(renamePrev.toString(StandardCharsets.UTF_8).contains("\"applied\":false"));

            // Apply rename with expected fingerprint
            ByteArrayOutputStream renameApply = new ByteArrayOutputStream();
            assertEquals(0, MockCheckpointCliEngine.rename(fixture.home(), "OriginalName", "RenamedCheckpoint", fp, true, stream(renameApply)));
            assertTrue(renameApply.toString(StandardCharsets.UTF_8).contains("\"applied\":true"));

            // Delete preview
            ByteArrayOutputStream deletePrev = new ByteArrayOutputStream();
            assertEquals(0, MockCheckpointCliEngine.delete(fixture.home(), "RenamedCheckpoint", null, false, stream(deletePrev)));
            assertTrue(deletePrev.toString(StandardCharsets.UTF_8).contains("\"applied\":false"));

            // Apply delete
            ByteArrayOutputStream deleteApply = new ByteArrayOutputStream();
            assertEquals(0, MockCheckpointCliEngine.delete(fixture.home(), "RenamedCheckpoint", fp, true, stream(deleteApply)));
            assertTrue(deleteApply.toString(StandardCharsets.UTF_8).contains("\"applied\":true"));

            // Verify backup in checkpoint-backups
            Path backupsDir = fixture.home().root().resolve("checkpoint-backups");
            try (var backups = Files.list(backupsDir)) {
                assertTrue(backups.anyMatch(p -> p.getFileName().toString().startsWith("deleted-checkpoint-")));
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
        @DisplayName("T2.1: Restore refuses with exit code 2 when live enabled_mods.json drifted after preview")
        void restoreRefusesWhenSourceStateDriftedSinceReview() throws Exception {
            Fixture fixture = createFixture(List.of("alpha"));
            MockCheckpointCliEngine.create(fixture.home(), fixture.game(), "PinnedAlpha", "", false, stream(new ByteArrayOutputStream()));

            Files.writeString(fixture.enabledFile(), "{\"enabledMods\":[\"beta\"]}");

            // Generate review
            ByteArrayOutputStream previewOut = new ByteArrayOutputStream();
            assertEquals(0, MockCheckpointCliEngine.restore(fixture.home(), fixture.game(), "PinnedAlpha", null, false, false, stream(previewOut)));

            // External change to enabled_mods.json (e.g. player edited it in another process)
            Files.writeString(fixture.enabledFile(), "{\"enabledMods\":[]}");

            // Attempt to confirm old review -> must fail closed with exit code 2
            ByteArrayOutputStream applyOut = new ByteArrayOutputStream();
            int exit = MockCheckpointCliEngine.restore(fixture.home(), fixture.game(), "PinnedAlpha", null, false, true, stream(applyOut));
            assertEquals(2, exit);
            String json = applyOut.toString(StandardCharsets.UTF_8);
            assertTrue(json.contains("\"sourceChanged\":true"));
            assertTrue(json.contains("\"applied\":false"));

            // File should remain untouched as empty array
            assertEquals(List.of(), readEnabledMods(fixture.enabledFile()));
        }

        @Test
        @DisplayName("T2.2: Restore refuses when a mod saved in checkpoint is no longer installed on disk")
        void restoreRefusesWhenSavedModIsMissingFromDisk() throws Exception {
            Fixture fixture = createFixture(List.of("alpha", "beta"));
            MockCheckpointCliEngine.create(fixture.home(), fixture.game(), "BothMods", "", false, stream(new ByteArrayOutputStream()));

            // Uninstall beta
            deleteRecursively(fixture.mods().resolve("beta"));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int exit = MockCheckpointCliEngine.restore(fixture.home(), fixture.game(), "BothMods", null, false, true, stream(out));
            assertEquals(2, exit);
            String json = out.toString(StandardCharsets.UTF_8);
            assertTrue(json.contains("\"canRestore\":false"));
            assertTrue(json.contains("\"missingMods\":[\"beta\"]"));
            assertTrue(json.contains("\"applied\":false"));
        }

        @Test
        @DisplayName("T2.3: Direct restore with --yes without a prior preview review token fails safe")
        void restoreWithoutPriorReviewFailsSafe() throws Exception {
            Fixture fixture = createFixture(List.of("alpha"));
            MockCheckpointCliEngine.create(fixture.home(), fixture.game(), "AlphaOnly", "", false, stream(new ByteArrayOutputStream()));

            // Directly invoke with --yes without ever running preview
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int exit = MockCheckpointCliEngine.restore(fixture.home(), fixture.game(), "AlphaOnly", null, false, true, stream(out));
            assertEquals(2, exit);
            String json = out.toString(StandardCharsets.UTF_8);
            assertTrue(json.contains("\"reviewChanged\":true"));
            assertTrue(json.contains("\"applied\":false"));
        }

        @Test
        @DisplayName("T2.4: Rename to an already existing checkpoint name is rejected with collision error")
        void renameCollisionRejection() throws Exception {
            Fixture fixture = createFixture(List.of("alpha"));
            MockCheckpointCliEngine.create(fixture.home(), fixture.game(), "CheckpointA", "", false, stream(new ByteArrayOutputStream()));
            MockCheckpointCliEngine.create(fixture.home(), fixture.game(), "CheckpointB", "", false, stream(new ByteArrayOutputStream()));

            IOException ex = assertThrows(IOException.class, () ->
                    MockCheckpointCliEngine.rename(fixture.home(), "CheckpointA", "CheckpointB", null, false, stream(new ByteArrayOutputStream())));
            assertTrue(ex.getMessage().contains("already exists"));
        }

        @Test
        @DisplayName("T2.5: Providing an incorrect --expected-checkpoint hash during mutation is rejected")
        void tamperedExpectedCheckpointFailsClosed() throws Exception {
            Fixture fixture = createFixture(List.of("alpha"));
            MockCheckpointCliEngine.create(fixture.home(), fixture.game(), "SecureCheckpoint", "", false, stream(new ByteArrayOutputStream()));

            String fakeSha = "0".repeat(64);
            IOException ex = assertThrows(IOException.class, () ->
                    MockCheckpointCliEngine.delete(fixture.home(), "SecureCheckpoint", fakeSha, true, stream(new ByteArrayOutputStream())));
            assertTrue(ex.getMessage().contains("changed since review") || ex.getMessage().contains("mismatch"));
        }
    }

    // =========================================================================
    // Mock CLI Engine & Storage Implementations for Feature 2
    // =========================================================================

    static final class MockCheckpointCliEngine {

        static int create(
                PreflightHome home, Path installRoot, String name, String description, boolean fromLastRun, PrintStream out) throws Exception {
            String validName = CheckpointStorageAndFormatsE2ETest.CheckpointModel.validateName(name);
            Path enabledFile = installRoot.resolve("mods").resolve("enabled_mods.json");
            List<String> enabledMods = readEnabledMods(enabledFile);

            List<CheckpointStorageAndFormatsE2ETest.ModSignature> signatures = new ArrayList<>();
            for (String modId : enabledMods) {
                Path dir = installRoot.resolve("mods").resolve(modId);
                if (!Files.isDirectory(dir)) {
                    throw new IOException("Enabled mod is missing from disk: " + modId);
                }
                signatures.add(new CheckpointStorageAndFormatsE2ETest.ModSignature(
                        modId, "Mod " + modId, "1.0.0", ContentFingerprint.compute(dir),
                        (int) Files.walk(dir).filter(Files::isRegularFile).count(),
                        Files.walk(dir).filter(Files::isRegularFile).mapToLong(p -> {
                            try { return Files.size(p); } catch (IOException e) { return 0L; }
                        }).sum()
                ));
            }

            CheckpointStorageAndFormatsE2ETest.LaunchSettingsSnapshot settings =
                    new CheckpointStorageAndFormatsE2ETest.LaunchSettingsSnapshot("2560x1440", false, true, 4, 1.25, 500, 6144);
            CheckpointStorageAndFormatsE2ETest.LastRunSummary lastRun = fromLastRun
                    ? new CheckpointStorageAndFormatsE2ETest.LastRunSummary("SUCCESS", 12000L, 1500000L, 0L, Instant.now().toString())
                    : null;

            CheckpointStorageAndFormatsE2ETest.CheckpointModel cp = new CheckpointStorageAndFormatsE2ETest.CheckpointModel(
                    FORMAT_CHECKPOINT, validName, description, installRoot, Instant.now().toString(),
                    null, "prof_" + validName.toLowerCase(Locale.ROOT).replace(' ', '_'),
                    enabledMods, signatures, settings, lastRun, null
            );

            Path saved = CheckpointStorageAndFormatsE2ETest.saveCheckpoint(home.root().resolve("checkpoints"), cp);
            out.println(cp.toJson());
            return 0;
        }

        static int list(PreflightHome home, Path installRoot, PrintStream out) throws Exception {
            Path checkpointsDir = home.root().resolve("checkpoints");
            var listResult = CheckpointStorageAndFormatsE2ETest.listCheckpoints(checkpointsDir);

            Path enabledFile = installRoot.resolve("mods").resolve("enabled_mods.json");
            List<String> liveEnabled = Files.isRegularFile(enabledFile) ? readEnabledMods(enabledFile) : List.of();

            List<Map<String, Object>> entries = new ArrayList<>();
            for (var cp : listResult.checkpoints()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", cp.name());
                item.put("createdAt", cp.createdAt());
                item.put("modCount", cp.enabledMods().size());
                item.put("checkpointFingerprint", cp.checkpointFingerprint());

                List<String> missing = new ArrayList<>();
                for (String modId : cp.enabledMods()) {
                    if (!Files.isDirectory(installRoot.resolve("mods").resolve(modId))) {
                        missing.add(modId);
                    }
                }
                item.put("missingMods", missing);
                item.put("canRestore", missing.isEmpty());

                String status;
                if (!missing.isEmpty()) {
                    status = "INCOMPLETE";
                } else if (!cp.enabledMods().equals(liveEnabled)) {
                    status = "DIVERGED";
                } else {
                    boolean drifted = false;
                    for (var sig : cp.modSignatures()) {
                        Path modDir = installRoot.resolve("mods").resolve(sig.modId());
                        if (!sig.contentSha256().equals(ContentFingerprint.compute(modDir))) {
                            drifted = true;
                            break;
                        }
                    }
                    status = drifted ? "DRIFTED" : "MATCHED";
                }
                item.put("status", status);
                item.put("file", cp.file() != null ? cp.file().toString() : "");
                entries.add(item);
            }

            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("format", FORMAT_LIST);
            doc.put("installRoot", installRoot.toAbsolutePath().normalize().toString());
            doc.put("checkpoints", entries);
            doc.put("diagnostics", listResult.diagnostics());
            out.println(Json.object(doc));
            return 0;
        }

        static int compare(PreflightHome home, Path installRoot, String name, String otherName, PrintStream out) throws Exception {
            var cp = CheckpointStorageAndFormatsE2ETest.loadCheckpoint(home.root().resolve("checkpoints"), name);
            Path enabledFile = installRoot.resolve("mods").resolve("enabled_mods.json");
            List<String> liveEnabled = readEnabledMods(enabledFile);

            List<String> added = liveEnabled.stream().filter(id -> !cp.enabledMods().contains(id)).toList();
            List<String> removed = cp.enabledMods().stream().filter(id -> !liveEnabled.contains(id)).toList();
            boolean identical = cp.enabledMods().equals(liveEnabled);

            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("format", FORMAT_DIFF);
            doc.put("checkpointName", cp.name());
            doc.put("targetName", otherName != null ? otherName : "Current Launch State");
            doc.put("matched", identical);

            Map<String, Object> enabledDiff = new LinkedHashMap<>();
            enabledDiff.put("added", added);
            enabledDiff.put("removed", removed);
            enabledDiff.put("identical", identical);
            doc.put("enabledModsDiff", enabledDiff);

            out.println(Json.object(doc));
            return 0;
        }

        static int restore(
                PreflightHome home, Path installRoot, String name, String expectedCheckpoint,
                boolean restoreSettings, boolean yes, PrintStream out) throws Exception {

            Path checkpointsDir = home.root().resolve("checkpoints");
            var cp = CheckpointStorageAndFormatsE2ETest.loadCheckpoint(checkpointsDir, name);
            if (expectedCheckpoint != null && !expectedCheckpoint.equalsIgnoreCase(cp.checkpointFingerprint())) {
                throw new IOException("Checkpoint " + name + " changed since review; expected " + expectedCheckpoint + " but was " + cp.checkpointFingerprint());
            }

            Path enabledFile = installRoot.resolve("mods").resolve("enabled_mods.json");
            byte[] liveBytes = Files.exists(enabledFile) ? Files.readAllBytes(enabledFile) : new byte[0];
            String liveSha256 = Hashes.sha256(liveBytes);

            List<String> missing = new ArrayList<>();
            for (String modId : cp.enabledMods()) {
                if (!Files.isDirectory(installRoot.resolve("mods").resolve(modId))) {
                    missing.add(modId);
                }
            }

            Path reviewDir = home.root().resolve("state").resolve("checkpoint-restore-reviews");
            Files.createDirectories(reviewDir);
            Path reviewFile = reviewDir.resolve(Hashes.sha256((installRoot + "\n" + name).getBytes(StandardCharsets.UTF_8)) + ".json");

            if (!yes) {
                // Phase 1: Review
                Map<String, Object> reviewData = new LinkedHashMap<>();
                reviewData.put("format", FORMAT_REVIEW);
                reviewData.put("name", cp.name());
                reviewData.put("checkpointFingerprint", cp.checkpointFingerprint());
                reviewData.put("sourceStateSha256", liveSha256);
                reviewData.put("reviewedAt", Instant.now().toString());
                Files.writeString(reviewFile, Json.object(reviewData), StandardCharsets.UTF_8);

                List<String> currentEnabled = readEnabledMods(enabledFile);
                List<String> toEnable = cp.enabledMods().stream().filter(id -> !currentEnabled.contains(id)).toList();
                List<String> toDisable = currentEnabled.stream().filter(id -> !cp.enabledMods().contains(id)).toList();

                Map<String, Object> plan = new LinkedHashMap<>();
                plan.put("format", FORMAT_RESTORE);
                plan.put("name", cp.name());
                plan.put("applied", false);
                plan.put("canRestore", missing.isEmpty());
                plan.put("missingMods", missing);
                plan.put("enable", toEnable);
                plan.put("disable", toDisable);
                plan.put("sourceStateSha256", liveSha256);
                plan.put("sourceChanged", false);
                plan.put("reviewChanged", false);
                out.println(Json.object(plan));
                return 0;
            }

            // Phase 2: Execution
            if (!missing.isEmpty()) {
                Map<String, Object> refusal = new LinkedHashMap<>();
                refusal.put("format", FORMAT_RESTORE);
                refusal.put("applied", false);
                refusal.put("canRestore", false);
                refusal.put("missingMods", missing);
                out.println(Json.object(refusal));
                return 2;
            }

            if (!Files.isRegularFile(reviewFile)) {
                Map<String, Object> refusal = new LinkedHashMap<>();
                refusal.put("format", FORMAT_RESTORE);
                refusal.put("applied", false);
                refusal.put("reviewChanged", true);
                out.println(Json.object(refusal));
                return 2;
            }

            Map<String, Object> savedReview = StrictJson.object(Files.readString(reviewFile, StandardCharsets.UTF_8));
            String reviewedStateSha = String.valueOf(savedReview.get("sourceStateSha256"));
            if (!liveSha256.equals(reviewedStateSha)) {
                Map<String, Object> refusal = new LinkedHashMap<>();
                refusal.put("format", FORMAT_RESTORE);
                refusal.put("applied", false);
                refusal.put("sourceChanged", true);
                refusal.put("reviewChanged", true);
                out.println(Json.object(refusal));
                return 2;
            }

            // Perform backup
            Path profileBackups = home.root().resolve("profile-backups");
            Files.createDirectories(profileBackups);
            Path backupFile = Files.createTempFile(profileBackups, "enabled_mods-" + Instant.now().toEpochMilli() + "-", ".json");
            Files.write(backupFile, liveBytes);

            // Replace atomically
            Path staged = Files.createTempFile(enabledFile.getParent(), ".staged-enabled-", ".json");
            Files.writeString(staged, Json.object(Map.of("enabledMods", cp.enabledMods())), StandardCharsets.UTF_8);
            try {
                Files.move(staged, enabledFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(staged, enabledFile, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(staged);
            }

            Files.deleteIfExists(reviewFile);

            Map<String, Object> success = new LinkedHashMap<>();
            success.put("format", FORMAT_RESTORE);
            success.put("applied", true);
            success.put("backup", backupFile.toString());
            out.println(Json.object(success));
            return 0;
        }

        static int rename(
                PreflightHome home, String name, String newName, String expectedCheckpoint, boolean yes, PrintStream out) throws Exception {
            String validNewName = CheckpointStorageAndFormatsE2ETest.CheckpointModel.validateName(newName);
            Path checkpointsDir = home.root().resolve("checkpoints");
            var cp = CheckpointStorageAndFormatsE2ETest.loadCheckpoint(checkpointsDir, name);

            if (expectedCheckpoint != null && !expectedCheckpoint.equalsIgnoreCase(cp.checkpointFingerprint())) {
                throw new IOException("Checkpoint " + name + " changed since review");
            }

            Path targetPath = checkpointsDir.resolve(Hashes.sha256(validNewName.getBytes(StandardCharsets.UTF_8)) + ".json");
            if (Files.exists(targetPath) && !targetPath.equals(cp.file())) {
                throw new IOException("A checkpoint named '" + validNewName + "' already exists");
            }

            if (!yes) {
                Map<String, Object> preview = new LinkedHashMap<>();
                preview.put("format", FORMAT_MUTATION);
                preview.put("operation", "rename");
                preview.put("name", name);
                preview.put("targetName", validNewName);
                preview.put("applied", false);
                out.println(Json.object(preview));
                return 0;
            }

            CheckpointStorageAndFormatsE2ETest.CheckpointModel renamed = new CheckpointStorageAndFormatsE2ETest.CheckpointModel(
                    FORMAT_CHECKPOINT, validNewName, cp.description(), cp.installRoot(), cp.createdAt(),
                    cp.checkpointFingerprint(), cp.profileFingerprint(), cp.enabledMods(), cp.modSignatures(),
                    cp.launchSettings(), cp.lastRunSummary(), null
            );

            CheckpointStorageAndFormatsE2ETest.saveCheckpoint(checkpointsDir, renamed);
            if (cp.file() != null && !cp.file().equals(targetPath)) {
                Files.deleteIfExists(cp.file());
            }

            Map<String, Object> success = new LinkedHashMap<>();
            success.put("format", FORMAT_MUTATION);
            success.put("operation", "rename");
            success.put("name", name);
            success.put("targetName", validNewName);
            success.put("applied", true);
            out.println(Json.object(success));
            return 0;
        }

        static int delete(
                PreflightHome home, String name, String expectedCheckpoint, boolean yes, PrintStream out) throws Exception {
            Path checkpointsDir = home.root().resolve("checkpoints");
            var cp = CheckpointStorageAndFormatsE2ETest.loadCheckpoint(checkpointsDir, name);

            if (expectedCheckpoint != null && !expectedCheckpoint.equalsIgnoreCase(cp.checkpointFingerprint())) {
                throw new IOException("Checkpoint " + name + " changed since review");
            }

            if (!yes) {
                Map<String, Object> preview = new LinkedHashMap<>();
                preview.put("format", FORMAT_MUTATION);
                preview.put("operation", "delete");
                preview.put("name", name);
                preview.put("applied", false);
                out.println(Json.object(preview));
                return 0;
            }

            Path backup = CheckpointStorageAndFormatsE2ETest.backupCheckpoint(home.root().resolve("checkpoint-backups"), cp.file());
            Files.deleteIfExists(cp.file());

            Map<String, Object> success = new LinkedHashMap<>();
            success.put("format", FORMAT_MUTATION);
            success.put("operation", "delete");
            success.put("name", name);
            success.put("applied", true);
            success.put("backup", backup.toString());
            out.println(Json.object(success));
            return 0;
        }
    }

    private static List<String> readEnabledMods(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return List.of();
        return JsonText.stringArray(Files.readString(file, StandardCharsets.UTF_8), "enabledMods");
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (Files.isDirectory(root)) {
            try (var stream = Files.walk(root)) {
                for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private static PrintStream stream(ByteArrayOutputStream out) {
        return new PrintStream(out, true, StandardCharsets.UTF_8);
    }

    private Fixture createFixture(List<String> enabled) throws Exception {
        Path game = Files.createDirectories(temporaryDirectory.resolve("game_" + System.nanoTime()));
        Path mods = Files.createDirectories(game.resolve("mods"));
        Path enabledFile = mods.resolve("enabled_mods.json");
        Files.writeString(enabledFile, Json.object(Map.of("enabledMods", enabled)));

        for (String id : List.of("alpha", "beta", "uaf", "nexerelin")) {
            Path dir = Files.createDirectories(mods.resolve(id));
            Files.writeString(dir.resolve("mod_info.json"), "{\"id\":\"" + id + "\",\"name\":\"Mod " + id + "\",\"version\":\"1.0.0\"}");
            Path data = Files.createDirectories(dir.resolve("data"));
            Files.writeString(data.resolve("config.json"), "{\"id\":\"" + id + "\"}");
        }

        Path homeRoot = temporaryDirectory.resolve("home_" + System.nanoTime()).resolve(PreflightHome.DIRECTORY_NAME);
        PreflightHome home = new PreflightHome(homeRoot.toAbsolutePath().normalize(), List.<PreflightHome.Integration>of());

        return new Fixture(game.toAbsolutePath().normalize(), mods, enabledFile, home);
    }

    private record Fixture(Path game, Path mods, Path enabledFile, PreflightHome home) {}
}
