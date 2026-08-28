package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact, opt-in phase timing within vanilla {@code CoreAutofitPlugin.doFit(...)}. */
public final class CoreAutofitTimeRuntime {
    static final String PLAN_ID = "vanilla-core-autofit-time-probe-v1";
    static final String DISABLED_PROPERTY = "preflight.campaign.coreAutofitTimes.disabled";

    static final int TOTAL = 0;
    static final int SETUP_MODULES = 1;
    static final int FIT_PREPARATION = 2;
    static final int STRIP_OR_PRESERVE = 3;
    static final int HULLMOD_SEED = 4;
    static final int PRIMARY_FIT = 5;
    static final int RANDOMIZE_REFIT = 6;
    static final int FINALIZE_ORDNANCE = 7;
    static final int PHASE_SPECIALIZATION = 8;
    static final int WEAPON_GROUPS = 9;
    static final int FINAL_SYNC = 10;
    static final int MODULE_AUTOFIT = 11;
    static final int STRIP_CALLS = 12;
    static final int HULLMOD_CALLS = 13;
    static final int FIGHTER_FIT_CALLS = 14;
    static final int WEAPON_FIT_CALLS = 15;
    static final int RANDOM_HULLMOD_CALLS = 16;
    static final int VENT_CAP_CALLS = 17;
    static final int SPARE_OP_SMOD_CALLS = 18;
    static final int SYNC_UI_CALLS = 19;

    private static final String[] NAMES = {
            "total", "setupModules", "fitPreparation", "stripOrPreserve", "hullmodSeed",
            "primaryFit", "randomizeRefit", "finalizeOrdnance", "phaseSpecialization",
            "weaponGroups", "finalSync", "moduleAutofit", "stripCalls", "hullmodCalls",
            "fighterFitCalls", "weaponFitCalls", "randomHullmodCalls", "ventCapCalls",
            "spareOpSmodCalls", "syncUiCalls"
    };
    private static final Stats[] phases = new Stats[NAMES.length];
    private static final List<ClassStats> autofitClasses = new ArrayList<>();
    private static final List<SlowSpan> slowSpans = new ArrayList<>();
    private static volatile ClassValue<ClassStats> classStats = newClassStats();

    private static volatile boolean enabled;
    private static volatile boolean installed;

    static {
        for (int id = 0; id < phases.length; id++) phases[id] = new Stats();
    }

    private CoreAutofitTimeRuntime() {
    }

    static synchronized void beginSession(boolean requested) {
        enabled = requested && !Boolean.getBoolean(DISABLED_PROPERTY);
        installed = false;
        for (Stats stats : phases) stats.reset();
        autofitClasses.clear();
        slowSpans.clear();
        classStats = newClassStats();
    }

    static boolean enabled() {
        return enabled;
    }

    static void installed() {
        installed = true;
    }

    /** Returns zero while disabled so woven exits remain inert. */
    public static long enter(int phase) {
        return enabled && phase >= 0 && phase < phases.length ? System.nanoTime() : 0L;
    }

    /** Records a phase without changing the original helper's arguments, result, or exception. */
    public static void exit(Object autofit, int phase, long startedNanos) {
        if (!enabled || phase < 0 || phase >= phases.length || startedNanos == 0L) return;
        try {
            long duration = System.nanoTime() - startedNanos;
            if (duration <= 0L) return;
            record(phases[phase], duration);
            String className = autofit == null ? "" : autofit.getClass().getName();
            if (phase == TOTAL && autofit != null) {
                record(classStats.get(autofit.getClass()), duration);
            }
            if (duration >= 1_000_000L) retain(new SlowSpan(
                    NAMES[phase], className,
                    autofit == null ? 0 : System.identityHashCode(autofit),
                    duration, System.currentTimeMillis()));
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Discovery telemetry woven into vanilla autofit must fail inertly.
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

        List<ClassStats> orderedClasses = new ArrayList<>(autofitClasses);
        orderedClasses.sort(Comparator.comparingLong(
                (ClassStats value) -> value.totalNanos).reversed());
        result.put("autofitClasses", orderedClasses.stream()
                .map(value -> value.report(value.className)).toList());

        List<SlowSpan> orderedSpans = new ArrayList<>(slowSpans);
        orderedSpans.sort(Comparator.comparingLong(SlowSpan::durationNanos).reversed());
        result.put("slowSpans", orderedSpans.stream().map(SlowSpan::report).toList());
        result.put("nesting", "Broad semantic phases include the named helper-call phases they contain; "
                + "setupModules may also include recursive moduleAutofit work.");
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
        if (slowSpans.size() > 48) slowSpans.remove(slowSpans.size() - 1);
    }

    private static ClassValue<ClassStats> newClassStats() {
        return new ClassValue<>() {
            @Override
            protected ClassStats computeValue(Class<?> type) {
                ClassStats value = new ClassStats(type);
                synchronized (CoreAutofitTimeRuntime.class) {
                    autofitClasses.add(value);
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
            String autofitClass,
            int autofitIdentity,
            long durationNanos,
            long endEpochMillis) {
        Map<String, Object> report() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("phase", phase);
            result.put("autofitClass", autofitClass);
            result.put("autofitIdentity", Integer.toUnsignedString(autofitIdentity));
            result.put("durationMillis", durationNanos / 1_000_000.0);
            result.put("endEpochMillis", endEpochMillis);
            return result;
        }
    }
}
