package dev.starsector.preflight.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

/**
 * How much of a run a recording actually accounts for, and whether it can be trusted by timestamp.
 *
 * <p>Flight Recorder rotates to a new chunk at 12 MB. When such a recording is read as one file,
 * the events of every chunk after the first come back **stamped inside the first chunk's window**:
 * a 240-second run whose events all claim to have happened in its first 96 seconds. The events are
 * all there -- splitting the file chunk by chunk returns exactly the same count -- so anything
 * derived from proportions is unaffected. Anything derived from *when* is not, and this project has
 * read multi-chunk recordings by timestamp without knowing it.
 *
 * <p>So this does not try to repair a recording. It reports the two facts that decide whether a
 * timestamp in it means anything: how many chunks it holds, and what window its events claim.
 * A single-chunk recording is safe to read either way.
 *
 * <p>To analyse a multi-chunk recording by time, split it first and read the parts separately:
 * {@code jfr disassemble --max-chunks 1 --output <dir> <recording>}.
 */
final class RecordingCoverage {
    /** Every JFR chunk starts with these four bytes. */
    private static final byte[] CHUNK_MAGIC = {'F', 'L', 'R', 0};

    private RecordingCoverage() {
    }

    record Result(int chunks, Instant firstEvent, Instant lastEvent, long events) {
        /** Whether timestamps in this recording can be read at face value. */
        boolean timestampsTrustworthy() {
            return chunks <= 1;
        }

        Duration eventSpan() {
            return firstEvent == null || lastEvent == null
                    ? Duration.ZERO
                    : Duration.between(firstEvent, lastEvent);
        }
    }

    static Result inspect(Path recording) throws IOException {
        int chunks = countChunks(recording);
        Instant first = null;
        Instant last = null;
        long events = 0;
        try (RecordingFile file = new RecordingFile(recording)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                if (event == null) {
                    continue;
                }
                events++;
                Instant at = event.getStartTime();
                if (at == null) {
                    continue;
                }
                if (first == null || at.isBefore(first)) {
                    first = at;
                }
                if (last == null || at.isAfter(last)) {
                    last = at;
                }
            }
        }
        return new Result(chunks, first, last, events);
    }

    /** Magic (4) + major (2) + minor (2) + chunk size (8). */
    private static final int HEADER_PREFIX = 16;

    /**
     * Counts chunks by walking the chunk headers.
     *
     * <p>The public JFR API exposes events but not the chunking that decides whether their
     * timestamps line up. Scanning the file for the magic does not work -- {@code FLR\0} occurs
     * inside chunk bodies too, and counted that way a single-chunk recording reports two. Each
     * header carries its own chunk length at offset 8, so the chunks can be stepped through exactly.
     */
    static int countChunks(Path recording) throws IOException {
        if (!Files.isRegularFile(recording)) {
            return 0;
        }
        long length = Files.size(recording);
        if (length < HEADER_PREFIX) {
            return 0;
        }
        int chunks = 0;
        byte[] header = new byte[HEADER_PREFIX];
        try (InputStream in = Files.newInputStream(recording)) {
            long offset = 0;
            while (offset + HEADER_PREFIX <= length) {
                if (!readFully(in, header)) {
                    break;
                }
                for (int i = 0; i < CHUNK_MAGIC.length; i++) {
                    if (header[i] != CHUNK_MAGIC[i]) {
                        // Not a chunk boundary, so the file is not a plain concatenation and the
                        // count stops rather than guessing.
                        return chunks;
                    }
                }
                long size = 0;
                for (int i = 8; i < HEADER_PREFIX; i++) {
                    size = (size << 8) | (header[i] & 0xFFL);
                }
                if (size < HEADER_PREFIX || offset + size > length) {
                    return chunks + 1;
                }
                chunks++;
                offset += size;
                if (in.skip(size - HEADER_PREFIX) != size - HEADER_PREFIX) {
                    break;
                }
            }
        }
        return chunks;
    }

    private static boolean readFully(InputStream in, byte[] into) throws IOException {
        int read = 0;
        while (read < into.length) {
            int step = in.read(into, read, into.length - read);
            if (step < 0) {
                return false;
            }
            read += step;
        }
        return true;
    }
}
