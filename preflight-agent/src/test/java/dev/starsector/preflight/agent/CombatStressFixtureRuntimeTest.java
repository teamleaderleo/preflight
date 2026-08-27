package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class CombatStressFixtureRuntimeTest {
    @AfterEach
    void reset() {
        CombatStressFixtureRuntime.reset();
    }

    @Test
    void recipeIsMirroredFastHighTechAndAtLeastFiveHundredDpPerSide() {
        List<Map<String, Object>> recipe = CombatStressFixtureRuntime.recipe();
        Map<String, Integer> counts = new LinkedHashMap<>();
        float deploymentPoints = 0f;
        for (Map<String, Object> row : recipe) {
            String variantId = (String) row.get("variantId");
            counts.merge(variantId, 1, Integer::sum);
            deploymentPoints += (Float) row.get("deploymentPoints");
        }

        assertEquals(CombatStressFixtureRuntime.SHIPS_PER_SIDE, recipe.size());
        assertEquals(520f, deploymentPoints);
        assertEquals(Map.of(
                "odyssey_Balanced", 4,
                "aurora_Balanced", 4,
                "fury_Attack", 4,
                "medusa_Attack", 4,
                "hyperion_Strike", 4,
                "tempest_Attack", 2,
                "scarab_Experimental", 2), counts);
        assertEquals("symmetric-fast-high-tech-1040dp-v1",
                CombatStressFixtureRuntime.RECIPE_ID);
        assertFalse((Boolean) CombatStressFixtureRuntime.telemetry().get("attempted"));
    }

    @Test
    void publicApiMethodRemainsInvocableOnNonPublicImplementation() throws Exception {
        List<Object> receiver = List.of();

        var method = CombatStressFixtureRuntime.exactApi(
                List.class, receiver, "size", int.class);

        assertEquals(List.class, method.getDeclaringClass());
        assertEquals(0, method.invoke(receiver));
        assertThrows(NoSuchMethodException.class, () -> CombatStressFixtureRuntime.exactApi(
                Map.class, receiver, "size", int.class));
    }
}
