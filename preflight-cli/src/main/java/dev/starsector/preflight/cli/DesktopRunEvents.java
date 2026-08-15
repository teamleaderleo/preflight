package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** A narrow machine-readable signal from the CLI parent to the desktop host. */
final class DesktopRunEvents implements AutoCloseable {
    static final String ENVIRONMENT_VARIABLE = "PREFLIGHT_DESKTOP_EVENTS";
    static final String ENVIRONMENT_VALUE = "stderr-v1";
    static final String PREFIX = "PREFLIGHT_DESKTOP_EVENT ";
    private static final String FORMAT = "preflight-desktop-run-event-v1";

    private final AtomicBoolean stopping;
    private final Thread watcher;

    private DesktopRunEvents(AtomicBoolean stopping, Thread watcher) {
        this.stopping = stopping;
        this.watcher = watcher;
    }

    static DesktopRunEvents watch(
            Path runtimeProcess, Map<String, String> environment, PrintStream destination) {
        if (!ENVIRONMENT_VALUE.equals(environment.get(ENVIRONMENT_VARIABLE))) {
            return new DesktopRunEvents(new AtomicBoolean(true), null);
        }
        Path identity = runtimeProcess.toAbsolutePath().normalize();
        // Do this before the child can start. Clearing it inside the watcher would race the agent
        // and could delete the new JVM's freshly published identity.
        try {
            Files.deleteIfExists(identity);
        } catch (IOException | RuntimeException cannotClearStaleIdentity) {
            return new DesktopRunEvents(new AtomicBoolean(true), null);
        }
        AtomicBoolean stopping = new AtomicBoolean(false);
        Thread watcher = new Thread(
                () -> observe(identity, destination, stopping), "preflight-desktop-run-events");
        watcher.setDaemon(true);
        watcher.start();
        return new DesktopRunEvents(stopping, watcher);
    }

    private static void observe(Path identity, PrintStream destination, AtomicBoolean stopping) {
        while (!stopping.get()) {
            try {
                RuntimeProcessIdentity process = RuntimeProcessIdentity.read(identity);
                if (process.attachable()) {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("format", FORMAT);
                    event.put("state", "game-running");
                    event.put("pid", process.pid());
                    destination.println(PREFIX + Json.object(event));
                    destination.flush();
                    return;
                }
            } catch (IOException | RuntimeException notReadyYet) {
                // The agent publishes atomically. Missing and half-observed files mean "wait".
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void close() {
        stopping.set(true);
        if (watcher == null) return;
        watcher.interrupt();
        try {
            watcher.join(1_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
