package dev.starsector.preflight.agent;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Per-load duplicate index that preserves vanilla's trigger-local duplicate rejection. */
public final class RulesDuplicateIndexRuntime {
    public static final String PLAN_ID = "vanilla-rules-duplicate-index-v1";

    private static volatile State state = new State();

    private RulesDuplicateIndexRuntime() {
    }

    static void beginSession() {
        state = new State();
    }

    static boolean ready() {
        return true;
    }

    /** Starts a fresh index for one vanilla rules-loader invocation. */
    public static void begin() {
        state.keys = new HashSet<>();
        state.loads.incrementAndGet();
    }

    /** Throws the same exception text as vanilla when an ID repeats under one trigger. */
    public static void check(String trigger, String ruleId) {
        State current = state;
        Set<Key> keys = current.keys;
        if (keys == null) {
            throw new IllegalStateException("Rules duplicate index was not initialized");
        }
        current.checks.incrementAndGet();
        if (!keys.add(new Key(trigger, ruleId))) {
            current.duplicates.incrementAndGet();
            throw new RuntimeException("Duplicate rule id: " + ruleId);
        }
    }

    /** Releases the temporary index after vanilla finishes all ordered insertions. */
    public static void complete() {
        state.keys = null;
        state.completions.incrementAndGet();
    }

    static Map<String, Object> telemetry() {
        State current = state;
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("loads", current.loads.get());
        values.put("checks", current.checks.get());
        values.put("duplicates", current.duplicates.get());
        values.put("completions", current.completions.get());
        values.put("active", current.keys != null);
        return values;
    }

    private record Key(String trigger, String ruleId) {
    }

    private static final class State {
        private volatile Set<Key> keys;
        private final AtomicLong loads = new AtomicLong();
        private final AtomicLong checks = new AtomicLong();
        private final AtomicLong duplicates = new AtomicLong();
        private final AtomicLong completions = new AtomicLong();
    }
}
