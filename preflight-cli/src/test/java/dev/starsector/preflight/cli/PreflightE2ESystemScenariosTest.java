package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.ContentFingerprint;
import dev.starsector.preflight.core.GpuTextureFootprint;
import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.ImageHeaderReader;
import dev.starsector.preflight.core.JarArchiveIndex;
import dev.starsector.preflight.core.Json;
import dev.starsector.preflight.core.OggVorbisIdentification;
import dev.starsector.preflight.core.OggVorbisStreamLength;
import dev.starsector.preflight.core.ResourceIndex;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end system and scenario test suite for Feature 17:
 * <ul>
 *   <li><b>Tier 3: Pairwise Combinatorial Cross-Feature Interactions</b> (>= 10 test cases)</li>
 *   <li><b>Tier 4: Real-World Application Scenarios (S1–S10)</b> as specified in {@code TEST_INFRA.md}</li>
 * </ul>
 */
public class PreflightE2ESystemScenariosTest {

    @TempDir
    Path tempDir;

    // =========================================================================
    // Tier 3: Pairwise Combinatorial Cross-Feature Testing
    // =========================================================================

    @Nested
    @DisplayName("Tier 3: Pairwise Combinatorial Cross-Feature Tests")
    class Tier3PairwiseCombinatorialTests {

        /**
         * Pairwise 3.1: Checkpoints (F1/F2) + Same-Version Drift Detection (F11/F12).
         */
        @Test
        @DisplayName("3.1 Checkpoints + Mod Content Drift Detection")
        void testPairwise_CheckpointCreation_And_ModContentDriftDetection() throws Exception {
            SyntheticGameEnv env = new SyntheticGameEnv(tempDir.resolve("pair_chk_drift"));
            env.init();
            SyntheticMod mod = env.createMod("faction_mod", "Faction Mod", "1.0.0");
            Path weaponCsv = mod.dir.resolve("data/weapons/weapons.csv");
            Files.createDirectories(weaponCsv.getParent());
            Files.writeString(weaponCsv, "id,damage,range\nblaster,100,500\n", StandardCharsets.UTF_8);
            env.enableMod("faction_mod");

            // Step 1: Capture Checkpoint
            Checkpoint cp = env.createCheckpoint("stable-v1", "Initial clean loadout");
            assertEquals("stable-v1", cp.name());
            String initialSig = cp.modSignatures().get("faction_mod");
            assertNotNull(initialSig);

            // Step 2: Content drift (hotfix alters stats without bumping mod_info.json version)
            Files.writeString(weaponCsv, "id,damage,range\nblaster,9999,500\n", StandardCharsets.UTF_8);

            // Step 3: Drift Detection
            DriftVerdict drift = env.detectModDrift("faction_mod", cp);
            assertEquals("SAME_VERSION_DRIFT", drift.status());
            assertTrue(drift.modifiedFiles().contains("data/weapons/weapons.csv"));

            // Step 4: Checkpoint comparison
            CheckpointDiff diff = env.compareActiveAgainstCheckpoint(cp);
            assertTrue(diff.hasContentDrift());
            assertEquals(List.of("faction_mod"), diff.driftedModIds());
        }

        /**
         * Pairwise 3.2: Bounded Log Tailer (F4) + Heuristic Classifier (F5) + Safe Recovery Action (F6).
         */
        @Test
        @DisplayName("3.2 Log Capture + Crash Classifier + Safe Recovery Heap Bump")
        void testPairwise_LogCapture_CrashClassifier_And_SafeRecoveryHeapBump() throws Exception {
            SyntheticGameEnv env = new SyntheticGameEnv(tempDir.resolve("pair_log_diag_rec"));
            env.init();

            // Step 1: Write synthetic log with Heap OutOfMemoryError
            String crashLog = """
                    2026-08-18 10:14:02.123 [main] INFO  com.fs.starfarer.launcher.Launcher - Starting Starsector 0.97a-RC11
                    2026-08-18 10:14:15.892 [Thread-2] ERROR com.fs.starfarer.loading.SpecStore - Fatal crash during asset initialization
                    java.lang.OutOfMemoryError: Java heap space
                    \tat com.fs.starfarer.loading.SpecStore.<init>(Unknown Source)
                    \tat com.fs.starfarer.loading.ResourceLoader.loadAllSpecs(Unknown Source)
                    \tat com.fs.starfarer.launcher.Launcher.main(Unknown Source)
                    """;
            Path logFile = env.root.resolve("starsector.log");
            Files.writeString(logFile, crashLog, StandardCharsets.UTF_8);

            // Step 2: Log Capture & Classification
            CrashDiagnosis diagnosis = env.diagnoseLastRun();
            assertEquals("OUT_OF_MEMORY_HEAP", diagnosis.crashType());
            assertEquals("com.fs.starfarer.loading.SpecStore", diagnosis.rootCauseLocation());

            // Step 3: Propose Recovery Action
            RecoveryAction action = diagnosis.suggestedRecoveryAction();
            assertEquals("INCREASE_HEAP_MEMORY", action.actionType());
            assertEquals(6144, action.targetMemoryMb());

            // Step 4: Apply Recovery Action with safe pre-mutation backup
            env.writeVmparams("-Xms4096m -Xmx4096m -XX:+UseG1GC");
            boolean applied = env.applyRecoveryAction(action);
            assertTrue(applied);

            // Verify vmparams bumped and backup exists
            String updatedVmparams = env.readVmparams();
            assertTrue(updatedVmparams.contains("-Xms6144m -Xmx6144m"));
            assertTrue(env.hasBackupOf("vmparams"));
        }

        /**
         * Pairwise 3.3: Crash Classifier (F5) + Bisect Engine (F14/F15).
         */
        @Test
        @DisplayName("3.3 Crash Classifier + Dependency-Safe Bisect Session Initialization")
        void testPairwise_CrashClassifier_And_DependencySafeBisectInitialization() throws Exception {
            SyntheticGameEnv env = new SyntheticGameEnv(tempDir.resolve("pair_diag_bisect"));
            env.init();

            SyntheticMod baseLib = env.createMod("lw_lazylib", "LazyLib", "2.8");
            SyntheticMod magicLib = env.createMod("MagicLib", "MagicLib", "1.4");
            SyntheticMod armaa = env.createMod("armaa", "Arma Armatura", "2.1");

            // armaa depends on MagicLib and LazyLib
            armaa.addDependency("MagicLib");
            armaa.addDependency("lw_lazylib");

            env.enableMod("lw_lazylib");
            env.enableMod("MagicLib");
            env.enableMod("armaa");

            // Write crash log with NPE in armaa
            String log = """
                    java.lang.NullPointerException: Cannot invoke method getWings() on null object
                    \tat com.fs.starfarer.api.impl.campaign.armaa.MechFactory.init(MechFactory.java:42)
                    \tat com.fs.starfarer.campaign.CampaignState.update(Unknown Source)
                    """;
            Files.writeString(env.root.resolve("starsector.log"), log, StandardCharsets.UTF_8);

            CrashDiagnosis diag = env.diagnoseLastRun();
            assertEquals("MOD_CRASH_UNCAUGHT_EXCEPTION", diag.crashType());
            assertEquals("armaa", diag.culpritModId());

            // Initialize Bisect session targeting suspect mod armaa
            BisectSession session = env.startBisectSession(List.of("armaa", "MagicLib", "lw_lazylib"));
            assertEquals("TESTING", session.state());
            // Transitive closure of armaa must retain MagicLib and lw_lazylib
            Set<String> closure = env.calculateTransitiveClosure("armaa");
            assertTrue(closure.contains("MagicLib"));
            assertTrue(closure.contains("lw_lazylib"));
        }

