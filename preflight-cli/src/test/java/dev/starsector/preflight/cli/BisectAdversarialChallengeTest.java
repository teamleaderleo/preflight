package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Json;
import dev.starsector.preflight.core.bisect.BisectStatus;
import dev.starsector.preflight.core.bisect.BisectStep;
import dev.starsector.preflight.core.bisect.BisectVerdict;
import dev.starsector.preflight.core.bisect.ModBisectEngine;
import dev.starsector.preflight.core.bisect.ModBisectSession;
import dev.starsector.preflight.core.bisect.ModDependencyGraph;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Adversarial stress harness and empirical challenge suite for Java Backend & Bisect Engine (Milestone 5).
 */
public class BisectAdversarialChallengeTest {

    @TempDir
    Path tempDir;

    private Path installRoot;
    private Path modsDir;
    private Path enabledModsFile;
    private Path preflightHomeDir;
    private Path stateDir;
    private Path sessionFile;

    @BeforeEach
    void setUp() throws IOException {
        installRoot = tempDir.resolve("Starsector");
        modsDir = installRoot.resolve("mods");
        enabledModsFile = modsDir.resolve("enabled_mods.json");
        preflightHomeDir = tempDir.resolve(".starsector-preflight");
        stateDir = preflightHomeDir.resolve("state");
        sessionFile = stateDir.resolve("bisect-session.json");

        Files.createDirectories(modsDir);
        Files.createDirectories(stateDir);
    }

    private void writeEnabledMods(List<String> mods) throws IOException {
        Files.writeString(enabledModsFile, Json.object(Map.of("enabledMods", mods)) + "\n", StandardCharsets.UTF_8);
    }

    private void createModOnDisk(String modId, List<String> dependencies) throws IOException {
        Path modFolder = modsDir.resolve(modId);
        Files.createDirectories(modFolder);
        Map<String, Object> modInfo = new LinkedHashMap<>();
        modInfo.put("id", modId);
        modInfo.put("name", "Mod " + modId);
        modInfo.put("version", "1.0.0");
        if (dependencies != null && !dependencies.isEmpty()) {
            List<Map<String, String>> depsList = new ArrayList<>();
            for (String dep : dependencies) {
                depsList.add(Map.of("id", dep));
            }
            modInfo.put("dependencies", depsList);
        }
        Files.writeString(modFolder.resolve("mod_info.json"), Json.object(modInfo), StandardCharsets.UTF_8);
    }

    // =========================================================================
    // 1. Circular Mod Dependencies & SCCs
    // =========================================================================

    @Nested
    @DisplayName("Adversarial Dimension 1: Circular Dependencies & SCC Cycles")
    class CircularDependencyChallenges {

        @Test
        @DisplayName("Direct 2-node cycle (A <-> B) with downstream dependent")
        void testDirectTwoNodeCycleWithDownstream() {
            List<String> allMods = List.of("A", "B", "C");
            Map<String, Set<String>> deps = Map.of(
                    "A", Set.of("B"),
                    "B", Set.of("A"),
                    "C", Set.of("A")
            );
            ModDependencyGraph graph = new ModDependencyGraph(allMods, deps);

            List<Set<String>> sccs = graph.findStronglyConnectedComponents();
            boolean foundCycle = sccs.stream().anyMatch(scc -> scc.contains("A") && scc.contains("B"));
            assertTrue(foundCycle, "Tarjan SCC must detect 2-node cycle {A, B}");

            // Closure tests
            assertEquals(Set.of("A", "B"), graph.transitiveClosure(Set.of("A")));
            assertEquals(Set.of("A", "B"), graph.transitiveClosure(Set.of("B")));
            assertEquals(Set.of("A", "B", "C"), graph.transitiveClosure(Set.of("C")));

            // Bisect engine with culprit C
            ModBisectEngine engine = new ModBisectEngine(graph, allMods, Set.of());
            int steps = 0;
            while (engine.hasMoreSteps() && steps < 10) {
                steps++;
                List<String> part = engine.computeNextPartition();
                engine.recordVerdict(part.contains("C") ? BisectVerdict.FAIL : BisectVerdict.PASS);
            }
            assertTrue(engine.isFinished());
            assertEquals("C", engine.getCulprit());
        }

