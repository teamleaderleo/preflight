package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Telemetry for AI Tweaks' per-target-selection geometry and engagement-range snapshot. */
public final class AiTweaksEngagementRangeRuntime {
    static final String PLAN_ID = "aitweaks-select-target-snapshot-v4";

    private static final AtomicLong snapshots = new AtomicLong();
    private static volatile boolean installed;

    private AiTweaksEngagementRangeRuntime() {
    }

    static void installed() {
        installed = true;
    }

    public static void snapshot() {
        snapshots.incrementAndGet();
    }

    static void beginSession() {
        installed = false;
        snapshots.set(0L);
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", PLAN_ID);
        values.put("installed", installed);
        values.put("snapshots", snapshots.get());
        return values;
    }
}
