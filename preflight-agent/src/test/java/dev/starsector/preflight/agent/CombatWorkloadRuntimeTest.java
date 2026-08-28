package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CombatWorkloadRuntimeTest {
    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void reset() {
        CombatWorkloadRuntime.reset();
        System.clearProperty(CombatWorkloadRuntime.ENABLE_PROPERTY);
        System.clearProperty(CombatWorkloadRuntime.OUTPUT_PROPERTY);
        System.clearProperty(CombatWorkloadRuntime.RUN_ID_PROPERTY);
        System.clearProperty(CombatWorkloadRuntime.CELL_ID_PROPERTY);
        System.clearProperty(CombatWorkloadRuntime.BATTLE_DP_PROPERTY);
        System.clearProperty(CombatWorkloadRuntime.EVERY_PROPERTY);
        System.clearProperty(CombatWorkloadRuntime.DENSITY_SAMPLE_PROPERTY);
    }

    @Test
    @SuppressWarnings("unchecked")
    void samplesPublicWorkloadDensityAiEffectsDpAndAdvanceTime() throws Exception {
        System.setProperty(CombatWorkloadRuntime.ENABLE_PROPERTY, "true");
        System.setProperty(CombatWorkloadRuntime.EVERY_PROPERTY, "1");
        System.setProperty(CombatWorkloadRuntime.DENSITY_SAMPLE_PROPERTY, "2");
        System.setProperty(CombatWorkloadRuntime.RUN_ID_PROPERTY, "run-a");
        System.setProperty(CombatWorkloadRuntime.CELL_ID_PROPERTY, "fighter-heavy-24");
        System.setProperty(CombatWorkloadRuntime.BATTLE_DP_PROPERTY, "1040");

        FakeMissile guided = new FakeMissile(new Object());
        FakeMissile dumb = new FakeMissile(null);
        FakeWeapon beam = new FakeWeapon(true, true, new FakeWeaponEffect());
        FakeWeapon gun = new FakeWeapon(false, false, null);
        FakeShip ship = new FakeShip(false, false, new Object(), List.of(beam, gun), 0, 30f);
        FakeShip fighter = new FakeShip(false, true, new Object(), List.of(gun), 0, 5f);
        FakeShip hulk = new FakeShip(true, false, null, List.of(), 1, 20f);
        FakeEngine engine = new FakeEngine(
                List.of(ship, fighter, hulk),
                List.of(guided, dumb),
                List.of(guided, dumb, new Object(), new Object(), new Object()),
                List.of(new Object(), new Object()));
        engine.effects.add(new FakeExplosionEffect());

        assertEquals(0L, CombatWorkloadRuntime.begin(engine));
        CombatWorkloadRuntime.beginMeasurementWindow();
        long started = CombatWorkloadRuntime.begin(engine);
        assertTrue(started > 0L);
        CombatWorkloadRuntime.end(started);

        Map<String, Object> telemetry = CombatWorkloadRuntime.telemetry();
        assertEquals("run-a", telemetry.get("runId"));
        assertEquals("fighter-heavy-24", telemetry.get("cellId"));
        assertEquals(1040.0, telemetry.get("battleDp"));
        assertEquals(1L, telemetry.get("completedSamples"));
        List<Map<String, Object>> samples = (List<Map<String, Object>>) telemetry.get("samples");
        assertEquals(1, samples.size());
        Map<String, Object> sample = samples.get(0);
        assertEquals(1040.0, sample.get("battleDp"));
        assertEquals(1L, sample.get("ships"));
        assertEquals(1L, sample.get("shipsOwner0"));
        assertEquals(0L, sample.get("shipsOwner1"));
        assertEquals(1L, sample.get("fighters"));
        assertEquals(1L, sample.get("wrecks"));
        assertEquals(30.0, sample.get("liveDeployedDp"));
        assertEquals(30.0, sample.get("liveDeployedDpOwner0"));
        assertEquals(0.0, sample.get("liveDeployedDpOwner1"));
        assertEquals(50.0, sample.get("shipDpPresent"));
        assertEquals(2, sample.get("missiles"));
        assertEquals(3, sample.get("projectiles"));
        assertEquals(2, sample.get("beams"));
        assertEquals(1L, sample.get("shipAi"));
        assertEquals(1L, sample.get("fighterAi"));
        assertEquals(1L, sample.get("missileAi"));
        assertEquals(3L, sample.get("weapons"));
        assertEquals(1L, sample.get("firingWeapons"));
        assertEquals(1L, sample.get("beamWeapons"));
        assertEquals(1L, sample.get("weaponEffectPlugins"));
        assertEquals(7.0, sample.get("nearbyEntitiesMean"));
        assertEquals(3.0, sample.get("nearbyShipsMean"));
        assertEquals(2.0, sample.get("nearbyMissilesMean"));
        assertTrue(((Number) sample.get("effectLikeObjectsHeuristic")).intValue() >= 1);
        assertTrue(((Number) sample.get("advanceMicros")).doubleValue() >= 0.0);
        assertTrue(((Number) sample.get("sampleOverheadMicros")).doubleValue() >= 0.0);

        Path report = temporaryDirectory.resolve("combat-scaling.json");
        CombatWorkloadRuntime.writeReport(report);
        String json = Files.readString(report, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"runId\":\"run-a\""));
        assertTrue(json.contains("\"battleDp\":1040.0"));
        assertTrue(json.contains("\"advanceMicros\""));
    }

    @Test
    void cadenceSkipsUntimedTicksAndKeepsSampleOverheadOutOfAdvanceTimer() {
        System.setProperty(CombatWorkloadRuntime.ENABLE_PROPERTY, "true");
        System.setProperty(CombatWorkloadRuntime.EVERY_PROPERTY, "3");
        FakeEngine engine = new FakeEngine(List.of(), List.of(), List.of(), List.of());

        CombatWorkloadRuntime.beginMeasurementWindow();
        assertEquals(0L, CombatWorkloadRuntime.begin(engine));
        assertEquals(0L, CombatWorkloadRuntime.begin(engine));
        long started = CombatWorkloadRuntime.begin(engine);
        assertTrue(started > 0L);
        CombatWorkloadRuntime.end(started);

        assertEquals(1L, CombatWorkloadRuntime.telemetry().get("completedSamples"));
    }

    @Test
    void densityAnchorsCoverTheFullCandidateOrderDeterministically() {
        List<Object> candidates = new ArrayList<>();
        for (int index = 0; index < 10; index++) candidates.add(index);

        assertEquals(
                List.of(0, 3, 6, 9),
                CombatWorkloadRuntime.selectDensityAnchors(candidates, 4));
        assertEquals(
                List.of(5),
                CombatWorkloadRuntime.selectDensityAnchors(candidates, 1));
    }

    public static final class FakeEngine {
        public final List<Object> effects = new ArrayList<>();
        private final List<?> ships;
        private final List<?> missiles;
        private final List<?> projectiles;
        private final List<?> beams;
        private final FakeGrid allGrid = new FakeGrid(7);
        private final FakeGrid shipGrid = new FakeGrid(3);
        private final FakeGrid missileGrid = new FakeGrid(2);

        FakeEngine(List<?> ships, List<?> missiles, List<?> projectiles, List<?> beams) {
            this.ships = ships;
            this.missiles = missiles;
            this.projectiles = projectiles;
            this.beams = beams;
        }

        public List<?> getShips() {
            return ships;
        }

        public List<?> getMissiles() {
            return missiles;
        }

        public List<?> getProjectiles() {
            return projectiles;
        }

        public List<?> getBeams() {
            return beams;
        }

        public float getTotalElapsedTime(boolean includePaused) {
            return 42.5f;
        }

        public FakeGrid getAllObjectGrid() {
            return allGrid;
        }

        public FakeGrid getAiGridShips() {
            return shipGrid;
        }

        public FakeGrid getAiGridMissiles() {
            return missileGrid;
        }
    }

    public static final class FakeShip {
        private final boolean hulk;
        private final boolean fighter;
        private final Object ai;
        private final List<?> weapons;
        private final Object location = new Object();
        private final int owner;
        private final float deployCost;

        FakeShip(boolean hulk, boolean fighter, Object ai, List<?> weapons, int owner, float deployCost) {
            this.hulk = hulk;
            this.fighter = fighter;
            this.ai = ai;
            this.weapons = weapons;
            this.owner = owner;
            this.deployCost = deployCost;
        }

        public boolean isHulk() {
            return hulk;
        }

        public boolean isFighter() {
            return fighter;
        }

        public Object getAI() {
            return ai;
        }

        public Object getLocation() {
            return location;
        }

        public List<?> getAllWeapons() {
            return weapons;
        }

        public int getOwner() {
            return owner;
        }

        public FakeShip getFleetMember() {
            return this;
        }

        public float getDeploymentPointsCost() {
            return deployCost;
        }
    }

    public static final class FakeMissile {
        private final Object ai;

        FakeMissile(Object ai) {
            this.ai = ai;
        }

        public Object getAI() {
            return ai;
        }
    }

    public static final class FakeWeapon {
        private final boolean firing;
        private final boolean beam;
        private final Object effect;

        FakeWeapon(boolean firing, boolean beam, Object effect) {
            this.firing = firing;
            this.beam = beam;
            this.effect = effect;
        }

        public boolean isFiring() {
            return firing;
        }

        public boolean isBeam() {
            return beam;
        }

        public Object getEffectPlugin() {
            return effect;
        }
    }

    public static final class FakeGrid {
        private final int count;

        FakeGrid(int count) {
            this.count = count;
        }

        public Iterator<Object> getCheckIterator(Object location, float width, float height) {
            List<Object> values = new ArrayList<>();
            for (int index = 0; index < count; index++) values.add(new Object());
            return values.iterator();
        }
    }

    public static final class FakeWeaponEffect {
    }

    public static final class FakeExplosionEffect {
    }
}
