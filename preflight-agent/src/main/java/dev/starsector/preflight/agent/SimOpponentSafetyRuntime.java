package dev.starsector.preflight.agent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Removes nonexistent ship specifications from the vanilla refit simulator's opponent list. */
public final class SimOpponentSafetyRuntime {
    static final String PLAN_ID = "sim-opponent-safety-v1";
    public static final String DISABLED_PROPERTY = "preflight.simOpponentSafety.disabled";

    private static final String HULL_VARIANT =
            "com.fs.starfarer.loading.specs.HullVariantSpec";
    private static final String FIGHTER_WING =
            "com.fs.starfarer.loading.specs.FighterWingSpec";
    private static final int MAX_REPORTED_IDS = 256;

    private static volatile boolean installed;
    private static final AtomicLong CALLS = new AtomicLong();
    private static final AtomicLong CANDIDATES = new AtomicLong();
    private static final AtomicLong REMOVED = new AtomicLong();
    private static final AtomicLong FAIL_OPEN = new AtomicLong();
    private static final AtomicLong ADD_RESULTS = new AtomicLong();
    private static final AtomicLong NULL_ADD_RESULTS = new AtomicLong();
    private static final AtomicLong BEFORE_LOAD_FLEET_SIZE = new AtomicLong(-1L);
    private static final AtomicLong AFTER_LOAD_FLEET_SIZE = new AtomicLong(-1L);
    private static final AtomicLong FLEET_INSPECTION_FAILURES = new AtomicLong();
    private static final AtomicLong POST_INIT_ENEMY_RESERVES = new AtomicLong(-1L);
    private static final AtomicLong POST_INIT_ENEMY_NON_ALLY_RESERVES = new AtomicLong(-1L);
    private static final AtomicLong POST_INIT_ENEMY_ALLY_RESERVES = new AtomicLong(-1L);
    private static final AtomicLong POST_INIT_ENEMY_DEPLOYED = new AtomicLong(-1L);
    private static final AtomicLong COMBAT_INSPECTION_FAILURES = new AtomicLong();
    private static final Map<String, Long> INVALID_IDS = new LinkedHashMap<>();
    private static final AtomicBoolean INVALID_IDS_TRUNCATED = new AtomicBoolean();

    private SimOpponentSafetyRuntime() {
    }

    static boolean ready() {
        return true;
    }

    static void installed() {
        installed = true;
    }

    static boolean enabled() {
        return installed && !Boolean.getBoolean(DISABLED_PROPERTY);
    }

    /**
     * Returns the shipped list unchanged when every entry exists or validation is unavailable.
     * A filtered copy is returned only when Starsector's own registry rejects an entry.
     */
    public static List<?> filter(List<?> source, Class<?> specStoreClass) {
        if (!enabled() || source == null || specStoreClass == null) {
            return source;
        }
        CALLS.incrementAndGet();
        try {
            if (source.isEmpty()) {
                return source;
            }
            VariantLookup lookup = lookup(specStoreClass);
            return filter(source, lookup);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            FAIL_OPEN.incrementAndGet();
            return source;
        }
    }

    static List<?> filter(List<?> source, VariantLookup lookup) throws Throwable {
        List<Object> valid = null;
        List<String> invalid = new ArrayList<>();
        int size = source.size();
        for (int i = 0; i < size; i++) {
            Object candidate = source.get(i);
            if (!(candidate instanceof String id)) {
                throw new IllegalArgumentException("Non-string simulation opponent id");
            }
            boolean wing = id.endsWith("_wing");
            if (lookup.exists(id, wing)) {
                if (valid != null) {
                    valid.add(candidate);
                }
                continue;
            }
            if (valid == null) {
                valid = new ArrayList<>(size - 1);
                for (int prior = 0; prior < i; prior++) {
                    valid.add(source.get(prior));
                }
            }
            invalid.add(id);
        }
        if (source.size() != size) {
            throw new IllegalStateException("Simulation opponent list changed during validation");
        }
        CANDIDATES.addAndGet(size);
        REMOVED.addAndGet(invalid.size());
        for (String id : invalid) {
            recordInvalid(id);
        }
        return valid == null ? source : valid;
    }

    private static VariantLookup lookup(Class<?> specStoreClass) throws ReflectiveOperationException {
        ClassLoader loader = specStoreClass.getClassLoader();
        Class<?> hullVariant = Class.forName(HULL_VARIANT, false, loader);
        Class<?> fighterWing = Class.forName(FIGHTER_WING, false, loader);
        Method exists = specStoreClass.getMethod("new", Class.class, String.class);
        if (exists.getReturnType() != boolean.class || !Modifier.isStatic(exists.getModifiers())) {
            throw new NoSuchMethodException("SpecStore.new(Class,String):boolean");
        }
        return (id, wing) -> invokeExists(
                exists, wing ? fighterWing : hullVariant, id);
    }

