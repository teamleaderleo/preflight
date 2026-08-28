package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.combat.CombatEngine;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class CombatStressFixtureRuntimeTest {
    @BeforeEach
    void installSettings() {
        Global.setSettings(new SettingsAPI() {
            @Override
            public List<String> getAllVariantIds() {
                return CombatStressFixtureRuntime.recipe().stream()
                        .map(row -> (String) row.get("variantId"))
                        .distinct()
                        .toList();
            }

            @Override
            public ShipVariantAPI getVariant(String id) {
                return null;
            }

            @Override
            public boolean doesVariantExist(String id) {
                return getAllVariantIds().contains(id);
            }
        });
    }

    @AfterEach
    void reset() {
        CombatStressFixtureRuntime.reset();
        Global.setSettings(null);
    }

    @Test
    void recipeIsMirroredHighTechAndAtLeastFiveHundredDpPerSide() {
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
        assertEquals(null, CombatStressFixtureRuntime.workloadTelemetry().get("begin"));
        assertEquals(null, CombatStressFixtureRuntime.workloadTelemetry().get("end"));
    }

    @Test
    void preparesMirroredFixtureAndCapturesComparableWorkloadWindow() throws Exception {
        CombatEngine engine = new CombatEngine();

        CombatStressFixtureRuntime.Result result = CombatStressFixtureRuntime.prepare(engine);

        assertFalse(result.beforePaused());
        assertTrue(result.afterPaused());
        assertTrue(result.detail().contains(CombatStressFixtureRuntime.RECIPE_ID));
        assertTrue(engine.isPaused());
        assertFalse(engine.doNotEndCombat());
        assertNotNull(engine.playerShip());
        assertEquals(24, engine.deployedCount(0));
        assertEquals(24, engine.deployedCount(1));
        Map<String, Object> fixture = CombatStressFixtureRuntime.telemetry();
        assertTrue((Boolean) fixture.get("attempted"));
        assertTrue((Boolean) fixture.get("prepared"));
        assertEquals(24, fixture.get("spawnedSideZero"));
        assertEquals(24, fixture.get("spawnedSideOne"));
        assertEquals(520f, fixture.get("deploymentPointsSideZero"));
        assertEquals(520f, fixture.get("deploymentPointsSideOne"));

        engine.addMissile(new Object());
        engine.addProjectile(new Object());
        CombatStressFixtureRuntime.captureWorkloadBegin(engine);
        engine.advanceTime(7.5f);
        engine.destroyOneNonFighter(0);
        String endDetail = CombatStressFixtureRuntime.captureWorkloadEnd(engine);

        assertTrue(endDetail.contains("7.500 game seconds"), endDetail);
        Map<String, Object> workload = CombatStressFixtureRuntime.workloadTelemetry();
        assertEquals(7.5f, ((Number) workload.get("combatSecondsElapsed")).floatValue(), 0.001f);
        assertEquals(1, workload.get("sideZeroNonFighterLosses"));
        assertEquals(0, workload.get("sideOneNonFighterLosses"));
        @SuppressWarnings("unchecked")
        Map<String, Object> begin = (Map<String, Object>) workload.get("begin");
        @SuppressWarnings("unchecked")
        Map<String, Object> end = (Map<String, Object>) workload.get("end");
        assertEquals(48, begin.get("ships"));
        assertEquals(1, begin.get("missiles"));
        assertEquals(1, begin.get("projectiles"));
        assertEquals(24, ((Map<?, ?>) begin.get("sideZero")).get("aliveNonFighters"));
        assertEquals(23, ((Map<?, ?>) end.get("sideZero")).get("aliveNonFighters"));
        assertThrows(IllegalStateException.class,
                () -> CombatStressFixtureRuntime.captureWorkloadBegin(engine));
        assertThrows(IllegalStateException.class,
                () -> CombatStressFixtureRuntime.captureWorkloadEnd(engine));
        assertThrows(IllegalStateException.class,
                () -> CombatStressFixtureRuntime.prepare(engine));
    }

    @Test
    void failedSpawnRollsBackNewShipsAndRestoresCombatControls() {
        CombatEngine engine = new CombatEngine();
        engine.failSpawnsAfter(0, 3);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> CombatStressFixtureRuntime.prepare(engine));

        assertTrue(failure.getMessage().contains("synthetic-spawn-failure"));
        assertEquals(0, engine.deployedCount(0));
        assertEquals(0, engine.deployedCount(1));
        assertFalse(engine.isPaused());
        assertFalse(engine.doNotEndCombat());
        Map<String, Object> telemetry = CombatStressFixtureRuntime.telemetry();
        assertTrue((Boolean) telemetry.get("attempted"));
        assertFalse((Boolean) telemetry.get("prepared"));
        assertTrue(telemetry.get("problem").toString().contains("synthetic-spawn-failure"));
    }

    @Test
    void rejectsNonSimulationAndInvalidMapBeforeCommittingFixture() throws Exception {
        CombatEngine nonSimulation = new CombatEngine();
        nonSimulation.setSimulation(false);
        IllegalStateException simulation = assertThrows(
                IllegalStateException.class,
                () -> CombatStressFixtureRuntime.prepare(nonSimulation));
        assertEquals("combat-stress-fixture-requires-simulation", simulation.getMessage());

        CombatStressFixtureRuntime.reset();
        CombatEngine invalidMap = new CombatEngine();
        invalidMap.setMapSize(2_000f, 1_000f);
        IllegalStateException map = assertThrows(
                IllegalStateException.class,
                () -> CombatStressFixtureRuntime.prepare(invalidMap));
        assertEquals("combat-stress-map-size-invalid", map.getMessage());
        assertFalse(invalidMap.isPaused());
        assertFalse(invalidMap.doNotEndCombat());
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
