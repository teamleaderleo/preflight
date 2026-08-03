package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/** Low-overhead counters for GraphicsLib's per-render fleet-manager memo. */
public final class GraphicsLibInsigniaManagerCacheRuntime {
    static final String PLAN_ID = "graphicslib-insignia-manager-cache-v1";

    private static volatile boolean enabled;
    private static long hits;
    private static long misses;

    private GraphicsLibInsigniaManagerCacheRuntime() {
    }

    static void beginSession() {
        enabled = false;
        hits = 0;
        misses = 0;
    }

    static void configure(boolean requested) {
        enabled = requested;
    }

    static boolean ready() {
        return enabled;
    }

    /** The renderer and shutdown report are single-writer/read-after-join in ordinary play. */
    public static void hit() {
        hits++;
    }

    /** The renderer and shutdown report are single-writer/read-after-join in ordinary play. */
    public static void miss() {
        misses++;
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", PLAN_ID);
        values.put("enabled", enabled);
        values.put("hits", hits);
        values.put("misses", misses);
        values.put("requests", hits + misses);
        return values;
    }
}
