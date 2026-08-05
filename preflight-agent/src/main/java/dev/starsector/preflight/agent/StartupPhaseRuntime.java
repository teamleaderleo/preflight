package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Direct, low-overhead timing for work hidden before, during, and after loading progress. */
public final class StartupPhaseRuntime {
    static final String PLAN_ID = "startup-phase-probe-v1";
    private static final int MAX_PHASES = 64;
    private static final int MAX_PLUGINS = 128;
    private static final int MAX_SPEC_LOADERS = 64;
    private static final int MAX_SPEC_SUBPHASES = 32;
    private static final int MAX_HOT_CALL_GROUPS = 64;
    private static final int MAX_MERGED_READ_GROUPS = 512;
    private static final int[] PROGRESS_MILESTONES = {1, 5, 10, 25, 50, 75, 90, 95, 99, 100};

    private static Path destination;
    private static Instant startedAt;
    private static long startedNanos;
    private static long lastPhaseNanos;
    private static boolean installed;
    private static String writeProblem;
    private static final List<Map<String, Object>> phases = new ArrayList<>();
    private static final List<Map<String, Object>> plugins = new ArrayList<>();
    private static final List<Map<String, Object>> specLoaders = new ArrayList<>();
    private static final Map<String, SpecSubphase> specSubphases = new LinkedHashMap<>();
    private static final Map<String, HotCall> hotCalls = new LinkedHashMap<>();
    private static final Map<String, MergedRead> mergedReads = new LinkedHashMap<>();
    private static volatile boolean mergedReadProbe;
    private static String activePlugin;
    private static long activePluginNanos;
    private static String activeSpecLoader;
    private static long activeSpecLoaderNanos;
    private static String activeSpecSubphase;
    private static long activeSpecSubphaseNanos;
    private static long progressCalls;
    private static int lastProgressPermille;
    private static int nextProgressMilestone;

    private StartupPhaseRuntime() {
    }

    static synchronized void beginSession(Path reportDestination) {
        destination = reportDestination == null ? null : reportDestination.toAbsolutePath().normalize();
        startedAt = Instant.now();
        startedNanos = System.nanoTime();
        lastPhaseNanos = startedNanos;
        installed = false;
        writeProblem = null;
        phases.clear();
        plugins.clear();
        specLoaders.clear();
        specSubphases.clear();
        hotCalls.clear();
        mergedReads.clear();
        activePlugin = null;
        activePluginNanos = 0L;
        activeSpecLoader = null;
        activeSpecLoaderNanos = 0L;
        activeSpecSubphase = null;
        activeSpecSubphaseNanos = 0L;
        progressCalls = 0L;
        lastProgressPermille = -1;
        nextProgressMilestone = 0;
    }

    static synchronized void installed() {
        installed = true;
        writeSafely();
    }

    /** The phase that means vanilla finished loading everything, without dying on the way. */
    private static final String LOADING_FINISHED = "resource-init-complete";
    private static final String PROFILE_STABLE = "resource-init-enter";

    /** Called from the reviewed game class. It must never let probe failure affect startup. */
    public static synchronized void mark(String name) {
        try {
            recordPhase(name, null);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // This code is woven into startup. Diagnostics are never allowed to become startup.
        }
        if (PROFILE_STABLE.equals(name)) {
            try {
                LoadJsonMemoRuntime.markProfileStable();
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable ignored) {
                // Persistent JSON reuse is optional; vanilla remains available.
            }
        }
        if (LOADING_FINISHED.equals(name)) {
            try {
                FrameTimeRuntime.markStartupComplete();
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable ignored) {
                // Frame telemetry is optional and never allowed to affect startup.
            }
            // The general merged-read cache has no single loader to publish at the end of -- it
            // serves every caller, including mod callbacks, which run right up to here. This is the
            // first moment at which everything it could learn has been learned and vanilla is known
            // to have got through it, which is the same rule the per-loader caches follow.
            try {
                MergedReadCacheRuntime.complete();
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable ignored) {
                // Failing to publish costs the next launch its cache, and nothing else.
            }
        }
    }

    /** Observes the value Starsector is about to render without changing it. */
    public static synchronized void progress(float fraction) {
        try {
            progressCalls++;
            int permille = Float.isFinite(fraction)
                    ? Math.max(0, Math.min(1000, Math.round(fraction * 1000f)))
                    : -1;
            lastProgressPermille = permille;
            if (progressCalls == 1L) {
                recordPhase("progress-first-render", permille);
            }
            while (nextProgressMilestone < PROGRESS_MILESTONES.length
                    && permille >= PROGRESS_MILESTONES[nextProgressMilestone] * 10) {
                int percent = PROGRESS_MILESTONES[nextProgressMilestone++];
                recordPhase("progress-" + percent + "-percent", permille);
            }
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
        }
    }

