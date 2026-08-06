package dev.starsector.preflight.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
            Files.writeString(
                    complete,
                    written ? "ok\n" : "recording-stop-failed\n",
                    StandardCharsets.UTF_8);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            try {
                Files.writeString(
                        complete,
                        "recording-stop-failed: " + error.getClass().getSimpleName() + "\n",
                        StandardCharsets.UTF_8);
            } catch (Throwable ignored) {
                // The ordinary shutdown fallback remains available.
            }
        }
    }
}