        @Test
        @DisplayName("3-node cycle (A -> B -> C -> A) isolated as atomic unit")
        void testThreeNodeCycleAtomicClosure() {
            List<String> allMods = List.of("A", "B", "C", "D", "E");
            Map<String, Set<String>> deps = Map.of(
                    "A", Set.of("B"),
                    "B", Set.of("C"),
                    "C", Set.of("A"),
                    "D", Set.of("E")
            );
            ModDependencyGraph graph = new ModDependencyGraph(allMods, deps);

            List<Set<String>> sccs = graph.findStronglyConnectedComponents();
            boolean found3Cycle = sccs.stream().anyMatch(scc -> scc.size() == 3 && scc.containsAll(Set.of("A", "B", "C")));
            assertTrue(found3Cycle, "Tarjan SCC must detect 3-node cycle {A, B, C}");

            // When culprit is D
            ModBisectEngine engine = new ModBisectEngine(graph, allMods, Set.of());
            int steps = 0;
            while (engine.hasMoreSteps() && steps < 10) {
                steps++;
                List<String> part = engine.computeNextPartition();
                // Partition must maintain transitive closure
                assertEquals(new HashSet<>(part), graph.transitiveClosure(new HashSet<>(part)));
                engine.recordVerdict(part.contains("D") ? BisectVerdict.FAIL : BisectVerdict.PASS);
            }
            assertTrue(engine.isFinished());
            assertEquals("D", engine.getCulprit());
        }

        @Test
        @DisplayName("Disjoint cycles in large mod list (Cycle 1: A-B-C, Cycle 2: D-E)")
        void testDisjointCyclesConvergence() {
            List<String> allMods = List.of("A", "B", "C", "D", "E", "F", "G", "H");
            Map<String, Set<String>> deps = Map.of(
                    "A", Set.of("B"),
                    "B", Set.of("C"),
                    "C", Set.of("A"),
                    "D", Set.of("E"),
                    "E", Set.of("D"),
                    "F", Set.of("A", "D")
            );
            ModDependencyGraph graph = new ModDependencyGraph(allMods, deps);

            // Culprit is F
            ModBisectEngine engine = new ModBisectEngine(graph, allMods, Set.of());
            int steps = 0;
            while (engine.hasMoreSteps() && steps < 15) {
                steps++;
                List<String> part = engine.computeNextPartition();
                engine.recordVerdict(part.contains("F") ? BisectVerdict.FAIL : BisectVerdict.PASS);
            }
            assertTrue(engine.isFinished());
            assertEquals("F", engine.getCulprit());
        }
    }

    // =========================================================================
    // 2. Missing Dependencies & Uninstalled Mods
    // =========================================================================

    @Nested
    @DisplayName("Adversarial Dimension 2: Missing Dependencies & Uninstalled Mods")
    class MissingDependencyChallenges {

        @Test
        @DisplayName("Mod requires missing dependency not present in active mods or install")
        void testModRequiresMissingDependency() {
            List<String> activeMods = List.of("mod_a", "mod_b", "mod_c");
            // mod_a requires non_existent_lib (missing from activeMods)
            Map<String, Set<String>> deps = Map.of(
                    "mod_a", Set.of("non_existent_lib"),
                    "mod_b", Set.of()
            );
            ModDependencyGraph graph = new ModDependencyGraph(activeMods, deps);

            // Transitive closure of mod_a includes non_existent_lib
            Set<String> closureA = graph.transitiveClosure(Set.of("mod_a"));
            assertTrue(closureA.contains("non_existent_lib"));

            // But ModBisectEngine must strictly retain only initialActive mods in partition
            ModBisectEngine engine = new ModBisectEngine(graph, activeMods, Set.of());
            int steps = 0;
            while (engine.hasMoreSteps() && steps < 10) {
                steps++;
                List<String> part = engine.computeNextPartition();
                for (String mod : part) {
                    assertTrue(activeMods.contains(mod), "Test partition must not contain uninstalled or inactive mods: " + mod);
                }
                engine.recordVerdict(part.contains("mod_b") ? BisectVerdict.FAIL : BisectVerdict.PASS);
            }
            assertTrue(engine.isFinished());
            assertEquals("mod_b", engine.getCulprit());
        }

