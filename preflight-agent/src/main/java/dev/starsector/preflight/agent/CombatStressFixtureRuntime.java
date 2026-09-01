package dev.starsector.preflight.agent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds one closed, symmetric, in-memory simulation stress fixture. */
final class CombatStressFixtureRuntime {
    static final String ACTION = "combat.prepare-symmetric-1000dp-fixture";
    static final String RECIPE_ID = "symmetric-fast-high-tech-1040dp-v1";
    static final int SHIPS_PER_SIDE = 24;
    static final float MINIMUM_DP_PER_SIDE = 500f;
    static final List<Integer> SCALING_BATTLE_DP = List.of(260, 520, 780, 1040);
    private static final String GLOBAL = "com.fs.starfarer.api.Global";
    private static final String SETTINGS_API = "com.fs.starfarer.api.SettingsAPI";
    private static final String COMBAT_ENGINE_API =
            "com.fs.starfarer.api.combat.CombatEngineAPI";
    private static final String COMBAT_ENTITY_API =
            "com.fs.starfarer.api.combat.CombatEntityAPI";
    private static final String COMBAT_FLEET_MANAGER_API =
            "com.fs.starfarer.api.combat.CombatFleetManagerAPI";
    private static final String SHIP_API = "com.fs.starfarer.api.combat.ShipAPI";
    private static final String FLEET_MEMBER_API = "com.fs.starfarer.api.fleet.FleetMemberAPI";
    private static final String VECTOR = "org.lwjgl.util.vector.Vector2f";
    private static final List<Variant> SIDE = List.of(
            new Variant("odyssey_Balanced", 45f),
            new Variant("odyssey_Balanced", 45f),
            new Variant("odyssey_Balanced", 45f),
            new Variant("odyssey_Balanced", 45f),
            new Variant("aurora_Balanced", 30f),
            new Variant("aurora_Balanced", 30f),
            new Variant("aurora_Balanced", 30f),
            new Variant("aurora_Balanced", 30f),
            new Variant("fury_Attack", 20f),
            new Variant("fury_Attack", 20f),
            new Variant("fury_Attack", 20f),
            new Variant("fury_Attack", 20f),
            new Variant("medusa_Attack", 12f),
            new Variant("medusa_Attack", 12f),
            new Variant("medusa_Attack", 12f),
            new Variant("medusa_Attack", 12f),
            new Variant("hyperion_Strike", 15f),
            new Variant("hyperion_Strike", 15f),
            new Variant("hyperion_Strike", 15f),
            new Variant("hyperion_Strike", 15f),
            new Variant("tempest_Attack", 8f),
            new Variant("tempest_Attack", 8f),
            new Variant("scarab_Experimental", 8f),
            new Variant("scarab_Experimental", 8f));
    /*
     * Four composition-balanced 130-DP blocks. The 1,040-DP cell deliberately uses SIDE in its
     * original order so the established stress fixture remains byte-for-byte equivalent. Lower
     * cells use prefixes of this index order, preserving the same major-hull proportions.
     */
    private static final List<Integer> SCALING_ORDER = List.of(
            0, 4, 8, 12, 16, 20,
            1, 5, 9, 13, 17, 22,
            2, 6, 10, 14, 18, 21,
            3, 7, 11, 15, 19, 23);

    private static boolean attempted;
    private static boolean prepared;
    private static int removedSideZero;
    private static int removedSideOne;
    private static int removedEngineShips;
    private static int verifiedPrimaryEngineShips;
    private static int verifiedDependentEngineShips;
    private static int spawnedSideZero;
    private static int spawnedSideOne;
    private static float deploymentPointsSideZero;
    private static float deploymentPointsSideOne;
    private static int requestedBattleDp = 1040;
    private static int activeShipsPerSide = SHIPS_PER_SIDE;
    private static String activeRecipeId = RECIPE_ID;
    private static List<Object> removedEngineShipIdentities = List.of();
    private static List<Object> expectedPrimaryEngineShipIdentities = List.of();
    private static Map<String, Object> workloadBegin = Map.of();
    private static Map<String, Object> workloadEnd = Map.of();
    private static String problem;

    private CombatStressFixtureRuntime() {
    }

