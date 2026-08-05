package dev.starsector.preflight.agent;

import java.util.Collection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Indexes the large preferred-resource membership test before vanilla restores its list order. */
public final class ResourcePriorityRuntime {
    static final String PLAN_ID = "vanilla-resource-priority-index-v1";
    private static final AtomicLong CALLS = new AtomicLong();
    private static final AtomicLong RESOURCES = new AtomicLong();
    private static final AtomicLong PRIORITIZED = new AtomicLong();
    private static final AtomicLong BASELINE_NANOS = new AtomicLong();
    private static final AtomicLong INDEXED_NANOS = new AtomicLong();
    private static final AtomicLong COMPARISON_MISMATCHES = new AtomicLong();

    private ResourcePriorityRuntime() {
    }

    public static <T> boolean removeAll(List<T> resources, Collection<?> prioritized) {
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(prioritized, "prioritized");
        CALLS.incrementAndGet();
        RESOURCES.addAndGet(resources.size());
        PRIORITIZED.addAndGet(prioritized.size());
        List<T> baseline = null;
        if (Boolean.getBoolean("preflight.resourcePriority.compare")) {
            baseline = new ArrayList<>(resources);
            long started = System.nanoTime();
            baseline.removeAll(prioritized);
            BASELINE_NANOS.addAndGet(System.nanoTime() - started);
        }
        long started = System.nanoTime();
        boolean changed;
        if (prioritized.size() < 2) {
            changed = resources.removeAll(prioritized);
        } else {
            changed = resources.removeAll(new HashSet<>(prioritized));
        }
        INDEXED_NANOS.addAndGet(System.nanoTime() - started);
        if (baseline != null && !resources.equals(baseline)) {
            COMPARISON_MISMATCHES.incrementAndGet();
            resources.clear();
            resources.addAll(baseline);
        }
        return changed;
    }

    static void beginSession() {
        CALLS.set(0);
        RESOURCES.set(0);
        PRIORITIZED.set(0);
        BASELINE_NANOS.set(0);
        INDEXED_NANOS.set(0);
        COMPARISON_MISMATCHES.set(0);
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("calls", CALLS.get());
        values.put("resources", RESOURCES.get());
        values.put("prioritized", PRIORITIZED.get());
        values.put("baselineNanos", BASELINE_NANOS.get());
        values.put("indexedNanos", INDEXED_NANOS.get());
        values.put("comparisonMismatches", COMPARISON_MISMATCHES.get());
        return values;
    }
}
