package dev.starsector.preflight.agent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Validates a cached HashMap entry without repeating its hash lookup on every commodity frame. */
public final class EventModMapSnapshotRuntime {
    private static volatile boolean resolutionAttempted;
    private static volatile VarHandle modCountHandle;
    private static long captures;
    private static long unavailableCaptures;
    private static long invalidations;

    private EventModMapSnapshotRuntime() {
    }

    /** Captures the exact backing map, structural generation, and node for one key. */
    public static Object capture(Object mapObject, Object key) {
        if (!(mapObject instanceof HashMap<?, ?> map)) {
            unavailableCaptures++;
            return null;
        }
        VarHandle handle = modCountHandle();
        if (handle == null) {
            unavailableCaptures++;
            return null;
        }
        try {
            Map.Entry<?, ?> found = null;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (Objects.equals(key, entry.getKey())) {
                    found = entry;
                    break;
                }
            }
            captures++;
            return new Snapshot(map, found, (int) handle.get(map));
        } catch (RuntimeException | LinkageError failure) {
            unavailableCaptures++;
            return null;
        }
    }

    /**
     * Returns true only while the same map has the same structure and cached node value.
     *
     * <p>A same-key {@code put} does not increment HashMap.modCount, but it updates the existing
     * node's value, which the identity comparison catches. Remove/reinsert and all other structural
     * changes alter modCount. The caller separately compares the StatMod's public value and
     * description fields, covering direct mutation of the cached value object itself.
     */
    public static boolean unchanged(Object snapshotObject, Object currentMap, Object expectedValue) {
        if (!(snapshotObject instanceof Snapshot snapshot) || currentMap != snapshot.map) {
            invalidations++;
            return false;
        }
        VarHandle handle = modCountHandle();
        if (handle == null) {
            invalidations++;
            return false;
        }
        try {
            if ((int) handle.get(snapshot.map) != snapshot.modCount) {
                invalidations++;
                return false;
            }
            Object currentValue = snapshot.entry == null ? null : snapshot.entry.getValue();
            if (currentValue != expectedValue) {
                invalidations++;
                return false;
            }
            return true;
        } catch (RuntimeException | LinkageError failure) {
            invalidations++;
            return false;
        }
    }

    static boolean available() {
        return modCountHandle() != null;
    }

    static long captures() {
        return captures;
    }

    static long unavailableCaptures() {
        return unavailableCaptures;
    }

    static long invalidations() {
        return invalidations;
    }

    static void reset() {
        captures = 0L;
        unavailableCaptures = 0L;
        invalidations = 0L;
    }

    private static VarHandle modCountHandle() {
        if (resolutionAttempted) return modCountHandle;
        synchronized (EventModMapSnapshotRuntime.class) {
            if (resolutionAttempted) return modCountHandle;
            try {
                modCountHandle = MethodHandles.privateLookupIn(HashMap.class, MethodHandles.lookup())
                        .findVarHandle(HashMap.class, "modCount", int.class);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                modCountHandle = null;
            } finally {
                resolutionAttempted = true;
            }
            return modCountHandle;
        }
    }

    private record Snapshot(HashMap<?, ?> map, Map.Entry<?, ?> entry, int modCount) {
    }
}
