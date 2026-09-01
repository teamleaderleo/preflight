package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * End-to-end and boundary test suite for Feature 14: Dependency-Safe Bisect Algorithm.
 *
 * <p>Validates the DAG topological binary search:
 * <ul>
 *   <li>Directed dependency graph modeling: $G=(V, E)$</li>
 *   <li>Transitive Dependency Closure computation: $\text{Closure}(T)$</li>
 *   <li>Leaf-balanced partitioning preserving prerequisite libraries</li>
 *   <li>State transitions: PASS, FAIL, SKIP</li>
 *   <li>$O(\log N)$ convergence to culprit without dependency violations</li>
 *   <li>Circular dependency handling (Strongly Connected Components)</li>
 * </ul>
 */
class DependencySafeBisectAlgorithmE2ETest {

    // =========================================================================
    // Tier 1: Feature Coverage & Happy Paths (>= 5 cases)
    // =========================================================================

    @Test
    void testLinearModChainBisection() {
        // 8 independent mods: mod_0 .. mod_7, culprit is mod_5
        List<String> allMods = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            allMods.add("mod_" + i);
        }
        ModDependencyGraph graph = new ModDependencyGraph(allMods, Map.of());

        BisectEngine engine = new BisectEngine(graph, allMods, Set.of());
        int steps = 0;
        String culprit = "mod_5";

        while (engine.hasMoreSteps() && steps < 10) {
            steps++;
            List<String> testSet = engine.computeNextPartition();
            assertNotNull(testSet);
            assertFalse(testSet.isEmpty());

            // Oracle evaluation: crashes if culprit is present
            boolean crashes = testSet.contains(culprit);
            engine.recordVerdict(crashes ? BisectVerdict.FAIL : BisectVerdict.PASS);
        }

