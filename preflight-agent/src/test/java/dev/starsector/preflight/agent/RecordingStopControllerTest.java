package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RecordingStopControllerTest {
    @Test
    void requestStopsAndPublishesTheLiveRecordingBeforeJvmExit(@TempDir Path directory)
            throws Exception {
        Path destination = directory.resolve("startup.jfr");
        Recording recording = new Recording();
        recording.setToDisk(true);
        recording.setDestination(destination);
        recording.enable("preflight.AgentStopping");
        recording.start();
        Thread controller = RecordingStopController.start(recording, destination);

        Files.createFile(RecordingStopController.requestFor(destination));
        controller.join(TimeUnit.SECONDS.toMillis(5));

        assertTrue(Files.size(destination) > 0L);
        assertEquals("ok\n", Files.readString(RecordingStopController.completeFor(destination)));
        boolean stopping = false;
        try (RecordingFile events = new RecordingFile(destination)) {
            while (events.hasMoreEvents()) {
                if ("preflight.AgentStopping".equals(events.readEvent().getEventType().getName())) {
                    stopping = true;
                }
            }
        }
        assertTrue(stopping);
    }

    @Test
    void everyObservedAcknowledgementIsComplete(@TempDir Path directory) throws Exception {
        // The harness that asked for the stop polls for the acknowledgement file and treats any
        // content other than "ok\n" as a hard failure, so publishing it in place is a torn read
        // waiting to happen: between the create/truncate and the write the file exists and is
        // empty. This reader polls the same way, only tighter.
        Path complete = directory.resolve("startup.stop-complete");
        List<String> observations = new CopyOnWriteArrayList<>();
        AtomicBoolean publishing = new AtomicBoolean(true);
        Thread reader = new Thread(() -> {
            while (publishing.get()) {
                if (Files.isRegularFile(complete)) {
                    try {
                        observations.add(Files.readString(complete));
                    } catch (IOException retryable) {
                        // A reader that loses the file mid-read simply looks again.
                    }
                }
            }
        });
        reader.setDaemon(true);
        reader.start();

        for (int attempt = 0; attempt < 2_000; attempt++) {
            RecordingStopController.publish(complete, "ok\n");
            Files.deleteIfExists(complete);
        }
        publishing.set(false);
        reader.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(observations.isEmpty(), "the reader never saw the acknowledgement at all");
        List<String> partial = observations.stream().filter(value -> !"ok\n".equals(value)).toList();
        assertEquals(List.of(), partial, "a reader saw the acknowledgement before it was written");
    }

    @Test
    void publishingLeavesNoTemporaryBesideTheAcknowledgement(@TempDir Path directory)
            throws Exception {
        Path complete = directory.resolve("startup.stop-complete");

        RecordingStopController.publish(complete, "ok\n");

        assertEquals("ok\n", Files.readString(complete));
        try (Stream<Path> entries = Files.list(directory)) {
            assertEquals(List.of(complete), entries.toList());
        }
    }

    @Test
    void controlFilesAreNamedBesideTheRecording(@TempDir Path directory) {
        Path destination = directory.resolve("startup.jfr");
        assertEquals(directory.resolve("startup.stop-request"),
                RecordingStopController.requestFor(destination));
        assertEquals(directory.resolve("startup.stop-complete"),
                RecordingStopController.completeFor(destination));
    }
}
