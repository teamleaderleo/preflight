package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded telemetry for the exact Detailed Combat Results state-map reuse plan. */
public final class DetailedCombatResultsStateReuseRuntime {
    static final String PLAN_ID = "detailed-combat-results-state-map-reuse-v1";
    static final String ENABLED_PROPERTY = "preflight.combat.detailedResultsStateReuse";

    private static volatile boolean telemetryEnabled;
    private static volatile boolean installed;
    private static long historyFrames;
    private static long historyEntriesBefore;
    private static long historyEntriesAfter;
    private static long currentProjectiles;
    private static long shipFrames;
    private static long shipsLastFrame;

    private DetailedCombatResultsStateReuseRuntime() {
    }

    static void beginSession() {
        beginSession(true);
    }

    static void beginSession(boolean telemetryRequested) {
        telemetryEnabled = telemetryRequested;
        installed = false;
        historyFrames = 0L;
        historyEntriesBefore = 0L;
        historyEntriesAfter = 0L;
        currentProjectiles = 0L;
        shipFrames = 0L;
        shipsLastFrame = 0L;
    }

    static void installed() {
        installed = true;
    }

    /** Called from the exact transformed mod class after one in-place history refresh. */
    public static void historyFrame(int before, int after, int current) {
        if (!telemetryEnabled) return;
        historyFrames++;
        historyEntriesBefore += Math.max(0, before);
        historyEntriesAfter += Math.max(0, after);
        currentProjectiles += Math.max(0, current);
    }

    /** Called after rotating the three distinct ship-state maps. */
    public static void shipFrame(int lastFrameShips) {
        if (!telemetryEnabled) return;
        shipFrames++;
        shipsLastFrame += Math.max(0, lastFrameShips);
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("enabled", Boolean.getBoolean(ENABLED_PROPERTY));
        result.put("telemetryEnabled", telemetryEnabled);
        result.put("installed", installed);
        result.put("historyFrames", historyFrames);
        result.put("historyEntriesBefore", historyEntriesBefore);
        result.put("historyEntriesAfter", historyEntriesAfter);
        result.put("currentProjectiles", currentProjectiles);
        result.put("shipFrames", shipFrames);
        result.put("shipsLastFrame", shipsLastFrame);
        return result;
    }
}
