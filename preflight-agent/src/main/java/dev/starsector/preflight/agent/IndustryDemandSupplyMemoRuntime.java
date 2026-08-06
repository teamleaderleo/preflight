package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Reuses one synthetic industry's applied state across Codex's adjacent demand/supply queries. */
public final class IndustryDemandSupplyMemoRuntime {
    static final String PLAN_ID = "vanilla-industry-demand-supply-memo-v1";

    private static final AtomicLong SCOPES = new AtomicLong();
    private static final AtomicLong RECORDS = new AtomicLong();
    private static final AtomicLong LOOKUPS = new AtomicLong();
    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong MISSES = new AtomicLong();
    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);
    private static boolean codexInstalled;
    private static boolean settingsInstalled;

    private IndustryDemandSupplyMemoRuntime() {
    }

    static void beginSession() {
        SCOPES.set(0L);
        RECORDS.set(0L);
        LOOKUPS.set(0L);
        HITS.set(0L);
        MISSES.set(0L);
        STATE.remove();
        codexInstalled = false;
        settingsInstalled = false;
    }

    static void codexInstalled() {
        codexInstalled = true;
    }

    static void settingsInstalled() {
        settingsInstalled = true;
    }

    /** Opens the exact Codex link pass; any stale per-thread candidate is discarded. */
    public static void enterCodex() {
        State state = STATE.get();
        state.active = true;
        state.applied.clear();
        SCOPES.incrementAndGet();
    }

    /** Closes the Codex link pass and makes later SettingsAPI calls completely vanilla. */
    public static void exitCodex() {
        State state = STATE.get();
        state.applied.clear();
        state.active = false;
    }

    /** Remembers the exact industry instance vanilla just applied for one demand query. */
    public static void record(String industryId, Object appliedIndustry) {
        State state = STATE.get();
        if (!state.active || industryId == null || appliedIndustry == null) return;
        state.applied.put(industryId, appliedIndustry);
        RECORDS.incrementAndGet();
    }

    /** Consumes the adjacent demand query's applied instance, or lets supply remain vanilla. */
    public static Object lookup(String industryId) {
        State state = STATE.get();
        if (!state.active || industryId == null) return null;
        LOOKUPS.incrementAndGet();
        Object result = state.applied.remove(industryId);
        if (result == null) {
            MISSES.incrementAndGet();
        } else {
            HITS.incrementAndGet();
        }
        return result;
    }

    static Map<String, Object> telemetry() {
        State state = STATE.get();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", PLAN_ID);
        values.put("codexInstalled", codexInstalled);
        values.put("settingsInstalled", settingsInstalled);
        values.put("scopes", SCOPES.get());
        values.put("records", RECORDS.get());
        values.put("lookups", LOOKUPS.get());
        values.put("hits", HITS.get());
        values.put("misses", MISSES.get());
        values.put("active", state.active);
        values.put("remaining", state.applied.size());
        return values;
    }

    private static final class State {
        private final Map<String, Object> applied = new LinkedHashMap<>();
        private boolean active;
    }
}
