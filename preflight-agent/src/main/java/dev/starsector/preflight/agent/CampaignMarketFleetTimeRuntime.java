package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Sampled attribution below the two dominant campaign market/fleet owners. */
public final class CampaignMarketFleetTimeRuntime {
    static final String PLAN_ID = "campaign-market-fleet-call-time-probe-v1";

    static final int MARKET_POWER = 0;
    static final int MARKET_MEMORY = 1;
    static final int MARKET_PEOPLE = 2;
    static final int MARKET_COMMODITY_STATS = 3;
    static final int MARKET_EVENT_MODS = 4;
    static final int FLEET_OFFICERS = 5;
    static final int FLEET_BASE_ENTITY = 6;
    static final int FLEET_STATS = 7;
    static final int FLEET_ACCIDENTS = 8;
    static final int FLEET_LOGISTICS = 9;
    static final int FLEET_UPDATE_COUNTS = 10;
    static final int FLEET_MOVEMENT = 11;
    static final int FLEET_BUFFS = 12;
    static final int FLEET_VIEW = 13;

    static final int MARKET_CONDITIONS = 0;
    static final int MARKET_SUBMARKETS = 1;
    static final int MARKET_INDUSTRIES = 2;
    static final int FLEET_AI = 3;
    static final int FLEET_HULLMOD_FLEET = 4;
    static final int FLEET_HULLMOD_SHIP = 5;

    private static final String[] FIXED_NAMES = {
            "marketPower", "marketMemory", "marketPeople", "marketCommodityStats",
            "marketEventMods", "fleetCommanderOfficers", "fleetBaseEntity", "fleetStats",
            "fleetAccidents", "fleetLogistics", "fleetUpdateCounts", "fleetMovement",
            "fleetBuffs", "fleetView"
    };
    private static final int[] FIXED_SAMPLE_RATES = {
            32, 32, 32, 32, 32, 1, 1, 1, 1, 1, 1, 1, 8, 1
    };
    private static final String[] CLASS_GROUP_NAMES = {
            "marketConditionClasses", "marketSubmarketClasses", "marketIndustryClasses",
            "fleetAiClasses", "fleetHullmodFleetClasses", "fleetHullmodShipClasses"
    };
    private static final int[] CLASS_SAMPLE_RATES = {32, 32, 32, 1, 8, 32};

    private static final Stats[] fixed = new Stats[FIXED_NAMES.length];
    @SuppressWarnings("unchecked")
    private static final List<ClassStats>[] classGroups = new List[CLASS_GROUP_NAMES.length];
    @SuppressWarnings("unchecked")
    private static volatile ClassValue<ClassStats>[] classStats = new ClassValue[CLASS_GROUP_NAMES.length];
    private static final long[] classAttempts = new long[CLASS_GROUP_NAMES.length];

    private static volatile boolean enabled;
    private static volatile boolean marketInstalled;
    private static volatile boolean fleetInstalled;
    private static long zeroMarketAdvances;
    private static long nonZeroMarketAdvances;

    static {
        for (int id = 0; id < fixed.length; id++) fixed[id] = new Stats(FIXED_SAMPLE_RATES[id]);
        for (int id = 0; id < classGroups.length; id++) classGroups[id] = new ArrayList<>();
        replaceClassValues();
    }

    private CampaignMarketFleetTimeRuntime() {
    }

    static synchronized void beginSession(boolean requested) {
        enabled = requested;
        marketInstalled = false;
        fleetInstalled = false;
        zeroMarketAdvances = 0L;
        nonZeroMarketAdvances = 0L;
        for (Stats stats : fixed) stats.reset();
        for (int group = 0; group < classGroups.length; group++) {
            classGroups[group].clear();
            classAttempts[group] = 0L;
        }
        replaceClassValues();
    }

    static boolean enabled() {
        return enabled;
    }

    static void marketInstalled() {
        marketInstalled = true;
    }

    static void fleetInstalled() {
        fleetInstalled = true;
    }

    public static long enter(int id) {
        if (!enabled || id < 0 || id >= fixed.length) return 0L;
        Stats stats = fixed[id];
        long attempt = ++stats.attempts;
        return sampled(attempt, stats.sampleRate) ? System.nanoTime() : 0L;
    }

    /** Counts exact zero-delta market calls before considering a guarded fast path. */
    public static void observeMarketAmount(float amount) {
        if (amount == 0f) {
            zeroMarketAdvances++;
        } else {
            nonZeroMarketAdvances++;
        }
    }

    public static void exit(int id, long startedNanos) {
        if (!enabled || id < 0 || id >= fixed.length || startedNanos == 0L) return;
        record(fixed[id], startedNanos);
    }

    public static long enterClass(Object value, int group) {
        if (!enabled || value == null || group < 0 || group >= classGroups.length) return 0L;
        long attempt = ++classAttempts[group];
        return sampled(attempt, CLASS_SAMPLE_RATES[group]) ? System.nanoTime() : 0L;
    }