    private static boolean invokeExists(Method method, Class<?> type, String id) throws Throwable {
        try {
            return Boolean.TRUE.equals(method.invoke(null, type, id));
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    /** Records whether vanilla produced a member for a validated simulation-opponent row. */
    public static void recordAdded(Object member) {
        ADD_RESULTS.incrementAndGet();
        if (member == null) {
            NULL_ADD_RESULTS.incrementAndGet();
        }
    }

    /** Records the enemy mission-fleet size immediately before or after vanilla mission loading. */
    public static void recordMission(Object mission, boolean afterLoad) {
        if (mission == null) {
            FLEET_INSPECTION_FAILURES.incrementAndGet();
            return;
        }
        try {
            ClassLoader loader = mission.getClass().getClassLoader();
            Class<?> fleetSide = Class.forName(
                    "com.fs.starfarer.api.mission.FleetSide", false, loader);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object enemy = Enum.valueOf((Class<? extends Enum>) fleetSide.asSubclass(Enum.class),
                    "ENEMY");
            Method getFleet = mission.getClass().getMethod("getFleet", fleetSide);
            Object fleet = invoke(getFleet, mission, enemy);
            if (fleet == null) {
                throw new IllegalStateException("Simulation enemy fleet is null");
            }
            Method getMembers = fleet.getClass().getMethod("Ó00000");
            Object members = invoke(getMembers, fleet);
            if (!(members instanceof List<?> list)) {
                throw new IllegalStateException("Simulation enemy fleet members are unavailable");
            }
            (afterLoad ? AFTER_LOAD_FLEET_SIZE : BEFORE_LOAD_FLEET_SIZE).set(list.size());
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            FLEET_INSPECTION_FAILURES.incrementAndGet();
        }
    }

    /** Records the exact enemy collections consumed by the stock deployment dialog. */
    public static void recordCombatEngine(Object combatEngine) {
        if (combatEngine == null) {
            COMBAT_INSPECTION_FAILURES.incrementAndGet();
            return;
        }
        try {
            Method getFleetManager = combatEngine.getClass().getMethod("getFleetManager", int.class);
            Object enemyManager = invoke(getFleetManager, combatEngine, 1);
            if (enemyManager == null) {
                throw new IllegalStateException("Simulation enemy combat manager is null");
            }
            Collection<?> reserves = collection(enemyManager, "getReserves");
            Collection<?> deployed = collection(enemyManager, "getDeployed");
            long allies = 0L;
            for (Object member : reserves) {
                if (member == null) {
                    throw new IllegalStateException("Simulation enemy reserve member is null");
                }
                Method isAlly = member.getClass().getMethod("isAlly");
                if (Boolean.TRUE.equals(invoke(isAlly, member))) {
                    allies++;
                }
            }
            POST_INIT_ENEMY_RESERVES.set(reserves.size());
            POST_INIT_ENEMY_ALLY_RESERVES.set(allies);
            POST_INIT_ENEMY_NON_ALLY_RESERVES.set(reserves.size() - allies);
            POST_INIT_ENEMY_DEPLOYED.set(deployed.size());
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            COMBAT_INSPECTION_FAILURES.incrementAndGet();
        }
    }

    private static Collection<?> collection(Object receiver, String methodName) throws Throwable {
        Method method = receiver.getClass().getMethod(methodName);
        Object value = invoke(method, receiver);
        if (!(value instanceof Collection<?> collection)) {
            throw new IllegalStateException(methodName + " did not return a collection");
        }
        return collection;
    }

    private static Object invoke(Method method, Object receiver, Object... arguments)
            throws Throwable {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    private static void recordInvalid(String id) {
        synchronized (INVALID_IDS) {
            Long prior = INVALID_IDS.get(id);
            if (prior != null) {
                INVALID_IDS.put(id, prior + 1L);
            } else if (INVALID_IDS.size() < MAX_REPORTED_IDS) {
                INVALID_IDS.put(id, 1L);
            } else {
                INVALID_IDS_TRUNCATED.set(true);
            }
        }
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", PLAN_ID);
        values.put("installed", installed);
        values.put("enabled", enabled());
        values.put("calls", CALLS.get());
        values.put("candidates", CANDIDATES.get());
        values.put("removed", REMOVED.get());
        values.put("failOpen", FAIL_OPEN.get());
        values.put("addResults", ADD_RESULTS.get());
        values.put("nullAddResults", NULL_ADD_RESULTS.get());
        values.put("beforeLoadEnemyFleetSize", BEFORE_LOAD_FLEET_SIZE.get());
        values.put("afterLoadEnemyFleetSize", AFTER_LOAD_FLEET_SIZE.get());
        values.put("fleetInspectionFailures", FLEET_INSPECTION_FAILURES.get());
        values.put("postInitEnemyReserves", POST_INIT_ENEMY_RESERVES.get());
        values.put("postInitEnemyNonAllyReserves", POST_INIT_ENEMY_NON_ALLY_RESERVES.get());
        values.put("postInitEnemyAllyReserves", POST_INIT_ENEMY_ALLY_RESERVES.get());
        values.put("postInitEnemyDeployed", POST_INIT_ENEMY_DEPLOYED.get());
        values.put("combatInspectionFailures", COMBAT_INSPECTION_FAILURES.get());
        synchronized (INVALID_IDS) {
            values.put("invalidVariantIds", new LinkedHashMap<>(INVALID_IDS));
            values.put("invalidVariantIdsTruncated", INVALID_IDS_TRUNCATED.get());
        }
        return values;
    }

    static void beginSession() {
        installed = false;
        CALLS.set(0L);
        CANDIDATES.set(0L);
        REMOVED.set(0L);
        FAIL_OPEN.set(0L);
        ADD_RESULTS.set(0L);
        NULL_ADD_RESULTS.set(0L);
        BEFORE_LOAD_FLEET_SIZE.set(-1L);
        AFTER_LOAD_FLEET_SIZE.set(-1L);
        FLEET_INSPECTION_FAILURES.set(0L);
        POST_INIT_ENEMY_RESERVES.set(-1L);
        POST_INIT_ENEMY_NON_ALLY_RESERVES.set(-1L);
        POST_INIT_ENEMY_ALLY_RESERVES.set(-1L);
        POST_INIT_ENEMY_DEPLOYED.set(-1L);
        COMBAT_INSPECTION_FAILURES.set(0L);
        INVALID_IDS_TRUNCATED.set(false);
        synchronized (INVALID_IDS) {
            INVALID_IDS.clear();
        }
    }

    @FunctionalInterface
    interface VariantLookup {
        boolean exists(String id, boolean fighterWing) throws Throwable;
    }
}
