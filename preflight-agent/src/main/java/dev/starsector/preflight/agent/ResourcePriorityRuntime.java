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
    static final String PLAN_ID = "vanilla-resource-priority-index-v3";
    private static final long PROGRESS_FRAME_NANOS = 33_333_334L;
    private static final AtomicLong CALLS = new AtomicLong();
    private static final AtomicLong RESOURCES = new AtomicLong();
    private static final AtomicLong PRIORITIZED = new AtomicLong();
    private static final AtomicLong BASELINE_NANOS = new AtomicLong();
    private static final AtomicLong INDEXED_NANOS = new AtomicLong();
    private static final AtomicLong COMPARISON_MISMATCHES = new AtomicLong();
    private static final AtomicLong PROGRESS_CALLS = new AtomicLong();
    private static final AtomicLong PROGRESS_RENDERS = new AtomicLong();
    private static final AtomicLong PROGRESS_SKIPS = new AtomicLong();
    private static volatile long lastProgressRenderNanos;

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

    /** Keeps the loading window responsive without redrawing it hundreds of times per second. */
    public static boolean shouldRenderProgress(float progress) {
        PROGRESS_CALLS.incrementAndGet();
        long now = System.nanoTime();
        long previous = lastProgressRenderNanos;
        if (progress >= 1f || previous == 0L || now - previous >= PROGRESS_FRAME_NANOS) {
            lastProgressRenderNanos = now;
            PROGRESS_RENDERS.incrementAndGet();
            return true;
        }
        PROGRESS_SKIPS.incrementAndGet();
        return false;
    }

    static void beginSession() {
        CALLS.set(0);
        RESOURCES.set(0);
        PRIORITIZED.set(0);
        BASELINE_NANOS.set(0);
        INDEXED_NANOS.set(0);
        COMPARISON_MISMATCHES.set(0);
        PROGRESS_CALLS.set(0);
        PROGRESS_RENDERS.set(0);
        PROGRESS_SKIPS.set(0);
        lastProgressRenderNanos = 0L;
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("calls", CALLS.get());
        values.put("resources", RESOURCES.get());
        values.put("prioritized", PRIORITIZED.get());
        values.put("baselineNanos", BASELINE_NANOS.get());
        values.put("indexedNanos", INDEXED_NANOS.get());
        values.put("comparisonMismatches", COMPARISON_MISMATCHES.get());
        values.put("progressCalls", PROGRESS_CALLS.get());
        values.put("progressRenders", PROGRESS_RENDERS.get());
        values.put("progressSkips", PROGRESS_SKIPS.get());
        values.put("progressFrameNanos", PROGRESS_FRAME_NANOS);
        return values;
    }
}
