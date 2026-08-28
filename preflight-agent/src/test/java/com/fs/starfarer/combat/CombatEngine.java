package com.fs.starfarer.combat;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.util.vector.Vector2f;

/** Exact-name in-memory combat engine for closed runtime-control and stress-fixture tests. */
public final class CombatEngine implements CombatEngineAPI {
    private final Manager[] managers = {new Manager(0), new Manager(1)};
    private final Viewport viewport = new Viewport();
    private final List<Object> missiles = new ArrayList<>();
    private final List<Object> projectiles = new ArrayList<>();
    private boolean simulation = true;
    private boolean paused;
    private boolean doNotEndCombat;
    private boolean combatOver;
    private float mapWidth = 20_000f;
    private float mapHeight = 12_000f;
    private float elapsed;
    private ShipAPI playerShip;

    @Override
    public CombatFleetManagerAPI getFleetManager(int owner) {
        return managers[owner];
    }

    @Override
    public boolean isSimulation() {
        return simulation;
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public void setPaused(boolean value) {
        paused = value;
    }

    @Override
    public void setDoNotEndCombat(boolean value) {
        doNotEndCombat = value;
    }

    @Override
    public void setPlayerShipExternal(ShipAPI ship) {
        playerShip = ship;
    }

    @Override
    public float getMapWidth() {
        return mapWidth;
    }

    @Override
    public float getMapHeight() {
        return mapHeight;
    }

    @Override
    public List<ShipAPI> getShips() {
        List<ShipAPI> result = new ArrayList<>();
        result.addAll(managers[0].ships());
        result.addAll(managers[1].ships());
        return result;
    }

    @Override
    public void removeEntity(CombatEntityAPI entity) {
        if (!(entity instanceof ShipAPI ship)) return;
        for (Manager manager : managers) {
            manager.removeShip(ship);
        }
    }

    @Override
    public List<Object> getMissiles() {
        return List.copyOf(missiles);
    }

    @Override
    public List<Object> getProjectiles() {
        return List.copyOf(projectiles);
    }

    @Override
    public float getTotalElapsedTime(boolean includingPaused) {
        return elapsed;
    }

    @Override
    public boolean isCombatOver() {
        return combatOver;
    }

    public Object getViewport() {
        return viewport;
    }

    public Viewport testViewport() {
        return viewport;
    }

    public void setSimulation(boolean value) {
        simulation = value;
    }

    public void setMapSize(float width, float height) {
        mapWidth = width;
        mapHeight = height;
    }

    public void setCombatOver(boolean value) {
        combatOver = value;
    }

    public void advanceTime(float seconds) {
        elapsed += seconds;
    }

    public void addMissile(Object missile) {
        missiles.add(missile);
    }

    public void addProjectile(Object projectile) {
        projectiles.add(projectile);
    }

    public boolean doNotEndCombat() {
        return doNotEndCombat;
    }

    public ShipAPI playerShip() {
        return playerShip;
    }

    public int deployedCount(int owner) {
        return managers[owner].members.size();
    }

    public void failSpawnsAfter(int owner, int successfulSpawns) {
        managers[owner].failAfter = successfulSpawns;
    }

    public void destroyOneNonFighter(int owner) {
        for (TestShip ship : managers[owner].ships.values()) {
            if (ship.alive && !ship.fighter) {
                ship.alive = false;
                ship.hulk = true;
                return;
            }
        }
        throw new IllegalStateException("no-live-ship");
    }

    public static final class Viewport {
        private static final float BASE_WIDTH = 2_000f;
        private static final float BASE_HEIGHT = 1_200f;
        private float viewMult = 1f;
        private float visibleWidth = BASE_WIDTH;
        private float visibleHeight = BASE_HEIGHT;
        private boolean externalControl;
        private final Center center = new Center();

        public float getViewMult() {
            return viewMult;
        }

        public float getVisibleWidth() {
            return visibleWidth;
        }

        public float getVisibleHeight() {
            return visibleHeight;
        }

        public void setExternalControl(boolean value) {
            externalControl = value;
        }

        public boolean isExternalControl() {
            return externalControl;
        }

        public void set(float x, float y, float width, float height) {
            visibleWidth = width;
            visibleHeight = height;
            viewMult = width / BASE_WIDTH;
            center.x = x + width / 2f;
            center.y = y + height / 2f;
        }

        public Center getCenter() {
            return center;
        }
    }

    public static final class Center {
        public float x;
        public float y;
    }

    private static final class Manager implements CombatFleetManagerAPI {
        private final int owner;
        private final List<FleetMemberAPI> members = new ArrayList<>();
        private final Map<FleetMemberAPI, TestShip> ships = new IdentityHashMap<>();
        private int successfulSpawns;
        private int failAfter = Integer.MAX_VALUE;

        Manager(int owner) {
            this.owner = owner;
        }

        @Override
        public List<FleetMemberAPI> getDeployedCopy() {
            return new ArrayList<>(members);
        }

        @Override
        public ShipAPI getShipFor(FleetMemberAPI member) {
            return ships.get(member);
        }

        @Override
        public ShipAPI spawnShipOrWing(
                String variantId, Vector2f location, float facing, float delay) {
            if (successfulSpawns >= failAfter) {
                throw new IllegalStateException("synthetic-spawn-failure");
            }
            successfulSpawns++;
            TestMember member = new TestMember(deploymentPoints(variantId));
            TestShip ship = new TestShip(owner, member);
            members.add(member);
            ships.put(member, ship);
            return ship;
        }

        @Override
        public void removeDeployed(ShipAPI ship, boolean withExplosion) {
            FleetMemberAPI member = ship.getFleetMember();
            members.remove(member);
            ships.remove(member);
        }

        List<TestShip> ships() {
            return new ArrayList<>(ships.values());
        }

        void removeShip(ShipAPI ship) {
            FleetMemberAPI member = ship.getFleetMember();
            members.remove(member);
            ships.remove(member);
        }

        private static float deploymentPoints(String variantId) {
            return switch (variantId) {
                case "odyssey_Balanced" -> 45f;
                case "aurora_Balanced" -> 30f;
                case "fury_Attack" -> 20f;
                case "medusa_Attack" -> 12f;
                case "hyperion_Strike" -> 15f;
                case "tempest_Attack", "scarab_Experimental" -> 8f;
                default -> throw new IllegalArgumentException("unknown variant " + variantId);
            };
        }
    }

    private record TestMember(float deploymentPoints) implements FleetMemberAPI {
        @Override
        public float getDeploymentPointsCost() {
            return deploymentPoints;
        }
    }

    private static final class TestShip implements ShipAPI {
        private final int owner;
        private final FleetMemberAPI member;
        private boolean alive = true;
        private boolean hulk;
        private boolean fighter;
        private float hitpoints = 100f;
        private float maximumHitpoints = 100f;
        private float flux = 0.25f;

        TestShip(int owner, FleetMemberAPI member) {
            this.owner = owner;
            this.member = member;
        }

        @Override
        public FleetMemberAPI getFleetMember() {
            return member;
        }

        @Override
        public int getOwner() {
            return owner;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public boolean isHulk() {
            return hulk;
        }

        @Override
        public boolean isFighter() {
            return fighter;
        }

        @Override
        public float getHitpoints() {
            return hitpoints;
        }

        @Override
        public float getMaxHitpoints() {
            return maximumHitpoints;
        }

        @Override
        public float getFluxLevel() {
            return flux;
        }
    }
}
