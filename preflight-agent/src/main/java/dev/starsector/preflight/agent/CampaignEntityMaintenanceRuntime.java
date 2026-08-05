package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** State and lightweight counters for exact vanilla campaign maintenance shortcuts. */
public final class CampaignEntityMaintenanceRuntime {
    static final String PLAN_ID = "campaign-entity-maintenance-v1";
    static final String DISABLED_PROPERTY = "preflight.campaign.entityMaintenance.disabled";

    private static volatile boolean enabled;
    private static volatile boolean entityScriptsInstalled;
    private static volatile boolean fleetViewInstalled;
    private static volatile boolean marketIndustrySnapshotInstalled;
    private static long emptyScriptLists;
    private static long nonEmptyScriptLists;
    private static long emptyMarketIndustries;
    private static long nonEmptyMarketIndustries;

    private CampaignEntityMaintenanceRuntime() {
    }

    static void beginSession() {
        enabled = !Boolean.getBoolean(DISABLED_PROPERTY);
        entityScriptsInstalled = false;
        fleetViewInstalled = false;
        marketIndustrySnapshotInstalled = false;
        emptyScriptLists = 0L;
        nonEmptyScriptLists = 0L;
        emptyMarketIndustries = 0L;
        nonEmptyMarketIndustries = 0L;
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

    static void marketIndustrySnapshotInstalled() {
        marketIndustrySnapshotInstalled = true;
    }

    public static void emptyScriptList() {
        emptyScriptLists++;
    }

    public static void nonEmptyScriptList() {
        nonEmptyScriptLists++;
    }

    /** Retains vanilla's defensive copy for non-empty plugin lists and allocates nothing when empty. */
    public static Iterator<?> marketIndustrySnapshotIterator(List<?> values) {
        if (values.isEmpty()) {
            emptyMarketIndustries++;
            return Collections.emptyIterator();
        }
        nonEmptyMarketIndustries++;
        return new ArrayList<>(values).iterator();
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("enabled", enabled);
        result.put("entityScriptsInstalled", entityScriptsInstalled);
        result.put("fleetViewInstalled", fleetViewInstalled);
        result.put("marketIndustrySnapshotInstalled", marketIndustrySnapshotInstalled);
        result.put("emptyScriptLists", emptyScriptLists);
        result.put("nonEmptyScriptLists", nonEmptyScriptLists);
        result.put("emptyMarketIndustries", emptyMarketIndustries);
        result.put("nonEmptyMarketIndustries", nonEmptyMarketIndustries);
        return result;
    }
}
