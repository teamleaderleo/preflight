package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Gate and telemetry for allocation-free campaign radar type membership. */
public final class RadarRenderRuntime {
    static final String PLAN_ID = "campaign-radar-type-set-v1";

    private static volatile boolean INSTALLED;
    private static final AtomicLong HITS = new AtomicLong();

    private RadarRenderRuntime() {
    }

    static void installed() {
        INSTALLED = true;
    }

    public static boolean enabled() {
        return INSTALLED && EntityLookupRuntime.enabled();
    }

    public static void hit() {
        HITS.incrementAndGet();
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", PLAN_ID);
        values.put("installed", INSTALLED);
        values.put("enabled", enabled());
        values.put("cachedFrames", HITS.get());
        return values;
    }

    static void beginSession() {
        INSTALLED = false;
        HITS.set(0L);
    }
}
