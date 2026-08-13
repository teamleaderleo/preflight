package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/** Tracks the vanilla profiler gate used to avoid disabled dynamic fleet-AI sample labels. */
public final class FleetAiProfilerRuntime {
    static final String PLAN_ID = "vanilla-fleet-ai-profiler-label-v1";
    static final String DISABLED_PROPERTY = "preflight.campaign.fleetAiProfiler.disabled";

    private static volatile boolean enabled;
    private static volatile boolean fleetInstalled;
    private static volatile boolean profilerInstalled;
    private static volatile boolean profilerEnabled;
    private static long labelsAvoided;
    private static long labelsDelegated;

    private FleetAiProfilerRuntime() {
    }

    static void beginSession() {
        enabled = !Boolean.getBoolean(DISABLED_PROPERTY);
        fleetInstalled = false;
        profilerInstalled = false;
        profilerEnabled = false;
        labelsAvoided = 0L;
        labelsDelegated = 0L;
    }

    static boolean enabled() {
        return enabled;
    }

    static void fleetInstalled() {
        fleetInstalled = true;
    }

    static void profilerInstalled() {
        profilerInstalled = true;
    }

    /** Called by the exact profiler toggle after vanilla stores its new state. */
    public static void profilerState(boolean active) {
        profilerEnabled = active;
    }

    /** True retains vanilla's dynamic label; false permits its allocation-free constant label. */
    public static boolean abilityLabelNeeded() {
        if (!enabled || !fleetInstalled || !profilerInstalled || profilerEnabled) {
            labelsDelegated++;
            return true;
        }
        labelsAvoided++;
        return false;
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("enabled", enabled);
        result.put("fleetInstalled", fleetInstalled);
        result.put("profilerInstalled", profilerInstalled);
        result.put("profilerEnabled", profilerEnabled);
        result.put("labelsAvoided", labelsAvoided);
        result.put("labelsDelegated", labelsDelegated);
        return result;
    }
}
