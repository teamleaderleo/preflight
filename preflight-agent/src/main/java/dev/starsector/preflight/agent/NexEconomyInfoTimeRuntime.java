package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact, opt-in phase timing for Nexerelin's economy-info cache rebuild. */
public final class NexEconomyInfoTimeRuntime {
    static final String PLAN_ID = "nexerelin-economy-info-time-probe-v1";
    static final String DISABLED_PROPERTY = "preflight.campaign.nexEconomyInfoTimes.disabled";

    static final int TOTAL = 0;
    static final int CACHE_RESET = 1;
    static final int MARKET_SNAPSHOT = 2;
    static final int COMMODITY_SCAN = 3;
    static final int PRODUCER_PASS = 4;
    static final int IMPORTER_PASS = 5;
    static final int DEMAND_PASS = 6;
    static final int HEAVY_INDUSTRY = 7;
    static final int MARKET_SUMMARY = 8;

    static final int COMMODITIES_VISITED = 0;
    static final int PRODUCER_CANDIDATES = 1;
    static final int IMPORTER_CANDIDATES = 2;
    static final int DEMAND_CANDIDATES = 3;
    static final int FIRST_RUN_MARKETS = 4;
    static final int REFRESH_FACTIONS = 5;
    static final int SUMMARY_MARKETS = 6;

    private static final String[] PHASE_NAMES = {
            "total", "cacheReset", "marketSnapshot", "commodityScan", "producerPass",
            "importerPass", "demandPass", "heavyIndustry", "marketSummary"
    };
    private static final String[] COUNTER_NAMES = {
            "commoditiesVisited", "producerCandidates", "importerCandidates",
            "demandCandidates", "firstRunMarkets", "refreshFactions", "summaryMarkets"
    };
    private static final Stats[] phases = new Stats[PHASE_NAMES.length];
    private static final long[] counters = new long[COUNTER_NAMES.length];
    private static final List<SlowSpan> slowSpans = new ArrayList<>();

    private static volatile boolean enabled;
    private static volatile boolean installed;
    private static long firstRunCalls;
    private static long refreshCalls;

    static {
        for (int id = 0; id < phases.length; id++) phases[id] = new Stats();
    }

    private NexEconomyInfoTimeRuntime() {
    }

    static synchronized void beginSession(boolean requested) {
        enabled = requested && !Boolean.getBoolean(DISABLED_PROPERTY);
        installed = false;
        firstRunCalls = 0L;
        refreshCalls = 0L;
        for (Stats stats : phases) stats.reset();
        for (int id = 0; id < counters.length; id++) counters[id] = 0L;
        slowSpans.clear();
    }

    static boolean enabled() {
        return enabled;
    }

    static void installed() {
        installed = true;
    }

    /** Starts the inclusive method timer and retains whether this was the first-run path. */
    public static long beginCall(boolean firstRun) {
        if (!enabled) return 0L;
        if (firstRun) firstRunCalls++;
        else refreshCalls++;
        return System.nanoTime();
    }

    /** Returns zero while disabled so every woven phase exit remains inert. */
    public static long enter(int phase) {
        return enabled && validPhase(phase) ? System.nanoTime() : 0L;
    }

    /** Records one reviewed loop cardinality without retaining game objects. */
    public static void visit(int counter) {
        if (enabled && counter >= 0 && counter < counters.length) counters[counter]++;
    }

    /** Records one phase without changing the cache builder's return or exception behavior. */
    public static void exit(int phase, long startedNanos) {
        if (!enabled || !validPhase(phase) || startedNanos == 0L) return;
        try {
            long duration = System.nanoTime() - startedNanos;
            if (duration <= 0L) return;
            record(phases[phase], duration);
            if (duration >= 1_000_000L) retain(new SlowSpan(
                    PHASE_NAMES[phase], duration, System.currentTimeMillis()));
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Discovery telemetry woven into a mod cache rebuild must fail inertly.
        }
    }

    static synchronized Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("enabled", enabled);
        result.put("installed", installed);
        result.put("firstRunCalls", firstRunCalls);
        result.put("refreshCalls", refreshCalls);

        List<Map<String, Object>> phaseReports = new ArrayList<>();
        for (int id = 0; id < phases.length; id++) {
            phaseReports.add(phases[id].report(PHASE_NAMES[id]));
        }
        result.put("phases", phaseReports);

        Map<String, Object> counterReport = new LinkedHashMap<>();
        for (int id = 0; id < counters.length; id++) {
            counterReport.put(COUNTER_NAMES[id], counters[id]);
        }
        result.put("cardinality", counterReport);

        List<SlowSpan> ordered = new ArrayList<>(slowSpans);
        ordered.sort(Comparator.comparingLong(SlowSpan::durationNanos).reversed());
        result.put("slowSpans", ordered.stream().map(SlowSpan::report).toList());
        return result;
    }

    static void reset() {
        beginSession(false);
    }

    private static boolean validPhase(int phase) {
        return phase >= 0 && phase < phases.length;
    }

    private static void record(Stats stats, long duration) {
        stats.calls++;
        stats.totalNanos += duration;
        stats.maximumNanos = Math.max(stats.maximumNanos, duration);
        if (duration > 16_666_667L) stats.overSixteenMillis++;
        if (duration > 33_333_333L) stats.overThirtyThreeMillis++;
        if (duration > 50_000_000L) stats.overFiftyMillis++;
        if (duration > 100_000_000L) stats.overOneHundredMillis++;
    }

    private static synchronized void retain(SlowSpan span) {
        slowSpans.add(span);
        slowSpans.sort(Comparator.comparingLong(SlowSpan::durationNanos).reversed());
        if (slowSpans.size() > 64) slowSpans.remove(slowSpans.size() - 1);
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
            overSixteenMillis = overThirtyThreeMillis = 0L;
            overFiftyMillis = overOneHundredMillis = 0L;
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

    private record SlowSpan(String phase, long durationNanos, long endEpochMillis) {
        Map<String, Object> report() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("phase", phase);
            result.put("durationMillis", durationNanos / 1_000_000.0);
            result.put("endEpochMillis", endEpochMillis);
            return result;
        }
    }
}
