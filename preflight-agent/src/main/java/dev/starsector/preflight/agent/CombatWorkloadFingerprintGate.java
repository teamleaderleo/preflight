package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Conservative report-time comparability gate for the existing combat workload fingerprint. */
final class CombatWorkloadFingerprintGate {
    static final String FORMAT = "starsector-preflight-combat-workload-gate-v1";
    static final String COMPARABLE_CLASS = "COMPARABLE_CLASS";
    static final String DIVERGED = "DIVERGED";
    static final String INCOMPLETE = "INCOMPLETE";

    // Calibrated to the retained B1/A1/A2/B2 1,040-DP cohort: 36.60-39.68 simulated seconds
    // and 122-133 end ships from an exact 102-ship begin fingerprint.
    static final double MAX_SIMULATION_SECONDS_RELATIVE_DELTA = 0.10;
    static final int MAX_END_SHIP_DELTA = 12;

    private CombatWorkloadFingerprintGate() {
    }

    static Map<String, Object> compare(Map<String, Object> left, Map<String, Object> right) {
        Fingerprint a = Fingerprint.read(left);
        Fingerprint b = Fingerprint.read(right);
        List<String> hardFailures = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        require(a.recipeId, "left.recipeId", missing);
        require(b.recipeId, "right.recipeId", missing);
        require(a.beginShips, "left.begin.ships", missing);
        require(b.beginShips, "right.begin.ships", missing);
        require(a.endShips, "left.end.ships", missing);
        require(b.endShips, "right.end.ships", missing);
        require(a.simulationSeconds, "left.combatSecondsElapsed", missing);
        require(b.simulationSeconds, "right.combatSecondsElapsed", missing);
        require(a.endCombatOver, "left.end.combatOver", missing);
        require(b.endCombatOver, "right.end.combatOver", missing);

        if (missing.isEmpty()) {
            if (!a.recipeId.equals(b.recipeId)) {
                hardFailures.add("recipe-id-mismatch");
            }
            if (!a.beginShips.equals(b.beginShips)) {
                hardFailures.add("begin-ship-count-mismatch");
            }
            double simulationDelta = relativeDelta(a.simulationSeconds, b.simulationSeconds);
            if (simulationDelta > MAX_SIMULATION_SECONDS_RELATIVE_DELTA) {
                hardFailures.add("simulated-time-diverged");
            }
            if (Math.abs(a.endShips - b.endShips) > MAX_END_SHIP_DELTA) {
                hardFailures.add("end-ship-count-diverged");
            }
            if (!a.endCombatOver.equals(b.endCombatOver)) {
                hardFailures.add("combat-ended-state-mismatch");
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", FORMAT);
        result.put("classification", missing.isEmpty()
                ? hardFailures.isEmpty() ? COMPARABLE_CLASS : DIVERGED
                : INCOMPLETE);
        result.put("hardFailures", hardFailures);
        result.put("missing", missing);
        result.put("policy", policy());
        result.put("deltas", deltas(a, b));
        result.put("left", a.summary());
        result.put("right", b.summary());
        result.put("claimBoundary",
                "rejects material workload-class drift; does not claim lockstep battle evolution");
        return result;
    }

    static Map<String, Object> summarize(Map<String, Object> workload) {
        return Fingerprint.read(workload).summary();
    }

    private static Map<String, Object> policy() {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("recipeId", "exact");
        policy.put("beginShips", "exact");
        policy.put("combatSecondsElapsedRelativeDeltaMax",
                MAX_SIMULATION_SECONDS_RELATIVE_DELTA);
        policy.put("endShipsAbsoluteDeltaMax", MAX_END_SHIP_DELTA);
        policy.put("endCombatOver", "exact");
        policy.put("calibration",
                "retained 2026-08-28 B1/A1/A2/B2 symmetric 1,040-DP cohort");
        policy.put("softAxes",
                List.of("fighters", "hulks", "missiles", "projectiles", "aliveHullFractionSum",
                        "aliveFluxFractionSum", "sideZero", "sideOne"));
        return policy;
    }

    private static Map<String, Object> deltas(Fingerprint a, Fingerprint b) {
        Map<String, Object> deltas = new LinkedHashMap<>();
        deltas.put("beginShips", difference(a.beginShips, b.beginShips));
        deltas.put("endShips", difference(a.endShips, b.endShips));
        deltas.put("combatSecondsElapsed", difference(a.simulationSeconds, b.simulationSeconds));
        deltas.put("combatSecondsRelative", a.simulationSeconds == null || b.simulationSeconds == null
                ? null : relativeDelta(a.simulationSeconds, b.simulationSeconds));
        deltas.put("beginMissiles", difference(a.beginMissiles, b.beginMissiles));
        deltas.put("endMissiles", difference(a.endMissiles, b.endMissiles));
        deltas.put("beginProjectiles", difference(a.beginProjectiles, b.beginProjectiles));
        deltas.put("endProjectiles", difference(a.endProjectiles, b.endProjectiles));
        deltas.put("beginFighters", difference(a.beginFighters, b.beginFighters));
        deltas.put("endFighters", difference(a.endFighters, b.endFighters));
        deltas.put("beginHulks", difference(a.beginHulks, b.beginHulks));
        deltas.put("endHulks", difference(a.endHulks, b.endHulks));
        return deltas;
    }

    private static Integer difference(Integer left, Integer right) {
        return left == null || right == null ? null : left - right;
    }

    private static Double difference(Double left, Double right) {
        return left == null || right == null ? null : left - right;
    }

    private static double relativeDelta(double left, double right) {
        double scale = Math.max(Math.abs(left), Math.abs(right));
        return scale == 0.0 ? 0.0 : Math.abs(left - right) / scale;
    }

    private static void require(Object value, String path, List<String> missing) {
        if (value == null) missing.add(path);
    }

    private static final class Fingerprint {
        private final String recipeId;
        private final Integer beginShips;
        private final Integer endShips;
        private final Double simulationSeconds;
        private final Boolean endCombatOver;
        private final Integer beginMissiles;
        private final Integer endMissiles;
        private final Integer beginProjectiles;
        private final Integer endProjectiles;
        private final Integer beginFighters;
        private final Integer endFighters;
        private final Integer beginHulks;
        private final Integer endHulks;
        private final Map<String, Object> beginSideZero;
        private final Map<String, Object> beginSideOne;
        private final Map<String, Object> endSideZero;
        private final Map<String, Object> endSideOne;

        private Fingerprint(
                String recipeId,
                Integer beginShips,
                Integer endShips,
                Double simulationSeconds,
                Boolean endCombatOver,
                Integer beginMissiles,
                Integer endMissiles,
                Integer beginProjectiles,
                Integer endProjectiles,
                Integer beginFighters,
                Integer endFighters,
                Integer beginHulks,
                Integer endHulks,
                Map<String, Object> beginSideZero,
                Map<String, Object> beginSideOne,
                Map<String, Object> endSideZero,
                Map<String, Object> endSideOne) {
            this.recipeId = recipeId;
            this.beginShips = beginShips;
            this.endShips = endShips;
            this.simulationSeconds = simulationSeconds;
            this.endCombatOver = endCombatOver;
            this.beginMissiles = beginMissiles;
            this.endMissiles = endMissiles;
            this.beginProjectiles = beginProjectiles;
            this.endProjectiles = endProjectiles;
            this.beginFighters = beginFighters;
            this.endFighters = endFighters;
            this.beginHulks = beginHulks;
            this.endHulks = endHulks;
            this.beginSideZero = beginSideZero;
            this.beginSideOne = beginSideOne;
            this.endSideZero = endSideZero;
            this.endSideOne = endSideOne;
        }

        static Fingerprint read(Map<String, Object> values) {
            Map<String, Object> begin = map(values.get("begin"));
            Map<String, Object> end = map(values.get("end"));
            return new Fingerprint(
                    text(values.get("recipeId")),
                    integer(begin.get("ships")),
                    integer(end.get("ships")),
                    decimal(values.get("combatSecondsElapsed")),
                    bool(end.get("combatOver")),
                    integer(begin.get("missiles")),
                    integer(end.get("missiles")),
                    integer(begin.get("projectiles")),
                    integer(end.get("projectiles")),
                    fighters(begin),
                    fighters(end),
                    hulks(begin),
                    hulks(end),
                    map(begin.get("sideZero")),
                    map(begin.get("sideOne")),
                    map(end.get("sideZero")),
                    map(end.get("sideOne")));
        }

        Map<String, Object> summary() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("recipeId", recipeId);
            result.put("beginShips", beginShips);
            result.put("endShips", endShips);
            result.put("combatSecondsElapsed", simulationSeconds);
            result.put("endCombatOver", endCombatOver);
            result.put("beginMissiles", beginMissiles);
            result.put("endMissiles", endMissiles);
            result.put("beginProjectiles", beginProjectiles);
            result.put("endProjectiles", endProjectiles);
            result.put("beginFighters", beginFighters);
            result.put("endFighters", endFighters);
            result.put("beginHulks", beginHulks);
            result.put("endHulks", endHulks);
            result.put("beginSideZero", beginSideZero);
            result.put("beginSideOne", beginSideOne);
            result.put("endSideZero", endSideZero);
            result.put("endSideOne", endSideOne);
            return result;
        }

        private static Integer fighters(Map<String, Object> snapshot) {
            return sumNested(snapshot, "aliveFighters");
        }

        private static Integer hulks(Map<String, Object> snapshot) {
            return sumNested(snapshot, "hulks");
        }

        private static Integer sumNested(Map<String, Object> snapshot, String key) {
            Integer zero = integer(map(snapshot.get("sideZero")).get(key));
            Integer one = integer(map(snapshot.get("sideOne")).get(key));
            Integer other = integer(map(snapshot.get("otherOwners")).get(key));
            if (zero == null && one == null && other == null) return null;
            return value(zero) + value(one) + value(other);
        }

        private static int value(Integer value) {
            return value == null ? 0 : value;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Double decimal(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static Boolean bool(Object value) {
        return value instanceof Boolean bool ? bool : null;
    }
}
