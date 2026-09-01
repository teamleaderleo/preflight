package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reuses one private empty array for exact empty combat-listener lists. */
public final class CombatListenerRangeSnapshotRuntime {
    static final String PLAN_ID = "vanilla-combat-listener-range-empty-snapshot-v1";
    static final String ENABLED_PROPERTY = "preflight.combat.listenerRangeEmptySnapshot";
    private static final Object[] EMPTY_SNAPSHOT = new Object[0];

    private static volatile boolean enabled;
    private static volatile boolean telemetryEnabled;
    private static volatile boolean installed;
    private static long emptySnapshots;
    private static long nonEmptyDelegations;
    private static long nonArrayListDelegations;

    private CombatListenerRangeSnapshotRuntime() {
    }

    static void beginSession() {
        beginSession(true);
    }

    static void beginSession(boolean telemetryRequested) {
        enabled = Boolean.getBoolean(ENABLED_PROPERTY);
        telemetryEnabled = telemetryRequested;
        installed = false;
        emptySnapshots = 0L;
        nonEmptyDelegations = 0L;
        nonArrayListDelegations = 0L;
    }

    static void installed() {
        installed = true;
    }

    /**
     * Returns an array private to transformed range-query loops. Unknown or non-empty sources keep
     * the original fresh-snapshot behavior.
     */
    public static Object[] snapshot(List<?> values) {
        if (!enabled) return values.toArray();
        if (values.getClass() != ArrayList.class) {
            if (telemetryEnabled) nonArrayListDelegations++;
            return values.toArray();
        }
        if (!values.isEmpty()) {
            if (telemetryEnabled) nonEmptyDelegations++;
            return values.toArray();
        }
        if (telemetryEnabled) emptySnapshots++;
        return EMPTY_SNAPSHOT;
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("enabled", enabled);
        result.put("telemetryEnabled", telemetryEnabled);
        result.put("installed", installed);
        result.put("emptySnapshots", emptySnapshots);
        result.put("nonEmptyDelegations", nonEmptyDelegations);
        result.put("nonArrayListDelegations", nonArrayListDelegations);
        return result;
    }
}
