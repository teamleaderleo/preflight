package dev.starsector.preflight.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/** File-signalled recording stop used to finish evidence before an external harness exits the JVM. */
final class RecordingStopController {
    static final String REQUEST_SUFFIX = ".stop-request";
    static final String COMPLETE_SUFFIX = ".stop-complete";
    private static final Duration POLL_INTERVAL = Duration.ofMillis(25);

    private RecordingStopController() {
    }

    static Thread start(RecordingFinalizer finalizer, Path destination) throws Exception {
        if (finalizer == null) {
            return null;
        }
        Path request = requestFor(destination);
        Path complete = completeFor(destination);
        Files.deleteIfExists(request);
        Files.deleteIfExists(complete);
        Thread thread = new Thread(
                () -> watch(finalizer, request, complete),
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
            RecordingFinalizer finalizer,
            Path request,
            Path complete) {
        try {
            while (finalizer.isRunning() && !Files.isRegularFile(request)) {
                Thread.sleep(POLL_INTERVAL.toMillis());
            }
            if (!Files.isRegularFile(request)) {
                return;
            }
            boolean written = finalizer.finish();
            publish(complete, written ? "ok\n" : "recording-stop-failed\n");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            try {
                publish(complete, "recording-stop-failed: " + error.getClass().getSimpleName() + "\n");
            } catch (Throwable ignored) {
                // The ordinary shutdown fallback remains available.
            }
        }
    }

    /**
     * Publishes the acknowledgement through a temporary file.
     *
     * <p>Whoever asked for the stop is polling for this file and reads it as soon as it exists.
     * Writing in place creates and truncates before writing, so that reader can catch the file
     * existing and still empty and conclude the stop failed. The rename makes the file appear
     * only once it holds the whole answer.
     */
    static void publish(Path complete, String acknowledgement) throws IOException {
        Path temporary = complete.resolveSibling(complete.getFileName() + ".tmp");
        Files.writeString(temporary, acknowledgement, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, complete, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(temporary, complete, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
