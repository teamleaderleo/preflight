package dev.starsector.preflight.agent;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;

/** One terminal owner shared by the live stop controller and shutdown fallback. */
final class RecordingFinalizer {
    private final BooleanSupplier running;
    private final BooleanSupplier finish;
    private boolean completed;
    private boolean written;

    RecordingFinalizer(Recording recording, Path destination) {
        Objects.requireNonNull(recording, "recording");
        Path normalizedDestination = Objects.requireNonNull(destination, "destination")
                .toAbsolutePath()
                .normalize();
        this.running = () -> recording.getState() == RecordingState.RUNNING;
        this.finish = () -> PreflightAgent.stopRecording(recording, normalizedDestination);
    }

    /** Test seam for proving once-only ownership without depending on JVM shutdown timing. */
    RecordingFinalizer(BooleanSupplier running, BooleanSupplier finish) {
        this.running = Objects.requireNonNull(running, "running");
        this.finish = Objects.requireNonNull(finish, "finish");
    }

    boolean isRunning() {
        return running.getAsBoolean();
    }

    synchronized boolean finish() {
        if (!completed) {
            written = finish.getAsBoolean();
            completed = true;
        }
        return written;
    }
}
