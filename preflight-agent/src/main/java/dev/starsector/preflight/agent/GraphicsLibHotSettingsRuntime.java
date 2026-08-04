package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/** Telemetry for GraphicsLib's event-invalidated hot settings. */
public final class GraphicsLibHotSettingsRuntime {
    static final String PLAN_ID = "graphicslib-hot-settings-cache-v1";

    private static volatile boolean installed;
    private static long hits;
    private static long misses;
    private static long invalidations;

    private GraphicsLibHotSettingsRuntime() {
    }

    static void installed() {
        installed = true;
    }

    public static void hit() {
        hits++;
    }

    public static void miss() {
        misses++;
    }

    public static void invalidated() {
        invalidations++;
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", PLAN_ID);
        values.put("installed", installed);
        values.put("hits", hits);
        values.put("misses", misses);
        values.put("invalidations", invalidations);
        return values;
    }

    static void reset() {
        installed = false;
        hits = 0L;
        misses = 0L;
        invalidations = 0L;
    }
}
