package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.agent.RecordingFlusherPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reconciliation happens after the game process is gone, which is the only place the two writers
 * cannot race. What it must never do is leave the shorter recording in place, or destroy the other.
 */
class RecordingSidecarTest {
    @Test
    void promotesTheSidecarWhenTheExitDumpLostTheTail(@TempDir Path directory) throws Exception {
        Path recording = directory.resolve("startup.jfr");
        Path sidecar = RecordingFlusherPaths.sidecarFor(recording);
        Files.writeString(recording, "exit dump, truncated");
        Files.writeString(sidecar, "in-flight dump, longer because it covers more of the run");

        RecordingSidecar.Result result = RecordingSidecar.reconcile(recording);

        assertTrue(result.promoted());
        assertEquals("in-flight dump, longer because it covers more of the run",
                Files.readString(recording));
        assertFalse(Files.exists(sidecar), "the sidecar moves rather than being copied");
        assertEquals("exit dump, truncated", Files.readString(result.displaced()),
                "the losing recording is kept, not deleted");
    }

    @Test
    void promotesTheSidecarWhenTheGameWasForceQuitAndNothingWasWritten(@TempDir Path directory)
            throws Exception {
        Path recording = directory.resolve("startup.jfr");
        Path sidecar = RecordingFlusherPaths.sidecarFor(recording);
        Files.writeString(sidecar, "everything up to the last flush");

        RecordingSidecar.Result result = RecordingSidecar.reconcile(recording);

        assertTrue(result.promoted());
        assertEquals(0L, result.recordingBytes());
        assertNull(result.displaced(), "there was no exit dump to keep");
        assertEquals("everything up to the last flush", Files.readString(recording));
    }

    @Test
    void keepsTheExitDumpWhenItSawAtLeastAsMuch(@TempDir Path directory) throws Exception {
        Path recording = directory.resolve("startup.jfr");
        Path sidecar = RecordingFlusherPaths.sidecarFor(recording);
        Files.writeString(recording, "the complete exit dump, which saw the final chunk");
        Files.writeString(sidecar, "flushed earlier");

        RecordingSidecar.Result result = RecordingSidecar.reconcile(recording);

        assertFalse(result.promoted());
        assertEquals("the complete exit dump, which saw the final chunk", Files.readString(recording));
        assertFalse(Files.exists(sidecar), "a redundant sidecar is cleaned up");
    }

    @Test
    void doesNothingWithoutASidecar(@TempDir Path directory) throws Exception {
        Path recording = directory.resolve("startup.jfr");
        Files.writeString(recording, "exit dump");

        assertNull(RecordingSidecar.reconcile(recording));
        assertEquals("exit dump", Files.readString(recording));
    }

    @Test
    void discardsAnEmptySidecarRatherThanPromotingIt(@TempDir Path directory) throws Exception {
        Path recording = directory.resolve("startup.jfr");
        Path sidecar = RecordingFlusherPaths.sidecarFor(recording);
        Files.writeString(recording, "exit dump");
        Files.createFile(sidecar);

        RecordingSidecar.Result result = RecordingSidecar.reconcile(recording);

        assertFalse(result.promoted());
        assertEquals("exit dump", Files.readString(recording));
        assertFalse(Files.exists(sidecar));
    }
}