    static synchronized Result prepare(Object engine) throws ReflectiveOperationException {
        if (attempted) throw new IllegalStateException("combat-stress-fixture-already-attempted");
        attempted = true;
        boolean beforePaused = false;
        Object sideZero = null;
        Object sideOne = null;
        List<Object> spawnedZero = new ArrayList<>();
        List<Object> spawnedOne = new ArrayList<>();
        Method setPaused = null;
        Method setDoNotEnd = null;
        boolean originalsRemoved = false;
        try {
            FixtureRecipe recipe = configuredRecipe();
            requestedBattleDp = recipe.battleDp();
            activeShipsPerSide = recipe.side().size();
            activeRecipeId = recipe.id();
            ClassLoader loader = engine.getClass().getClassLoader();
            Class<?> shipApi = Class.forName(SHIP_API, false, loader);
            Class<?> combatEntityApi = Class.forName(COMBAT_ENTITY_API, false, loader);
            Class<?> fleetMemberApi = Class.forName(FLEET_MEMBER_API, false, loader);
            Class<?> vector = Class.forName(VECTOR, false, loader);
            Class<?> engineApi = Class.forName(COMBAT_ENGINE_API, false, loader);
            Method isSimulation = exactApi(
                    engineApi, engine, "isSimulation", boolean.class);
            Method isPaused = exactApi(engineApi, engine, "isPaused", boolean.class);
            setPaused = exactApi(
                    engineApi, engine, "setPaused", void.class, boolean.class);
            setDoNotEnd = exactApi(
                    engineApi, engine, "setDoNotEndCombat", void.class, boolean.class);
            Method getFleetManager = exactApi(
                    engineApi, engine, "getFleetManager", Object.class, int.class);
            Method setPlayerShip = exactApi(
                    engineApi, engine, "setPlayerShipExternal", void.class, shipApi);
            Method getMapWidth = exactApi(
                    engineApi, engine, "getMapWidth", float.class);
            Method getMapHeight = exactApi(
                    engineApi, engine, "getMapHeight", float.class);
            Method getShips = exactApi(engineApi, engine, "getShips", List.class);
            Method removeEntity = exactApi(
                    engineApi, engine, "removeEntity", void.class, combatEntityApi);
            if (!Boolean.TRUE.equals(invoke(isSimulation, engine))) {
                throw new IllegalStateException("combat-stress-fixture-requires-simulation");
            }
            validateVariants(loader, recipe.side());
            sideZero = invoke(getFleetManager, engine, 0);
            sideOne = invoke(getFleetManager, engine, 1);
            if (sideZero == null || sideOne == null || sideZero.getClass() != sideOne.getClass()) {
                throw new IllegalStateException("combat-stress-fleet-manager-shape-mismatch");
            }
            ManagerApi api = managerApi(loader, sideZero, fleetMemberApi, shipApi, vector);
            List<Object> originalZero = deployedShips(sideZero, api);
            List<Object> originalOne = deployedShips(sideOne, api);
            List<Object> originalEngineShips = engineShips(engine, getShips, shipApi);

            beforePaused = (Boolean) invoke(isPaused, engine);
            if (!beforePaused) invoke(setPaused, engine, true);
            invoke(setDoNotEnd, engine, true);
            float width = (Float) invoke(getMapWidth, engine);
            float height = (Float) invoke(getMapHeight, engine);
            validateMap(width, height);
            spawnSide(sideZero, api, vector, -1, width, height, recipe.side(), spawnedZero);
            spawnSide(sideOne, api, vector, 1, width, height, recipe.side(), spawnedOne);
            deploymentPointsSideZero = deploymentPoints(spawnedZero, api);
            deploymentPointsSideOne = deploymentPoints(spawnedOne, api);
            float expectedDpPerSide = requestedBattleDp / 2f;
            if (spawnedZero.size() != recipe.side().size()
                    || spawnedOne.size() != recipe.side().size()
                    || Math.abs(deploymentPointsSideZero - expectedDpPerSide) > 0.01f
                    || Math.abs(deploymentPointsSideZero - deploymentPointsSideOne) > 0.01f) {
                throw new IllegalStateException("combat-stress-spawned-fleet-mismatch");
            }

            for (Object ship : originalZero) invoke(api.removeDeployed(), sideZero, ship, false);
            for (Object ship : originalOne) invoke(api.removeDeployed(), sideOne, ship, false);
            originalsRemoved = true;
            removedSideZero = originalZero.size();
            removedSideOne = originalOne.size();
            for (Object ship : originalEngineShips) {
                invoke(removeEntity, engine, ship);
                removedEngineShips++;
            }
            invoke(setPlayerShip, engine, spawnedZero.get(0));

            spawnedSideZero = deployedCount(sideZero, api);
            spawnedSideOne = deployedCount(sideOne, api);
            if (spawnedSideZero != recipe.side().size()
                    || spawnedSideOne != recipe.side().size()) {
                throw new IllegalStateException("combat-stress-spawn-count-mismatch");
            }
            removedEngineShipIdentities = List.copyOf(originalEngineShips);
            List<Object> expectedPrimaryShips = new ArrayList<>(spawnedZero);
            expectedPrimaryShips.addAll(spawnedOne);
            expectedPrimaryEngineShipIdentities = List.copyOf(expectedPrimaryShips);
            invoke(setDoNotEnd, engine, false);
            if (!Boolean.TRUE.equals(invoke(isPaused, engine))) {
                throw new IllegalStateException("combat-stress-fixture-did-not-remain-paused");
            }
            prepared = true;
            return new Result(beforePaused, true,
                    String.format(java.util.Locale.ROOT,
                            "prepared %s: %d mirrored ships and %.1f DP per side; combat paused",
                            activeRecipeId, spawnedSideZero, deploymentPointsSideZero));
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            problem = bounded(failure);
            if (!originalsRemoved && (!spawnedZero.isEmpty() || !spawnedOne.isEmpty())) {
                rollback(sideZero, spawnedZero);
                rollback(sideOne, spawnedOne);
            }
            if (!originalsRemoved) {
                safeInvoke(setDoNotEnd, engine, false);
                if (!beforePaused) safeInvoke(setPaused, engine, false);
            }
            throw failure;
        }
    }

