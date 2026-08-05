package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/** State and lightweight counters for exact vanilla campaign-entity maintenance shortcuts. */
public final class CampaignEntityMaintenanceRuntime {
    static final String PLAN_ID = "campaign-entity-maintenance-v1";
    static final String DISABLED_PROPERTY = "preflight.campaign.entityMaintenance.disabled";

    private static volatile boolean enabled;
    private static volatile boolean entityScriptsInstalled;
    private static volatile boolean fleetViewInstalled;
    private static long emptyScriptLists;
    private static long nonEmptyScriptLists;

    private CampaignEntityMaintenanceRuntime() {
    }

    static void beginSession() {
        enabled = !Boolean.getBoolean(DISABLED_PROPERTY);
        entityScriptsInstalled = false;
        fleetViewInstalled = false;
        emptyScriptLists = 0L;
        nonEmptyScriptLists = 0L;
    }

    static boolean enabled() {
        return enabled;
    }

    static void entityScriptsInstalled() {
        entityScriptsInstalled = true;
    }

    static void fleetViewInstalled() {
        fleetViewInstalled = true;
    }

    public static void emptyScriptList() {
        emptyScriptLists++;
    }

    public static void nonEmptyScriptList() {
        nonEmptyScriptLists++;
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("enabled", enabled);
        result.put("entityScriptsInstalled", entityScriptsInstalled);
        result.put("fleetViewInstalled", fleetViewInstalled);
        result.put("emptyScriptLists", emptyScriptLists);
        result.put("nonEmptyScriptLists", nonEmptyScriptLists);
        return result;
    }
}