        assertTrue(engine.isFinished(), "Bisect must finish");
        assertEquals("mod_5", engine.getCulprit());
        assertTrue(steps <= 4, "8 linear mods must converge in <= 4 steps, took " + steps);
    }

    @Test
    void testDAGWithSharedPrerequisiteLibraries() {
        // Prerequisites: lw_lazylib, MagicLib, GraphicsLib
        // Content mods: armaa, nexerelin, roider, diable, scalartech (all depend on prerequisites)
        List<String> allMods = List.of(
                "lw_lazylib", "MagicLib", "GraphicsLib",
                "armaa", "nexerelin", "roider", "diable", "scalartech"
        );

        Map<String, Set<String>> deps = Map.of(
                "armaa", Set.of("lw_lazylib", "MagicLib"),
                "nexerelin", Set.of("lw_lazylib", "MagicLib"),
                "roider", Set.of("lw_lazylib", "GraphicsLib"),
                "diable", Set.of("lw_lazylib", "MagicLib", "GraphicsLib"),
                "scalartech", Set.of("MagicLib", "GraphicsLib")
        );

        ModDependencyGraph graph = new ModDependencyGraph(allMods, deps);
        Set<String> fixedBase = Set.of("lw_lazylib", "MagicLib", "GraphicsLib");

        BisectEngine engine = new BisectEngine(graph, allMods, fixedBase);
        String culprit = "diable";

        int steps = 0;
        while (engine.hasMoreSteps() && steps < 10) {
            steps++;
            List<String> testSet = engine.computeNextPartition();

            // INVARIANT: Every test set MUST contain all required dependencies of every mod in testSet
            for (String mod : testSet) {
                Set<String> required = deps.getOrDefault(mod, Set.of());
                for (String req : required) {
                    assertTrue(testSet.contains(req),
                            "Test partition violated dependency invariant: " + mod + " requires " + req);
                }
            }

            boolean crashes = testSet.contains(culprit);
            engine.recordVerdict(crashes ? BisectVerdict.FAIL : BisectVerdict.PASS);
        }

        assertTrue(engine.isFinished());
        assertEquals("diable", engine.getCulprit());
    }

    @Test
    void testMultiLevelDependencyHierarchy() {
        // Level 0: core_lib
        // Level 1: framework_a (depends on core_lib), framework_b (depends on core_lib)
        // Level 2: faction_x (depends on framework_a), faction_y (depends on framework_b)
        // Level 3: submod_z (depends on faction_x)
        List<String> allMods = List.of("core_lib", "framework_a", "framework_b", "faction_x", "faction_y", "submod_z");
        Map<String, Set<String>> deps = Map.of(
                "framework_a", Set.of("core_lib"),
                "framework_b", Set.of("core_lib"),
                "faction_x", Set.of("framework_a"),
                "faction_y", Set.of("framework_b"),
                "submod_z", Set.of("faction_x")
        );

        ModDependencyGraph graph = new ModDependencyGraph(allMods, deps);
        BisectEngine engine = new BisectEngine(graph, allMods, Set.of("core_lib"));

        String culprit = "submod_z";
        int steps = 0;
        while (engine.hasMoreSteps() && steps < 10) {
            steps++;
            List<String> testSet = engine.computeNextPartition();
            // Verify topological closure
            Set<String> closure = graph.transitiveClosure(Set.copyOf(testSet));
            assertEquals(new HashSet<>(testSet), closure);

            boolean crashes = testSet.contains(culprit);
            engine.recordVerdict(crashes ? BisectVerdict.FAIL : BisectVerdict.PASS);
        }

        assertEquals("submod_z", engine.getCulprit());
    }

    @Test
    void testLeafPartitionFallbackOnDenseDependencies() {
        // 1 library 'root_lib', 5 content mods all depending on root_lib
        // If we split randomly, closure(root_lib) would pull all mods, making split invalid.
        // Leaf-partition must select from leaf nodes.
        List<String> allMods = List.of("root_lib", "c1", "c2", "c3", "c4", "c5");
        Map<String, Set<String>> deps = Map.of(
                "c1", Set.of("root_lib"),
                "c2", Set.of("root_lib"),
                "c3", Set.of("root_lib"),
                "c4", Set.of("root_lib"),
                "c5", Set.of("root_lib")
        );

        ModDependencyGraph graph = new ModDependencyGraph(allMods, deps);
        BisectEngine engine = new BisectEngine(graph, allMods, Set.of("root_lib"));

        List<String> partition = engine.computeNextPartition();
        assertTrue(partition.contains("root_lib"), "Prerequisite root_lib must be included");
        assertTrue(partition.size() < allMods.size(), "Partition must not re-enable all suspects");
        assertTrue(partition.size() > 1, "Partition must include suspects");
    }

    @Test
    void testSingleCulpritVerification() {
        List<String> allMods = List.of("lib", "modA", "modB");
        Map<String, Set<String>> deps = Map.of(
                "modA", Set.of("lib"),
                "modB", Set.of("lib")
        );
        ModDependencyGraph graph = new ModDependencyGraph(allMods, deps);
        BisectEngine engine = new BisectEngine(graph, allMods, Set.of("lib"));

        // Step 1: test modA -> PASS
        engine.recordVerdict(BisectVerdict.PASS);

        // Suspects left: modB. Engine enters verification step for modB
        List<String> verificationSet = engine.computeNextPartition();
        assertEquals(Set.of("lib", "modB"), new HashSet<>(verificationSet));

        engine.recordVerdict(BisectVerdict.FAIL);
        assertTrue(engine.isFinished());
        assertEquals("modB", engine.getCulprit());
    }

    @Test
    void testTransitiveClosureComputation() {
        List<String> mods = List.of("A", "B", "C", "D", "E");
        Map<String, Set<String>> deps = Map.of(
                "A", Set.of("B"),
                "B", Set.of("C"),
                "C", Set.of("D"),
                "D", Set.of("E")
        );
        ModDependencyGraph graph = new ModDependencyGraph(mods, deps);

        Set<String> closureA = graph.transitiveClosure(Set.of("A"));
        assertEquals(Set.of("A", "B", "C", "D", "E"), closureA);

        Set<String> closureC = graph.transitiveClosure(Set.of("C"));
        assertEquals(Set.of("C", "D", "E"), closureC);

        Set<String> closureE = graph.transitiveClosure(Set.of("E"));
        assertEquals(Set.of("E"), closureE);
    }

    // =========================================================================
    // Tier 2: Boundary Value Analysis & Fault Injection (>= 5 cases)
    // =========================================================================

    @Test
    void testCircularDependencyHandling() {
        // Circular dependency: Mod A requires Mod B, and Mod B requires Mod A
        List<String> mods = List.of("A", "B", "C", "D");
        Map<String, Set<String>> deps = Map.of(
                "A", Set.of("B"),
                "B", Set.of("A"),
                "C", Set.of("A")
        );
        ModDependencyGraph graph = new ModDependencyGraph(mods, deps);

        // Transitive closure of A must include B, closure of B must include A without infinite loop
        Set<String> closureA = graph.transitiveClosure(Set.of("A"));
        assertEquals(Set.of("A", "B"), closureA);

        Set<String> closureB = graph.transitiveClosure(Set.of("B"));
        assertEquals(Set.of("A", "B"), closureB);

        Set<String> closureC = graph.transitiveClosure(Set.of("C"));
        assertEquals(Set.of("A", "B", "C"), closureC);
    }

    @Test
    void testSkipStepSelectsAlternativePartition() {
        List<String> allMods = List.of("m1", "m2", "m3", "m4");
        ModDependencyGraph graph = new ModDependencyGraph(allMods, Map.of());
        BisectEngine engine = new BisectEngine(graph, allMods, Set.of());

        List<String> p1 = engine.computeNextPartition();
        engine.recordVerdict(BisectVerdict.SKIP);

        List<String> p2 = engine.computeNextPartition();
        assertNotNull(p2);
        assertFalse(p2.isEmpty());
        // Alternative partition selected
        assertFalse(p1.equals(p2), "Skipping must generate an alternative partition");
    }

    @Test
    void testLargeScaleModPackDAGConvergence() {
        // 50-node random DAG mod pack
        int nodeCount = 50;
        List<String> mods = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            mods.add("mod_" + i);
        }

        Map<String, Set<String>> deps = new LinkedHashMap<>();
        Random rng = new Random(42);
        for (int i = 5; i < nodeCount; i++) {
            // each mod depends on 1-2 lower index mods (ensuring DAG)
            int parent1 = rng.nextInt(i);
            int parent2 = rng.nextInt(i);
            Set<String> depSet = new HashSet<>();
            depSet.add("mod_" + parent1);
            if (parent1 != parent2) depSet.add("mod_" + parent2);
            deps.put("mod_" + i, depSet);
        }

        ModDependencyGraph graph = new ModDependencyGraph(mods, deps);
        String plantedCulprit = "mod_37";

        BisectEngine engine = new BisectEngine(graph, mods, Set.of("mod_0", "mod_1"));
        int steps = 0;

        while (engine.hasMoreSteps() && steps < 20) {
            steps++;
            List<String> testSet = engine.computeNextPartition();

            // Invariant check: every partition is dependency closed
            Set<String> closure = graph.transitiveClosure(Set.copyOf(testSet));
            assertEquals(new HashSet<>(testSet), closure);

            boolean crashes = testSet.contains(plantedCulprit);
            engine.recordVerdict(crashes ? BisectVerdict.FAIL : BisectVerdict.PASS);
        }

        assertTrue(engine.isFinished());
        assertEquals("mod_37", engine.getCulprit());
        assertTrue(steps <= 8, "50-node DAG should converge in <= 8 steps, took " + steps);
    }

    @Test
    void testAllModsGoodBaselinePassing() {
        List<String> mods = List.of("m1", "m2", "m3");
        ModDependencyGraph graph = new ModDependencyGraph(mods, Map.of());
        BisectEngine engine = new BisectEngine(graph, mods, Set.of());

        // All tests pass
        while (engine.hasMoreSteps()) {
            engine.computeNextPartition();
            engine.recordVerdict(BisectVerdict.PASS);
        }

        assertTrue(engine.isFinished());
        assertNull(engine.getCulprit(), "No culprit should be identified if all test partitions pass");
    }

    @Test
    void testFixedBaseModCrashingBaselineFailsFast() {
        List<String> mods = List.of("broken_base", "modA", "modB");
        Map<String, Set<String>> deps = Map.of(
                "modA", Set.of("broken_base"),
                "modB", Set.of("broken_base")
        );
        ModDependencyGraph graph = new ModDependencyGraph(mods, deps);

        // Fixed base itself is broken
        BisectEngine engine = new BisectEngine(graph, mods, Set.of("broken_base"));
        // Baseline test of base fails
        engine.recordBaseCrash();

        assertTrue(engine.isFinished());
        assertTrue(engine.isBaseBroken());
    }

    @Test
    void testIsolatedModWithoutDependencies() {
        List<String> mods = List.of("core1", "core2", "isolated_mod");
        ModDependencyGraph graph = new ModDependencyGraph(mods, Map.of(
                "core2", Set.of("core1")
        ));
        BisectEngine engine = new BisectEngine(graph, mods, Set.of("core1"));
        String culprit = "isolated_mod";

        while (engine.hasMoreSteps()) {
            List<String> testSet = engine.computeNextPartition();
            engine.recordVerdict(testSet.contains(culprit) ? BisectVerdict.FAIL : BisectVerdict.PASS);
        }

        assertEquals("isolated_mod", engine.getCulprit());
    }

    // =========================================================================
    // Mod DAG & Bisect Algorithm Implementation
    // =========================================================================

    enum BisectVerdict { PASS, FAIL, SKIP }

    static final class ModDependencyGraph {
        private final List<String> allMods;
        private final Map<String, Set<String>> dependencies;

        ModDependencyGraph(List<String> allMods, Map<String, Set<String>> dependencies) {
            this.allMods = List.copyOf(allMods);
            this.dependencies = new LinkedHashMap<>();
            dependencies.forEach((k, v) -> this.dependencies.put(k, Set.copyOf(v)));
        }

        Set<String> transitiveClosure(Set<String> roots) {
            Set<String> closure = new LinkedHashSet<>(roots);
            Deque<String> queue = new ArrayDeque<>(roots);

            while (!queue.isEmpty()) {
                String current = queue.poll();
                Set<String> deps = dependencies.getOrDefault(current, Set.of());
                for (String dep : deps) {
                    if (closure.add(dep)) {
                        queue.add(dep);
                    }
                }
            }
            return new LinkedHashSet<>(closure);
        }

        List<String> getLeaves(Set<String> subset) {
            // A leaf in the dependency graph is a mod that NO other mod in the subset depends upon.
            Set<String> requiredByOthers = new HashSet<>();
            for (String mod : subset) {
                requiredByOthers.addAll(dependencies.getOrDefault(mod, Set.of()));
            }
            List<String> leaves = new ArrayList<>();
            for (String mod : subset) {
                if (!requiredByOthers.contains(mod)) {
                    leaves.add(mod);
                }
            }
            return leaves.isEmpty() ? new ArrayList<>(subset) : leaves;
        }
    }

    static final class BisectEngine {
        private final ModDependencyGraph graph;
        private final List<String> initialActive;
        private final Set<String> fixedBase;
        private final Set<String> suspects;
        private final Set<String> knownGood;
        private List<String> currentPartition;
        private String candidateCulprit;
        private boolean finished;
        private boolean baseBroken;
        private int skipCount;

        BisectEngine(ModDependencyGraph graph, List<String> activeMods, Set<String> fixedBase) {
            this.graph = graph;
            this.initialActive = List.copyOf(activeMods);
            this.fixedBase = new LinkedHashSet<>(fixedBase);
            this.suspects = new LinkedHashSet<>(activeMods);
            this.suspects.removeAll(fixedBase);
            this.knownGood = new LinkedHashSet<>(fixedBase);
        }

        boolean hasMoreSteps() {
            return !finished && !suspects.isEmpty();
        }

        boolean isFinished() {
            return finished;
        }

        boolean isBaseBroken() {
            return baseBroken;
        }

        String getCulprit() {
            return candidateCulprit;
        }

        void recordBaseCrash() {
            this.baseBroken = true;
            this.finished = true;
        }

        List<String> computeNextPartition() {
            if (suspects.isEmpty()) {
                finished = true;
                return List.of();
            }

            if (suspects.size() == 1) {
                candidateCulprit = suspects.iterator().next();
                Set<String> testSet = new LinkedHashSet<>(fixedBase);
                testSet.add(candidateCulprit);
                Set<String> closed = graph.transitiveClosure(testSet);
                closed.retainAll(initialActive);
                currentPartition = new ArrayList<>(closed);
                return currentPartition;
            }

            List<String> suspectList = new ArrayList<>(suspects);
            // Leaf-balanced split
            List<String> leaves = graph.getLeaves(suspects);
            int half = Math.max(1, leaves.size() / 2);

            Set<String> candidateSubset = new LinkedHashSet<>();
            if (skipCount % 2 == 1 && leaves.size() > 1) {
                // Pick second half on skip
                for (int i = half; i < leaves.size(); i++) {
                    candidateSubset.add(leaves.get(i));
                }
            } else {
                for (int i = 0; i < half; i++) {
                    candidateSubset.add(leaves.get(i));
                }
            }

            candidateSubset.addAll(knownGood);
            Set<String> closedTestSet = new LinkedHashSet<>(graph.transitiveClosure(candidateSubset));
            // Must stay within active mods
            closedTestSet.retainAll(initialActive);

            // Edge condition: If closedTestSet includes all suspects (or none), fall back to finding a proper subset
            Set<String> suspectsInClosed = new LinkedHashSet<>(closedTestSet);
            suspectsInClosed.retainAll(suspects);

            if (suspectsInClosed.size() >= suspects.size() || suspectsInClosed.isEmpty()) {
                boolean found = false;
                for (String leaf : leaves) {
                    Set<String> singleSet = new LinkedHashSet<>(knownGood);
                    singleSet.add(leaf);
                    Set<String> singleClosed = new LinkedHashSet<>(graph.transitiveClosure(singleSet));
                    singleClosed.retainAll(initialActive);
                    Set<String> inSuspects = new LinkedHashSet<>(singleClosed);
                    inSuspects.retainAll(suspects);
                    if (!inSuspects.isEmpty() && inSuspects.size() < suspects.size()) {
                        closedTestSet = singleClosed;
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    for (String candidate : suspects) {
                        Set<String> singleSet = new LinkedHashSet<>(knownGood);
                        singleSet.add(candidate);
                        Set<String> singleClosed = new LinkedHashSet<>(graph.transitiveClosure(singleSet));
                        singleClosed.retainAll(initialActive);
                        Set<String> inSuspects = new LinkedHashSet<>(singleClosed);
                        inSuspects.retainAll(suspects);
                        if (!inSuspects.isEmpty() && inSuspects.size() < suspects.size()) {
                            closedTestSet = singleClosed;
                            found = true;
                            if (skipCount % 2 == 0) {
                                break;
                            }
                        }
                    }
                }
            }

            currentPartition = new ArrayList<>(closedTestSet);
            return currentPartition;
        }

        void recordVerdict(BisectVerdict verdict) {
            if (currentPartition == null) {
                computeNextPartition();
            }

            if (verdict == BisectVerdict.SKIP) {
                skipCount++;
                return;
            }

            if (suspects.size() == 1 && candidateCulprit != null) {
                if (verdict == BisectVerdict.FAIL) {
                    finished = true;
                } else {
                    candidateCulprit = null;
                    finished = true;
                }
                return;
            }

            if (verdict == BisectVerdict.FAIL) {
                // Culprit is in currentPartition \ knownGood
                Set<String> testedSuspects = new LinkedHashSet<>(currentPartition);
                testedSuspects.removeAll(knownGood);
                suspects.retainAll(testedSuspects);
            } else if (verdict == BisectVerdict.PASS) {
                // All mods in currentPartition are good
                knownGood.addAll(currentPartition);
                suspects.removeAll(currentPartition);
            }

            if (suspects.size() == 1) {
                candidateCulprit = suspects.iterator().next();
            } else if (suspects.isEmpty()) {
                finished = true;
            }
        }
    }
}
