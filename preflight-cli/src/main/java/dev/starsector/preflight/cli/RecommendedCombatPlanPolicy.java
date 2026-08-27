package dev.starsector.preflight.cli;

import java.util.List;

/** Enables accepted, exact-gated combat plans for the recommended product preset. */
final class RecommendedCombatPlanPolicy {
    static final List<String> PROPERTIES = List.of(
            "preflight.combat.aiTweaksArcCapacity",
            "preflight.combat.aiTweaksAffineVectors",
            "preflight.combat.listenerRangeSnapshotArray",
            "preflight.combat.listenerRangeEmptySnapshot");

    private RecommendedCombatPlanPolicy() {
    }

    static String appendOptions(String existing, OptimizationPreset preset) {
        if (preset != OptimizationPreset.RECOMMENDED) return existing;
        String result = existing == null ? "" : existing.trim();
        for (String property : PROPERTIES) {
            if (!containsProperty(result, property)) {
                result = append(result, "-D" + property + "=true");
            }
        }
        return result;
    }

    private static boolean containsProperty(String options, String property) {
        if (options.isBlank()) return false;
        String prefix = "-D" + property;
        for (String token : options.split("\\s+")) {
            if (token.equals(prefix) || token.startsWith(prefix + "=")) return true;
        }
        return false;
    }

    private static String append(String existing, String option) {
        return existing.isBlank() ? option : existing + " " + option;
    }
}
