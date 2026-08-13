package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AccessAwareTextureStoragePlannerTest {
    @Test
    void spendsBudgetOnHighestMeasuredBenefitPerByteFirst() {
        var high = candidate("high", 32, 8_000_000, 1_000_000);
        var medium = candidate("medium", 64, 10_000_000, 2_000_000);
        var low = candidate("low", 32, 3_000_000, 2_000_000);

        var plan = AccessAwareTextureStoragePlanner.plan(
                List.of(low, medium, high), 96L * AccessAwareTextureStoragePlanner.MIB);

        assertEquals(List.of(high, medium), plan.selected());
        assertEquals(96L * AccessAwareTextureStoragePlanner.MIB, plan.usedBytes());
        assertEquals(15_000_000L, plan.estimatedSavedNanos());
    }

    @Test
    void skipsCandidatesThatDoNotRecoverMeasuredTime() {
        var slowerRaw = candidate("slower", 16, 1_000_000, 2_000_000);
        var equal = candidate("equal", 16, 2_000_000, 2_000_000);

        var plan = AccessAwareTextureStoragePlanner.plan(
                List.of(slowerRaw, equal), 128L * AccessAwareTextureStoragePlanner.MIB);

        assertTrue(plan.selected().isEmpty());
        assertEquals(0, plan.usedBytes());
        assertEquals(0, plan.estimatedSavedNanos());
    }

    @Test
    void usesStableIdentityTieBreakForEquivalentScores() {
        var beta = candidate("beta", 32, 6_000_000, 2_000_000);
        var alpha = candidate("alpha", 32, 6_000_000, 2_000_000);

        var plan = AccessAwareTextureStoragePlanner.plan(
                List.of(beta, alpha), 32L * AccessAwareTextureStoragePlanner.MIB);

        assertEquals(List.of(alpha), plan.selected());
    }

    @Test
    void publishesTheFourExperimentBudgetsExactly() {
        assertEquals(
                List.of(128L, 256L, 512L, 1024L),
                AccessAwareTextureStoragePlanner.DEFAULT_BUDGET_BYTES.stream()
                        .map(bytes -> bytes / AccessAwareTextureStoragePlanner.MIB)
                        .toList());
    }

    private static AccessAwareTextureStoragePlanner.Candidate candidate(
            String identity, long extraMib, long balancedNanos, long rawNanos) {
        return new AccessAwareTextureStoragePlanner.Candidate(
                identity,
                identity + "-lz4.spft",
                identity + ".spft",
                extraMib * AccessAwareTextureStoragePlanner.MIB,
                balancedNanos,
                rawNanos);
    }
}
