package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A recording with a destination is a zero-byte file until the JVM exits, and the exit dump has been
 * measured losing the tail of a clean 240-second run at 96 seconds. The sidecar is what makes a
 * force-quit, a crash, or a truncated exit dump survivable, so what matters here is that it exists
 * on disk while the process is still alive and that failures of it stay silent.
 */
class RecordingFlusherTest {
    @Test
    void writesTheSidecarWhileTheProcessIsStillRunning(@TempDir Path directory) throws Exception {
        Path destination = directory.resolve("startup.jfr");
        try (Recording recording = new Recording()) {
            recording.enable("jdk.JavaMonitorWait").withThreshold(Duration.ofMillis(1));
            recording.setToDisk(true);
            recording.start();
            burn();

            RecordingFlusher flusher =
                    RecordingFlusher.start(recording, destination, Duration.ofHours(1));
            assertNotNull(flusher);
            try {
                assertTrue(flusher.flush(), "a running recording must dump");
                assertTrue(Files.isRegularFile(flusher.sidecar()));
                assertTrue(Files.size(flusher.sidecar()) > 0L);
                // The whole point: nothing has been written to the destination yet.
                assertFalse(Files.exists(destination),
                        "the sidecar must not be confused with the exit dump");
            } finally {
                flusher.close();
            }
        }
    }

    @Test
    void keepsTheLastGoodSidecarWhenALaterFlushFails(@TempDir Path directory) throws Exception {
        Path destination = directory.resolve("startup.jfr");
        Path sidecar = RecordingFlusherPaths.sidecarFor(destination);
        RecordingFlusher flusher;
        long afterFirstFlush;
        try (Recording recording = new Recording()) {
            recording.setToDisk(true);
            recording.start();
            burn();
            flusher = RecordingFlusher.start(recording, destination, Duration.ofHours(1));
            assertNotNull(flusher);
            assertTrue(flusher.flush());
            afterFirstFlush = Files.size(sidecar);
            recording.stop();
        }

        // A stopped recording cannot be dumped. The flush has to decline rather than truncate.
        assertFalse(flusher.flush(), "a stopped recording must not be dumped over the sidecar");
        assertEquals(afterFirstFlush, Files.size(sidecar), "the last good sidecar must survive");
        assertEquals(1, flusher.flushes());
        flusher.close();
    }

    @Test
    void leavesNoTemporaryBehind(@TempDir Path directory) throws Exception {
        Path destination = directory.resolve("startup.jfr");
        try (Recording recording = new Recording()) {
            recording.setToDisk(true);
            recording.start();
            burn();
            RecordingFlusher flusher =
                    RecordingFlusher.start(recording, destination, Duration.ofHours(1));
            assertNotNull(flusher);
            flusher.flush();
            flusher.close();
        }
        try (var entries = Files.list(directory)) {
            assertTrue(entries.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                    "a dump that fails halfway must not publish a partial file");
        }
    }

    @Test
    void aZeroIntervalTurnsFlushingOff(@TempDir Path directory) throws Exception {
        try (Recording recording = new Recording()) {
            recording.setToDisk(true);
            recording.start();
            assertNull(RecordingFlusher.start(recording, directory.resolve("s.jfr"), Duration.ZERO));
            assertNull(RecordingFlusher.start(
                    recording, directory.resolve("s.jfr"), Duration.ofSeconds(-1)));
            assertNull(RecordingFlusher.start(recording, directory.resolve("s.jfr"), null));
            assertNull(RecordingFlusher.start(null, directory.resolve("s.jfr"), Duration.ofSeconds(1)));
        }
    }

    @Test
    void namesTheSidecarBesideTheRecording() {
        assertEquals(Path.of("/runs/a/startup.partial.jfr"),
                RecordingFlusherPaths.sidecarFor(Path.of("/runs/a/startup.jfr")));
        // A destination without the extension still gets a distinct sidecar rather than itself.
        assertEquals(Path.of("/runs/a/startup.partial.jfr"),
                RecordingFlusherPaths.sidecarFor(Path.of("/runs/a/startup")));
    }

    @Test
    void theIntervalParsesAndDefaultsToSomethingAStartupDoesNotPayFor() {
        assertEquals(AgentOptions.DEFAULT_FLUSH_INTERVAL,
                AgentOptions.parse("dest=build/startup.jfr").flushInterval());
        assertEquals(Duration.ofSeconds(15),
                AgentOptions.parse("dest=build/startup.jfr,flush=15").flushInterval());
        assertEquals(Duration.ZERO, AgentOptions.parse("dest=build/startup.jfr,flush=0").flushInterval());
        assertThrows(IllegalArgumentException.class,
                () -> AgentOptions.parse("dest=build/startup.jfr,flush=-5"));
        assertThrows(IllegalArgumentException.class,
                () -> AgentOptions.parse("dest=build/startup.jfr,flush=soon"));
    }

    /** Enough activity that the recording has a chunk worth dumping. */
    private static void burn() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            builder.append(i);
            if (builder.length() > 4096) {
                builder.setLength(0);
            }
        }
    }
}