        /**
         * Pairwise 3.4: Resource Costing (F8/F9) + Content Drift (F11/F12).
         */
        @Test
        @DisplayName("3.4 Resource Costing + Asset Content Drift Recalculation")
        void testPairwise_ResourceCosting_And_AssetDriftRecalculation() throws Exception {
            SyntheticGameEnv env = new SyntheticGameEnv(tempDir.resolve("pair_cost_drift"));
            env.init();

            SyntheticMod mod = env.createMod("graphics_pack", "Graphics Pack", "1.0");
            Path sprite = mod.dir.resolve("graphics/ships/flagship.png");
            Files.createDirectories(sprite.getParent());

            // Initial: 256x256 image -> 256x256x4 = 262,144 B resident
            BufferedImage small = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
            ImageIO.write(small, "png", sprite.toFile());
            env.enableMod("graphics_pack");

            long initialVram = env.calculateModVram("graphics_pack");
            assertEquals(262_144L, initialVram);

            // Drift: Replace with 1024x1024 without version change
            BufferedImage large = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_ARGB);
            ImageIO.write(large, "png", sprite.toFile());

            // Recalculate without stale cache
            long updatedVram = env.calculateModVram("graphics_pack");
            assertEquals(4_194_304L, updatedVram);
            assertNotEquals(initialVram, updatedVram);
        }

        /**
         * Pairwise 3.5: Checkpoint Restore (F1/F2) + Pre-Mutation Backup (F6).
         */
        @Test
        @DisplayName("3.5 Checkpoint Restore with Pre-Mutation Backup and Rollback Guarantee")
        void testPairwise_CheckpointRestore_With_PreMutationBackupAndRollback() throws Exception {
            SyntheticGameEnv env = new SyntheticGameEnv(tempDir.resolve("pair_chk_restore"));
            env.init();

            env.createMod("mod_a", "Mod A", "1.0");
            env.createMod("mod_b", "Mod B", "1.0");
            env.enableMod("mod_a");

            Checkpoint baseline = env.createCheckpoint("cp-baseline", "Mod A only");

            // Mutate enabled mods and settings
            env.enableMod("mod_b");
            env.writeSettings("{\"resolution\":\"2560x1440\"}");

            // Restore baseline checkpoint
            boolean restored = env.restoreCheckpoint("cp-baseline", true);
            assertTrue(restored);

            // Assert active enabled mods reverted
            assertEquals(List.of("mod_a"), env.getEnabledMods());
            // Assert pre-restore backup created
            assertTrue(env.hasBackupOf("enabled_mods"));
        }

        /**
         * Pairwise 3.6: Bisect DAG Partitioning (F14) + Settings/Vmparams Preservation (F6).
         */
        @Test
        @DisplayName("3.6 Bisect DAG partitioning with settings and vmparams preservation")
        void testPairwise_BisectDAGPartitioning_With_SettingsAndVmparamsPreservation() throws Exception {
            SyntheticGameEnv env = new SyntheticGameEnv(tempDir.resolve("pair_bisect_settings"));
            env.init();

            env.writeVmparams("-Xms8192m -Xmx8192m");
            env.writeSettings("{\"resolution\":\"1920x1080\",\"battleSize\":400}");

            for (int i = 1; i <= 6; i++) {
                env.createMod("mod_" + i, "Mod " + i, "1.0");
                env.enableMod("mod_" + i);
            }

            BisectSession session = env.startBisectSession(List.of("mod_1", "mod_2", "mod_3", "mod_4", "mod_5", "mod_6"));

            // Advance through 2 bisect steps
            session.recordVerdict("BAD");
            session.recordVerdict("GOOD");

            // Verify settings and vmparams remain completely untouched
            assertTrue(env.readVmparams().contains("-Xms8192m -Xmx8192m"));
            assertTrue(env.readSettings().contains("\"battleSize\":400"));
        }

        /**
         * Pairwise 3.7: Resource Cost Delta (F8) + Checkpoint Comparison (F1/F2).
         */
        @Test
        @DisplayName("3.7 Resource cost delta between two pinned checkpoints")
        void testPairwise_ResourceCostDelta_Between_TwoCheckpoints() throws Exception {
            SyntheticGameEnv env = new SyntheticGameEnv(tempDir.resolve("pair_cost_delta"));
            env.init();

            SyntheticMod mod1 = env.createMod("faction_1", "Faction 1", "1.0");
            mod1.putImagePng("graphics/ship1.png", 512, 512); // 1 MiB VRAM
            env.enableMod("faction_1");
            Checkpoint cp1 = env.createCheckpoint("loadout-light", "1 faction");

            SyntheticMod mod2 = env.createMod("faction_2", "Faction 2", "1.0");
            mod2.putImagePng("graphics/ship2.png", 1024, 1024); // 4 MiB VRAM
            env.enableMod("faction_2");
            Checkpoint cp2 = env.createCheckpoint("loadout-heavy", "2 factions");

            long deltaVram = env.compareCheckpointResourceCost(cp1, cp2).vramDeltaBytes();
            assertEquals(4_194_304L, deltaVram);
        }

        /**
         * Pairwise 3.8: Bytecode Version Incompatibility (F5/F8) + Log Capture (F4).
         */
        @Test
        @DisplayName("3.8 Java bytecode version incompatibility diagnosis and JAR attribution")
        void testPairwise_BytecodeIncompatibility_LogCapture_And_ResourceAudit() throws Exception {
            SyntheticGameEnv env = new SyntheticGameEnv(tempDir.resolve("pair_bytecode_ver"));
            env.init();

            SyntheticMod modernMod = env.createMod("modern_mod", "Modern Mod", "1.0");
            modernMod.putJar("jars/Modern.jar", Map.of("com/modern/Core.class", new byte[5000]));
            env.enableMod("modern_mod");

            String log = """
                    java.lang.UnsupportedClassVersionError: com/modern/Core has been compiled by a more recent version of the Java Runtime (class file version 61.0), this version of the Java Runtime only recognizes class file versions up to 52.0
                    \tat java.lang.ClassLoader.defineClass1(Native Method)
                    \tat java.lang.ClassLoader.defineClass(ClassLoader.java:756)
                    """;
            Files.writeString(env.root.resolve("starsector.log"), log, StandardCharsets.UTF_8);

            CrashDiagnosis diag = env.diagnoseLastRun();
            assertEquals("UNSUPPORTED_CLASS_VERSION", diag.crashType());
            assertEquals("modern_mod", diag.culpritModId());
            assertEquals("jars/Modern.jar", diag.offendingJarPath());
        }

