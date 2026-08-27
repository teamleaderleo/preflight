package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RecommendedCombatPlanPolicyTest {
    @Test
    void recommendedEnablesEveryAcceptedCombatPlan() {
        String options = RecommendedCombatPlanPolicy.appendOptions(
                "-Dexisting=true", OptimizationPreset.RECOMMENDED);

        for (String property : RecommendedCombatPlanPolicy.PROPERTIES) {
            assertTrue(options.contains("-D" + property + "=true"), property);
        }
        assertEquals(options, RecommendedCombatPlanPolicy.appendOptions(
                options, OptimizationPreset.RECOMMENDED));
    }

    @Test
    void explicitPerPlanChoiceWins() {
        String property = RecommendedCombatPlanPolicy.PROPERTIES.get(0);
        String existing = "-D" + property + "=false";

        String options = RecommendedCombatPlanPolicy.appendOptions(
                existing, OptimizationPreset.RECOMMENDED);

        assertTrue(options.contains(existing));
        assertEquals(1, occurrences(options, "-D" + property));
        for (String other : RecommendedCombatPlanPolicy.PROPERTIES.subList(
                1, RecommendedCombatPlanPolicy.PROPERTIES.size())) {
            assertTrue(options.contains("-D" + other + "=true"), other);
        }
    }

    @Test
    void otherPresetsPreserveTheEnvironmentExactly() {
        for (OptimizationPreset preset : new OptimizationPreset[] {
                OptimizationPreset.CUSTOM,
                OptimizationPreset.CONSERVATIVE,
                OptimizationPreset.OFF}) {
            assertEquals(" -Dexisting=true ", RecommendedCombatPlanPolicy.appendOptions(
                    " -Dexisting=true ", preset));
            assertNull(RecommendedCombatPlanPolicy.appendOptions(null, preset));
        }
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0;
                index = text.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }
}
