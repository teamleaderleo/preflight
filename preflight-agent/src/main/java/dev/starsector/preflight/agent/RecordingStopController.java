package dev.starsector.preflight.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;

/** File-signalled recording stop used to finish evidence before an external harness exits the JVM. */
final class RecordingStopController {
    static final String REQUEST_SUFFIX = ".stop-request";
    static final String COMPLETE_SUFFIX = ".stop-complete";
    private static final Duration POLL_INTERVAL = Duration.ofMillis(25);

    private RecordingStopController() {
    }

    static Thread start(Recording recording, Path destination) throws Exception {
        if (recording == null) {
            return null;
        }
        Path request = requestFor(destination);
        Path complete = completeFor(destination);
        Files.deleteIfExists(request);
        Files.deleteIfExists(complete);
        Thread thread = new Thread(
                () -> watch(recording, destination, request, complete),
                "Preflight-Recording-Stop");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
        return thread;
    }

    static Path requestFor(Path destination) {
        return sibling(destination, REQUEST_SUFFIX);
    }

    static Path completeFor(Path destination) {
        return sibling(destination, COMPLETE_SUFFIX);
    }

    private static Path sibling(Path destination, String suffix) {
        Path absolute = destination.toAbsolutePath().normalize();
        String name = absolute.getFileName().toString();
        String stem = name.endsWith(".jfr") ? name.substring(0, name.length() - 4) : name;
        return absolute.resolveSibling(stem + suffix);
    }

    private static void watch(
            Recording recording,
            Path destination,
            Path request,
            Path complete) {
        try {
            while (recording.getState() == RecordingState.RUNNING && !Files.isRegularFile(request)) {
                Thread.sleep(POLL_INTERVAL.toMillis());
            }
            if (!Files.isRegularFile(request)) {
                return;
            }
            boolean written = PreflightAgent.stopRecording(recording, destination);
            publishCompletion(complete, written ? "ok\n" : "recording-stop-failed\n");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            try {
                publishCompletion(
                        complete,
                        "recording-stop-failed: " + error.getClass().getSimpleName() + "\n");
            } catch (Throwable ignored) {
                // The ordinary shutdown fallback remains available.
            }
        }
    }

    /**
     * Publishes the terminal response as one filesystem transition.
     *
     * <p>The reader treats existence of {@code complete} as acknowledgement. Writing directly to
     * that path creates/truncates the file before all response bytes are necessarily visible, so a
     * fast reader can observe a regular zero-length file and mistake a successful stop for failure.
     * Finish the bytes under a private sibling name first and only then expose the terminal path.
     */
    static void publishCompletion(Path complete, String response) throws Exception {
        Path absolute = complete.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = absolute.resolveSibling(
                absolute.getFileName() + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        boolean moved = false;
        try {
            Files.writeString(
                    temporary,
                    response,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            try {
                Files.move(
                        temporary,
                        absolute,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }
}
