package dev.starsector.preflight.agent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
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