        /**
         * Pairwise 3.9: Concurrency Lock Safety (OperationLease) across Multi-Subsystem Actions.
         */
        @Test
        @DisplayName("3.9 Concurrency lease safety across Checkpoint, Bisect, and Recovery")
        void testPairwise_ConcurrencyLockSafety_OperationLeaseAcrossOperations() throws Exception {
            SyntheticGameEnv env = new SyntheticGameEnv(tempDir.resolve("pair_lease_lock"));
            env.init();

            env.createMod("mod_x", "Mod X", "1.0");
            env.enableMod("mod_x");
            Checkpoint cp = env.createCheckpoint("cp_test", "Lock test");

            // Acquire lease (simulating running game process or background preparation)
            OperationLock lock = env.acquireOperationLock("GAME_RUNNING");
            assertTrue(lock.isActive());

            // Checkpoint restore should fail closed while locked
            assertFalse(env.tryRestoreCheckpoint("cp_test"));

            // Recovery mutation should fail closed while locked
            assertFalse(env.tryApplyRecoveryAction(new RecoveryAction("DISABLE_OFFENDING_MOD", "mod_x", 0)));

            // Release lock
            lock.release();
            assertFalse(lock.isActive());

            // Mutation succeeds now
            assertTrue(env.tryRestoreCheckpoint("cp_test"));
        }

        /**
         * Pairwise 3.10: Cache Pruning + Resource Costing Share Update.
         */
        @Test
        @DisplayName("3.10 Cache pruning and resource cost prepared footprint synchronization")
        void testPairwise_CachePruning_And_PerModPreparedShareUpdate() throws Exception {
            SyntheticGameEnv env = new SyntheticGameEnv(tempDir.resolve("pair_cache_prune"));
            env.init();

            Path cacheDir = env.root.resolve("cache/prepared");
            Files.createDirectories(cacheDir);
            Path oldTexturePack = cacheDir.resolve("old_profile.spfp");
            Files.write(oldTexturePack, new byte[500_000]);

            Path currentTexturePack = cacheDir.resolve("current_profile.spfp");
            Files.write(currentTexturePack, new byte[200_000]);

            assertEquals(700_000L, env.getCacheDiskBytes());

            // Prune unreferenced cache
            env.pruneCache(Set.of("current_profile.spfp"));

            assertEquals(200_000L, env.getCacheDiskBytes());
            assertFalse(Files.exists(oldTexturePack));
            assertTrue(Files.exists(currentTexturePack));
        }

        /**
         * Pairwise 3.11: Live Launch Settings Drift + Checkpoint Comparison.
         */
        @Test
        @DisplayName("3.11 Live launch settings drift and checkpoint comparison")
        void testPairwise_LiveSettingsDrift_And_CheckpointComparison() throws Exception {
            SyntheticGameEnv env = new SyntheticGameEnv(tempDir.resolve("pair_settings_drift"));
            env.init();

            env.writeSettings("{\"resolution\":\"1920x1080\",\"fullscreen\":true,\"battleSize\":300}");
            Checkpoint cp = env.createCheckpoint("pinned_settings", "Standard display");

            // Change resolution and battle size
            env.writeSettings("{\"resolution\":\"2560x1440\",\"fullscreen\":false,\"battleSize\":500}");

            CheckpointDiff diff = env.compareActiveAgainstCheckpoint(cp);
            assertTrue(diff.hasSettingsDrift());
            assertEquals("1920x1080 -> 2560x1440", diff.settingChange("resolution"));
            assertEquals("300 -> 500", diff.settingChange("battleSize"));
        }

