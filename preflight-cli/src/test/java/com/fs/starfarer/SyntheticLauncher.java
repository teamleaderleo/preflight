package com.fs.starfarer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Tiny child-JVM target used to verify packaged javaagent probe behavior. */
public final class SyntheticLauncher {
    private SyntheticLauncher() {
    }

    public static void main(String[] args) throws Exception {
        new org.newdawn.slick.openal.OggDecoder().reset();
        int sampleRate = new com.fs.starfarer.loading.A().load();
        System.out.println("synthetic-starsector-launcher:" + sampleRate);
        requestRecordingStopWhenConfigured();
    }

    private static void requestRecordingStopWhenConfigured() throws Exception {
        String requestValue = System.getProperty("preflight.test.recordingStopRequest");
        String completeValue = System.getProperty("preflight.test.recordingStopComplete");
        if (requestValue == null || completeValue == null) {
            return;
        }
        Path request = Path.of(requestValue);
        Path complete = Path.of(completeValue);
        Files.writeString(request, "stop\n");
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(complete)) {
                String response = Files.readString(complete);
                if (!"ok\n".equals(response)) {
                    throw new IllegalStateException("Recording stop failed: " + response.trim());
                }
                System.out.println("recording-stop-complete:ok");
                return;
            }
            Thread.sleep(10L);
        }
        throw new IllegalStateException("Recording stop was not acknowledged");
    }
}