        @Test
        @DisplayName("Filesystem scan with missing/corrupt mod_info.json files")
        void testFilesystemScanWithCorruptedModInfo() throws IOException {
            // Mod 1: valid
            createModOnDisk("valid_mod", List.of());
            // Mod 2: malformed JSON in mod_info.json
            Path badModFolder = modsDir.resolve("bad_mod");
            Files.createDirectories(badModFolder);
            Files.writeString(badModFolder.resolve("mod_info.json"), "{ NOT VALID JSON");
            // Mod 3: directory without mod_info.json
            Files.createDirectories(modsDir.resolve("empty_folder"));

            List<String> active = List.of("valid_mod", "bad_mod");
            ModDependencyGraph graph = ModDependencyGraph.fromInstallation(installRoot, active);

            assertNotNull(graph);
            assertTrue(graph.allMods().contains("valid_mod"));
            assertTrue(graph.allMods().contains("bad_mod"));
            // Graceful degradation: no crash on corrupted mod_info.json
        }
    }

    // =========================================================================
    // 3. Deep Multi-Tier Hierarchies & Extreme Graphs
    // =========================================================================

    @Nested
    @DisplayName("Adversarial Dimension 3: Deep Multi-Tier Hierarchies")
    class DeepHierarchyChallenges {

        @Test
        @DisplayName("100-level linear chain M0 -> M1 -> ... -> M99")
        void test100LevelLinearChain() {
            int count = 100;
            List<String> allMods = new ArrayList<>();
            Map<String, Set<String>> deps = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                String mod = "M" + i;
                allMods.add(mod);
                if (i > 0) {
                    deps.put(mod, Set.of("M" + (i - 1)));
                }
            }
            ModDependencyGraph graph = new ModDependencyGraph(allMods, deps);

            // Transitive closure of M99 must include all 100 mods
            Set<String> closure99 = graph.transitiveClosure(Set.of("M99"));
            assertEquals(100, closure99.size());

            // Bisect to isolate M42
            String plantedCulprit = "M42";
            ModBisectEngine engine = new ModBisectEngine(graph, allMods, Set.of("M0"));

            int steps = 0;
            while (engine.hasMoreSteps() && steps < 120) {
                steps++;
                List<String> part = engine.computeNextPartition();
                // INVARIANT: every partition is dependency-closed
                Set<String> closure = graph.transitiveClosure(new HashSet<>(part));
                closure.retainAll(allMods);
                assertEquals(new HashSet<>(part), closure);

                boolean crashes = part.contains(plantedCulprit);
                engine.recordVerdict(crashes ? BisectVerdict.FAIL : BisectVerdict.PASS);
            }

            assertTrue(engine.isFinished());
            assertEquals("M42", engine.getCulprit());
        }

        @Test
        @DisplayName("Full 6-level binary tree DAG (63 mods)")
        void testFullBinaryTreeDAG() {
            int depth = 6;
            int total = (1 << depth) - 1; // 63 mods
            List<String> allMods = new ArrayList<>();
            Map<String, Set<String>> deps = new LinkedHashMap<>();

            for (int i = 1; i <= total; i++) {
                String mod = "node_" + i;
                allMods.add(mod);
                if (i > 1) {
                    int parent = i / 2;
                    deps.put(mod, Set.of("node_" + parent));
                }
            }

            ModDependencyGraph graph = new ModDependencyGraph(allMods, deps);
            String plantedCulprit = "node_57"; // Deep leaf

            ModBisectEngine engine = new ModBisectEngine(graph, allMods, Set.of("node_1"));
            int steps = 0;
            while (engine.hasMoreSteps() && steps < 25) {
                steps++;
                List<String> part = engine.computeNextPartition();
                boolean crashes = part.contains(plantedCulprit);
                engine.recordVerdict(crashes ? BisectVerdict.FAIL : BisectVerdict.PASS);
            }

            assertTrue(engine.isFinished());
            assertEquals("node_57", engine.getCulprit());
            assertTrue(steps <= 10, "63-node tree must converge in <= 10 steps, took " + steps);
        }

