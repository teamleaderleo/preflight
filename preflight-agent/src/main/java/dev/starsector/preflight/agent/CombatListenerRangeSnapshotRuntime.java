package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reuses private combat-listener snapshots only after full ordered identity validation. */
public final class CombatListenerRangeSnapshotRuntime {
    static final String PLAN_ID = "vanilla-combat-listener-range-snapshot-reuse-v1";
    static final String ENABLED_PROPERTY = "preflight.combat.listenerRangeSnapshotReuse";
    private static final int MAX_SNAPSHOT_OWNERS = 512;
    private static final Object[] EMPTY_SNAPSHOT = new Object[0];
    private static final IdentityHashMap<List<?>, Object[]> SNAPSHOTS = new IdentityHashMap<>();

    private static volatile boolean enabled;
    private static volatile boolean installed;
    private static long hits;
    private static long rebuilds;
    private static long comparedElements;
    private static long emptySnapshots;
    private static long nonArrayListDelegations;
    private static long evictions;
    private static long failures;

    private CombatListenerRangeSnapshotRuntime() {
    }

    static void beginSession() {
        enabled = Boolean.getBoolean(ENABLED_PROPERTY);
        installed = false;
        synchronized (SNAPSHOTS) {
            SNAPSHOTS.clear();
        }
        hits = 0L;
        rebuilds = 0L;
        comparedElements = 0L;
        emptySnapshots = 0L;
        nonArrayListDelegations = 0L;
        evictions = 0L;
        failures = 0L;
    }

    static boolean enabled() {
        return enabled;
    }

    static void installed() {
        installed = true;
    }

    /**
     * Returns an array private to transformed range-query loops. A cached array is returned only
     * when the source list still has the same size, order, and element identities.
     */
    public static Object[] snapshot(List<?> values) {
        if (!enabled) {
            return values.toArray();
        }
        if (values.getClass() != ArrayList.class) {
            nonArrayListDelegations++;
            return values.toArray();
        }
        if (values.isEmpty()) {
            emptySnapshots++;
            return EMPTY_SNAPSHOT;
        }
        synchronized (SNAPSHOTS) {
            try {
                Object[] current = SNAPSHOTS.get(values);
                if (current != null && matches(values, current)) {
                    hits++;
                    comparedElements += current.length;
                    return current;
                }
                Object[] replacement = values.toArray();
                if (current == null && SNAPSHOTS.size() >= MAX_SNAPSHOT_OWNERS) {
                    SNAPSHOTS.clear();
                    evictions++;
                }
                SNAPSHOTS.put(values, replacement);
                rebuilds++;
                return replacement;
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable failure) {
                failures++;
                // Reuse is optional. Preserve a fresh per-call snapshot on any uncertainty.
                return values.toArray();
            }
        }
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("enabled", enabled);
        result.put("installed", installed);
        result.put("hits", hits);
        result.put("rebuilds", rebuilds);
        result.put("comparedElements", comparedElements);
        result.put("emptySnapshots", emptySnapshots);
        result.put("nonArrayListDelegations", nonArrayListDelegations);
        result.put("evictions", evictions);
        result.put("failures", failures);
        synchronized (SNAPSHOTS) {
            result.put("snapshotOwners", SNAPSHOTS.size());
        }
        result.put("maximumSnapshotOwners", MAX_SNAPSHOT_OWNERS);
        return result;
    }

    private static boolean matches(List<?> values, Object[] snapshot) {
        if (values.size() != snapshot.length) return false;
        for (int index = 0; index < snapshot.length; index++) {
            if (values.get(index) != snapshot[index]) return false;
        }
        return true;
    }
}
