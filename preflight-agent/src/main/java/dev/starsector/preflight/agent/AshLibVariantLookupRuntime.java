package dev.starsector.preflight.agent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** A callback-scoped index for AshLib's otherwise repeated full variant-registry scan. */
public final class AshLibVariantLookupRuntime {
    static final String PLAN_ID = "ashlib-variant-lookup-v1";

    private static final ThreadLocal<State> ACTIVE = new ThreadLocal<>();
    private static final AtomicLong BUILDS = new AtomicLong();
    private static final AtomicLong INDEXED_VARIANTS = new AtomicLong();
    private static final AtomicLong LOOKUPS = new AtomicLong();
    private static final AtomicLong EXACT_HITS = new AtomicLong();
    private static final AtomicLong FALLBACK_NAME_HITS = new AtomicLong();
    private static final AtomicLong NULL_RESULTS = new AtomicLong();
    private static final AtomicLong BUILD_FAILURES = new AtomicLong();
    private static final AtomicLong SHIP_JSON_HITS = new AtomicLong();
    private static final AtomicLong SHIP_JSON_MISSES = new AtomicLong();
    private static final AtomicLong SHIP_JSON_CAPTURES = new AtomicLong();
    private static volatile boolean repositoryInstalled;
    private static volatile boolean lookupInstalled;
    private static volatile boolean shipJsonInstalled;

    private AshLibVariantLookupRuntime() {
    }

    static void beginSession() {
        ACTIVE.remove();
        BUILDS.set(0);
        INDEXED_VARIANTS.set(0);
        LOOKUPS.set(0);
        EXACT_HITS.set(0);
        FALLBACK_NAME_HITS.set(0);
        NULL_RESULTS.set(0);
        BUILD_FAILURES.set(0);
        SHIP_JSON_HITS.set(0);
        SHIP_JSON_MISSES.set(0);
        SHIP_JSON_CAPTURES.set(0);
        repositoryInstalled = false;
        lookupInstalled = false;
        shipJsonInstalled = false;
    }

    static void repositoryInstalled() {
        repositoryInstalled = true;
    }

    static void lookupInstalled() {
        lookupInstalled = true;
    }

    static void shipJsonInstalled() {
        shipJsonInstalled = true;
    }

    /** Builds from the same ordered live list AshLib is about to scan. Any disagreement disables it. */
    public static void begin(Object settings) {
        ACTIVE.remove();
        if (settings == null) {
            BUILD_FAILURES.incrementAndGet();
            return;
        }
        try {
            ClassLoader loader = settings.getClass().getClassLoader();
            Class<?> settingsApi = Class.forName(
                    "com.fs.starfarer.api.SettingsAPI", false, loader);
            Class<?> variantApi = Class.forName(
                    "com.fs.starfarer.api.combat.ShipVariantAPI", false, loader);
            Class<?> hullApi = Class.forName(
                    "com.fs.starfarer.api.combat.ShipHullSpecAPI", false, loader);
            Method getAllVariantIds = settingsApi.getMethod("getAllVariantIds");
            Method getVariant = settingsApi.getMethod("getVariant", String.class);
            Method getVariantFilePath = variantApi.getMethod("getVariantFilePath");
            Method getHullSpec = variantApi.getMethod("getHullSpec");
            Method getHullId = hullApi.getMethod("getHullId");

            Object rawIds = invoke(getAllVariantIds, settings);
            if (!(rawIds instanceof List<?> ids)) {
                throw new IllegalStateException("AshLib variant id source was not a List");
            }
            Map<String, String> firstByHull = new LinkedHashMap<>();
            List<String> orderedIds = new ArrayList<>(ids.size());
            for (Object rawId : ids) {
                if (!(rawId instanceof String id)) {
                    throw new IllegalStateException("AshLib variant id was not a String");
                }
                orderedIds.add(id);
                Object variant = invoke(getVariant, settings, id);
                if (invoke(getVariantFilePath, variant) == null) {
                    continue;
                }
                Object hull = invoke(getHullSpec, variant);
                Object rawHullId = invoke(getHullId, hull);
                if (!(rawHullId instanceof String hullId)) {
                    throw new IllegalStateException("AshLib variant hull id was not a String");
                }
                firstByHull.putIfAbsent(hullId, id);
            }
            ACTIVE.set(new State(
                    Map.copyOf(firstByHull), List.copyOf(orderedIds), new LinkedHashMap<>()));
            BUILDS.incrementAndGet();
            INDEXED_VARIANTS.addAndGet(orderedIds.size());
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            ACTIVE.remove();
            BUILD_FAILURES.incrementAndGet();
        }
    }

    /** Ends the only interval in which the indexed answer is allowed to replace AshLib's scan. */
    public static void end() {
        ACTIVE.remove();
    }

    public static boolean active() {
        return ACTIVE.get() != null;
    }

    /** Equivalent to AshLib's exact-hull scan followed by its ordered equalsIgnoreCase fallback. */
    public static String lookup(String hullId) {
        State state = ACTIVE.get();
        if (state == null) {
            return null;
        }
        LOOKUPS.incrementAndGet();
        String exact = state.firstByHull.get(hullId);
        if (exact != null) {
            EXACT_HITS.incrementAndGet();
            return exact;
        }
        String desired = hullId + "_Hull";
        for (String candidate : state.orderedIds) {
            if (desired.equalsIgnoreCase(candidate)) {
                FALLBACK_NAME_HITS.incrementAndGet();
                return candidate;
            }
        }
        NULL_RESULTS.incrementAndGet();
        return null;
    }

    /** Returns a read-only callback-local JSON value previously produced by AshLib, or null. */
    public static Object cachedShipJson(String hullId) {
        State state = ACTIVE.get();
        if (state == null) {
            return null;
        }
        Object cached = state.shipJsonByHull.get(hullId);
        if (cached == null) {
            SHIP_JSON_MISSES.incrementAndGet();
        } else {
            SHIP_JSON_HITS.incrementAndGet();
        }
        return cached;
    }

    /** Remembers only a successful non-null result from AshLib's unchanged loader. */
    public static void rememberShipJson(String hullId, Object json) {
        State state = ACTIVE.get();
        if (state == null || json == null) {
            return;
        }
        if (state.shipJsonByHull.putIfAbsent(hullId, json) == null) {
            SHIP_JSON_CAPTURES.incrementAndGet();
        }
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("repositoryInstalled", repositoryInstalled);
        values.put("lookupInstalled", lookupInstalled);
        values.put("shipJsonInstalled", shipJsonInstalled);
        values.put("builds", BUILDS.get());
        values.put("indexedVariants", INDEXED_VARIANTS.get());
        values.put("lookups", LOOKUPS.get());
        values.put("exactHits", EXACT_HITS.get());
        values.put("fallbackNameHits", FALLBACK_NAME_HITS.get());
        values.put("nullResults", NULL_RESULTS.get());
        values.put("buildFailures", BUILD_FAILURES.get());
        values.put("shipJsonHits", SHIP_JSON_HITS.get());
        values.put("shipJsonMisses", SHIP_JSON_MISSES.get());
        values.put("shipJsonCaptures", SHIP_JSON_CAPTURES.get());
        return values;
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) throws Throwable {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException failed) {
            throw failed.getCause();
        }
    }

    private record State(
            Map<String, String> firstByHull,
            List<String> orderedIds,
            Map<String, Object> shipJsonByHull) {
    }
}