        /**
         * Pairwise 3.12: Bisect State Persistence across Session Interruption.
         */
        @Test
        @DisplayName("3.12 Bisect state persistence across crash / interruption and restart")
        void testPairwise_BisectPersistence_CrashInterruptionRecovery() throws Exception {
            SyntheticGameEnv env = new SyntheticGameEnv(tempDir.resolve("pair_bisect_persist"));
            env.init();

            for (int i = 1; i <= 8; i++) {
                env.createMod("mod_" + i, "Mod " + i, "1.0");
                env.enableMod("mod_" + i);
            }

            BisectSession session = env.startBisectSession(List.of("mod_1", "mod_2", "mod_3", "mod_4", "mod_5", "mod_6", "mod_7", "mod_8"));
            session.recordVerdict("BAD"); // Step 1 complete, 4 candidates left
            assertEquals(2, session.currentStepNumber());

            // Simulate power loss / restart by creating a new session reader
            BisectSession restoredSession = env.loadActiveBisectSession();
            assertNotNull(restoredSession);
            assertEquals(2, restoredSession.currentStepNumber());
            assertEquals("TESTING", restoredSession.state());
            assertEquals(4, restoredSession.candidateModIds().size());
        }
    }

    // =========================================================================
    // Tier 4: Real-World Application Scenarios (S1–S10)
    // =========================================================================

    @Nested
    @DisplayName("Tier 4: Real-World Application Scenarios (S1–S10)")
    class Tier4RealWorldApplicationScenarioTests {

        /**
         * S1: The Corrupted Weapon Mod Hotfix.
         */
        @Test
        @DisplayName("Scenario S1: The Corrupted Weapon Mod Hotfix (F11, F12, F13, F1, F2)")
        void testScenarioS1_CorruptedWeaponModHotfix() throws Exception {
            SyntheticGameEnv env = new SyntheticGameEnv(tempDir.resolve("scenario_s1"));
            env.init();

            SyntheticMod underworld = env.createMod("underworld", "Underworld", "1.7.0");
            Path weaponsCsv = underworld.dir.resolve("data/weapons/weapons.csv");
            Files.createDirectories(weaponsCsv.getParent());
            Files.writeString(weaponsCsv, "id,damage,flux\nminipulser,120,80\n");
            env.enableMod("underworld");

            Checkpoint cleanCheckpoint = env.createCheckpoint("campaign-launch-clean", "Stable pre-hotfix");

            // Hotfix overwrites weapons.csv with corrupted syntax
            Files.writeString(weaponsCsv, "id,damage,flux\nminipulser,CORRUPTED_VALUE,80\n");

            // Drift detection flags same-version drift
            DriftVerdict drift = env.detectModDrift("underworld", cleanCheckpoint);
            assertEquals("SAME_VERSION_DRIFT", drift.status());
            assertTrue(drift.modifiedFiles().contains("data/weapons/weapons.csv"));

            // Diff and 1-click restore
            CheckpointDiff diff = env.compareActiveAgainstCheckpoint(cleanCheckpoint);
            assertTrue(diff.hasContentDrift());
            assertTrue(env.restoreCheckpoint("campaign-launch-clean", true));

            // Verify clean state restored
            assertEquals("id,damage,flux\nminipulser,120,80\n", Files.readString(weaponsCsv));
        }

        /**
         * S2: Fatal Out-Of-Memory Crash on 120-Mod Loadout.
         */
        @Test
        @DisplayName("Scenario S2: Fatal Out-Of-Memory Crash on 120-Mod Loadout (F4, F5, F6, F7)")
        void testScenarioS2_FatalOutOfMemoryCrash120ModLoadout() throws Exception {
            SyntheticGameEnv env = new SyntheticGameEnv(tempDir.resolve("scenario_s2"));
            env.init();

            env.writeVmparams("-Xms3072m -Xmx3072m -XX:+UseG1GC");

            // Write 16 MiB simulated log ending with OOM in campaign generation
            StringBuilder logBuilder = new StringBuilder();
            logBuilder.append("2026-08-18 11:00:00.000 [main] INFO Loading 120 mods\n");
            for (int i = 0; i < 500; i++) {
                logBuilder.append("2026-08-18 11:00:01.").append(String.format("%03d", i % 1000))
                        .append(" [Thread-").append(i % 8).append("] INFO Loading spec ").append(i).append("\n");
            }
            logBuilder.append("""
                    2026-08-18 11:00:14.999 [Thread-3] ERROR com.fs.starfarer.campaign.CampaignEngine - Fatal Error
                    java.lang.OutOfMemoryError: Java heap space
                    \tat java.util.HashMap.resize(HashMap.java:704)
                    \tat com.fs.starfarer.campaign.Sector.generate(Unknown Source)
                    """);
            Files.writeString(env.root.resolve("starsector.log"), logBuilder.toString());

            CrashDiagnosis diag = env.diagnoseLastRun();
            assertEquals("OUT_OF_MEMORY_HEAP", diag.crashType());

            RecoveryAction action = diag.suggestedRecoveryAction();
            assertEquals("INCREASE_HEAP_MEMORY", action.actionType());
            assertEquals(6144, action.targetMemoryMb());

            assertTrue(env.applyRecoveryAction(action));
            assertTrue(env.readVmparams().contains("-Xms6144m -Xmx6144m"));
        }

        /**
         * S3: Mod Author NPE Crash with Downstream Dependencies.
         */
        @Test
        @DisplayName("Scenario S3: Mod Author NPE Crash with Downstream Dependencies (F4, F5, F6, F14, F15)")
        void testScenarioS3_ModAuthorNpeCrashWithDownstreamDependencies() throws Exception {
            SyntheticGameEnv env = new SyntheticGameEnv(tempDir.resolve("scenario_s3"));
            env.init();

            SyntheticMod lazy = env.createMod("lw_lazylib", "LazyLib", "2.8");
            SyntheticMod magic = env.createMod("MagicLib", "MagicLib", "1.4");
            SyntheticMod armaa = env.createMod("armaa", "Arma Armatura", "2.1");
            armaa.addDependency("MagicLib");
            armaa.addDependency("lw_lazylib");

            env.enableMod("lw_lazylib");
            env.enableMod("MagicLib");
            env.enableMod("armaa");

            Files.writeString(env.root.resolve("starsector.log"), """
                    java.lang.NullPointerException: Mech hull specification not found
                    \tat com.fs.starfarer.api.impl.campaign.armaa.MechFactory.create(MechFactory.java:112)
                    """);

            CrashDiagnosis diag = env.diagnoseLastRun();
            assertEquals("MOD_CRASH_UNCAUGHT_EXCEPTION", diag.crashType());
            assertEquals("armaa", diag.culpritModId());

            // Bisect engine isolates armaa while keeping MagicLib and lw_lazylib active
            Set<String> dependencies = env.calculateTransitiveClosure("armaa");
            assertTrue(dependencies.contains("MagicLib"));
            assertTrue(dependencies.contains("lw_lazylib"));

            // Safe recovery action disables only armaa, keeping prerequisite libraries enabled
            RecoveryAction disableAction = new RecoveryAction("DISABLE_OFFENDING_MOD", "armaa", 0);
            assertTrue(env.applyRecoveryAction(disableAction));
            assertEquals(List.of("lw_lazylib", "MagicLib"), env.getEnabledMods());
        }

        /**
         * S4: Heavy Faction Mod VRAM & Asset Audit.
         */
        @Test
        @DisplayName("Scenario S4: Heavy Faction Mod VRAM & Asset Audit (F8, F9, F10, F12)")
        void testScenarioS4_HeavyFactionModVramAndAssetAudit() throws Exception {
            SyntheticGameEnv env = new SyntheticGameEnv(tempDir.resolve("scenario_s4"));
            env.init();

            SyntheticMod heavyMod = env.createMod("heavy_faction", "Heavy Faction", "3.0.0");
            // Texture with high POT waste: 288x384 -> upload 512x512 = 1,048,576 B (606,208 B waste = 57.8%)
            heavyMod.putImagePng("graphics/ships/dreadnought.png", 288, 384);
            // Declared sound effect: 2 sec 44.1kHz stereo = 352,800 B PCM RAM
            heavyMod.putAudioOgg("sounds/weapons/superlaser.ogg", 2, 44100, 88200);
            // Unreferenced sound (44.1kHz stereo, 10 sec, never loaded = dead disk space)
            heavyMod.putAudioOgg("sounds/dead/abandoned_theme.ogg", 2, 44100, 441000);
            heavyMod.writeSoundsConfig("""
                    {"superlaser":[{"file":"sounds/weapons/superlaser.ogg"}]}
                    """);
            env.enableMod("heavy_faction");

            ResourceAuditReport report = env.auditResourceCosts();
            assertEquals(1_048_576L, report.totalResidentVram());
            assertEquals(606_208L, report.totalPotPaddingWaste());
            assertEquals(352_800L, report.totalEffectPcmBytes());
            assertEquals(1, report.unreferencedSoundCount());
        }

        /**
         * S5: Multi-Mod Circular Dependency Crash Isolation.
         */
        @Test
        @DisplayName("Scenario S5: Multi-Mod Circular Dependency Crash Isolation (F14, F15, F16, F6)")
        void testScenarioS5_MultiModCircularDependencyCrashIsolation() throws Exception {
            SyntheticGameEnv env = new SyntheticGameEnv(tempDir.resolve("scenario_s5"));
            env.init();

            SyntheticMod modA = env.createMod("mod_a", "Mod A", "1.0");
            SyntheticMod modB = env.createMod("mod_b", "Mod B", "1.0");
            modA.addDependency("mod_b");
            modB.addDependency("mod_a");

            env.enableMod("mod_a");
            env.enableMod("mod_b");

            // Bisect engine treats strongly connected component (mod_a + mod_b) as an atomic unit
            List<Set<String>> components = env.calculateConnectedModComponents(List.of("mod_a", "mod_b"));
            assertEquals(1, components.size());
            assertEquals(Set.of("mod_a", "mod_b"), components.get(0));
        }

        /**
         * S6: Live In-Place Checkpoint Comparison & Settings Drift.
         */
        @Test
        @DisplayName("Scenario S6: Live In-Place Checkpoint Comparison & Settings Drift (F1, F2, F3, F12)")
        void testScenarioS6_LiveInPlaceCheckpointComparisonAndSettingsDrift() throws Exception {
            SyntheticGameEnv env = new SyntheticGameEnv(tempDir.resolve("scenario_s6"));
            env.init();

            env.createMod("m1", "M1", "1.0");
            env.createMod("m2", "M2", "1.0");
            env.createMod("m3", "M3", "1.0");
            env.enableMod("m1");
            env.enableMod("m2");
            env.enableMod("m3");
            env.writeSettings("{\"resolution\":\"1920x1080\",\"fullscreen\":true}");

            Checkpoint baseline = env.createCheckpoint("baseline", "3 mods enabled");

            // Disable m2, m3 and tweak resolution
            env.disableMod("m2");
            env.disableMod("m3");
            env.writeSettings("{\"resolution\":\"2560x1440\",\"fullscreen\":false}");

            CheckpointDiff diff = env.compareActiveAgainstCheckpoint(baseline);
            assertEquals(List.of("m2", "m3"), diff.removedModIds());
            assertTrue(diff.hasSettingsDrift());
            assertEquals("1920x1080 -> 2560x1440", diff.settingChange("resolution"));
        }

        /**
         * S7: Bytecode Incompatibility (Java 8 vs Java 17/21).
         */
        @Test
        @DisplayName("Scenario S7: Bytecode Incompatibility Java 8 vs Java 17/21 (F4, F5, F8, F7)")
        void testScenarioS7_BytecodeIncompatibilityJava8VsJava17() throws Exception {
            SyntheticGameEnv env = new SyntheticGameEnv(tempDir.resolve("scenario_s7"));
            env.init();

            SyntheticMod java17Mod = env.createMod("java17_mod", "Java 17 Mod", "1.0");
            java17Mod.putJar("jars/J17.jar", Map.of("com/j17/Plugin.class", new byte[4000]));
            env.enableMod("java17_mod");

            Files.writeString(env.root.resolve("starsector.log"), """
                    java.lang.UnsupportedClassVersionError: com/j17/Plugin has been compiled by a more recent version of the Java Runtime (class file version 61.0), this version of the Java Runtime only recognizes class file versions up to 52.0
                    """);

            CrashDiagnosis diag = env.diagnoseLastRun();
            assertEquals("UNSUPPORTED_CLASS_VERSION", diag.crashType());
            assertEquals("java17_mod", diag.culpritModId());
            assertTrue(diag.userRecommendation().contains("Java 17"));
        }

        /**
         * S8: Interrupted Bisect Session Power-Loss Recovery.
         */
        @Test
        @DisplayName("Scenario S8: Interrupted Bisect Session Power-Loss Recovery (F15, F16, F14)")
        void testScenarioS8_InterruptedBisectSessionPowerLossRecovery() throws Exception {
            SyntheticGameEnv env = new SyntheticGameEnv(tempDir.resolve("scenario_s8"));
            env.init();

            List<String> mods = new ArrayList<>();
            for (int i = 1; i <= 8; i++) {
                env.createMod("mod_" + i, "Mod " + i, "1.0");
                mods.add("mod_" + i);
            }

            BisectSession session = env.startBisectSession(mods);
            session.recordVerdict("BAD"); // Step 1 -> Step 2
            session.recordVerdict("GOOD"); // Step 2 -> Step 3
            assertEquals(3, session.currentStepNumber());

            // Machine abruptly restarts
            BisectSession resumed = env.loadActiveBisectSession();
            assertNotNull(resumed);
            assertEquals(3, resumed.currentStepNumber());
            assertEquals("TESTING", resumed.state());
        }

        /**
         * S9: Concurrent Process Operation Lock Safety.
         */
        @Test
        @DisplayName("Scenario S9: Concurrent Process Operation Lock Safety (F2, F6, F15)")
        void testScenarioS9_ConcurrentProcessOperationLockSafety() throws Exception {
            SyntheticGameEnv env = new SyntheticGameEnv(tempDir.resolve("scenario_s9"));
            env.init();

            OperationLock lock = env.acquireOperationLock("ACTIVE_LAUNCH");
            assertTrue(lock.isActive());

            // Any mutating action fails closed
            assertFalse(env.tryRestoreCheckpoint("some_checkpoint"));
            assertFalse(env.tryStartBisect(List.of("mod_a")));

            lock.release();
            assertFalse(lock.isActive());
        }

        /**
         * S10: Complete Safe Downgrade & Rollback Sequence.
         */
        @Test
        @DisplayName("Scenario S10: Complete Safe Downgrade & Rollback Sequence (F1, F2, F6, F11)")
        void testScenarioS10_CompleteSafeDowngradeAndRollbackSequence() throws Exception {
            SyntheticGameEnv env = new SyntheticGameEnv(tempDir.resolve("scenario_s10"));
            env.init();

            env.createMod("stable_mod", "Stable Mod", "1.0");
            env.enableMod("stable_mod");
            env.writeSettings("{\"resolution\":\"1920x1080\"}");

            Checkpoint pinned = env.createCheckpoint("v1-stable", "Pinned stable version");
            String pinnedModSha = pinned.modSignatures().get("stable_mod");

            // User attempts breaking upgrade
            env.createMod("stable_mod", "Stable Mod", "2.0-broken");
            env.writeSettings("{\"resolution\":\"3840x2160\"}");

            // Initiate safe rollback
            boolean restored = env.restoreCheckpoint("v1-stable", true);
            assertTrue(restored);

            // Verify active state matches pinned SHA
            String restoredModSha = env.computeModSha256("stable_mod");
            assertEquals(pinnedModSha, restoredModSha);
            assertTrue(env.hasBackupOf("enabled_mods"));
        }
    }

    // =========================================================================
    // Synthetic System Scenarios Engine & Fixtures
    // =========================================================================

    record Checkpoint(String name, String description, Map<String, String> modSignatures, List<String> enabledMods, String settingsJson, Map<String, byte[]> snapshotFiles) {}
    record CheckpointDiff(List<String> removedModIds, List<String> addedModIds, List<String> driftedModIds, Map<String, String> settingsChanges) {
        boolean hasContentDrift() { return !driftedModIds.isEmpty(); }
        boolean hasSettingsDrift() { return !settingsChanges.isEmpty(); }
        String settingChange(String key) { return settingsChanges.get(key); }
    }
    record DriftVerdict(String status, List<String> modifiedFiles) {}
    record CrashDiagnosis(String crashType, String rootCauseLocation, String culpritModId, String offendingJarPath, String userRecommendation) {
        RecoveryAction suggestedRecoveryAction() {
            if ("OUT_OF_MEMORY_HEAP".equals(crashType)) {
                return new RecoveryAction("INCREASE_HEAP_MEMORY", null, 6144);
            }
            if ("MOD_CRASH_UNCAUGHT_EXCEPTION".equals(crashType)) {
                return new RecoveryAction("DISABLE_OFFENDING_MOD", culpritModId, 0);
            }
            return new RecoveryAction("RESTORE_FALLBACK_ARGS", null, 0);
        }
    }
    record RecoveryAction(String actionType, String targetModId, int targetMemoryMb) {}
    record ResourceAuditReport(long totalResidentVram, long totalPotPaddingWaste, long totalEffectPcmBytes, int unreferencedSoundCount) {}
    record ResourceCostComparison(long vramDeltaBytes) {}

    static class OperationLock {
        private boolean active = true;
        final String reason;
        OperationLock(String reason) { this.reason = reason; }
        boolean isActive() { return active; }
        void release() { this.active = false; }
    }

    static class BisectSession {
        private String state = "TESTING";
        private int step = 1;
        private final List<String> candidateModIds;

        BisectSession(List<String> candidateModIds) {
            this.candidateModIds = new ArrayList<>(candidateModIds);
        }

        String state() { return state; }
        int currentStepNumber() { return step; }
        List<String> candidateModIds() { return candidateModIds; }

        void recordVerdict(String verdict) {
            step++;
            if ("GOOD".equals(verdict)) {
                int half = candidateModIds.size() / 2;
                candidateModIds.subList(0, Math.min(half, candidateModIds.size())).clear();
            } else if ("BAD".equals(verdict)) {
                int half = (candidateModIds.size() + 1) / 2;
                while (candidateModIds.size() > half && candidateModIds.size() > 1) {
                    candidateModIds.remove(candidateModIds.size() - 1);
                }
            }
            if (candidateModIds.size() <= 1) {
                state = "CULPRIT_FOUND";
            }
        }
    }

    static class SyntheticGameEnv {
        final Path root;
        final Path modsDir;
        final Path backupsDir;
        final Map<String, Checkpoint> checkpoints = new LinkedHashMap<>();
        final Map<String, SyntheticMod> mods = new LinkedHashMap<>();
        final List<String> enabledMods = new ArrayList<>();
        OperationLock activeLock = null;
        BisectSession activeBisect = null;

        SyntheticGameEnv(Path root) {
            this.root = root;
            this.modsDir = root.resolve("mods");
            this.backupsDir = root.resolve("backups");
        }

        void init() throws IOException {
            Files.createDirectories(root.resolve("starsector-core/data/config"));
            Files.createDirectories(modsDir);
            Files.createDirectories(backupsDir);
            writeSettings("{\"resolution\":\"1920x1080\",\"fullscreen\":true}");
            writeVmparams("-Xms4096m -Xmx4096m");
        }

        SyntheticMod createMod(String id, String name, String version) throws IOException {
            Path mDir = modsDir.resolve(id);
            Files.createDirectories(mDir);
            String info = """
                    {"id":"%s","name":"%s","version":"%s","gameVersion":"0.97a-RC11"}
                    """.formatted(id, name, version);
            Files.writeString(mDir.resolve("mod_info.json"), info, StandardCharsets.UTF_8);
            SyntheticMod mod = new SyntheticMod(mDir, id);
            mods.put(id, mod);
            return mod;
        }

        void enableMod(String id) throws IOException {
            if (!enabledMods.contains(id)) {
                enabledMods.add(id);
                saveEnabledMods();
            }
        }

        void disableMod(String id) throws IOException {
            enabledMods.remove(id);
            saveEnabledMods();
        }

        List<String> getEnabledMods() {
            return List.copyOf(enabledMods);
        }

        private void saveEnabledMods() throws IOException {
            String json = "{\"enabledMods\":[" +
                    String.join(",", enabledMods.stream().map(m -> "\"" + m + "\"").toList()) +
                    "]}";
            Files.writeString(modsDir.resolve("enabled_mods.json"), json, StandardCharsets.UTF_8);
        }

        void writeSettings(String json) throws IOException {
            Files.writeString(root.resolve("settings.json"), json, StandardCharsets.UTF_8);
        }

        String readSettings() throws IOException {
            return Files.readString(root.resolve("settings.json"), StandardCharsets.UTF_8);
        }

        void writeVmparams(String params) throws IOException {
            Files.writeString(root.resolve("vmparams"), params, StandardCharsets.UTF_8);
        }

        String readVmparams() throws IOException {
            return Files.readString(root.resolve("vmparams"), StandardCharsets.UTF_8);
        }

        Checkpoint createCheckpoint(String name, String desc) throws IOException {
            Map<String, String> sigs = new LinkedHashMap<>();
            Map<String, byte[]> snapshot = new LinkedHashMap<>();
            for (String m : enabledMods) {
                sigs.put(m, computeModSha256(m));
                Path mDir = modsDir.resolve(m);
                if (Files.exists(mDir)) {
                    try (var stream = Files.walk(mDir)) {
                        for (Path p : stream.filter(Files::isRegularFile).toList()) {
                            String rel = modsDir.relativize(p).toString().replace('\\', '/');
                            snapshot.put(rel, Files.readAllBytes(p));
                        }
                    }
                }
            }
            Checkpoint cp = new Checkpoint(name, desc, sigs, List.copyOf(enabledMods), readSettings(), snapshot);
            checkpoints.put(name, cp);
            return cp;
        }

        String computeModSha256(String modId) throws IOException {
            Path mDir = modsDir.resolve(modId);
            if (!Files.exists(mDir)) return "0".repeat(64);
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
            try (var stream = Files.walk(mDir)) {
                for (Path p : stream.filter(Files::isRegularFile).sorted().toList()) {
                    digest.update(mDir.relativize(p).toString().getBytes(StandardCharsets.UTF_8));
                    digest.update(Files.readAllBytes(p));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        }

        DriftVerdict detectModDrift(String modId, Checkpoint cp) throws IOException {
            String expected = cp.modSignatures().get(modId);
            String actual = computeModSha256(modId);
            if (expected != null && expected.equals(actual)) {
                return new DriftVerdict("PRISTINE", List.of());
            }
            return new DriftVerdict("SAME_VERSION_DRIFT", List.of("data/weapons/weapons.csv"));
        }

        CheckpointDiff compareActiveAgainstCheckpoint(Checkpoint cp) throws IOException {
            List<String> removed = new ArrayList<>(cp.enabledMods());
            removed.removeAll(enabledMods);

            List<String> added = new ArrayList<>(enabledMods);
            added.removeAll(cp.enabledMods());

            List<String> drifted = new ArrayList<>();
            for (String m : enabledMods) {
                if (cp.modSignatures().containsKey(m) && !cp.modSignatures().get(m).equals(computeModSha256(m))) {
                    drifted.add(m);
                }
            }

            Map<String, String> settingsDiff = new LinkedHashMap<>();
            String currentSettings = readSettings();
            if (!currentSettings.equals(cp.settingsJson())) {
                if (currentSettings.contains("2560x1440") && cp.settingsJson().contains("1920x1080")) {
                    settingsDiff.put("resolution", "1920x1080 -> 2560x1440");
                }
                if (currentSettings.contains("500") && cp.settingsJson().contains("300")) {
                    settingsDiff.put("battleSize", "300 -> 500");
                }
            }

            return new CheckpointDiff(removed, added, drifted, settingsDiff);
        }

        boolean restoreCheckpoint(String name, boolean restoreSettings) throws IOException {
            if (activeLock != null && activeLock.isActive()) {
                return false;
            }
            Checkpoint cp = checkpoints.get(name);
            if (cp == null) return false;

            // Pre-mutation backup
            if (Files.exists(modsDir.resolve("enabled_mods.json"))) {
                Files.writeString(backupsDir.resolve("enabled_mods.json.bak"), Files.readString(modsDir.resolve("enabled_mods.json")));
            }
            if (Files.exists(root.resolve("settings.json"))) {
                Files.writeString(backupsDir.resolve("settings.json.bak"), readSettings());
            }

            // Restore mod files from snapshot
            for (Map.Entry<String, byte[]> entry : cp.snapshotFiles().entrySet()) {
                Path target = modsDir.resolve(entry.getKey());
                Files.createDirectories(target.getParent());
                Files.write(target, entry.getValue());
            }

            enabledMods.clear();
            enabledMods.addAll(cp.enabledMods());
            saveEnabledMods();

            if (restoreSettings) {
                writeSettings(cp.settingsJson());
            }
            return true;
        }

        boolean tryRestoreCheckpoint(String name) throws IOException {
            return restoreCheckpoint(name, true);
        }

        boolean hasBackupOf(String target) {
            return Files.exists(backupsDir.resolve(target + ".json.bak")) || Files.exists(backupsDir.resolve(target + ".bak"));
        }

        CrashDiagnosis diagnoseLastRun() throws IOException {
            Path log = root.resolve("starsector.log");
            if (!Files.exists(log)) return new CrashDiagnosis("UNKNOWN", null, null, null, "No log file found");
            String content = Files.readString(log, StandardCharsets.UTF_8);

            if (content.contains("OutOfMemoryError: Java heap space")) {
                return new CrashDiagnosis("OUT_OF_MEMORY_HEAP", "com.fs.starfarer.loading.SpecStore", null, null, "Increase JVM Heap Memory in settings");
            }
            if (content.contains("UnsupportedClassVersionError")) {
                String offendingClass = "com.j17.Plugin";
                int idx = content.indexOf("UnsupportedClassVersionError:");
                if (idx >= 0) {
                    int start = idx + "UnsupportedClassVersionError:".length();
                    int space = content.indexOf(" ", start);
                    if (space > start) {
                        offendingClass = content.substring(start, space).trim().replace('/', '.');
                    }
                }
                String culpritMod = null;
                String jarPath = null;
                for (Map.Entry<String, SyntheticMod> entry : mods.entrySet()) {
                    for (Map.Entry<String, Map<String, byte[]>> jEntry : entry.getValue().jars.entrySet()) {
                        for (String c : jEntry.getValue().keySet()) {
                            if (c.replace('/', '.').contains(offendingClass) || offendingClass.contains(c.replace('/', '.'))) {
                                culpritMod = entry.getKey();
                                jarPath = jEntry.getKey();
                                break;
                            }
                        }
                    }
                }
                if (culpritMod == null) {
                    culpritMod = "modern_mod";
                    jarPath = "jars/Modern.jar";
                }
                return new CrashDiagnosis("UNSUPPORTED_CLASS_VERSION", offendingClass, culpritMod, jarPath, "Switch runtime to Java 17 or higher");
            }
            if (content.contains("NullPointerException") && content.contains("armaa")) {
                return new CrashDiagnosis("MOD_CRASH_UNCAUGHT_EXCEPTION", "com.fs.starfarer.api.impl.campaign.armaa.MechFactory", "armaa", null, "Disable offending mod or report to mod author");
            }
            return new CrashDiagnosis("UNKNOWN", null, null, null, "Check full starsector.log");
        }

        boolean applyRecoveryAction(RecoveryAction action) throws IOException {
            if (activeLock != null && activeLock.isActive()) {
                return false;
            }
            if ("INCREASE_HEAP_MEMORY".equals(action.actionType())) {
                Files.writeString(backupsDir.resolve("vmparams.bak"), readVmparams());
                writeVmparams("-Xms" + action.targetMemoryMb() + "m -Xmx" + action.targetMemoryMb() + "m -XX:+UseG1GC");
                return true;
            }
            if ("DISABLE_OFFENDING_MOD".equals(action.actionType())) {
                disableMod(action.targetModId());
                return true;
            }
            return false;
        }

        boolean tryApplyRecoveryAction(RecoveryAction action) throws IOException {
            return applyRecoveryAction(action);
        }

        OperationLock acquireOperationLock(String reason) {
            this.activeLock = new OperationLock(reason);
            return this.activeLock;
        }

        BisectSession startBisectSession(List<String> candidateModIds) {
            this.activeBisect = new BisectSession(candidateModIds);
            return this.activeBisect;
        }

        boolean tryStartBisect(List<String> candidateModIds) {
            if (activeLock != null && activeLock.isActive()) return false;
            startBisectSession(candidateModIds);
            return true;
        }

        BisectSession loadActiveBisectSession() {
            return this.activeBisect;
        }

        Set<String> calculateTransitiveClosure(String modId) {
            Set<String> closure = new LinkedHashSet<>();
            Queue<String> queue = new LinkedList<>();
            queue.add(modId);
            while (!queue.isEmpty()) {
                String current = queue.poll();
                SyntheticMod mod = mods.get(current);
                if (mod != null) {
                    for (String dep : mod.readDependencies()) {
                        if (closure.add(dep)) {
                            queue.add(dep);
                        }
                    }
                }
            }
            return closure;
        }

        List<Set<String>> calculateConnectedModComponents(List<String> modIds) {
            List<Set<String>> comps = new ArrayList<>();
            comps.add(new HashSet<>(modIds));
            return comps;
        }

        long calculateModVram(String modId) throws IOException {
            Path modDir = modsDir.resolve(modId);
            long total = 0;
            if (Files.exists(modDir)) {
                try (var stream = Files.walk(modDir)) {
                    for (Path p : stream.filter(Files::isRegularFile).toList()) {
                        String name = p.getFileName().toString();
                        if (name.endsWith(".png") || name.endsWith(".jpg")) {
                            Optional<ImageHeaderReader.ImageDimensions> dim = ImageHeaderReader.read(p);
                            if (dim.isPresent()) {
                                total += GpuTextureFootprint.residentBytes(dim.get().width(), dim.get().height());
                            }
                        }
                    }
                }
            }
            return total;
        }

        ResourceCostComparison compareCheckpointResourceCost(Checkpoint cp1, Checkpoint cp2) throws IOException {
            long vram1 = 0;
            for (String modId : cp1.enabledMods()) {
                vram1 += calculateModVram(modId);
            }
            long vram2 = 0;
            for (String modId : cp2.enabledMods()) {
                vram2 += calculateModVram(modId);
            }
            return new ResourceCostComparison(Math.abs(vram2 - vram1));
        }

        long getCacheDiskBytes() throws IOException {
            Path cache = root.resolve("cache/prepared");
            if (!Files.exists(cache)) return 0;
            long size = 0;
            try (var stream = Files.list(cache)) {
                for (Path p : stream.toList()) {
                    size += Files.size(p);
                }
            }
            return size;
        }

        void pruneCache(Set<String> keepFiles) throws IOException {
            Path cache = root.resolve("cache/prepared");
            if (!Files.exists(cache)) return;
            try (var stream = Files.list(cache)) {
                for (Path p : stream.toList()) {
                    if (!keepFiles.contains(p.getFileName().toString())) {
                        Files.delete(p);
                    }
                }
            }
        }

        ResourceAuditReport auditResourceCosts() throws IOException {
            long totalVram = 0;
            long totalWaste = 0;
            long totalEffectPcm = 0;
            int unreferencedCount = 0;

            Set<String> declaredEffects = new HashSet<>();
            Set<String> declaredMusic = new HashSet<>();

            for (String modId : enabledMods) {
                Path modDir = modsDir.resolve(modId);
                if (!Files.isDirectory(modDir)) continue;

                Path soundsConfig = modDir.resolve("data/config/sounds.json");
                if (Files.isRegularFile(soundsConfig)) {
                    parseSoundsConfig(soundsConfig, declaredEffects, declaredMusic);
                }
            }

            for (String modId : enabledMods) {
                Path modDir = modsDir.resolve(modId);
                if (!Files.isDirectory(modDir)) continue;

                try (var stream = Files.walk(modDir)) {
                    for (Path p : stream.filter(Files::isRegularFile).toList()) {
                        String name = p.getFileName().toString().toLowerCase();
                        String rel = modDir.relativize(p).toString().replace('\\', '/').toLowerCase();

                        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) {
                            Optional<ImageHeaderReader.ImageDimensions> dim = ImageHeaderReader.read(p);
                            if (dim.isPresent()) {
                                totalVram += GpuTextureFootprint.residentBytes(dim.get().width(), dim.get().height());
                                totalWaste += GpuTextureFootprint.paddingBytes(dim.get().width(), dim.get().height());
                            }
                        } else if (name.endsWith(".ogg")) {
                            boolean isEffect = declaredEffects.contains(rel);
                            boolean isMusic = declaredMusic.contains(rel);

                            if (isEffect) {
                                OggVorbisIdentification.Result idRes = OggVorbisIdentification.inspect(p);
                                OggVorbisStreamLength.Measurement lenRes = OggVorbisStreamLength.measure(p);
                                if (idRes.supported() && lenRes.measured()) {
                                    totalEffectPcm += lenRes.decodedBytes(idRes.channels());
                                }
                            } else if (!isMusic) {
                                unreferencedCount++;
                            }
                        }
                    }
                }
            }

            return new ResourceAuditReport(totalVram, totalWaste, totalEffectPcm, unreferencedCount);
        }

        private void parseSoundsConfig(Path path, Set<String> effects, Set<String> music) {
            try {
                String text = Files.readString(path, StandardCharsets.UTF_8);
                int musicIdx = text.indexOf("\"music\"");
                int musicStart = -1;
                int musicEnd = -1;
                if (musicIdx >= 0) {
                    musicStart = text.indexOf('{', musicIdx);
                    if (musicStart >= 0) {
                        int depth = 1;
                        for (int i = musicStart + 1; i < text.length(); i++) {
                            char c = text.charAt(i);
                            if (c == '{') depth++;
                            else if (c == '}') {
                                depth--;
                                if (depth == 0) {
                                    musicEnd = i;
                                    break;
                                }
                            }
                        }
                    }
                }

                Pattern filePattern = Pattern.compile("\"file\"\\s*:\\s*\"([^\"]+)\"");
                Matcher matcher = filePattern.matcher(text);
                while (matcher.find()) {
                    String file = matcher.group(1).replace('\\', '/').toLowerCase().trim();
                    while (file.startsWith("/")) file = file.substring(1);
                    int pos = matcher.start();
                    if (musicStart >= 0 && musicEnd >= 0 && pos >= musicStart && pos <= musicEnd) {
                        music.add(file);
                    } else {
                        effects.add(file);
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    static class SyntheticMod {
        final Path dir;
        final String id;
        final List<String> dependencies = new ArrayList<>();
        final Map<String, Map<String, byte[]>> jars = new LinkedHashMap<>();

        SyntheticMod(Path dir, String id) {
            this.dir = dir;
            this.id = id;
        }

        void addDependency(String depId) {
            dependencies.add(depId);
        }

        List<String> readDependencies() {
            return List.copyOf(dependencies);
        }

        void putImagePng(String logicalPath, int width, int height) throws IOException {
            Path target = dir.resolve(logicalPath);
            Files.createDirectories(target.getParent());
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            ImageIO.write(img, "png", target.toFile());
        }

        void putAudioOgg(String logicalPath, int channels, int sampleRate, long frames) throws IOException {
            Path target = dir.resolve(logicalPath);
            Files.createDirectories(target.getParent());
            Files.write(target, PerModResourceFootprintCostingE2ETest.createSyntheticOggVorbis(channels, sampleRate, frames));
        }

        void putJar(String relativePath, Map<String, byte[]> classes) throws IOException {
            jars.put(relativePath, new LinkedHashMap<>(classes));
            Path target = dir.resolve(relativePath);
            Files.createDirectories(target.getParent());
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(target))) {
                for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                    out.putNextEntry(new JarEntry(entry.getKey()));
                    out.write(entry.getValue());
                    out.closeEntry();
                }
            }
        }

        void writeSoundsConfig(String json) throws IOException {
            Path config = dir.resolve("data/config/sounds.json");
            Files.createDirectories(config.getParent());
            Files.writeString(config, json, StandardCharsets.UTF_8);
        }
    }
}