    /** Starts timing one enabled mod's {@code onApplicationLoad} callback. */
    public static synchronized void pluginStart(Object plugin) {
        try {
            if (activePlugin != null && plugins.size() < MAX_PLUGINS) {
                plugins.add(pluginTiming(activePlugin, activePluginNanos, System.nanoTime(), false));
            }
            activePlugin = plugin == null ? "<null>" : plugin.getClass().getName();
            activePluginNanos = System.nanoTime();
            // Persist the identity before invoking the plugin. If it hangs or throws, the report
            // still names the callback startup reached.
            writeSafely();
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
        }
    }

    /** Completes the timing started by {@link #pluginStart(Object)}. */
    public static synchronized void pluginEnd() {
        try {
            if (activePlugin != null && plugins.size() < MAX_PLUGINS) {
                plugins.add(pluginTiming(activePlugin, activePluginNanos, System.nanoTime(), true));
            }
            activePlugin = null;
            activePluginNanos = 0L;
            writeSafely();
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
        }
    }

    /** Starts timing one top-level loader called by {@code SpecStore}. */
    public static synchronized void specLoaderStart(String label) {
        try {
            long now = System.nanoTime();
            if (activeSpecLoader != null && specLoaders.size() < MAX_SPEC_LOADERS) {
                specLoaders.add(specLoaderTiming(
                        activeSpecLoader, activeSpecLoaderNanos, now, false));
            }
            activeSpecLoader = label == null ? "<null>" : label;
            activeSpecLoaderNanos = now;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
        }
    }

    /** Completes the top-level {@code SpecStore} loader timing without adding file I/O to it. */
    public static synchronized void specLoaderEnd() {
        try {
            if (activeSpecLoader != null && specLoaders.size() < MAX_SPEC_LOADERS) {
                specLoaders.add(specLoaderTiming(
                        activeSpecLoader, activeSpecLoaderNanos, System.nanoTime(), true));
            }
            activeSpecLoader = null;
            activeSpecLoaderNanos = 0L;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
        }
    }

    /** Starts one repeated operation inside a measured {@code SpecStore} loader. */
    public static synchronized void specSubphaseStart(String label) {
        try {
            long now = System.nanoTime();
            if (activeSpecSubphase != null) {
                recordSpecSubphase(activeSpecSubphase, activeSpecSubphaseNanos, now, false);
            }
            activeSpecSubphase = label == null ? "<null>" : label;
            activeSpecSubphaseNanos = now;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
        }
    }

    /** Aggregates a repeated loader operation without performing file I/O in the hot loop. */
    public static synchronized void specSubphaseEnd() {
        try {
            if (activeSpecSubphase != null) {
                recordSpecSubphase(
                        activeSpecSubphase, activeSpecSubphaseNanos, System.nanoTime(), true);
            }
            activeSpecSubphase = null;
            activeSpecSubphaseNanos = 0L;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
        }
    }

    /** Turns on the {@code LoadingUtils} merged-read timing woven by {@link MergedReadProbePlan}. */
    static void enableMergedReadProbe(boolean enabled) {
        mergedReadProbe = enabled;
    }

    static boolean mergedReadProbeEnabled() {
        return mergedReadProbe;
    }

    static boolean phaseProbeEnabled() {
        return destination != null;
    }

    /**
     * Times one merged CSV read and returns exactly what the original returned.
     *
     * <p>The handle is invoked whether or not the probe is on, so an installed-but-disabled probe
     * is the original call with one extra branch. Timing brackets the invocation only; the
     * aggregation behind it takes a lock, and holding that lock across the read would make the
     * probe part of what it measures.
     */
    public static Object mergedCsvRead(
            Object roots, String path, boolean first, boolean second, MethodHandle vanilla)
            throws Throwable {
        if (!mergedReadProbe) {
            return vanilla.invoke(roots, path, first, second);
        }
        long start = System.nanoTime();
        try {
            return vanilla.invoke(roots, path, first, second);
        } finally {
            recordMergedRead("csv", path, System.nanoTime() - start);
        }
    }

