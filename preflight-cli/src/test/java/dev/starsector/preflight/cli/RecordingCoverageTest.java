package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The chunk count is what decides whether a timestamp in a recording means anything, and nothing in
 * the public JFR API reports it. Reading a multi-chunk recording by time was silently wrong here for
 * as long as this project has been taking profiles.
 */
class RecordingCoverageTest {
    @Test
    void aSingleChunkRecordingIsSafeToReadByTime(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("startup.jfr");
        try (Recording recording = new Recording()) {
            recording.enable("jdk.ThreadSleep").withThreshold(Duration.ZERO);
            recording.setToDisk(true);
            recording.start();
            burn();
            recording.stop();
            recording.dump(file);
        }

        RecordingCoverage.Result coverage = RecordingCoverage.inspect(file);

        assertEquals(1, coverage.chunks());
        assertTrue(coverage.timestampsTrustworthy());
        assertTrue(coverage.events() > 0);
        assertNotNull(coverage.firstEvent());
        assertNotNull(coverage.lastEvent());
        assertFalse(coverage.eventSpan().isNegative());
    }

    @Test
    void concatenatedChunksAreCountedAndDistrusted(@TempDir Path directory) throws Exception {
        Path one = directory.resolve("one.jfr");
        Path two = directory.resolve("two.jfr");
        for (Path each : new Path[] {one, two}) {
            try (Recording recording = new Recording()) {
                recording.enable("jdk.ThreadSleep").withThreshold(Duration.ZERO);
                recording.setToDisk(true);
                recording.start();
                burn();
                recording.stop();
                recording.dump(each);
            }
        }
        // Exactly what dumpOnExit produces for a run that rotated: the chunks, end to end.
        Path joined = directory.resolve("startup.jfr");
        byte[] first = Files.readAllBytes(one);
        byte[] second = Files.readAllBytes(two);
        byte[] both = new byte[first.length + second.length];
        System.arraycopy(first, 0, both, 0, first.length);
        System.arraycopy(second, 0, both, first.length, second.length);
        Files.write(joined, both);

        RecordingCoverage.Result coverage = RecordingCoverage.inspect(joined);

        assertEquals(2, coverage.chunks());
        assertFalse(coverage.timestampsTrustworthy(),
                "two chunks means the second one's timestamps are not on the first one's clock");
        assertEquals(
                RecordingCoverage.inspect(one).events() + RecordingCoverage.inspect(two).events(),
                coverage.events(),
                "no events are lost by concatenation -- only their timestamps become incomparable");
    }

    @Test
    void anAbsentOrEmptyRecordingCountsNoChunks(@TempDir Path directory) throws Exception {
        assertEquals(0, RecordingCoverage.countChunks(directory.resolve("missing.jfr")));
        Path empty = Files.createFile(directory.resolve("empty.jfr"));
        assertEquals(0, RecordingCoverage.countChunks(empty));
    }

    @Test
    void theMagicInsideAChunkBodyIsNotCountedAsAChunk(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("startup.jfr");
        try (Recording recording = new Recording()) {
            recording.enable("jdk.ThreadSleep").withThreshold(Duration.ZERO);
            recording.setToDisk(true);
            recording.start();
            burn();
            recording.stop();
            recording.dump(file);
        }
        // A real single-chunk recording contains FLR\0 in its body; counting by scanning for the
        // magic reports two chunks for this file and distrusts a perfectly good recording.
        assertEquals(1, RecordingCoverage.countChunks(file));
    }

    @Test
    void aFileThatIsNotARecordingCountsNothing(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("notes.jfr");
        Files.writeString(file, "this is not a flight recording, whatever its name says");
        assertEquals(0, RecordingCoverage.countChunks(file));
    }

    /** Enough real events that the recording has a window to report. */
    private static void burn() throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            Thread.sleep(1);
        }
    }
}
