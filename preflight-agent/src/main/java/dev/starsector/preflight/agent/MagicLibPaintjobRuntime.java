package dev.starsector.preflight.agent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Reads MagicLib's authoritative unlocked-id set without allocating and scanning a copied list. */
public final class MagicLibPaintjobRuntime {
    static final String PLAN_ID = "magiclib-paintjob-unlocked-set-v1";

    private static final String MANAGER = "org.magiclib.paintjobs.MagicPaintjobManager";
    private static final String IDS_FIELD = "unlockedPaintjobsInner";

    private static final AtomicLong unlocked = new AtomicLong();
    private static final AtomicLong locked = new AtomicLong();
    private static final AtomicLong delegated = new AtomicLong();
    private static final AtomicLong failures = new AtomicLong();
    private static volatile Accessor accessor;
    private static volatile boolean installed;

    private MagicLibPaintjobRuntime() {
    }

    static void installed() {
        installed = true;
    }

    /**
     * Returns 1 or 0 from the authoritative set, or -1 so the wrapper invokes preserved MagicLib.
     */
    public static int lookup(Object paintjob) {
        if (paintjob == null) {
            delegated.incrementAndGet();
            return -1;
        }
        try {
            Accessor current = accessor;
            if (current == null || current.specClass != paintjob.getClass()) {
                current = resolve(paintjob.getClass());
                accessor = current;
            }
            String id = (String) current.getId.invoke(paintjob);
            @SuppressWarnings("unchecked")
            Set<String> ids = (Set<String>) current.ids.get(null);
            boolean found = ids.contains(id);
            (found ? unlocked : locked).incrementAndGet();
            return found ? 1 : 0;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            failures.incrementAndGet();
            delegated.incrementAndGet();
            return -1;
        }
    }

    private static Accessor resolve(Class<?> specClass) throws ReflectiveOperationException {
        ClassLoader loader = specClass.getClassLoader();
        Class<?> manager = Class.forName(MANAGER, false, loader);
        Field ids = manager.getDeclaredField(IDS_FIELD);
        ids.setAccessible(true);
        if (!Set.class.isAssignableFrom(ids.getType())) {
            throw new NoSuchFieldException(IDS_FIELD + " is not a Set");
        }
        MethodHandle getId = MethodHandles.publicLookup().findVirtual(
                specClass, "getId", MethodType.methodType(String.class));
        return new Accessor(specClass, ids, getId);
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("installed", installed);
        result.put("unlocked", unlocked.get());
        result.put("locked", locked.get());
        result.put("delegated", delegated.get());
        result.put("failures", failures.get());
        return result;
    }

    static void reset() {
        accessor = null;
        installed = false;
        unlocked.set(0);
        locked.set(0);
        delegated.set(0);
        failures.set(0);
    }

    private record Accessor(Class<?> specClass, Field ids, MethodHandle getId) {
    }
}