    /** Times one merged JSON read and returns exactly what the original returned. */
    public static Object mergedJsonRead(String path, Object keys, MethodHandle vanilla)
            throws Throwable {
        if (!mergedReadProbe) {
            return vanilla.invoke(path, keys);
        }
        long start = System.nanoTime();
        try {
            return vanilla.invoke(path, keys);
        } finally {
            recordMergedRead("json", path, System.nanoTime() - start);
        }
    }

    /** Returns the entry token for one exact, opt-in startup call-site timer. */
    public static long hotCallStart() {
        return System.nanoTime();
    }

    /** Aggregates a reviewed AshLib or GraphicsLib call without writing in the hot path. */
    public static void hotCallEnd(String label, long startedNanos) {
        try {
            long duration = System.nanoTime() - startedNanos;
            if (label == null || duration < 0L) {
                return;
            }
            recordHotCall(label, duration);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Woven diagnostics are never allowed to affect startup.
        }
    }

    static synchronized Map<String, Object> telemetry() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("installed", installed);
        output.put("destination", destination == null ? null : destination.toString());
        output.put("startedAt", startedAt);
        output.put("phases", List.copyOf(phases));
        output.put("plugins", List.copyOf(plugins));
        output.put("specLoaders", List.copyOf(specLoaders));
        output.put("specSubphases", specSubphases.values().stream()
                .map(SpecSubphase::toMap).toList());
        output.put("hotCalls", hotCalls.values().stream().map(HotCall::toMap).toList());
        output.put("mergedReads", mergedReads.values().stream().map(MergedRead::toMap).toList());
        output.put("activePlugin", activePlugin);
        output.put("activeSpecLoader", activeSpecLoader);
        output.put("activeSpecSubphase", activeSpecSubphase);
        output.put("progressCalls", progressCalls);
        output.put("lastProgressPermille", lastProgressPermille);
        output.put("writeProblem", writeProblem);
        return output;
    }

    private static void recordPhase(String name, Integer progressPermille) {
        if (phases.size() >= MAX_PHASES) {
            return;
        }
        long now = System.nanoTime();
        Map<String, Object> phase = new LinkedHashMap<>();
        phase.put("name", name);
        phase.put("elapsedMillis", millis(now - startedNanos));
        phase.put("sincePreviousMillis", millis(now - lastPhaseNanos));
        if (progressPermille != null) {
            phase.put("progressPermille", progressPermille);
        }
        phases.add(phase);
        lastPhaseNanos = now;
        writeSafely();
    }

    private static Map<String, Object> pluginTiming(
            String className, long startNanos, long endNanos, boolean completed) {
        Map<String, Object> timing = new LinkedHashMap<>();
        timing.put("className", className);
        timing.put("startedAtMillis", millis(startNanos - startedNanos));
        timing.put("durationMillis", millis(endNanos - startNanos));
        timing.put("completed", completed);
        return timing;
    }

    private static Map<String, Object> specLoaderTiming(
            String label, long startNanos, long endNanos, boolean completed) {
        Map<String, Object> timing = new LinkedHashMap<>();
        timing.put("label", label);
        timing.put("startedAtMillis", millis(startNanos - startedNanos));
        timing.put("durationMillis", millis(endNanos - startNanos));
        timing.put("completed", completed);
        return timing;
    }

    private static void recordSpecSubphase(
            String label, long startNanos, long endNanos, boolean completed) {
        SpecSubphase timing = specSubphases.get(label);
        if (timing == null) {
            if (specSubphases.size() >= MAX_SPEC_SUBPHASES) {
                return;
            }
            timing = new SpecSubphase(label);
            specSubphases.put(label, timing);
        }
        timing.record(Math.max(0L, endNanos - startNanos), completed);
    }

    /**
     * Counts one merged read against its group.
     *
     * <p>Package-private because {@link MergedReadCacheRuntime} weaves the same two methods this
     * probe does and only one of them can be installed. The cache reports through here so that
     * choosing to serve a launch does not cost the measurement of what it served.
     */
    static void recordMergedRead(String kind, String path, long durationNanos) {
        try {
            record(kind, mergedReadGroup(path), durationNanos);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Woven into startup. Diagnostics are never allowed to become startup.
        }
    }

    /**
     * Names the row a path is counted under.
     *
     * <p>A spreadsheet is its own subject -- {@code ship_data.csv} and {@code rules.csv} are read
     * once each and want their own line. A spec file is one of thousands in a directory and is only
     * interesting in aggregate, so {@code data/hulls/afflictor.ship} counts under
     * {@code data/hulls/*}. Without that split the report is either five rows of nothing or five
     * thousand rows of one read each.
     */
    static String mergedReadGroup(String path) {
        if (path == null || path.isEmpty()) {
            return "<null>";
        }
        String normalized = path.replace('\\', '/');
        if (normalized.endsWith(".csv") || normalized.endsWith(".json")) {
            return normalized;
        }
        int lastSlash = normalized.lastIndexOf('/');
        return lastSlash < 0 ? "*" : normalized.substring(0, lastSlash + 1) + "*";
    }

    private static synchronized void record(String kind, String group, long durationNanos) {
        String key = kind + " " + group;
        MergedRead timing = mergedReads.get(key);
        if (timing == null) {
            if (mergedReads.size() >= MAX_MERGED_READ_GROUPS) {
                key = kind + " <overflow>";
                timing = mergedReads.get(key);
            }
            if (timing == null) {
                timing = new MergedRead(kind, key.substring(kind.length() + 1));
                mergedReads.put(key, timing);
            }
        }
        timing.record(Math.max(0L, durationNanos));
    }

    private static synchronized void recordHotCall(String label, long durationNanos) {
        HotCall timing = hotCalls.get(label);
        if (timing == null) {
            if (hotCalls.size() >= MAX_HOT_CALL_GROUPS) {
                label = "<overflow>";
                timing = hotCalls.get(label);
            }
            if (timing == null) {
                timing = new HotCall(label);
                hotCalls.put(label, timing);
            }
        }
        timing.record(durationNanos);
    }

    private static long millis(long nanos) {
        return Math.max(0L, nanos / 1_000_000L);
    }

    private static final class SpecSubphase {
        private final String label;
        private long calls;
        private long completedCalls;
        private long totalNanos;
        private long maxNanos;

        private SpecSubphase(String label) {
            this.label = label;
        }

        private void record(long durationNanos, boolean completed) {
            calls++;
            if (completed) {
                completedCalls++;
            }
            totalNanos = Math.addExact(totalNanos, durationNanos);
            maxNanos = Math.max(maxNanos, durationNanos);
        }

        private Map<String, Object> toMap() {
            Map<String, Object> timing = new LinkedHashMap<>();
            timing.put("label", label);
            timing.put("calls", calls);
            timing.put("completedCalls", completedCalls);
            timing.put("durationMillis", millis(totalNanos));
            timing.put("maxCallMillis", millis(maxNanos));
            return timing;
        }
    }

    private static final class MergedRead {
        private final String kind;
        private final String group;
        private long calls;
        private long totalNanos;
        private long maxNanos;

        private MergedRead(String kind, String group) {
            this.kind = kind;
            this.group = group;
        }

        private void record(long durationNanos) {
            calls++;
            totalNanos = Math.addExact(totalNanos, durationNanos);
            maxNanos = Math.max(maxNanos, durationNanos);
        }

        private Map<String, Object> toMap() {
            Map<String, Object> timing = new LinkedHashMap<>();
            timing.put("kind", kind);
            timing.put("group", group);
            timing.put("calls", calls);
            timing.put("durationMillis", millis(totalNanos));
            timing.put("maxCallMillis", millis(maxNanos));
            return timing;
        }
    }

    private static final class HotCall {
        private final String label;
        private long calls;
        private long totalNanos;
        private long maxNanos;

        private HotCall(String label) {
            this.label = label;
        }

        private void record(long durationNanos) {
            calls++;
            totalNanos = Math.addExact(totalNanos, durationNanos);
            maxNanos = Math.max(maxNanos, durationNanos);
        }

        private Map<String, Object> toMap() {
            Map<String, Object> timing = new LinkedHashMap<>();
            timing.put("label", label);
            timing.put("calls", calls);
            timing.put("durationMillis", millis(totalNanos));
            timing.put("maxCallMillis", millis(maxNanos));
            return timing;
        }
    }

    private static void writeSafely() {
        if (destination == null) {
            return;
        }
        try {
            Path parent = destination.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String json = Json.object(telemetry());
            Path temporary = destination.resolveSibling(destination.getFileName()
                    + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
            boolean moved = false;
            try {
                Files.writeString(temporary, json + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                try {
                    Files.move(temporary, destination,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                moved = true;
                writeProblem = null;
            } finally {
                if (!moved) {
                    Files.deleteIfExists(temporary);
                }
            }
        } catch (IOException | RuntimeException error) {
            writeProblem = error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }
}
