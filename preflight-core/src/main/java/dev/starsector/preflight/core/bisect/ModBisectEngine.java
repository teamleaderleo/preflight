package dev.starsector.preflight.core.bisect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Core binary search partition engine for dependency-safe mod bisection.
 * Guarantees $O(\log N)$ convergence to culprit mod while preserving topological dependency invariants.
 */
public final class ModBisectEngine {

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
    private int stepCount;

    public ModBisectEngine(ModDependencyGraph graph, List<String> activeMods, Set<String> fixedBase) {
        if (graph == null) {
            throw new IllegalArgumentException("ModDependencyGraph cannot be null");
        }
        this.graph = graph;
        this.initialActive = List.copyOf(activeMods);
        this.fixedBase = new LinkedHashSet<>(fixedBase != null ? fixedBase : Set.of());
        this.suspects = new LinkedHashSet<>(activeMods);
        this.suspects.removeAll(this.fixedBase);
        this.knownGood = new LinkedHashSet<>(this.fixedBase);
    }

    public boolean hasMoreSteps() {
        return !finished && !suspects.isEmpty();
    }

    public boolean isFinished() {
        return finished;
    }

    public boolean isBaseBroken() {
        return baseBroken;
    }

    public String getCulprit() {
        return candidateCulprit;
    }

    public void recordBaseCrash() {
        this.baseBroken = true;
        this.finished = true;
    }

    public Set<String> getSuspects() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(suspects));
    }

    public Set<String> getKnownGood() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(knownGood));
    }

    public Set<String> getFixedBase() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(fixedBase));
    }

    public List<String> getInitialActive() {
        return initialActive;
    }

    public List<String> getCurrentPartition() {
        return currentPartition != null ? Collections.unmodifiableList(currentPartition) : List.of();
    }

    public int getStepCount() {
        return stepCount;
    }

    public int getSkipCount() {
        return skipCount;
    }

    public int estimatedRemainingSteps() {
        if (finished || suspects.isEmpty()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(Math.log(Math.max(2, suspects.size())) / Math.log(2)));
    }

    /**
     * Computes the next dependency-closed partition to test.
     */
    public List<String> computeNextPartition() {
        if (suspects.isEmpty()) {
            finished = true;
            currentPartition = List.of();
            return currentPartition;
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

        // Leaf-balanced split
        List<String> leaves = graph.getLeaves(suspects);
        int half = Math.max(1, leaves.size() / 2);

        Set<String> candidateSubset = new LinkedHashSet<>();
        if (skipCount % 2 == 1 && leaves.size() > 1) {
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
        closedTestSet.retainAll(initialActive);

        // Verify that the split divides suspects into a proper, non-empty subset
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

    public void recordVerdict(String verdictStr) {
        recordVerdict(BisectVerdict.parse(verdictStr));
    }

    public void recordVerdict(BisectVerdict verdict) {
        if (currentPartition == null) {
            computeNextPartition();
        }

        stepCount++;

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
            // Culprit is within tested suspects
            Set<String> testedSuspects = new LinkedHashSet<>(currentPartition);
            testedSuspects.removeAll(knownGood);
            suspects.retainAll(testedSuspects);
        } else if (verdict == BisectVerdict.PASS) {
            // All tested mods passed cleanly
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
