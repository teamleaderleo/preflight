package dev.starsector.preflight.agent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds one closed, symmetric, in-memory 1,000+ DP simulation stress fixture. */
final class CombatStressFixtureRuntime {
    static final String ACTION = "combat.prepare-symmetric-1000dp-fixture";
    static final String RECIPE_ID = "symmetric-fast-high-tech-1040dp-v1";
    static final int SHIPS_PER_SIDE = 24;
    static final float MINIMUM_DP_PER_SIDE = 500f;
    private static final String GLOBAL = "com.fs.starfarer.api.Global";
    private static final String SETTINGS_API = "com.fs.starfarer.api.SettingsAPI";
    private static final String COMBAT_ENGINE_API =
            "com.fs.starfarer.api.combat.CombatEngineAPI";
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

    private static boolean attempted;
    private static boolean prepared;
    private static int removedSideZero;
    private static int removedSideOne;
    private static int spawnedSideZero;
    private static int spawnedSideOne;
    private static float deploymentPointsSideZero;
    private static float deploymentPointsSideOne;
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
            ClassLoader loader = engine.getClass().getClassLoader();
            Class<?> shipApi = Class.forName(SHIP_API, false, loader);
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
            if (!Boolean.TRUE.equals(invoke(isSimulation, engine))) {
                throw new IllegalStateException("combat-stress-fixture-requires-simulation");
            }
            validateVariants(loader);
            sideZero = invoke(getFleetManager, engine, 0);
            sideOne = invoke(getFleetManager, engine, 1);
            if (sideZero == null || sideOne == null || sideZero.getClass() != sideOne.getClass()) {
                throw new IllegalStateException("combat-stress-fleet-manager-shape-mismatch");
            }
            ManagerApi api = managerApi(loader, sideZero, fleetMemberApi, shipApi, vector);
            List<Object> originalZero = deployedShips(sideZero, api);
            List<Object> originalOne = deployedShips(sideOne, api);

            beforePaused = (Boolean) invoke(isPaused, engine);
            if (!beforePaused) invoke(setPaused, engine, true);
            invoke(setDoNotEnd, engine, true);
            float width = (Float) invoke(getMapWidth, engine);
            float height = (Float) invoke(getMapHeight, engine);
            validateMap(width, height);
            spawnSide(sideZero, api, vector, -1, width, height, spawnedZero);
            spawnSide(sideOne, api, vector, 1, width, height, spawnedOne);
            deploymentPointsSideZero = deploymentPoints(spawnedZero, api);
            deploymentPointsSideOne = deploymentPoints(spawnedOne, api);
            if (spawnedZero.size() != SHIPS_PER_SIDE || spawnedOne.size() != SHIPS_PER_SIDE
                    || deploymentPointsSideZero < MINIMUM_DP_PER_SIDE
                    || Math.abs(deploymentPointsSideZero - deploymentPointsSideOne) > 0.01f) {
                throw new IllegalStateException("combat-stress-spawned-fleet-mismatch");
            }

            for (Object ship : originalZero) invoke(api.removeDeployed(), sideZero, ship, false);
            for (Object ship : originalOne) invoke(api.removeDeployed(), sideOne, ship, false);
            originalsRemoved = true;
            removedSideZero = originalZero.size();
            removedSideOne = originalOne.size();
            invoke(setPlayerShip, engine, spawnedZero.get(0));

            spawnedSideZero = deployedCount(sideZero, api);
            spawnedSideOne = deployedCount(sideOne, api);
            if (spawnedSideZero != SHIPS_PER_SIDE || spawnedSideOne != SHIPS_PER_SIDE) {
                throw new IllegalStateException("combat-stress-spawn-count-mismatch");
            }
            invoke(setDoNotEnd, engine, false);
            if (!Boolean.TRUE.equals(invoke(isPaused, engine))) {
                throw new IllegalStateException("combat-stress-fixture-did-not-remain-paused");
            }
            prepared = true;
            return new Result(beforePaused, true,
                    String.format(java.util.Locale.ROOT,
                            "prepared %s: %d mirrored ships and %.1f DP per side; combat paused",
                            RECIPE_ID, spawnedSideZero, deploymentPointsSideZero));
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

    private static void validateVariants(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> global = Class.forName(GLOBAL, false, loader);
        Object settings = invoke(global.getMethod("getSettings"), null);
        Class<?> settingsApi = Class.forName(SETTINGS_API, false, loader);
        Method exists = exactApi(
                settingsApi, settings, "doesVariantExist", boolean.class, String.class);
        for (Variant variant : SIDE) {
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
            float width, float height, List<Object> spawned)
            throws ReflectiveOperationException {
        float xBase = side * Math.min(width * 0.25f, 4_000f);
        float ySpacing = Math.min(height / 8f, 700f);
        for (int index = 0; index < SIDE.size(); index++) {
            int column = index / 6;
            int row = index % 6;
            float x = xBase + side * column * 650f;
            float y = (row - 2.5f) * ySpacing;
            Object location = vector.getConstructor(float.class, float.class).newInstance(x, y);
            float facing = side < 0 ? 0f : 180f;
            Object ship = invoke(api.spawnShipOrWing(), manager,
                    SIDE.get(index).id(), location, facing, 0f);
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
        List<Map<String, Object>> values = new ArrayList<>();
        for (Variant variant : SIDE) {
            values.add(Map.of("variantId", variant.id(), "deploymentPoints", variant.dp()));
        }
        return List.copyOf(values);
    }

    static synchronized Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("action", ACTION);
        values.put("recipeId", RECIPE_ID);
        values.put("attempted", attempted);
        values.put("prepared", prepared);
        values.put("shipsPerSide", SHIPS_PER_SIDE);
        values.put("removedSideZero", removedSideZero);
        values.put("removedSideOne", removedSideOne);
        values.put("spawnedSideZero", spawnedSideZero);
        values.put("spawnedSideOne", spawnedSideOne);
        values.put("deploymentPointsSideZero", deploymentPointsSideZero);
        values.put("deploymentPointsSideOne", deploymentPointsSideOne);
        values.put("problem", problem);
        return values;
    }

    static synchronized void reset() {
        attempted = false;
        prepared = false;
        removedSideZero = 0;
        removedSideOne = 0;
        spawnedSideZero = 0;
        spawnedSideOne = 0;
        deploymentPointsSideZero = 0f;
        deploymentPointsSideOne = 0f;
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

    private record ManagerApi(
            Method getDeployedCopy,
            Method getShipFor,
            Method spawnShipOrWing,
            Method removeDeployed,
            Method getFleetMember,
            Method getDeploymentPointsCost) {
    }
}
