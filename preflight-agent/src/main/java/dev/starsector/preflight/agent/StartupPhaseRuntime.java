package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
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
    private static final int MAX_SPEC_SUBPHASES = 16;
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

    /** Called from the reviewed game class. It must never let probe failure affect startup. */
    public static synchronized void mark(String name) {
        try {
            recordPhase(name, null);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // This code is woven into startup. Diagnostics are never allowed to become startup.
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