    private static void validateVariants(ClassLoader loader, List<Variant> side)
            throws ReflectiveOperationException {
        Class<?> global = Class.forName(GLOBAL, false, loader);
        Object settings = invoke(global.getMethod("getSettings"), null);
        Class<?> settingsApi = Class.forName(SETTINGS_API, false, loader);
        Method exists = exactApi(
                settingsApi, settings, "doesVariantExist", boolean.class, String.class);
        for (Variant variant : side) {
            if (!Boolean.TRUE.equals(invoke(exists, settings, variant.id()))) {
                throw new IllegalStateException("combat-stress-variant-missing:" + variant.id());
            }
        }
    }

    private static ManagerApi managerApi(
            ClassLoader loader, Object owner,
            Class<?> fleetMemberApi, Class<?> shipApi, Class<?> vector)
            throws ReflectiveOperationException {
        Class<?> managerApi = Class.forName(COMBAT_FLEET_MANAGER_API, false, loader);
        return new ManagerApi(
                exactApi(managerApi, owner, "getDeployedCopy", List.class),
                exactApi(managerApi, owner, "getShipFor", shipApi, fleetMemberApi),
                exactApi(managerApi, owner, "spawnShipOrWing", shipApi,
                        String.class, vector, float.class, float.class),
                exactApi(managerApi, owner, "removeDeployed", void.class,
                        shipApi, boolean.class),
                exact(shipApi, "getFleetMember", fleetMemberApi),
                exact(fleetMemberApi, "getDeploymentPointsCost", float.class));
    }

    private static List<Object> deployedShips(Object manager, ManagerApi api)
            throws ReflectiveOperationException {
        Object value = invoke(api.getDeployedCopy(), manager);
        if (!(value instanceof List<?> members)) {
            throw new IllegalStateException("combat-stress-deployed-list-mismatch");
        }
        List<Object> ships = new ArrayList<>();
        for (Object member : members) {
            Object ship = invoke(api.getShipFor(), manager, member);
            if (ship == null) throw new IllegalStateException("combat-stress-deployed-ship-missing");
            ships.add(ship);
        }
        return ships;
    }

