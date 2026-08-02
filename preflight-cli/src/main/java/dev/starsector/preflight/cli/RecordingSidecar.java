package dev.starsector.preflight.cli;

import dev.starsector.preflight.agent.RecordingFlusherPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Decides, after the game process is gone, which of two recordings of the same run to keep.
 *
 * <p>The agent's flusher writes a sidecar as the run goes; the JVM writes the destination from its
 * own shutdown hook. Neither can be trusted to be the longer one. The exit dump is the only writer
 * that sees the final chunk, so it usually wins -- but this project's own recordings include a
 * 391-second session whose exit dump stopped at 157 seconds, and one that was never written at all
 * because the game was force-quit.
 *
 * <p>Doing this here rather than in the agent is the point: inside the process the two writers race,
 * and outside it they cannot. Both files come from the same recording, so the larger one holds
 * strictly more of it; size is the comparison. The loser is kept under a suffixed name rather than
 * deleted -- it costs a few megabytes and it is the only copy of whichever tail the winner lacks if
 * that assumption is ever wrong.
 */
final class RecordingSidecar {
    private RecordingSidecar() {
    }

    /** What happened, for the run metadata and for the operator. */
    record Result(boolean promoted, long recordingBytes, long sidecarBytes, Path displaced) {
    }

    /**
     * Reconciles {@code recording} with its sidecar, leaving the fuller of the two at
     * {@code recording}.
     *
     * @return null when there is no sidecar to reconcile
     */
    static Result reconcile(Path recording) throws IOException {
        Path sidecar = RecordingFlusherPaths.sidecarFor(recording.toAbsolutePath().normalize());
        if (!Files.isRegularFile(sidecar)) {
            return null;
        }
        long sidecarBytes = Files.size(sidecar);
        long recordingBytes = Files.isRegularFile(recording) ? Files.size(recording) : 0L;
        if (sidecarBytes == 0L) {
            Files.deleteIfExists(sidecar);
            return new Result(false, recordingBytes, 0L, null);
        }
        if (recordingBytes >= sidecarBytes) {
            // The exit dump saw at least as much. The sidecar is redundant.
            Files.deleteIfExists(sidecar);
            return new Result(false, recordingBytes, sidecarBytes, null);
        }

        Path displaced = null;
        if (recordingBytes > 0L) {
            displaced = recording.resolveSibling(stem(recording) + ".exit-dump.jfr");
            Files.move(recording, displaced, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(sidecar, recording, StandardCopyOption.REPLACE_EXISTING);
        return new Result(true, recordingBytes, sidecarBytes, displaced);
    }

    private static String stem(Path recording) {
        String name = recording.getFileName().toString();
        return name.endsWith(".jfr") ? name.substring(0, name.length() - 4) : name;
    }
}