    public static void exitClass(Object value, int group, long startedNanos) {
        if (!enabled || value == null || group < 0 || group >= classGroups.length
                || startedNanos == 0L) return;
        try {
            record(classStats[group].get(value.getClass()), startedNanos);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Per-plugin attribution must never affect campaign execution.
        }
    }

    static synchronized Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("enabled", enabled);
        result.put("marketInstalled", marketInstalled);
        result.put("fleetInstalled", fleetInstalled);
        result.put("zeroMarketAdvances", zeroMarketAdvances);
        result.put("nonZeroMarketAdvances", nonZeroMarketAdvances);

        List<Map<String, Object>> phases = new ArrayList<>();
        for (int id = 0; id < fixed.length; id++) phases.add(fixed[id].report(FIXED_NAMES[id]));
        result.put("phases", phases);

        for (int group = 0; group < CLASS_GROUP_NAMES.length; group++) {
            List<ClassStats> ordered = new ArrayList<>(classGroups[group]);
            ordered.sort(Comparator.comparingDouble(ClassStats::estimatedTotalMillis).reversed());
            List<Map<String, Object>> values = new ArrayList<>();
            for (ClassStats stats : ordered) values.add(stats.report(stats.className));
            result.put(CLASS_GROUP_NAMES[group], values);
        }
        return result;
    }

    static void reset() {
        beginSession(false);
    }

    private static boolean sampled(long attempt, int rate) {
        return rate == 1 || attempt % rate == 0L;
    }

    private static void record(Stats stats, long startedNanos) {
        try {
            long duration = System.nanoTime() - startedNanos;
            if (duration <= 0L) return;
            stats.samples++;
            stats.totalNanos += duration;
            long retainedEndEpochMillis = stats.slowestCalls.record(duration);
            if (duration > stats.maximumNanos) {
                stats.maximumNanos = duration;
                stats.maximumEndEpochMillis = retainedEndEpochMillis == 0L
                        ? System.currentTimeMillis() : retainedEndEpochMillis;
            }
            if (duration > 1_000_000L) stats.overOneMillis++;
            if (duration > 5_000_000L) stats.overFiveMillis++;
            if (duration > 16_666_667L) stats.overSixteenMillis++;
            if (duration > 33_333_333L) stats.overThirtyThreeMillis++;
            if (duration > 100_000_000L) stats.overOneHundredMillis++;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // These methods are woven into vanilla loops. Telemetry must fail inertly.
        }
    }

    @SuppressWarnings("unchecked")
    private static void replaceClassValues() {
        ClassValue<ClassStats>[] replacements = new ClassValue[CLASS_GROUP_NAMES.length];
        for (int group = 0; group < replacements.length; group++) {
            final int groupId = group;
            replacements[group] = new ClassValue<>() {
                @Override
                protected ClassStats computeValue(Class<?> type) {
                    ClassStats value = new ClassStats(type.getName(), CLASS_SAMPLE_RATES[groupId]);
                    synchronized (CampaignMarketFleetTimeRuntime.class) {
                        classGroups[groupId].add(value);
                    }
                    return value;
                }
            };
        }
        classStats = replacements;
    }

    private static class Stats {
        final int sampleRate;
        long attempts;
        long samples;
        long totalNanos;
        long maximumNanos;
        long maximumEndEpochMillis;
        long overOneMillis;
        long overFiveMillis;
        long overSixteenMillis;
        long overThirtyThreeMillis;
        long overOneHundredMillis;
        final SlowCallWindows slowestCalls = new SlowCallWindows();

        Stats(int sampleRate) {
            this.sampleRate = sampleRate;
        }

        void reset() {
            attempts = samples = totalNanos = maximumNanos = maximumEndEpochMillis = 0L;
            overOneMillis = overFiveMillis = overSixteenMillis = 0L;
            overThirtyThreeMillis = overOneHundredMillis = 0L;
            slowestCalls.reset();
        }

        double estimatedTotalMillis() {
            return totalNanos / 1_000_000.0 * sampleRate;
        }

        Map<String, Object> report(String name) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", name);
            result.put("calls", attempts == 0L ? samples * sampleRate : attempts);
            result.put("sampledCalls", samples);
            result.put("sampleRate", sampleRate);
            result.put("measuredMillis", totalNanos / 1_000_000.0);
            result.put("estimatedTotalMillis", estimatedTotalMillis());
            result.put("averageMicros", samples == 0L ? null : totalNanos / 1_000.0 / samples);
            result.put("sampledMaximumMillis", maximumNanos / 1_000_000.0);
            result.put("sampledMaximumEndEpochMillis",
                    maximumEndEpochMillis == 0L ? null : maximumEndEpochMillis);
            result.put("sampledOver1Millis", overOneMillis);
            result.put("sampledOver5Millis", overFiveMillis);
            result.put("sampledOver16Millis", overSixteenMillis);
            result.put("sampledOver33Millis", overThirtyThreeMillis);
            result.put("sampledOver100Millis", overOneHundredMillis);
            result.put("slowestCalls", slowestCalls.report());
            return result;
        }
    }

    private static final class ClassStats extends Stats {
        final String className;

        ClassStats(String className, int sampleRate) {
            super(sampleRate);
            this.className = className;
        }
    }
}
