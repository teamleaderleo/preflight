package dev.starsector.preflight.cli;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Deterministic budgeted selector for the access-aware texture-storage experiment. */
final class AccessAwareTextureStoragePlanner {
    static final long MIB = 1024L * 1024L;
    static final List<Long> DEFAULT_BUDGET_BYTES = List.of(
            128L * MIB,
            256L * MIB,
            512L * MIB,
            1024L * MIB);

    private AccessAwareTextureStoragePlanner() {
    }

    static List<Plan> defaultPlans(List<Candidate> candidates) {
        return DEFAULT_BUDGET_BYTES.stream().map(budget -> plan(candidates, budget)).toList();
    }

    static Plan plan(List<Candidate> candidates, long budgetBytes) {
        if (budgetBytes < 0) {
            throw new IllegalArgumentException("Texture experiment budget cannot be negative");
        }
        List<Candidate> ranked = candidates.stream()
                .filter(candidate -> candidate.extraBytes() > 0 && candidate.savedNanos() > 0)
                .sorted(candidateOrder())
                .toList();
        List<Candidate> selected = new ArrayList<>();
        long used = 0;
        long saved = 0;
        for (Candidate candidate : ranked) {
            if (candidate.extraBytes() > budgetBytes - used) {
                continue;
            }
            selected.add(candidate);
            used = Math.addExact(used, candidate.extraBytes());
            saved = saturatedAdd(saved, candidate.savedNanos());
        }
        return new Plan(budgetBytes, used, saved, List.copyOf(selected));
    }

    private static Comparator<Candidate> candidateOrder() {
        return (left, right) -> {
            double leftScore = left.savedNanos() / (double) left.extraBytes();
            double rightScore = right.savedNanos() / (double) right.extraBytes();
            int score = Double.compare(rightScore, leftScore);
            if (score != 0) {
                return score;
            }
            int saved = Long.compare(right.savedNanos(), left.savedNanos());
            if (saved != 0) {
                return saved;
            }
            int bytes = Long.compare(left.extraBytes(), right.extraBytes());
            if (bytes != 0) {
                return bytes;
            }
            return left.identity().compareTo(right.identity());
        };
    }

    private static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    record Candidate(
            String identity,
            String balancedPath,
            String rawPath,
            long extraBytes,
            long balancedMedianNanos,
            long rawMedianNanos) {
        Candidate {
            if (identity == null || identity.isBlank()
                    || balancedPath == null || balancedPath.isBlank()
                    || rawPath == null || rawPath.isBlank()) {
                throw new IllegalArgumentException("Texture experiment candidate identity and paths are required");
            }
            if (extraBytes < 0 || balancedMedianNanos < 0 || rawMedianNanos < 0) {
                throw new IllegalArgumentException("Texture experiment candidate measurements cannot be negative");
            }
        }

        long savedNanos() {
            return Math.max(0, balancedMedianNanos - rawMedianNanos);
        }

        double savedMillisPerAdditionalMib() {
            if (extraBytes == 0) {
                return Double.POSITIVE_INFINITY;
            }
            return (savedNanos() / 1_000_000.0) / (extraBytes / (double) MIB);
        }
    }

    record Plan(
            long budgetBytes,
            long usedBytes,
            long estimatedSavedNanos,
            List<Candidate> selected) {
        Plan {
            selected = List.copyOf(selected);
        }
    }
}
