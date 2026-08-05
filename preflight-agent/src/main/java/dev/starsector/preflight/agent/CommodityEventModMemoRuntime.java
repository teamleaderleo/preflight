package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/** Counters and the runtime gate for the exact vanilla commodity event-mod memo. */
public final class CommodityEventModMemoRuntime {
    static final String PLAN_ID = "commodity-event-mod-memo-v1";
    public static final String ENABLED_PROPERTY = "preflight.campaign.eventModMemo";
    public static final String DISABLED_PROPERTY = "preflight.campaign.eventModMemo.disabled";

    private static volatile boolean installed;
    private static volatile boolean enabled;
    // Starsector advances campaign markets on one thread. These intentionally approximate counters
    // avoid putting an atomic read-modify-write on every commodity's frame-time fast path.
    private static long hits;
    private static long delegated;
    private static long fastValidationUnavailable;

    private CommodityEventModMemoRuntime() {
    }

    static boolean ready() {
        return true;
    }

    static void installed() {
        installed = true;
        enabled = Boolean.getBoolean(ENABLED_PROPERTY)
                && !Boolean.getBoolean(DISABLED_PROPERTY);
    }

    public static boolean enabled() {
        return installed && enabled;
    }

    public static void hit() {
        hits++;
    }

    public static void delegated() {
        delegated++;
    }

    /** Permanently fails open if the exact MutableStat accessor was not installed. */
    public static void fastValidationUnavailable() {
        fastValidationUnavailable++;
        enabled = false;
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", PLAN_ID);
        values.put("installed", installed);
        values.put("enabled", enabled());
        values.put("validationStrategy",
                "clean-stat-and-direct-exact-key-fast-path-with-exact-post-state-fingerprint");
        values.put("hits", hits);
        values.put("delegated", delegated);
        values.put("fastValidationUnavailable", fastValidationUnavailable);
        return values;
    }

    static void beginSession() {
        installed = false;
        enabled = false;
        hits = 0L;
        delegated = 0L;
        fastValidationUnavailable = 0L;
    }
}
