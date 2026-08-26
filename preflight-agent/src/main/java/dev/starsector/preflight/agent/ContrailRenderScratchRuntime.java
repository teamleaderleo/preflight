package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/** Installation state for allocation-free temporary vectors in the campaign contrail renderer. */
public final class ContrailRenderScratchRuntime {
    static final String PLAN_ID = "campaign-contrail-render-scratch-v1";

    private static volatile boolean installed;

    private ContrailRenderScratchRuntime() {
    }

    static void installed() {
        installed = true;
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", PLAN_ID);
        values.put("installed", installed);
        values.put("enabled", installed);
        values.put("strategy", "reuse-transient-per-engine-vector-scratch");
        values.put("vectorsPerEngine", ContrailRenderScratchPlan.SCRATCH_COUNT);
        return values;
    }

    static void beginSession() {
        installed = false;
    }
}
