package dev.starsector.preflight.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;

/**
 * Writes the recording to disk while the game is still running.
 *
 * <p>A recording with a destination and {@code dumpOnExit} produces a **zero-byte file for the
 * entire life of the process**: every event stays in Flight Recorder's chunk repository until the
 * JVM's own shutdown hook writes it out. So any run that is force-quit, killed, or crashes leaves
 * nothing at all, and — measured on this project's own recordings — even runs that exit cleanly can
 * come back with their tail missing: a 391-second session whose events stopped at 157 seconds, and
 * a 251-second one that stopped at 100. Every {@code --profile} run this project had taken was
 * silently losing its end, which is exactly the part a save-load or a late-campaign question needs.
 *
 * <p>This flusher does not replace that mechanism, because it cannot: the JVM's dump is the only
 * one guaranteed to see the final chunk. It writes a **sidecar** alongside the destination instead,
 * periodically and once more on the way out, and never touches the destination itself. Whichever
 * file covers more is then a decision made after the process is gone, where there is no race
 * ({@code RunCommand} makes it).
 *
 * <p>Everything here is best effort by construction. A failed dump leaves the previous sidecar in
 * place, a failed move leaves the sidecar untouched, and nothing thrown from this class is allowed
 * to reach the game.
 */
final class RecordingFlusher implements AutoCloseable {
    private final Recording recording;
    private final Path sidecar;
    private final Duration interval;
    private final Thread thread;
    private volatile boolean running = true;
    private volatile int flushes;

    private RecordingFlusher(Recording recording, Path destination, Duration interval) {
        this.recording = recording;
        this.sidecar = RecordingFlusherPaths.sidecarFor(destination);
        this.interval = interval;
        this.thread = new Thread(this::loop, "Preflight-Recording-Flush");
        // A daemon thread cannot hold the game open if anything here wedges, and the shutdown hook
        // takes the final flush regardless of where this thread happens to be.
        this.thread.setDaemon(true);
        this.thread.setPriority(Thread.MIN_PRIORITY);
    }

    /**
     * Starts flushing, or returns null when flushing is off or unavailable.
     *
     * @param interval how often to write the sidecar; zero or negative disables flushing entirely
     */
    static RecordingFlusher start(Recording recording, Path destination, Duration interval) {
        if (recording == null || destination == null || interval == null || interval.isZero()
                || interval.isNegative()) {
            return null;
        }
        RecordingFlusher flusher = new RecordingFlusher(recording, destination, interval);
        flusher.thread.start();
        return flusher;
    }

    Path sidecar() {
        return sidecar;
    }

    int flushes() {
        return flushes;
    }

    private void loop() {
        while (running) {
            try {
                Thread.sleep(interval.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!running) {
                return;
            }
            flush();
        }
    }

    /**
     * Dumps the recording so far onto the sidecar, atomically.
     *
     * <p>The dump goes to a temporary file first. Flight Recorder deletes the chunk repository
     * during shutdown, so a dump racing that deletion can produce an empty or partial file; writing
     * through a temporary means such a dump is discarded rather than published over a good one.
     */
    synchronized boolean flush() {
        if (recording.getState() != RecordingState.RUNNING) {
            return false;
        }
        Path temporary = sidecar.resolveSibling(sidecar.getFileName() + ".tmp");
        try {
            recording.dump(temporary);
            if (!Files.isRegularFile(temporary) || Files.size(temporary) == 0L) {
                Files.deleteIfExists(temporary);
                return false;
            }
            try {
                Files.move(temporary, sidecar, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
                Files.move(temporary, sidecar, StandardCopyOption.REPLACE_EXISTING);
            }
            flushes++;
            return true;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            try {
                Files.deleteIfExists(temporary);
            } catch (Throwable ignored) {
                // Nothing useful to do; the stale temporary is harmless next to the sidecar.
            }
            return false;
        }
    }

    /** Takes one last flush, so the sidecar covers as much of the run as the repository still has. */
    @Override
    public void close() {
        running = false;
        flush();
        thread.interrupt();
    }
}
