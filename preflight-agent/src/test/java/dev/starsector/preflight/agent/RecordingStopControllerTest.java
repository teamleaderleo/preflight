package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
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
    void completionPublicationReplacesOnlyWithTheFinishedResponse(@TempDir Path directory)
            throws Exception {
        Path complete = directory.resolve("startup.stop-complete");
        Files.writeString(complete, "stale\n");

        RecordingStopController.publishCompletion(complete, "ok\n");

        assertEquals("ok\n", Files.readString(complete));
        try (var entries = Files.list(directory)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().contains(".stop-complete.tmp-")));
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
