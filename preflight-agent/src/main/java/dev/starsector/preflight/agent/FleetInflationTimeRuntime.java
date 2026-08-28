package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact, opt-in phase timing within vanilla {@code DefaultFleetInflater.inflate(...)}. */
public final class FleetInflationTimeRuntime {
    static final String PLAN_ID = "vanilla-default-fleet-inflater-time-probe-v1";
    static final String DISABLED_PROPERTY = "preflight.campaign.fleetInflaterTimes.disabled";

    static final int TOTAL = 0;
    static final int INITIAL_SETUP = 1;
    static final int HULLMOD_POOL = 2;
    static final int WEAPON_POOL = 3;
    static final int FIGHTER_POOL = 4;
    static final int MEMBER_WORK = 5;
    static final int AUTOFIT = 6;
    static final int DMOD_WORK = 7;
    static final int FINAL_SYNC = 8;
    static final int SYNC_CALL = 9;

    private static final String[] NAMES = {
            "total", "initialSetup", "hullmodPool", "weaponPool", "fighterPool",
            "memberWork", "autofit", "dmodWork", "finalSync", "syncCall"
    };
    private static final Stats[] phases = new Stats[NAMES.length];
    private static final List<ClassStats> inflaterClasses = new ArrayList<>();
    private static final List<SlowSpan> slowSpans = new ArrayList<>();
    private static volatile ClassValue<ClassStats> classStats = newClassStats();

    private static volatile boolean enabled;
    private static volatile boolean installed;
    private static long membersVisited;

    static {
        for (int id = 0; id < phases.length; id++) phases[id] = new Stats();
    }

    private FleetInflationTimeRuntime() {
    }

    static synchronized void beginSession(boolean requested) {
        enabled = requested && !Boolean.getBoolean(DISABLED_PROPERTY);
        installed = false;
        membersVisited = 0L;
        for (Stats stats : phases) stats.reset();
        inflaterClasses.clear();
        slowSpans.clear();
        classStats = newClassStats();
    }

    static boolean enabled() {
        return enabled;
    }

    static void installed() {
        installed = true;
    }

    /** Returns zero while disabled so the woven exit path remains inert. */
    public static long enter(int phase) {
        return enabled && phase >= 0 && phase < phases.length ? System.nanoTime() : 0L;
    }

    public static void memberVisited() {
        if (enabled) membersVisited++;
    }

    /** Records phase time without changing the original inflater's return or exception behavior. */
    public static void exit(Object inflater, int phase, long startedNanos) {
        if (!enabled || phase < 0 || phase >= phases.length || startedNanos == 0L) return;
        try {
            long duration = System.nanoTime() - startedNanos;
            if (duration <= 0L) return;
            record(phases[phase], duration);
            String inflaterClass = inflater == null ? "" : inflater.getClass().getName();
            if (phase == TOTAL && inflater != null) {
                record(classStats.get(inflater.getClass()), duration);
            }
            if (duration >= 2_000_000L) retain(new SlowSpan(
                    NAMES[phase], inflaterClass,
                    inflater == null ? 0 : System.identityHashCode(inflater),
                    duration, System.currentTimeMillis()));
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Discovery telemetry woven into vanilla fleet inflation must fail inertly.
        }
    }

    static synchronized Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("enabled", enabled);
        result.put("installed", installed);
        result.put("membersVisited", membersVisited);

        List<Map<String, Object>> phaseReports = new ArrayList<>();
        for (int id = 0; id < phases.length; id++) phaseReports.add(phases[id].report(NAMES[id]));
        result.put("phases", phaseReports);

        List<ClassStats> orderedClasses = new ArrayList<>(inflaterClasses);
        orderedClasses.sort(Comparator.comparingLong(
                (ClassStats value) -> value.totalNanos).reversed());
        result.put("inflaterClasses", orderedClasses.stream()
                .map(value -> value.report(value.className)).toList());

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

    private static synchronized void retain(SlowSpan span) {
        slowSpans.add(span);
        slowSpans.sort(Comparator.comparingLong(SlowSpan::durationNanos).reversed());
        if (slowSpans.size() > 32) slowSpans.remove(slowSpans.size() - 1);
    }

    private static ClassValue<ClassStats> newClassStats() {
        return new ClassValue<>() {
            @Override
            protected ClassStats computeValue(Class<?> type) {
                ClassStats value = new ClassStats(type);
                synchronized (FleetInflationTimeRuntime.class) {
                    inflaterClasses.add(value);
                }
                return value;
            }
        };
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

    private static final class ClassStats extends Stats {
        final Class<?> type;
        final String className;

        ClassStats(Class<?> type) {
            this.type = type;
            this.className = type.getName();
        }

        @Override
        Map<String, Object> report(String name) {
            Map<String, Object> result = super.report(name);
            result.put("ownership", RuntimeClassOwnership.resolve(type).report());
            return result;
        }
    }

    private record SlowSpan(
            String phase,
            String inflaterClass,
            int inflaterIdentity,
            long durationNanos,
            long endEpochMillis) {
        Map<String, Object> report() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("phase", phase);
            result.put("inflaterClass", inflaterClass);
            result.put("inflaterIdentity", Integer.toUnsignedString(inflaterIdentity));
            result.put("durationMillis", durationNanos / 1_000_000.0);
            result.put("endEpochMillis", endEpochMillis);
            return result;
        }
    }
}
