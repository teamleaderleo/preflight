package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CombatWorkloadFingerprintGateTest {
    @Test
    void retainedStressCohortEnvelopeRemainsComparable() {
        Map<String, Object> a2 = workload(102, 122, 39.090755, false);
        Map<String, Object> b2 = workload(102, 133, 36.600124, false);

        Map<String, Object> result = CombatWorkloadFingerprintGate.compare(a2, b2);

        assertEquals(CombatWorkloadFingerprintGate.COMPARABLE_CLASS,
                result.get("classification"));
        assertTrue(list(result.get("hardFailures")).isEmpty());
    }

    @Test
    void differentStartingBattleClassIsRejected() {
        Map<String, Object> reference = workload(102, 125, 39.0, false);
        Map<String, Object> divergent = workload(90, 124, 39.0, false);

        Map<String, Object> result = CombatWorkloadFingerprintGate.compare(reference, divergent);

        assertEquals(CombatWorkloadFingerprintGate.DIVERGED, result.get("classification"));
        assertTrue(list(result.get("hardFailures")).contains("begin-ship-count-mismatch"));
    }

    @Test
    void materiallyDifferentSimulationProgressIsRejected() {
        Map<String, Object> reference = workload(102, 125, 40.0, false);
        Map<String, Object> divergent = workload(102, 125, 34.0, false);

        Map<String, Object> result = CombatWorkloadFingerprintGate.compare(reference, divergent);

        assertEquals(CombatWorkloadFingerprintGate.DIVERGED, result.get("classification"));
        assertTrue(list(result.get("hardFailures")).contains("simulated-time-diverged"));
    }

    @Test
    void missingEndSnapshotStaysExplicit() {
        Map<String, Object> incomplete = new LinkedHashMap<>();
        incomplete.put("recipeId", CombatStressFixtureRuntime.RECIPE_ID);
        incomplete.put("begin", snapshot(102, false));

        Map<String, Object> result = CombatWorkloadFingerprintGate.compare(
                incomplete, workload(102, 125, 39.0, false));

        assertEquals(CombatWorkloadFingerprintGate.INCOMPLETE, result.get("classification"));
        assertTrue(list(result.get("missing")).contains("left.end.ships"));
    }

    private static Map<String, Object> workload(
            int beginShips, int endShips, double seconds, boolean combatOver) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("recipeId", CombatStressFixtureRuntime.RECIPE_ID);
        values.put("begin", snapshot(beginShips, false));
        values.put("end", snapshot(endShips, combatOver));
        values.put("combatSecondsElapsed", seconds);
        return values;
    }

    private static Map<String, Object> snapshot(int ships, boolean combatOver) {
        return Map.of(
                "ships", ships,
                "missiles", 20,
                "projectiles", 200,
                "combatOver", combatOver,
                "sideZero", side(4, 0),
                "sideOne", side(4, 0),
                "otherOwners", side(0, 0));
    }

    private static Map<String, Object> side(int fighters, int hulks) {
        return Map.of(
                "aliveFighters", fighters,
                "hulks", hulks,
                "aliveNonFighters", 24,
                "aliveHullFractionSum", 20.0,
                "aliveFluxFractionSum", 5.0);
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<String> list(Object value) {
        return (java.util.List<String>) value;
    }
}
