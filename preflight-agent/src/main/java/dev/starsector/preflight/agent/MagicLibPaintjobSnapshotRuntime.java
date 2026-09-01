package dev.starsector.preflight.agent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Skips MagicLib's private notification scan between reviewed collection mutations. */
public final class MagicLibPaintjobSnapshotRuntime {
    static final String PLAN_ID = "magiclib-paintjob-frame-snapshot-v1";
    static final String DISABLED_PROPERTY = "preflight.magiclib.paintjobSnapshot.disabled";

    private static volatile Accessor accessor;
    private static volatile boolean installed;
    private static volatile boolean dirty = true;
    private static long hits;
    private static long rebuilds;
    private static long mutations;
    private static long delegated;
    private static long failures;

    private MagicLibPaintjobSnapshotRuntime() {
    }

    static void installed() {
        installed = true;
    }

    /** Matches Kotlin's synthetic default-argument bridge used by the reviewed advance method. */
    public static Set<?> snapshot(
            boolean includeShiny, int mask, Object marker, Class<?> managerClass) {
        boolean effectiveIncludeShiny = (mask & 1) != 0 ? false : includeShiny;
        if (!installed || Boolean.getBoolean(DISABLED_PROPERTY)
                || effectiveIncludeShiny || marker != null) {
            delegated++;
            return original(managerClass, effectiveIncludeShiny);
        }
        if (!dirty) {
            hits++;
            return Set.of();
        }
        // Mark clean before building the stable iteration snapshot. A reviewed mutation from a
        // callback during construction or the following loop sets dirty again for the next frame.
        dirty = false;
        try {
            Set<?> current = original(managerClass, false);
            rebuilds++;
            return current;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            dirty = true;
            throw fatal;
        } catch (Throwable failure) {
            dirty = true;
            return sneakyThrow(failure);
        }
    }

    public static void mutated() {
        dirty = true;
        mutations++;
    }

    public static boolean mutated(boolean changed) {
        if (changed) mutated();
        return changed;
    }

    @SuppressWarnings("unchecked")
    private static Set<?> original(Class<?> managerClass, boolean includeShiny) {
        Accessor current = accessor;
        if (current == null || current.managerClass != managerClass) {
            try {
                current = new Accessor(managerClass, MethodHandles.publicLookup().findStatic(
                        managerClass,
                        "getPaintjobs",
                        MethodType.methodType(Set.class, boolean.class)));
                accessor = current;
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable resolutionFailure) {
                failures++;
                delegated++;
                return reflectiveOriginal(managerClass, includeShiny, resolutionFailure);
            }
        }
        try {
            return (Set<?>) current.getPaintjobs.invokeExact(includeShiny);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable originalFailure) {
            return sneakyThrow(originalFailure);
        }
    }

    private static Set<?> reflectiveOriginal(
            Class<?> managerClass, boolean includeShiny, Throwable firstFailure) {
        try {
            Method method = managerClass.getMethod("getPaintjobs", boolean.class);
            return (Set<?>) method.invoke(null, includeShiny);
        } catch (InvocationTargetException invocation) {
            return sneakyThrow(invocation.getCause());
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable secondFailure) {
            secondFailure.addSuppressed(firstFailure);
            return sneakyThrow(secondFailure);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T sneakyThrow(Throwable failure) throws E {
        throw (E) failure;
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", PLAN_ID);
        values.put("installed", installed);
        values.put("enabled", installed && !Boolean.getBoolean(DISABLED_PROPERTY));
        values.put("strategy", "skip-notification-scan-between-reviewed-private-collection-mutations");
        values.put("dirty", dirty);
        values.put("hits", hits);
        values.put("rebuilds", rebuilds);
        values.put("mutations", mutations);
        values.put("delegated", delegated);
        values.put("failures", failures);
        return values;
    }

    static void beginSession() {
        accessor = null;
        installed = false;
        dirty = true;
        hits = 0L;
        rebuilds = 0L;
        mutations = 0L;
        delegated = 0L;
        failures = 0L;
    }

    private record Accessor(Class<?> managerClass, MethodHandle getPaintjobs) {
    }
}
