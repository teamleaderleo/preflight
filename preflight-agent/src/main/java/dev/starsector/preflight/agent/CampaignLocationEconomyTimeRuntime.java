package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deeper opt-in timers for the two largest vanilla campaign-engine buckets. */
public final class CampaignLocationEconomyTimeRuntime {
    static final String PLAN_ID = "campaign-location-economy-call-time-probe-v1";

    static final int LOCATION_MAP = 0;
    static final int ECONOMY_STEPPER = 1;
    static final int MARKET_ADVANCE = 2;

    static final int ENTITY_ACTIVE = 0;
    static final int ENTITY_PAUSED = 1;
    static final int LOCATION_SCRIPT = 2;

    private static final String[] ECONOMY_NAMES = {
            "locationMap", "economyStepper", "marketAdvance"
    };
    private static final String[] CLASS_GROUP_NAMES = {
            "entityActiveClasses", "entityPausedClasses", "locationScriptClasses"
    };
    private static final int[] CLASS_SAMPLE_RATES = {64, 64, 1};
    private static final Stats[] economy = new Stats[ECONOMY_NAMES.length];
    @SuppressWarnings("unchecked")
    private static final List<ClassStats>[] classGroups = new List[CLASS_GROUP_NAMES.length];
    @SuppressWarnings("unchecked")
    private static volatile ClassValue<ClassStats>[] classStats = new ClassValue[CLASS_GROUP_NAMES.length];
    private static final long[] classAttempts = new long[CLASS_GROUP_NAMES.length];

    private static volatile boolean enabled;
    private static volatile boolean locationInstalled;
    private static volatile boolean economyInstalled;

    static {
        for (int id = 0; id < economy.length; id++) economy[id] = new Stats();
        for (int id = 0; id < classGroups.length; id++) classGroups[id] = new ArrayList<>();
        replaceClassValues();
    }

    private CampaignLocationEconomyTimeRuntime() {
    }

    static synchronized void beginSession(boolean requested) {
        enabled = requested;
        locationInstalled = false;
        economyInstalled = false;
        for (Stats stats : economy) stats.reset();
        for (int group = 0; group < classGroups.length; group++) {
            classGroups[group].clear();
            classAttempts[group] = 0L;
        }
        replaceClassValues();
    }

    static boolean enabled() {
        return enabled;
    }

    static void locationInstalled() {
        locationInstalled = true;
    }

    static void economyInstalled() {
        economyInstalled = true;
    }

    public static long enter(int id) {
        return enabled && validEconomy(id) ? System.nanoTime() : 0L;
    }

    public static void exit(int id, long startedNanos) {
        if (!enabled || !validEconomy(id) || startedNanos == 0L) return;
        record(economy[id], startedNanos);
    }

    public static long enterClass(Object value, int group) {
        if (!enabled || value == null || !validGroup(group)) return 0L;
        long attempt = ++classAttempts[group];
        return CLASS_SAMPLE_RATES[group] == 1 || attempt % CLASS_SAMPLE_RATES[group] == 0L
                ? System.nanoTime() : 0L;
    }

    public static void exitClass(Object value, int group, long startedNanos) {
        if (!enabled || value == null || !validGroup(group) || startedNanos == 0L) return;
        try {
            record(classStats[group].get(value.getClass()), startedNanos);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Per-class attribution is diagnostic and must never affect campaign execution.
        }
    }

    static synchronized Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("enabled", enabled);
        result.put("locationInstalled", locationInstalled);
        result.put("economyInstalled", economyInstalled);

        List<Map<String, Object>> economyValues = new ArrayList<>();
        for (int id = 0; id < ECONOMY_NAMES.length; id++) {
            economyValues.add(economy[id].report(ECONOMY_NAMES[id]));
        }
        result.put("economyPhases", economyValues);

        for (int group = 0; group < CLASS_GROUP_NAMES.length; group++) {
            List<ClassStats> ordered = new ArrayList<>(classGroups[group]);
            ordered.sort(Comparator.comparingLong((ClassStats value) -> value.totalNanos).reversed());
            List<Map<String, Object>> values = new ArrayList<>();
            for (ClassStats stats : ordered) values.add(stats.report(stats.className));
            result.put(CLASS_GROUP_NAMES[group], values);
            result.put(CLASS_GROUP_NAMES[group] + "OwnerTax",
                    RuntimeOwnerTax.report(values, "totalMillis", "maximumMillis"));
        }
        return result;
    }

    static void reset() {
        beginSession(false);
    }

    private static void record(Stats stats, long startedNanos) {
        try {
            long duration = System.nanoTime() - startedNanos;
            if (duration <= 0L) return;
            stats.calls++;
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
            // These calls are woven into vanilla loops. Telemetry must fail inertly.
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
                    ClassStats value = new ClassStats(type, CLASS_SAMPLE_RATES[groupId]);
                    synchronized (CampaignLocationEconomyTimeRuntime.class) {
                        classGroups[groupId].add(value);
                    }
                    return value;
                }
            };
        }
        classStats = replacements;
    }

    private static boolean validEconomy(int id) {
        return id >= 0 && id < economy.length;
    }

    private static boolean validGroup(int group) {
        return group >= 0 && group < classGroups.length;
    }

    private static class Stats {
        long calls;
        long totalNanos;
        long maximumNanos;
        long maximumEndEpochMillis;
        long overOneMillis;
        long overFiveMillis;
        long overSixteenMillis;
        long overThirtyThreeMillis;
        long overOneHundredMillis;
        final SlowCallWindows slowestCalls = new SlowCallWindows();

        void reset() {
            calls = 0L;
            totalNanos = 0L;
            maximumNanos = 0L;
            maximumEndEpochMillis = 0L;
            overOneMillis = 0L;
            overFiveMillis = 0L;
            overSixteenMillis = 0L;
            overThirtyThreeMillis = 0L;
            overOneHundredMillis = 0L;
            slowestCalls.reset();
        }

        Map<String, Object> report(String name) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", name);
            result.put("calls", calls);
            result.put("totalMillis", totalNanos / 1_000_000.0);
            result.put("averageMicros", calls == 0L ? null : totalNanos / 1_000.0 / calls);
            result.put("maximumMillis", maximumNanos / 1_000_000.0);
            result.put("maximumEndEpochMillis",
                    maximumEndEpochMillis == 0L ? null : maximumEndEpochMillis);
            result.put("over1Millis", overOneMillis);
            result.put("over5Millis", overFiveMillis);
            result.put("over16Millis", overSixteenMillis);
            result.put("over33Millis", overThirtyThreeMillis);
            result.put("over100Millis", overOneHundredMillis);
            result.put("slowestCalls", slowestCalls.report());
            return result;
        }
    }

    private static final class ClassStats extends Stats {
        final Class<?> type;
        final String className;
        final int sampleRate;

        ClassStats(Class<?> type, int sampleRate) {
            this.type = type;
            this.className = type.getName();
            this.sampleRate = sampleRate;
        }

        @Override
        Map<String, Object> report(String name) {
            Map<String, Object> result = super.report(name);
            if (sampleRate != 1) {
                long sampledCalls = calls;
                double measuredMillis = totalNanos / 1_000_000.0;
                result.put("calls", sampledCalls * sampleRate);
                result.put("sampledCalls", sampledCalls);
                result.put("sampleRate", sampleRate);
                result.put("measuredMillis", measuredMillis);
                result.put("totalMillis", measuredMillis * sampleRate);
            }
            result.put("ownership", RuntimeClassOwnership.resolve(type).report());
            return result;
        }
    }
}
