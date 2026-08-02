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

/** Direct, low-overhead timing for the work hidden behind the loading bar's 100% state. */
public final class StartupPhaseRuntime {
    static final String PLAN_ID = "startup-phase-probe-v1";
    private static final int MAX_PHASES = 64;
    private static final int MAX_PLUGINS = 128;

    private static Path destination;
    private static Instant startedAt;
    private static long startedNanos;
    private static long lastPhaseNanos;
    private static boolean installed;
    private static String writeProblem;
    private static final List<Map<String, Object>> phases = new ArrayList<>();
    private static final List<Map<String, Object>> plugins = new ArrayList<>();
    private static String activePlugin;
    private static long activePluginNanos;

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
        activePlugin = null;
        activePluginNanos = 0L;
    }

    static synchronized void installed() {
        installed = true;
        writeSafely();
    }

    /** Called from the reviewed game class. It must never let probe failure affect startup. */
    public static synchronized void mark(String name) {
        try {
            if (phases.size() >= MAX_PHASES) {
                return;
            }
            long now = System.nanoTime();
            Map<String, Object> phase = new LinkedHashMap<>();
            phase.put("name", name);
            phase.put("elapsedMillis", millis(now - startedNanos));
            phase.put("sincePreviousMillis", millis(now - lastPhaseNanos));
            phases.add(phase);
            lastPhaseNanos = now;
            writeSafely();
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // This code is woven into startup. Diagnostics are never allowed to become startup.
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

    static synchronized Map<String, Object> telemetry() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("installed", installed);
        output.put("destination", destination == null ? null : destination.toString());
        output.put("startedAt", startedAt);
        output.put("phases", List.copyOf(phases));
        output.put("plugins", List.copyOf(plugins));
        output.put("activePlugin", activePlugin);
        output.put("writeProblem", writeProblem);
        return output;
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

    private static long millis(long nanos) {
        return Math.max(0L, nanos / 1_000_000L);
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
