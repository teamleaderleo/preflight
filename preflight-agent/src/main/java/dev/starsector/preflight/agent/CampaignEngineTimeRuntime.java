package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact call-site timers for the major vanilla campaign-engine subphases and script classes. */
public final class CampaignEngineTimeRuntime {
    static final String PLAN_ID = "campaign-engine-call-time-probe-v1";

    static final int INTEL = 0;
    static final int EVENTS = 1;
    static final int IMPORTANT_PEOPLE = 2;
    static final int UI_DATA = 3;
    static final int ECONOMY = 4;
    static final int MEMORY = 5;
    static final int FACTIONS = 6;
    static final int LOCATIONS = 7;
    static final int CAMPAIGN_HELP = 8;

    private static final String[] NAMES = {
            "intelManager",
            "eventManager",
            "importantPeople",
            "uiData",
            "economy",
            "memory",
            "factions",
            "locations",
            "campaignHelp"
    };
    private static final Stats[] phases = new Stats[NAMES.length];
    private static final List<ScriptStats> scripts = new ArrayList<>();

    private static volatile ClassValue<ScriptStats> scriptStats = newScriptStats();
    private static volatile boolean enabled;
    private static volatile boolean installed;

    static {
        for (int id = 0; id < phases.length; id++) phases[id] = new Stats();
    }

    private CampaignEngineTimeRuntime() {
    }

    static synchronized void beginSession(boolean requested) {
        enabled = requested;
        HitchPacketRuntime.configureCampaignPhaseProducer(requested);
        installed = false;
        for (Stats stats : phases) stats.reset();
        scripts.clear();
        scriptStats = newScriptStats();
    }

    static boolean enabled() {
        return enabled;
    }

    static void installed() {
        installed = true;
    }

    public static long enter(int id) {
        return enabled && valid(id) ? System.nanoTime() : 0L;
    }

    public static void exit(int id, long startedNanos) {
        if (!enabled || !valid(id) || startedNanos == 0L) return;
        record(phases[id], id, startedNanos);
    }

    public static long enterScript(Object script) {
        return enabled && script != null ? System.nanoTime() : 0L;
    }

    public static void exitScript(Object script, long startedNanos) {
        if (!enabled || script == null || startedNanos == 0L) return;
        try {
            record(scriptStats.get(script.getClass()), -1, startedNanos);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Per-class attribution is diagnostic and must never affect a campaign script.
        }
    }

    static synchronized Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("enabled", enabled);
        result.put("installed", installed);
        List<Map<String, Object>> phaseValues = new ArrayList<>();
        for (int id = 0; id < NAMES.length; id++) {
            phaseValues.add(phases[id].report(NAMES[id]));
        }
        result.put("phases", phaseValues);

        List<ScriptStats> ordered = new ArrayList<>(scripts);
        ordered.sort(Comparator.comparingLong((ScriptStats value) -> value.totalNanos).reversed());
        List<Map<String, Object>> scriptValues = new ArrayList<>();
        for (ScriptStats stats : ordered) scriptValues.add(stats.report(stats.className));
        result.put("scriptClasses", scriptValues);
        result.put("scriptOwnerTax", RuntimeOwnerTax.report(
                scriptValues, "totalMillis", "maximumMillis"));
        return result;
    }

    static void reset() {
        beginSession(false);
    }

    static String phaseName(int id) {
        return valid(id) ? NAMES[id] : "unknown";
    }

    private static void record(Stats stats, int phaseId, long startedNanos) {
        try {
            long endedNanos = System.nanoTime();
            long duration = endedNanos - startedNanos;
            if (duration <= 0L) return;
            if (phaseId >= 0) {
                HitchPacketRuntime.recordCampaignPhase(phaseId, startedNanos, endedNanos);
            }
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
            // These calls are woven into the campaign engine. Telemetry must fail inertly.
        }
    }

    private static ClassValue<ScriptStats> newScriptStats() {
        return new ClassValue<>() {
            @Override
            protected ScriptStats computeValue(Class<?> type) {
                ScriptStats value = new ScriptStats(type);
                synchronized (CampaignEngineTimeRuntime.class) {
                    scripts.add(value);
                }
                return value;
            }
        };
    }

    private static boolean valid(int id) {
        return id >= 0 && id < phases.length;
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

    private static final class ScriptStats extends Stats {
        final Class<?> type;
        final String className;

        ScriptStats(Class<?> type) {
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
}
