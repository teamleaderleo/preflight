package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact, opt-in timing below vanilla {@code ModularFleetAI.advance(float)}. */
public final class FleetAiModuleTimeRuntime {
    static final String PLAN_ID = "vanilla-fleet-ai-module-time-probe-v1";
    static final String DISABLED_PROPERTY = "preflight.campaign.fleetAiModuleTimes.disabled";

    static final int ASSIGNMENT = 0;
    static final int STRATEGIC = 1;
    static final int TACTICAL = 2;
    static final int NAVIGATION = 3;
    static final int ABILITY = 4;

    private static final String[] NAMES = {
            "assignment", "strategic", "tactical", "navigation", "ability"
    };
    private static final Stats[] phases = new Stats[NAMES.length];
    @SuppressWarnings("unchecked")
    private static final List<ClassStats>[] moduleClasses = new List[NAMES.length];
    @SuppressWarnings("unchecked")
    private static volatile ClassValue<ClassStats>[] classStats = new ClassValue[NAMES.length];
    private static final List<SlowSpan> slowSpans = new ArrayList<>();

    private static volatile boolean enabled;
    private static volatile boolean installed;

    static {
        for (int id = 0; id < NAMES.length; id++) {
            phases[id] = new Stats();
            moduleClasses[id] = new ArrayList<>();
        }
        replaceClassValues();
    }

    private FleetAiModuleTimeRuntime() {
    }

    static synchronized void beginSession(boolean requested) {
        enabled = requested && !Boolean.getBoolean(DISABLED_PROPERTY);
        installed = false;
        for (Stats stats : phases) stats.reset();
        for (List<ClassStats> values : moduleClasses) values.clear();
        slowSpans.clear();
        replaceClassValues();
    }

    static boolean enabled() {
        return enabled;
    }

    static void installed() {
        installed = true;
    }

    /** Returns zero when the probe is disabled so the woven exit is inert. */
    public static long enter(Object module, int phase) {
        if (!enabled || module == null || phase < 0 || phase >= phases.length) return 0L;
        return System.nanoTime();
    }

    /** Records exact module time while preserving the original call and exception behavior. */
    public static void exit(Object fleetAi, Object module, int phase, long startedNanos) {
        if (!enabled || module == null || phase < 0 || phase >= phases.length
                || startedNanos == 0L) return;
        try {
            long duration = System.nanoTime() - startedNanos;
            if (duration <= 0L) return;
            record(phases[phase], duration);
            ClassStats byClass = classStats[phase].get(module.getClass());
            record(byClass, duration);
            if (duration >= 5_000_000L) retainSlowSpan(new SlowSpan(
                    NAMES[phase], module.getClass().getName(),
                    fleetAi == null ? 0 : System.identityHashCode(fleetAi),
                    duration, System.currentTimeMillis()));
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Telemetry woven into vanilla fleet AI must fail inertly.
        }
    }

    static synchronized Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("enabled", enabled);
        result.put("installed", installed);

        List<Map<String, Object>> phaseReports = new ArrayList<>();
        for (int id = 0; id < phases.length; id++) phaseReports.add(phases[id].report(NAMES[id]));
        result.put("phases", phaseReports);

        Map<String, Object> classes = new LinkedHashMap<>();
        for (int id = 0; id < moduleClasses.length; id++) {
            List<ClassStats> ordered = new ArrayList<>(moduleClasses[id]);
            ordered.sort(Comparator.comparingLong((ClassStats value) -> value.totalNanos).reversed());
            List<Map<String, Object>> reports = new ArrayList<>();
            for (ClassStats stats : ordered) reports.add(stats.report(stats.className));
            classes.put(NAMES[id], reports);
        }
        result.put("moduleClasses", classes);

        List<SlowSpan> orderedSpans = new ArrayList<>(slowSpans);
        orderedSpans.sort(Comparator.comparingLong(SlowSpan::durationNanos).reversed());
        result.put("slowSpans", orderedSpans.stream().map(SlowSpan::report).toList());
        return result;
    }

    static void reset() {
        beginSession(false);
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

    private static synchronized void retainSlowSpan(SlowSpan candidate) {
        slowSpans.add(candidate);
        slowSpans.sort(Comparator.comparingLong(SlowSpan::durationNanos).reversed());
        if (slowSpans.size() > 32) slowSpans.remove(slowSpans.size() - 1);
    }

    @SuppressWarnings("unchecked")
    private static void replaceClassValues() {
        ClassValue<ClassStats>[] replacements = new ClassValue[NAMES.length];
        for (int phase = 0; phase < replacements.length; phase++) {
            final int phaseId = phase;
            replacements[phase] = new ClassValue<>() {
                @Override
                protected ClassStats computeValue(Class<?> type) {
                    ClassStats value = new ClassStats(type.getName());
                    synchronized (FleetAiModuleTimeRuntime.class) {
                        moduleClasses[phaseId].add(value);
                    }
                    return value;
                }
            };
        }
        classStats = replacements;
    }

    private static class Stats {
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

    private static final class ClassStats extends Stats {
        final String className;

        ClassStats(String className) {
            this.className = className;
        }
    }

    private record SlowSpan(
            String phase,
            String moduleClass,
            int fleetAiIdentity,
            long durationNanos,
            long endEpochMillis) {
        Map<String, Object> report() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("phase", phase);
            result.put("moduleClass", moduleClass);
            result.put("fleetAiIdentity", Integer.toUnsignedString(fleetAiIdentity));
            result.put("durationMillis", durationNanos / 1_000_000.0);
            result.put("endEpochMillis", endEpochMillis);
            return result;
        }
    }
}
