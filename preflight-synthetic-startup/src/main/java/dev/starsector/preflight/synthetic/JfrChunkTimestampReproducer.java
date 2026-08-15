package dev.starsector.preflight.synthetic;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Recording;

/**
 * Standalone reproducer for cross-chunk JFR timestamp readback anomalies.
 *
 * <p>This deliberately uses only the JDK Flight Recorder API: no Preflight agent and no game.
 * Padding events make the recording rotate through multiple chunks while marker events retain an
 * independent elapsed-wall-time value that can be compared with their decoded JFR timestamps.
 */
public final class JfrChunkTimestampReproducer {
    static final String MARKER_EVENT_NAME = "preflight.repro.ChunkTimestampMarker";
    private static final String PADDING_EVENT_NAME = "preflight.repro.ChunkTimestampPadding";
    private static final int DEFAULT_SECONDS = 12;
    private static final int DEFAULT_PADDING_EVENTS_PER_SECOND = 1_500;
    private static final String PADDING = "x".repeat(2_048);

    private JfrChunkTimestampReproducer() {}

    public static void main(String[] args) throws Exception {
        Path destination = args.length >= 1
                ? Path.of(args[0]).toAbsolutePath().normalize()
                : Path.of("jfr-chunk-timestamp-reproducer.jfr").toAbsolutePath().normalize();
        int seconds = positiveInt(args, 1, DEFAULT_SECONDS, "seconds");
        int paddingEventsPerSecond = positiveInt(
                args, 2, DEFAULT_PADDING_EVENTS_PER_SECOND, "padding-events-per-second");

        Recording recording = new Recording();
        recording.setName("preflight-jfr-chunk-timestamp-reproducer");
        recording.setToDisk(true);
        recording.setDumpOnExit(true);
        recording.setDestination(destination);
        recording.enable(MarkerEvent.class);
        recording.enable(PaddingEvent.class);
        recording.start();

        Instant startedAt = Instant.now();
        long startedNanos = System.nanoTime();
        long sequence = 0;
        for (int second = 0; second < seconds; second++) {
            MarkerEvent marker = new MarkerEvent();
            marker.sequence = second;
            marker.expectedElapsedMillis = Duration.between(startedAt, Instant.now()).toMillis();
            marker.commit();

            for (int i = 0; i < paddingEventsPerSecond; i++) {
                PaddingEvent padding = new PaddingEvent();
                padding.sequence = sequence;
                // Keep each payload distinct so it cannot collapse into one constant-pool entry.
                padding.payload = PADDING + sequence;
                padding.commit();
                sequence++;
            }

            sleepUntil(startedNanos + Duration.ofSeconds(second + 1L).toNanos());
        }

        MarkerEvent marker = new MarkerEvent();
        marker.sequence = seconds;
        marker.expectedElapsedMillis = Duration.between(startedAt, Instant.now()).toMillis();
        marker.commit();

        System.out.printf(
                "Recording remains active for dump-on-exit: %s (%d marker seconds, %d padding events)%n",
                destination,
                seconds,
                sequence);
        // Intentionally do not stop or close the Recording. Returning from main exercises the JDK's
        // dump-on-exit path directly, which is the minimal lifecycle involved in the original report.
    }

    private static int positiveInt(String[] args, int index, int fallback, String label) {
        if (args.length <= index) {
            return fallback;
        }
        int value = Integer.parseInt(Objects.requireNonNull(args[index], label));
        if (value <= 0) {
            throw new IllegalArgumentException(label + " must be positive");
        }
        return value;
    }

    private static void sleepUntil(long deadlineNanos) throws InterruptedException {
        while (true) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            long millis = Math.max(1L, Math.min(Duration.ofNanos(remaining).toMillis(), 100L));
            Thread.sleep(millis);
        }
    }

    @Name(MARKER_EVENT_NAME)
    @Label("Chunk timestamp marker")
    static final class MarkerEvent extends Event {
        @Label("Sequence")
        long sequence;

        @Label("Expected elapsed wall time (ms)")
        long expectedElapsedMillis;
    }

    @Name(PADDING_EVENT_NAME)
    @Label("Chunk rotation padding")
    static final class PaddingEvent extends Event {
        @Label("Sequence")
        long sequence;

        @Label("Unique payload")
        String payload;
    }
}