    private static void spawnSide(
            Object manager, ManagerApi api, Class<?> vector, int side,
            float width, float height, List<Variant> recipe, List<Object> spawned)
            throws ReflectiveOperationException {
        float xBase = side * Math.min(width * 0.25f, 4_000f);
        float ySpacing = Math.min(height / 8f, 700f);
        for (int index = 0; index < recipe.size(); index++) {
            int column = index / 6;
            int row = index % 6;
            float x = xBase + side * column * 650f;
            float y = (row - 2.5f) * ySpacing;
            Object location = vector.getConstructor(float.class, float.class).newInstance(x, y);
            float facing = side < 0 ? 0f : 180f;
            Object ship = invoke(api.spawnShipOrWing(), manager,
                    recipe.get(index).id(), location, facing, 0f);
            if (ship == null) throw new IllegalStateException("combat-stress-spawn-returned-null");
            spawned.add(ship);
        }
    }

    private static int deployedCount(Object manager, ManagerApi api)
            throws ReflectiveOperationException {
        Object value = invoke(api.getDeployedCopy(), manager);
        if (!(value instanceof List<?> members)) {
            throw new IllegalStateException("combat-stress-deployed-list-mismatch");
        }
        return members.size();
    }

    private static List<Object> engineShips(Object engine, Method getShips, Class<?> shipApi)
            throws ReflectiveOperationException {
        Object value = invoke(getShips, engine);
        if (!(value instanceof List<?> ships)) {
            throw new IllegalStateException("combat-stress-engine-ships-list-mismatch");
        }
        List<Object> snapshot = new ArrayList<>();
        for (Object ship : ships) {
            if (ship == null || !shipApi.isInstance(ship)) {
                throw new IllegalStateException("combat-stress-engine-ship-shape-mismatch");
            }
            snapshot.add(ship);
        }
        return snapshot;
    }

    private static int verifyEngineFixture(
            Object engine, Method getShips, Class<?> shipApi,
            List<Object> originalEngineShips, List<Object> expectedPrimaryShips)
            throws ReflectiveOperationException {
        IdentityHashMap<Object, Boolean> removed = new IdentityHashMap<>();
        for (Object ship : originalEngineShips) removed.put(ship, Boolean.TRUE);
        IdentityHashMap<Object, Boolean> expected = new IdentityHashMap<>();
        for (Object ship : expectedPrimaryShips) expected.put(ship, Boolean.TRUE);
        int matched = 0;
        int dependent = 0;
        for (Object ship : engineShips(engine, getShips, shipApi)) {
            if (removed.containsKey(ship)) {
                throw new IllegalStateException("combat-stress-removed-engine-ship-survived");
            } else if (expected.containsKey(ship)) {
                matched++;
            } else {
                dependent++;
            }
        }
        if (matched != expected.size()) {
            throw new IllegalStateException("combat-stress-engine-spawn-count-mismatch");
        }
        verifiedDependentEngineShips = dependent;
        return matched;
    }

    private static float deploymentPoints(List<Object> ships, ManagerApi api)
            throws ReflectiveOperationException {
        float total = 0f;
        for (Object ship : ships) {
            Object member = invoke(api.getFleetMember(), ship);
            Object value = invoke(api.getDeploymentPointsCost(), member);
            if (!(value instanceof Float points) || !Float.isFinite(points) || points <= 0f) {
                throw new IllegalStateException("combat-stress-deployment-points-invalid");
            }
            total += points;
        }
        return total;
    }

    private static void rollback(Object manager, List<Object> ships) {
        if (manager == null) return;
        try {
            ClassLoader loader = manager.getClass().getClassLoader();
            Class<?> shipApi = Class.forName(SHIP_API, false, loader);
            Class<?> managerApi = Class.forName(COMBAT_FLEET_MANAGER_API, false, loader);
            Method remove = exactApi(managerApi, manager, "removeDeployed", void.class,
                    shipApi, boolean.class);
            for (Object ship : ships) invoke(remove, manager, ship, false);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // The original deployed fleet is left in place until every new ship has spawned.
        }
    }

    private static void safeInvoke(Method method, Object receiver, Object... arguments) {
        if (method == null) return;
        try {
            invoke(method, receiver, arguments);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Best effort only after the primary operation has already failed.
        }
    }

    private static void validateMap(float width, float height) {
        if (!Float.isFinite(width) || !Float.isFinite(height) || width < 8_000f || height < 4_000f) {
            throw new IllegalStateException("combat-stress-map-size-invalid");
        }
    }

