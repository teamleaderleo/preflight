package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Keeps the resource resolver's one-shot mod-source hint on the thread that supplied it. */
public final class SourceHintIsolationRuntime {
    static final String PLAN_ID = "resource-source-hint-isolation-v1";

    private static final ThreadLocal<String> hints = new ThreadLocal<>();
    private static final AtomicLong sets = new AtomicLong();
    private static final AtomicLong takes = new AtomicLong();
    private static final AtomicLong hintsTaken = new AtomicLong();
    private static final AtomicLong clears = new AtomicLong();
    private static volatile boolean installed;

    private SourceHintIsolationRuntime() {
    }

    static void installed() {
        installed = true;
    }

    /** Replaces the game's shared singleton field write. Called from the transformed game class. */
    public static void set(String hint) {
        sets.incrementAndGet();
        if (hint == null) {
            clears.incrementAndGet();
            hints.remove();
        } else {
            hints.set(hint);
        }
    }

    /** Returns and clears only the current thread's one-shot hint. */
    public static String take() {
        takes.incrementAndGet();
        String hint = hints.get();
        hints.remove();
        if (hint != null) {
            hintsTaken.incrementAndGet();
        }
        return hint;
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("installed", installed);
        result.put("enabled", installed);
        result.put("sets", sets.get());
        result.put("takes", takes.get());
        result.put("hintsTaken", hintsTaken.get());
        result.put("clears", clears.get());
        return result;
    }

    static void reset() {
        hints.remove();
        sets.set(0);
        takes.set(0);
        hintsTaken.set(0);
        clears.set(0);
        installed = false;
    }
}
