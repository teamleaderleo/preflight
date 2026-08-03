package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/** Low-overhead counters for GraphicsLib's per-render fleet-manager memo. */
public final class GraphicsLibInsigniaManagerCacheRuntime {
    static final String PLAN_ID = "graphicslib-insignia-manager-cache-v1";

    private static volatile boolean enabled;
    private static long hits;
    private static long misses;
    private static long hitInsideNanos;
    private static long missInsideNanos;

    private GraphicsLibInsigniaManagerCacheRuntime() {
    }

    static void beginSession() {
        enabled = false;
        hits = 0;
        misses = 0;
        hitInsideNanos = 0;
        missInsideNanos = 0;
    }

    static void configure(boolean requested) {
        enabled = requested;
    }

    static boolean ready() {
        return enabled;
    }

    /** The renderer and shutdown report are single-writer/read-after-join in ordinary play. */
    public static void hit(long startedNanos) {
        hits++;
        hitInsideNanos += elapsedSince(startedNanos);
    }

    /** The renderer and shutdown report are single-writer/read-after-join in ordinary play. */
    public static void miss(long startedNanos) {
        misses++;
        missInsideNanos += elapsedSince(startedNanos);
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", PLAN_ID);
        values.put("enabled", enabled);
        values.put("hits", hits);
        values.put("misses", misses);
        values.put("requests", hits + misses);
        values.put("hitInsideNanos", hitInsideNanos);
        values.put("missInsideNanos", missInsideNanos);
        values.put("meanHitMicros", meanMicros(hitInsideNanos, hits));
        values.put("meanMissMicros", meanMicros(missInsideNanos, misses));
        values.put("estimatedAvoidedMillisFromSessionMeans", estimatedAvoidedMillis());
        return values;
    }

    private static long elapsedSince(long startedNanos) {
        return Math.max(0L, System.nanoTime() - startedNanos);
    }

    private static double meanMicros(long nanos, long calls) {
        return calls == 0 ? 0.0 : nanos / (calls * 1_000.0);
    }

    private static double estimatedAvoidedMillis() {
        if (hits == 0 || misses == 0) {
            return 0.0;
        }
        double projectedVanillaHitNanos = (missInsideNanos / (double) misses) * hits;
        return Math.max(0.0, projectedVanillaHitNanos - hitInsideNanos) / 1_000_000.0;
    }
}
