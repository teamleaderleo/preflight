package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/** Installation state for the allocation-free vanilla font wrapping character checks. */
public final class FontWrapAllocationRuntime {
    static final String PLAN_ID = "font-wrap-character-allocation-v1";

    private static volatile boolean installed;

    private FontWrapAllocationRuntime() {
    }

    static void installed() {
        installed = true;
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", PLAN_ID);
        values.put("installed", installed);
        values.put("enabled", installed);
        values.put("strategy", "literal-punctuation-tables-and-indexof-char");
        return values;
    }

    static void beginSession() {
        installed = false;
    }
}