    static List<Map<String, Object>> recipe() {
        return recipeRows(SIDE);
    }

    static List<Map<String, Object>> recipeForBattleDp(int battleDp) {
        return recipeRows(fixtureRecipe(battleDp).side());
    }

    private static List<Map<String, Object>> recipeRows(List<Variant> side) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (Variant variant : side) {
            values.add(Map.of("variantId", variant.id(), "deploymentPoints", variant.dp()));
        }
        return List.copyOf(values);
    }

    private static FixtureRecipe configuredRecipe() {
        if (!CombatWorkloadRuntime.enabled()) return fixtureRecipe(1040);
        String configured = System.getProperty(CombatWorkloadRuntime.BATTLE_DP_PROPERTY);
        if (configured == null || configured.isBlank()) return fixtureRecipe(1040);
        final double parsed;
        try {
            parsed = Double.parseDouble(configured);
        } catch (NumberFormatException problem) {
            throw new IllegalStateException("combat-scaling-battle-dp-invalid", problem);
        }
        int rounded = (int) Math.rint(parsed);
        if (!Double.isFinite(parsed) || Math.abs(parsed - rounded) > 0.001) {
            throw new IllegalStateException("combat-scaling-battle-dp-invalid");
        }
        return fixtureRecipe(rounded);
    }

    private static FixtureRecipe fixtureRecipe(int battleDp) {
        int ships = switch (battleDp) {
            case 260 -> 6;
            case 520 -> 12;
            case 780 -> 18;
            case 1040 -> 24;
            default -> throw new IllegalStateException(
                    "combat-scaling-battle-dp-unsupported:" + battleDp);
        };
        List<Variant> side;
        if (battleDp == 1040) {
            side = SIDE;
        } else {
            List<Variant> selected = new ArrayList<>();
            for (int index = 0; index < ships; index++) {
                selected.add(SIDE.get(SCALING_ORDER.get(index)));
            }
            side = List.copyOf(selected);
        }
        return new FixtureRecipe(
                "symmetric-fast-high-tech-" + battleDp + "dp-v1", battleDp, side);
    }

    static synchronized Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("action", ACTION);
        values.put("recipeId", activeRecipeId);
        values.put("requestedBattleDp", requestedBattleDp);
        values.put("actualBattleDp", deploymentPointsSideZero + deploymentPointsSideOne);
        values.put("attempted", attempted);
        values.put("prepared", prepared);
        values.put("shipsPerSide", activeShipsPerSide);
        values.put("removedSideZero", removedSideZero);
        values.put("removedSideOne", removedSideOne);
        values.put("removedEngineShips", removedEngineShips);
        values.put("verifiedPrimaryEngineShips", verifiedPrimaryEngineShips);
        values.put("verifiedDependentEngineShips", verifiedDependentEngineShips);
        values.put("spawnedSideZero", spawnedSideZero);
        values.put("spawnedSideOne", spawnedSideOne);
        values.put("deploymentPointsSideZero", deploymentPointsSideZero);
        values.put("deploymentPointsSideOne", deploymentPointsSideOne);
        values.put("problem", problem);
        return values;
    }

    static synchronized void captureWorkloadBegin(Object engine)
            throws ReflectiveOperationException {
        if (!prepared) throw new IllegalStateException("combat-stress-fixture-not-prepared");
        if (!workloadBegin.isEmpty()) {
            throw new IllegalStateException("combat-workload-begin-already-captured");
        }
        ClassLoader loader = engine.getClass().getClassLoader();
        Class<?> engineApi = Class.forName(COMBAT_ENGINE_API, false, loader);
        Class<?> shipApi = Class.forName(SHIP_API, false, loader);
        Method getShips = exactApi(engineApi, engine, "getShips", List.class);
        verifiedPrimaryEngineShips = verifyEngineFixture(
                engine, getShips, shipApi,
                removedEngineShipIdentities, expectedPrimaryEngineShipIdentities);
        workloadBegin = workloadSnapshot(engine);
    }

    static synchronized String captureWorkloadEnd(Object engine)
            throws ReflectiveOperationException {
        if (workloadBegin.isEmpty()) {
            throw new IllegalStateException("combat-workload-begin-missing");
        }
        if (!workloadEnd.isEmpty()) {
            throw new IllegalStateException("combat-workload-end-already-captured");
        }
        workloadEnd = workloadSnapshot(engine);
        float gameSeconds = number(workloadEnd, "combatSecondsExcludingPaused")
                - number(workloadBegin, "combatSecondsExcludingPaused");
        return String.format(java.util.Locale.ROOT,
                "ended steady-state combat frame window after %.3f game seconds; workload fingerprint captured",
                gameSeconds);
    }

    static synchronized Map<String, Object> workloadTelemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("recipeId", activeRecipeId);
        values.put("requestedBattleDp", requestedBattleDp);
        values.put("begin", workloadBegin.isEmpty() ? null : workloadBegin);
        values.put("end", workloadEnd.isEmpty() ? null : workloadEnd);
        if (!workloadBegin.isEmpty() && !workloadEnd.isEmpty()) {
            values.put("combatSecondsElapsed",
                    number(workloadEnd, "combatSecondsExcludingPaused")
                            - number(workloadBegin, "combatSecondsExcludingPaused"));
            values.put("sideZeroNonFighterLosses",
                    nestedInt(workloadBegin, "sideZero", "aliveNonFighters")
                            - nestedInt(workloadEnd, "sideZero", "aliveNonFighters"));
            values.put("sideOneNonFighterLosses",
                    nestedInt(workloadBegin, "sideOne", "aliveNonFighters")
                            - nestedInt(workloadEnd, "sideOne", "aliveNonFighters"));
        }
        return values;
    }

    private static Map<String, Object> workloadSnapshot(Object engine)
            throws ReflectiveOperationException {
        ClassLoader loader = engine.getClass().getClassLoader();
        Class<?> engineApi = Class.forName(COMBAT_ENGINE_API, false, loader);
        Class<?> shipApi = Class.forName(SHIP_API, false, loader);
        Method getShips = exactApi(engineApi, engine, "getShips", List.class);
        Method getMissiles = exactApi(engineApi, engine, "getMissiles", List.class);
        Method getProjectiles = exactApi(engineApi, engine, "getProjectiles", List.class);
        Method getTotalElapsedTime = exactApi(
                engineApi, engine, "getTotalElapsedTime", float.class, boolean.class);
        Method isPaused = exactApi(engineApi, engine, "isPaused", boolean.class);
        Method isCombatOver = exactApi(engineApi, engine, "isCombatOver", boolean.class);
        Method getOwner = exact(shipApi, "getOwner", int.class);
        Method isAlive = exact(shipApi, "isAlive", boolean.class);
        Method isHulk = exact(shipApi, "isHulk", boolean.class);
        Method isFighter = exact(shipApi, "isFighter", boolean.class);
        Method getHitpoints = exact(shipApi, "getHitpoints", float.class);
        Method getMaxHitpoints = exact(shipApi, "getMaxHitpoints", float.class);
        Method getFluxLevel = exact(shipApi, "getFluxLevel", float.class);

        Object shipsValue = invoke(getShips, engine);
        Object missilesValue = invoke(getMissiles, engine);
        Object projectilesValue = invoke(getProjectiles, engine);
        if (!(shipsValue instanceof List<?> ships)
                || !(missilesValue instanceof List<?> missiles)
                || !(projectilesValue instanceof List<?> projectiles)) {
            throw new IllegalStateException("combat-workload-collection-shape-mismatch");
        }
        SideWorkload[] sides = {new SideWorkload(), new SideWorkload(), new SideWorkload()};
        for (Object ship : ships) {
            if (ship == null || !shipApi.isInstance(ship)) {
                throw new IllegalStateException("combat-workload-ship-shape-mismatch");
            }
            int owner = (Integer) invoke(getOwner, ship);
            SideWorkload side = sides[owner == 0 ? 0 : owner == 1 ? 1 : 2];
            boolean alive = (Boolean) invoke(isAlive, ship);
            boolean fighter = (Boolean) invoke(isFighter, ship);
            boolean hulk = (Boolean) invoke(isHulk, ship);
            side.total++;
            if (alive) {
                side.alive++;
                if (fighter) side.aliveFighters++;
                else side.aliveNonFighters++;
                float maximum = (Float) invoke(getMaxHitpoints, ship);
                float hitpoints = (Float) invoke(getHitpoints, ship);
                float flux = (Float) invoke(getFluxLevel, ship);
                if (Float.isFinite(maximum) && maximum > 0f && Float.isFinite(hitpoints)) {
                    side.aliveHullFractionSum += Math.max(0f, hitpoints / maximum);
                }
                if (Float.isFinite(flux)) side.aliveFluxFractionSum += Math.max(0f, flux);
            }
            if (hulk) side.hulks++;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("combatSecondsExcludingPaused", invoke(getTotalElapsedTime, engine, false));
        values.put("combatSecondsIncludingPaused", invoke(getTotalElapsedTime, engine, true));
        values.put("paused", invoke(isPaused, engine));
        values.put("combatOver", invoke(isCombatOver, engine));
        values.put("ships", ships.size());
        values.put("missiles", missiles.size());
        values.put("projectiles", projectiles.size());
        values.put("sideZero", sides[0].toMap());
        values.put("sideOne", sides[1].toMap());
        values.put("otherOwners", sides[2].toMap());
        return Map.copyOf(values);
    }

    private static float number(Map<String, Object> values, String key) {
        return ((Number) values.get(key)).floatValue();
    }

    @SuppressWarnings("unchecked")
    private static int nestedInt(Map<String, Object> values, String parent, String key) {
        return ((Number) ((Map<String, Object>) values.get(parent)).get(key)).intValue();
    }

    static synchronized void reset() {
        attempted = false;
        prepared = false;
        removedSideZero = 0;
        removedSideOne = 0;
        removedEngineShips = 0;
        verifiedPrimaryEngineShips = 0;
        verifiedDependentEngineShips = 0;
        spawnedSideZero = 0;
        spawnedSideOne = 0;
        deploymentPointsSideZero = 0f;
        deploymentPointsSideOne = 0f;
        requestedBattleDp = 1040;
        activeShipsPerSide = SHIPS_PER_SIDE;
        activeRecipeId = RECIPE_ID;
        removedEngineShipIdentities = List.of();
        expectedPrimaryEngineShipIdentities = List.of();
        workloadBegin = Map.of();
        workloadEnd = Map.of();
        problem = null;
    }

    private static Method exact(
            Class<?> owner, String name, Class<?> returnType, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = owner.getMethod(name, parameterTypes);
        if (returnType != Object.class && method.getReturnType() != returnType) {
            throw new NoSuchMethodException(owner.getName() + "." + name + " return type mismatch");
        }
        return method;
    }

    /** Resolves through a public API type so non-public game implementations remain invocable. */
    static Method exactApi(
            Class<?> api, Object receiver, String name,
            Class<?> returnType, Class<?>... parameterTypes) throws NoSuchMethodException {
        if (!api.isInterface() || !Modifier.isPublic(api.getModifiers())
                || receiver == null || !api.isInstance(receiver)) {
            throw new NoSuchMethodException(api.getName() + " receiver/API mismatch");
        }
        return exact(api, name, returnType, parameterTypes);
    }

    private static Object invoke(Method method, Object receiver, Object... arguments)
            throws ReflectiveOperationException {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof ReflectiveOperationException reflected) throw reflected;
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw failure;
        }
    }

    private static String bounded(Throwable failure) {
        String message = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    record Result(boolean beforePaused, boolean afterPaused, String detail) {
    }

    private record Variant(String id, float dp) {
    }

    private record FixtureRecipe(String id, int battleDp, List<Variant> side) {
    }

    private static final class SideWorkload {
        private int total;
        private int alive;
        private int aliveNonFighters;
        private int aliveFighters;
        private int hulks;
        private double aliveHullFractionSum;
        private double aliveFluxFractionSum;

        private Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("total", total);
            values.put("alive", alive);
            values.put("aliveNonFighters", aliveNonFighters);
            values.put("aliveFighters", aliveFighters);
            values.put("hulks", hulks);
            values.put("aliveHullFractionSum", aliveHullFractionSum);
            values.put("aliveFluxFractionSum", aliveFluxFractionSum);
            return Map.copyOf(values);
        }
    }

    private record ManagerApi(
            Method getDeployedCopy,
            Method getShipFor,
            Method spawnShipOrWing,
            Method removeDeployed,
            Method getFleetMember,
            Method getDeploymentPointsCost) {
    }
}
