package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Passive inclusive timers for exact campaign catch-up call sites. */
public final class CampaignCallTimeRuntime {
    static final String PLAN_ID = "campaign-catch-up-call-time-probe-v1";

    static final int NEX_FLEET_POOL_ADVANCE = 0;
    static final int NEX_ROUTE_SPAWN_DESPAWN = 1;
    static final int NEX_RESOURCE_POOL_UPDATE = 2;
    static final int NEX_DIPLOMACY_ADVANCE = 3;
    static final int VANILLA_REP_TRACKER_ADVANCE = 4;
    static final int VANILLA_ECONOMY_FLEET_ADVANCE = 5;

    private static final int TOP_LIMIT = 32;
    private static final String[] NAMES = {
            "nexFleetPoolAdvance",
            "nexRouteSpawnAndDespawn",
            "nexResourcePoolUpdate",
            "nexDiplomacyAdvance",
            "vanillaRepTrackerAdvance",
            "vanillaEconomyFleetAdvance"
    };
    private static final boolean[] installed = new boolean[NAMES.length];
    private static final long[] calls = new long[NAMES.length];
    private static final long[] totalNanos = new long[NAMES.length];
    private static final long[] maximumNanos = new long[NAMES.length];
    private static final long[] overOneMillis = new long[NAMES.length];
    private static final long[] overFiveMillis = new long[NAMES.length];
    private static final long[] overSixteenMillis = new long[NAMES.length];
    private static final long[] overThirtyThreeMillis = new long[NAMES.length];
    private static final long[] overOneHundredMillis = new long[NAMES.length];
    private static final long[][] topDurations = new long[NAMES.length][TOP_LIMIT];
    private static final long[][] topEndEpochMillis = new long[NAMES.length][TOP_LIMIT];
    private static final int[] topCounts = new int[NAMES.length];

    private static volatile boolean enabled;

    private CampaignCallTimeRuntime() {
    }

    static void beginSession(boolean requested) {
        enabled = requested;
        for (int id = 0; id < NAMES.length; id++) {
            installed[id] = false;
            calls[id] = 0L;
            totalNanos[id] = 0L;
            maximumNanos[id] = 0L;
            overOneMillis[id] = 0L;
            overFiveMillis[id] = 0L;
            overSixteenMillis[id] = 0L;
            overThirtyThreeMillis[id] = 0L;
            overOneHundredMillis[id] = 0L;
            topCounts[id] = 0;
            for (int slot = 0; slot < TOP_LIMIT; slot++) {
                topDurations[id][slot] = 0L;
                topEndEpochMillis[id][slot] = 0L;
            }
        }
    }

    static boolean enabled() {
        return enabled;
    }

    static void installed(int id) {
        if (valid(id)) installed[id] = true;
    }

    /** Returns the entry token woven into a reviewed method. */
    public static long enter(int id) {
        return enabled && valid(id) ? System.nanoTime() : 0L;
    }

    /** Records one inclusive method duration and never lets diagnostics affect the game. */
    public static void exit(int id, long startedNanos) {
        if (!enabled || !valid(id) || startedNanos == 0L) return;
        try {
            long duration = System.nanoTime() - startedNanos;
            if (duration <= 0L) return;
            calls[id]++;
            totalNanos[id] += duration;
            maximumNanos[id] = Math.max(maximumNanos[id], duration);
            if (duration > 1_000_000L) overOneMillis[id]++;
            if (duration > 5_000_000L) overFiveMillis[id]++;
            if (duration > 16_666_667L) overSixteenMillis[id]++;
            if (duration > 33_333_333L) overThirtyThreeMillis[id]++;
            if (duration > 100_000_000L) overOneHundredMillis[id]++;
            retainSlowCall(id, duration);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // These calls are woven into campaign scripts. Telemetry must fail inertly.
        }
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("enabled", enabled);
        List<Map<String, Object>> seams = new ArrayList<>();
        for (int id = 0; id < NAMES.length; id++) {
            Map<String, Object> seam = new LinkedHashMap<>();
            seam.put("name", NAMES[id]);
            seam.put("installed", installed[id]);
            seam.put("calls", calls[id]);
            seam.put("totalMillis", totalNanos[id] / 1_000_000.0);
            seam.put("averageMicros", calls[id] == 0L
                    ? null : totalNanos[id] / 1_000.0 / calls[id]);
            seam.put("maximumMillis", maximumNanos[id] / 1_000_000.0);
            seam.put("over1Millis", overOneMillis[id]);
            seam.put("over5Millis", overFiveMillis[id]);
            seam.put("over16Millis", overSixteenMillis[id]);
            seam.put("over33Millis", overThirtyThreeMillis[id]);
            seam.put("over100Millis", overOneHundredMillis[id]);
            seam.put("slowestCalls", slowestCalls(id));
            seams.add(seam);
        }
        result.put("seams", seams);
        return result;
    }

    static void reset() {
        beginSession(false);
    }

    private static void retainSlowCall(int id, long duration) {
        int count = topCounts[id];
        int slot;
        if (count < TOP_LIMIT) {
            slot = count;
            topCounts[id] = count + 1;
        } else {
            slot = 0;
            for (int candidate = 1; candidate < TOP_LIMIT; candidate++) {
                if (topDurations[id][candidate] < topDurations[id][slot]) slot = candidate;
            }
            if (duration <= topDurations[id][slot]) return;
        }
        topDurations[id][slot] = duration;
        topEndEpochMillis[id][slot] = System.currentTimeMillis();
    }

    private static List<Map<String, Object>> slowestCalls(int id) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (int slot = 0; slot < topCounts[id]; slot++) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("durationMillis", topDurations[id][slot] / 1_000_000.0);
            value.put("endEpochMillis", topEndEpochMillis[id][slot]);
            values.add(value);
        }
        values.sort(Comparator.comparingDouble(
                value -> -((Number) value.get("durationMillis")).doubleValue()));
        return values;
    }

    private static boolean valid(int id) {
        return id >= 0 && id < NAMES.length;
    }
}
