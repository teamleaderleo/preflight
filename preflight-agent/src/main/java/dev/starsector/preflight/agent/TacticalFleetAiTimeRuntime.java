package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact, opt-in semantic timing within vanilla {@code TacticalModule.advance(float)}. */
public final class TacticalFleetAiTimeRuntime {
    static final String PLAN_ID = "vanilla-tactical-fleet-ai-time-probe-v1";
    static final String DISABLED_PROPERTY = "preflight.campaign.tacticalFleetAiTimes.disabled";

    static final int EVERY_FRAME = 0;
    static final int AVOID_LIST = 1;
    static final int FLEET_LIST = 2;
    static final int OTHER_FLEETS = 3;
    static final int ENCOUNTER_OPTION = 4;
    static final int POST_SCAN = 5;
    static final int VISIBILITY = 6;
    static final int HOSTILITY = 7;
    static final int PURSUIT = 8;
    static final int BATTLE_JOIN = 9;
    static final int NEARBY_FLEETS = 10;
    static final int FLEET_INFLATION = 11;
    static final int NEARBY_FLEET_LIST = 12;
    static final int FLEET_STRENGTH = 13;

    private static final String[] NAMES = {
            "everyFrame", "avoidList", "fleetList", "otherFleets", "encounterOption", "postScan",
            "visibility", "hostility", "pursuit", "battleJoin", "nearbyFleets",
            "fleetInflation", "nearbyFleetList", "fleetStrength"
    };
    private static final Stats[] phases = new Stats[NAMES.length];
    private static final List<SlowSpan> slowSpans = new ArrayList<>();

    private static volatile boolean enabled;
    private static volatile boolean installed;
    private static long candidateFleetsVisited;
    private static long nearbyCandidatesVisited;
    private static long strengthModeCalls;
    private static long fleetPointModeCalls;

    static {
        for (int id = 0; id < phases.length; id++) phases[id] = new Stats();
    }

    private TacticalFleetAiTimeRuntime() {
    }

    static synchronized void beginSession(boolean requested) {
        enabled = requested && !Boolean.getBoolean(DISABLED_PROPERTY);
        installed = false;
        candidateFleetsVisited = 0L;
        nearbyCandidatesVisited = 0L;
        strengthModeCalls = 0L;
        fleetPointModeCalls = 0L;
        for (Stats stats : phases) stats.reset();
        slowSpans.clear();
    }

    static boolean enabled() {
        return enabled;
    }

    static void installed() {
        installed = true;
    }

    public static long enter(int phase) {
        return enabled && phase >= 0 && phase < phases.length ? System.nanoTime() : 0L;
    }

    public static void candidateVisited() {
        if (enabled) candidateFleetsVisited++;
    }

    public static void nearbyCandidateVisited() {
        if (enabled) nearbyCandidatesVisited++;
    }

    public static void nearbyMode(boolean computesStrength) {
        if (!enabled) return;
        if (computesStrength) {
            strengthModeCalls++;
        } else {
            fleetPointModeCalls++;
        }
    }

    public static void exit(Object tacticalAi, int phase, long startedNanos) {
        if (!enabled || phase < 0 || phase >= phases.length || startedNanos == 0L) return;
        try {
            long duration = System.nanoTime() - startedNanos;
            if (duration <= 0L) return;
            Stats stats = phases[phase];
            stats.calls++;
            stats.totalNanos += duration;
            stats.maximumNanos = Math.max(stats.maximumNanos, duration);
            if (duration > 16_666_667L) stats.overSixteenMillis++;
            if (duration > 33_333_333L) stats.overThirtyThreeMillis++;
            if (duration > 50_000_000L) stats.overFiftyMillis++;
            if (duration > 100_000_000L) stats.overOneHundredMillis++;
            if (duration >= 2_000_000L) retain(new SlowSpan(
                    NAMES[phase], tacticalAi == null ? 0 : System.identityHashCode(tacticalAi),
                    duration, System.currentTimeMillis()));
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Discovery telemetry woven into vanilla fleet AI must fail inertly.
        }
    }

    static synchronized Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("enabled", enabled);
        result.put("installed", installed);
        result.put("candidateFleetsVisited", candidateFleetsVisited);
        result.put("nearbyCandidatesVisited", nearbyCandidatesVisited);
        result.put("strengthModeCalls", strengthModeCalls);
        result.put("fleetPointModeCalls", fleetPointModeCalls);
        result.put("preEncounterDeclines", Math.max(
                0L, candidateFleetsVisited - phases[ENCOUNTER_OPTION].calls));
        List<Map<String, Object>> reports = new ArrayList<>();
        for (int id = 0; id < phases.length; id++) reports.add(phases[id].report(NAMES[id]));
        result.put("phases", reports);
        List<SlowSpan> ordered = new ArrayList<>(slowSpans);
        ordered.sort(Comparator.comparingLong(SlowSpan::durationNanos).reversed());
        result.put("slowSpans", ordered.stream().map(SlowSpan::report).toList());
        return result;
    }

    static void reset() {
        beginSession(false);
    }

    private static synchronized void retain(SlowSpan span) {
        slowSpans.add(span);
        slowSpans.sort(Comparator.comparingLong(SlowSpan::durationNanos).reversed());
        if (slowSpans.size() > 32) slowSpans.remove(slowSpans.size() - 1);
    }

    private static final class Stats {
        long calls;
        long totalNanos;
        long maximumNanos;
        long overSixteenMillis;
        long overThirtyThreeMillis;
        long overFiftyMillis;
        long overOneHundredMillis;

        void reset() {
            calls = totalNanos = maximumNanos = 0L;
            overSixteenMillis = overThirtyThreeMillis = overFiftyMillis = overOneHundredMillis = 0L;
        }

        Map<String, Object> report(String name) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", name);
            result.put("calls", calls);
            result.put("totalMillis", totalNanos / 1_000_000.0);
            result.put("averageMicros", calls == 0L ? null : totalNanos / 1_000.0 / calls);
            result.put("maximumMillis", maximumNanos / 1_000_000.0);
            result.put("over16Millis", overSixteenMillis);
            result.put("over33Millis", overThirtyThreeMillis);
            result.put("over50Millis", overFiftyMillis);
            result.put("over100Millis", overOneHundredMillis);
            return result;
        }
    }

    private record SlowSpan(
            String phase, int tacticalAiIdentity, long durationNanos, long endEpochMillis) {
        Map<String, Object> report() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("phase", phase);
            result.put("tacticalAiIdentity", Integer.toUnsignedString(tacticalAiIdentity));
            result.put("durationMillis", durationNanos / 1_000_000.0);
            result.put("endEpochMillis", endEpochMillis);
            return result;
        }
    }
}
