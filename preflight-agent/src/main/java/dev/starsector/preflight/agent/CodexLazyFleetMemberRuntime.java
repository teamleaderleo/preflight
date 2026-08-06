package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Telemetry for fleet members deferred from Codex population until their detail is opened. */
public final class CodexLazyFleetMemberRuntime {
    static final String PLAN_ID = "vanilla-codex-lazy-fleet-members-v1";

    private static final AtomicLong DEFERRED = new AtomicLong();
    private static final AtomicLong MATERIALIZED = new AtomicLong();
    private static boolean codexInstalled;
    private static boolean entryAccessorInstalled;

    private CodexLazyFleetMemberRuntime() {
    }

    static void beginSession() {
        DEFERRED.set(0L);
        MATERIALIZED.set(0L);
        codexInstalled = false;
        entryAccessorInstalled = false;
    }

    static void codexInstalled() {
        codexInstalled = true;
    }

    static void entryAccessorInstalled() {
        entryAccessorInstalled = true;
    }

    /** Lets transformed population code remain exactly vanilla unless both halves installed. */
    public static boolean active() {
        return codexInstalled && entryAccessorInstalled;
    }

    /** Retains the exact variant while avoiding FleetMember construction during startup. */
    public static Object defer(Object variant) {
        DEFERRED.incrementAndGet();
        return variant;
    }

    /** Records the detail panel replacing one retained variant with vanilla's FleetMember. */
    public static void materialized() {
        MATERIALIZED.incrementAndGet();
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", PLAN_ID);
        values.put("codexInstalled", codexInstalled);
        values.put("entryAccessorInstalled", entryAccessorInstalled);
        values.put("active", active());
        values.put("deferred", DEFERRED.get());
        values.put("materialized", MATERIALIZED.get());
        return values;
    }
}
