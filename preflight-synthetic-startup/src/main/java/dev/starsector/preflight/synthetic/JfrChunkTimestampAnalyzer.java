package dev.starsector.preflight.synthetic;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

/** Compares decoded JFR marker timestamps with elapsed wall time recorded inside those events. */
public final class JfrChunkTimestampAnalyzer {
    private static final long SUSPICIOUS_ERROR_MILLIS = 1_000L;

    private JfrChunkTimestampAnalyzer() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: JfrChunkTimestampAnalyzer <recording.jfr>");
        }

        Path recording = Path.of(args[0]).toAbsolutePath().normalize();
        List<Marker> markers = readMarkers(recording);
        if (markers.size() < 2) {
            throw new IllegalStateException("expected at least two marker events in " + recording);
        }

        Marker first = markers.get(0);
        long maxAbsoluteErrorMillis = 0;
        for (Marker marker : markers) {
            long decodedElapsedMillis = Duration.between(first.timestamp(), marker.timestamp()).toMillis();
            long expectedElapsedMillis = marker.expectedElapsedMillis() - first.expectedElapsedMillis();
            long errorMillis = decodedElapsedMillis - expectedElapsedMillis;
            maxAbsoluteErrorMillis = Math.max(maxAbsoluteErrorMillis, Math.abs(errorMillis));
            System.out.printf(
                    "marker=%d expectedElapsedMs=%d decodedElapsedMs=%d errorMs=%+d timestamp=%s%n",
                    marker.sequence(),
                    expectedElapsedMillis,
                    decodedElapsedMillis,
                    errorMillis,
                    marker.timestamp());
        }

        String status = maxAbsoluteErrorMillis >= SUSPICIOUS_ERROR_MILLIS
                ? "SUSPICIOUS_TIMESTAMP_FOLDING"
                : "TIMESTAMPS_TRACK_WALL_TIME";
        System.out.printf(
                "status=%s markers=%d maxAbsoluteErrorMs=%d recording=%s%n",
                status,
                markers.size(),
                maxAbsoluteErrorMillis,
                recording);
    }

    static List<Marker> readMarkers(Path recording) throws IOException {
        List<Marker> markers = new ArrayList<>();
        try (RecordingFile file = new RecordingFile(recording)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                if (!event.getEventType().getName().equals(JfrChunkTimestampReproducer.MARKER_EVENT_NAME)) {
                    continue;
                }
                markers.add(new Marker(
                        event.getLong("sequence"),
                        event.getLong("expectedElapsedMillis"),
                        event.getStartTime()));
            }
        }
        return List.copyOf(markers);
    }

    record Marker(long sequence, long expectedElapsedMillis, Instant timestamp) {}
}