        @Test
        @DisplayName("Dense diamond DAG with multiple prerequisite pathways")
        void testDiamondDAGMultiPathways() {
            // Root: R
            // Intermediates: A1, A2, B1, B2
            // Sinks: S1, S2, S3
            // S1 -> A1 -> R, S1 -> A2 -> R
            // S2 -> B1 -> R, S2 -> B2 -> R
            // S3 -> A1, A2, B1, B2 -> R
            List<String> allMods = List.of("R", "A1", "A2", "B1", "B2", "S1", "S2", "S3");
            Map<String, Set<String>> deps = Map.of(
                    "A1", Set.of("R"),
                    "A2", Set.of("R"),
                    "B1", Set.of("R"),
                    "B2", Set.of("R"),
                    "S1", Set.of("A1", "A2"),
                    "S2", Set.of("B1", "B2"),
                    "S3", Set.of("A1", "A2", "B1", "B2")
            );

            ModDependencyGraph graph = new ModDependencyGraph(allMods, deps);
            ModBisectEngine engine = new ModBisectEngine(graph, allMods, Set.of("R"));

            String plantedCulprit = "S2";
            int steps = 0;
            while (engine.hasMoreSteps() && steps < 15) {
                steps++;
                List<String> part = engine.computeNextPartition();
                boolean crashes = part.contains(plantedCulprit);
                engine.recordVerdict(crashes ? BisectVerdict.FAIL : BisectVerdict.PASS);
            }

            assertTrue(engine.isFinished());
            assertEquals("S2", engine.getCulprit());
        }
    }

    // =========================================================================
    // 4. Exhaustive Culprit Isolation Oracle Across Every Position
    // =========================================================================

    @Nested
    @DisplayName("Adversarial Dimension 4: Exhaustive Culprit Isolation")
    class ExhaustiveCulpritIsolation {

        @Test
        @DisplayName("Exhaustive planting of culprit across every node in 20-node random DAG")
        void testExhaustivePlantedCulprits() {
            int nodeCount = 20;
            List<String> allMods = new ArrayList<>();
            for (int i = 0; i < nodeCount; i++) {
                allMods.add("mod_" + i);
            }

            Map<String, Set<String>> deps = new LinkedHashMap<>();
            Random rng = new Random(12345);
            for (int i = 3; i < nodeCount; i++) {
                int p = rng.nextInt(i);
                deps.put("mod_" + i, Set.of("mod_" + p));
            }

            ModDependencyGraph graph = new ModDependencyGraph(allMods, deps);
            Set<String> baseMods = Set.of("mod_0", "mod_1", "mod_2");

            // Test every suspect as planted culprit
            for (int targetIdx = 3; targetIdx < nodeCount; targetIdx++) {
                String plantedCulprit = "mod_" + targetIdx;
                ModBisectEngine engine = new ModBisectEngine(graph, allMods, baseMods);

                int steps = 0;
                while (engine.hasMoreSteps() && steps < 25) {
                    steps++;
                    List<String> part = engine.computeNextPartition();
                    boolean crashes = part.contains(plantedCulprit);
                    engine.recordVerdict(crashes ? BisectVerdict.FAIL : BisectVerdict.PASS);
                }

                assertTrue(engine.isFinished(), "Engine must finish for planted culprit " + plantedCulprit);
                assertEquals(plantedCulprit, engine.getCulprit(), "Failed to isolate culprit " + plantedCulprit);
            }
        }

        @Test
        @DisplayName("Zero suspects (all mods are fixed base) finishes immediately")
        void testZeroSuspects() {
            List<String> mods = List.of("lw_lazylib", "MagicLib");
            ModDependencyGraph graph = new ModDependencyGraph(mods, Map.of());
            ModBisectEngine engine = new ModBisectEngine(graph, mods, Set.of("lw_lazylib", "MagicLib"));

            assertFalse(engine.hasMoreSteps());
            assertEquals(0, engine.computeNextPartition().size());
            assertNull(engine.getCulprit());
        }

        @Test
        @DisplayName("Single suspect mod verified in exactly 1 test step")
        void testSingleSuspectMod() {
            List<String> mods = List.of("lw_lazylib", "lone_suspect");
            ModDependencyGraph graph = new ModDependencyGraph(mods, Map.of(
                    "lone_suspect", Set.of("lw_lazylib")
            ));
            ModBisectEngine engine = new ModBisectEngine(graph, mods, Set.of("lw_lazylib"));

            assertTrue(engine.hasMoreSteps());
            List<String> part = engine.computeNextPartition();
            assertEquals(Set.of("lw_lazylib", "lone_suspect"), new HashSet<>(part));

            engine.recordVerdict(BisectVerdict.FAIL);
            assertTrue(engine.isFinished());
            assertEquals("lone_suspect", engine.getCulprit());
        }
    }

    // =========================================================================
    // 5. Step Progression With Skips & Edge Cases
    // =========================================================================

    @Nested
    @DisplayName("Adversarial Dimension 5: Step Progression With Skips")
    class SkipProgressionChallenges {

        @Test
        @DisplayName("Repeated skips do not loop infinitely or corrupt state")
        void testRepeatedSkips() {
            List<String> mods = List.of("m1", "m2", "m3", "m4", "m5", "m6");
            ModDependencyGraph graph = new ModDependencyGraph(mods, Map.of());
            ModBisectEngine engine = new ModBisectEngine(graph, mods, Set.of());

            for (int i = 0; i < 10; i++) {
                List<String> part = engine.computeNextPartition();
                assertNotNull(part);
                assertFalse(part.isEmpty());
                engine.recordVerdict(BisectVerdict.SKIP);
                assertEquals(i + 1, engine.getSkipCount());
            }

            // After 10 skips, regular FAIL converges
            while (engine.hasMoreSteps()) {
                List<String> part = engine.computeNextPartition();
                engine.recordVerdict(part.contains("m3") ? BisectVerdict.FAIL : BisectVerdict.PASS);
            }
            assertTrue(engine.isFinished());
            assertEquals("m3", engine.getCulprit());
        }

        @Test
        @DisplayName("Skip on single suspect verification step")
        void testSkipOnSingleSuspectVerification() {
            List<String> mods = List.of("base", "target");
            ModDependencyGraph graph = new ModDependencyGraph(mods, Map.of("target", Set.of("base")));
            ModBisectEngine engine = new ModBisectEngine(graph, mods, Set.of("base"));

            List<String> p1 = engine.computeNextPartition();
            assertEquals(List.of("base", "target"), p1);

            engine.recordVerdict(BisectVerdict.SKIP);
            assertFalse(engine.isFinished());
            assertEquals(1, engine.getSkipCount());

            List<String> p2 = engine.computeNextPartition();
            assertEquals(List.of("base", "target"), p2);

            engine.recordVerdict(BisectVerdict.FAIL);
            assertTrue(engine.isFinished());
            assertEquals("target", engine.getCulprit());
        }
    }

    // =========================================================================
    // 6. Corrupted Session Files & Power Loss Recovery
    // =========================================================================

    @Nested
    @DisplayName("Adversarial Dimension 6: Corrupted Session Files & Power Loss Recovery")
    class CorruptedSessionAndPowerLossChallenges {

        @Test
        @DisplayName("Truncated and malformed session JSON handled gracefully as null")
        void testTruncatedSessionJson() throws IOException {
            writeEnabledMods(List.of("lw_lazylib", "mod_1", "mod_2"));
            createModOnDisk("lw_lazylib", List.of());
            createModOnDisk("mod_1", List.of("lw_lazylib"));
            createModOnDisk("mod_2", List.of("lw_lazylib"));

            ModBisectSession session = ModBisectSession.start(installRoot, preflightHomeDir, null);
            assertNotNull(session);
            assertTrue(Files.exists(sessionFile));

            // Power loss simulation: truncate session file
            Files.writeString(sessionFile, "{\"format\": \"starsector-preflight-bisect-session-v1\", \"state\": ");

            ModBisectSession reloaded = ModBisectSession.load(sessionFile);
            assertNull(reloaded, "Corrupted JSON must be loaded safely as null");
        }

        @Test
        @DisplayName("Session file with unknown/wrong format version rejected")
        void testWrongFormatVersionRejected() throws IOException {
            String fakeJson = """
                    {
                      "format": "starsector-preflight-bisect-session-v999",
                      "sessionId": "fake-1",
                      "state": "TESTING"
                    }
                    """;
            Files.writeString(sessionFile, fakeJson);

            ModBisectSession session = ModBisectSession.load(sessionFile);
            assertNull(session, "Wrong format version must return null");
        }

        @Test
        @DisplayName("Power loss mid-bisect restores step progress and backup cleanly")
        void testPowerLossMidBisectRecovery() throws Exception {
            List<String> initial = List.of("lw_lazylib", "mod_a", "mod_b", "mod_c", "mod_d");
            writeEnabledMods(initial);
            for (String mod : initial) {
                createModOnDisk(mod, mod.equals("lw_lazylib") ? List.of() : List.of("lw_lazylib"));
            }

            // Step 1: start
            ModBisectSession session1 = ModBisectSession.start(installRoot, preflightHomeDir, null);
            assertEquals(1, session1.stepNumber());

            // Step 2: record bad verdict
            ModBisectSession session2 = session1.recordVerdict("bad", preflightHomeDir);
            assertEquals(2, session2.stepNumber());

            // Simulate application crash / restart: load from disk
            ModBisectSession recovered = ModBisectSession.load(sessionFile);
            assertNotNull(recovered);
            assertEquals(2, recovered.stepNumber());
            assertEquals(BisectStatus.TESTING, recovered.state());
            assertEquals(1, recovered.history().size());
            assertEquals("FAIL", recovered.history().get(0).verdict());
            assertTrue(Files.exists(recovered.backupFile()), "Backup file must persist");

            // Continue to culprit isolation
            ModBisectSession session3 = recovered.recordVerdict("bad", preflightHomeDir);
            if (session3.state() == BisectStatus.TESTING) {
                session3 = session3.recordVerdict("bad", preflightHomeDir);
            }

            assertEquals(BisectStatus.CULPRIT_FOUND, session3.state());
            assertNotNull(session3.candidateCulpritId());

            // Apply fix: disables culprit and removes backup & session
            session3.apply(true, preflightHomeDir);
            assertFalse(Files.exists(sessionFile));

            List<String> finalMods = JsonText.stringArray(Files.readString(enabledModsFile), "enabledMods");
            assertFalse(finalMods.contains(session3.candidateCulpritId()));
            assertTrue(finalMods.contains("lw_lazylib"));
        }

        @Test
        @DisplayName("Bisect reset restores original enabled_mods.json even if dirty")
        void testResetRestoresDirtyEnabledMods() throws Exception {
            List<String> initial = List.of("lw_lazylib", "mod_1", "mod_2", "mod_3");
            writeEnabledMods(initial);
            for (String mod : initial) {
                createModOnDisk(mod, List.of());
            }

            ModBisectSession session = ModBisectSession.start(installRoot, preflightHomeDir, null);

            // Dirty modifications to enabled_mods.json
            Files.writeString(enabledModsFile, "{\"enabledMods\": [\"malformed_external_edit\"]}");

            // Reset
            session.reset(preflightHomeDir);

            assertFalse(Files.exists(sessionFile));
            List<String> restored = JsonText.stringArray(Files.readString(enabledModsFile), "enabledMods");
            assertEquals(initial, restored, "Original enabled_mods.json must be restored exactly on reset");
        }
    }

    // =========================================================================
    // 7. Heuristic Log Crash Verdict Classifier
    // =========================================================================

    @Nested
    @DisplayName("Adversarial Dimension 7: Heuristic Log Crash Verdict Detection")
    class LogVerdictDetectionChallenges {

        @Test
        @DisplayName("Detects all standard crash signatures accurately")
        void testCrashSignatures() {
            assertEquals("bad", ModBisectSession.detectVerdictFromLog("ERROR com.fs.starfarer.combat.CombatMain - crash"));
            assertEquals("bad", ModBisectSession.detectVerdictFromLog("FATAL: Out of memory"));
            assertEquals("bad", ModBisectSession.detectVerdictFromLog("Exception in thread \"main\" java.lang.RuntimeException"));
            assertEquals("bad", ModBisectSession.detectVerdictFromLog("java.lang.NullPointerException: null"));
            assertEquals("bad", ModBisectSession.detectVerdictFromLog("java.lang.ClassNotFoundException: com.mod.Core"));
            assertEquals("bad", ModBisectSession.detectVerdictFromLog("java.lang.NoSuchMethodError: method"));
            assertEquals("bad", ModBisectSession.detectVerdictFromLog("java.lang.OutOfMemoryError: Java heap space"));
        }

        @Test
        @DisplayName("Detects clean launch signatures accurately")
        void testCleanLaunchSignatures() {
            assertEquals("good", ModBisectSession.detectVerdictFromLog("INFO com.fs.starfarer.StarfarerLauncher - Main menu ready"));
            assertEquals("good", ModBisectSession.detectVerdictFromLog("INFO Starting Starsector 0.97a"));
            assertEquals("good", ModBisectSession.detectVerdictFromLog("Launcher displayed"));
        }

        @Test
        @DisplayName("Ambiguous or null logs return 'skip'")
        void testAmbiguousLogsFallbackToSkip() {
            assertEquals("skip", ModBisectSession.detectVerdictFromLog(null));
            assertEquals("skip", ModBisectSession.detectVerdictFromLog(""));
            assertEquals("skip", ModBisectSession.detectVerdictFromLog("Some random debug message without markers"));
        }
    }

    // =========================================================================
    // 8. Serialization Round-Trip Invariants & ModInfo Dependency Extraction
    // =========================================================================

    @Nested
    @DisplayName("Adversarial Dimension 8: Serialization Invariants & Extraction")
    class SerializationAndExtractionChallenges {

        @Test
        @DisplayName("ModBisectSession toJson and fromJson lossless round-trip invariant")
        void testSessionSerializationLosslessRoundTrip() {
            Instant now = Instant.now();
            List<BisectStep> history = List.of(
                    new BisectStep(1, now.minusSeconds(10), List.of("m1", "m2"), "PASS", "note1"),
                    new BisectStep(2, now.minusSeconds(5), List.of("m3", "m4"), "FAIL", "note2")
            );

            ModBisectSession original = new ModBisectSession(
                    ModBisectSession.FORMAT,
                    "session-roundtrip-123",
                    installRoot,
                    now.minusSeconds(60),
                    now,
                    BisectStatus.TESTING,
                    List.of("m1", "m2", "m3", "m4", "m5"),
                    List.of("m1"),
                    Set.of("m3", "m4"),
                    Set.of("m1", "m2"),
                    List.of("m3"),
                    3,
                    4,
                    history,
                    null,
                    tempDir.resolve("backup.json"),
                    true
            );

            String json = original.toJson();
            assertNotNull(json);

            ModBisectSession restored = ModBisectSession.fromJson(json);
            assertNotNull(restored);
            assertEquals(original.format(), restored.format());
            assertEquals(original.sessionId(), restored.sessionId());
            assertEquals(original.state(), restored.state());
            assertEquals(original.initialEnabledMods(), restored.initialEnabledMods());
            assertEquals(original.fixedBaseMods(), restored.fixedBaseMods());
            assertEquals(original.suspectMods(), restored.suspectMods());
            assertEquals(original.eliminatedGoodMods(), restored.eliminatedGoodMods());
            assertEquals(original.currentTestSubset(), restored.currentTestSubset());
            assertEquals(original.stepNumber(), restored.stepNumber());
            assertEquals(original.totalEstimatedSteps(), restored.totalEstimatedSteps());
            assertEquals(original.history().size(), restored.history().size());
            assertEquals(original.history().get(0).verdict(), restored.history().get(0).verdict());
            assertEquals(original.active(), restored.active());
        }

        @Test
        @DisplayName("ModDependencyGraph extraction supports mixed dependency formats")
        void testMixedDependencyFormatExtraction() throws IOException {
            Path modFolder = modsDir.resolve("complex_mod");
            Files.createDirectories(modFolder);

            // Mix array of string and array of object dependencies
            String modInfoContent = """
                    {
                      "id": "complex_mod",
                      "name": "Complex Mod",
                      "dependencies": [
                        "dep_as_string_1",
                        {"id": "dep_as_object_2", "version": "1.0"},
                        {"id": "", "version": "invalid"},
                        12345,
                        null
                      ],
                      "requiredMods": [
                        "dep_from_required_mods_3"
                      ]
                    }
                    """;
            Files.writeString(modFolder.resolve("mod_info.json"), modInfoContent);

            ModDependencyGraph graph = ModDependencyGraph.fromInstallation(installRoot, List.of("complex_mod"));
            Set<String> deps = graph.getDependencies("complex_mod");

            assertTrue(deps.contains("dep_as_string_1"), "Must extract string array dependencies");
            assertTrue(deps.contains("dep_as_object_2"), "Must extract object id dependencies");
            assertTrue(deps.contains("dep_from_required_mods_3"), "Must extract requiredMods dependencies");
            assertFalse(deps.contains(""), "Must ignore empty id");
        }

        @Test
        @DisplayName("BisectVerdict parsing covers all aliases and throws on invalid")
        void testBisectVerdictParsing() {
            assertEquals(BisectVerdict.PASS, BisectVerdict.parse("pass"));
            assertEquals(BisectVerdict.PASS, BisectVerdict.parse("GOOD"));
            assertEquals(BisectVerdict.PASS, BisectVerdict.parse("passed"));
            assertEquals(BisectVerdict.PASS, BisectVerdict.parse("success"));

            assertEquals(BisectVerdict.FAIL, BisectVerdict.parse("fail"));
            assertEquals(BisectVerdict.FAIL, BisectVerdict.parse("BAD"));
            assertEquals(BisectVerdict.FAIL, BisectVerdict.parse("failed"));
            assertEquals(BisectVerdict.FAIL, BisectVerdict.parse("crash"));
            assertEquals(BisectVerdict.FAIL, BisectVerdict.parse("crashed"));

            assertEquals(BisectVerdict.SKIP, BisectVerdict.parse("skip"));
            assertEquals(BisectVerdict.SKIP, BisectVerdict.parse("SKIPPED"));

            assertThrows(IllegalArgumentException.class, () -> BisectVerdict.parse(null));
            assertThrows(IllegalArgumentException.class, () -> BisectVerdict.parse("invalid_verdict_xyz"));
        }

        @Test
        @DisplayName("Star topology (1 base mod, 50 leaves) converges in O(log N)")
        void testStarTopologyConvergence() {
            int leafCount = 50;
            List<String> allMods = new ArrayList<>();
            allMods.add("base_core");
            Map<String, Set<String>> deps = new LinkedHashMap<>();

            for (int i = 1; i <= leafCount; i++) {
                String mod = "leaf_" + i;
                allMods.add(mod);
                deps.put(mod, Set.of("base_core"));
            }

            ModDependencyGraph graph = new ModDependencyGraph(allMods, deps);
            String plantedCulprit = "leaf_33";

            ModBisectEngine engine = new ModBisectEngine(graph, allMods, Set.of("base_core"));
            int steps = 0;
            while (engine.hasMoreSteps() && steps < 15) {
                steps++;
                List<String> part = engine.computeNextPartition();
                assertTrue(part.contains("base_core"), "Every partition must include base_core prerequisite");
                boolean crashes = part.contains(plantedCulprit);
                engine.recordVerdict(crashes ? BisectVerdict.FAIL : BisectVerdict.PASS);
            }

            assertTrue(engine.isFinished());
            assertEquals("leaf_33", engine.getCulprit());
            assertTrue(steps <= 8, "50 star leaves must converge in <= 8 steps (log2(50) = 6), took " + steps);
        }
    }
}
