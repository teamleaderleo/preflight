package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SelfAgentIT {
    @TempDir
    Path temporaryDirectory;

    @Test
    void packagedJarCanRecordAsAnAgent() throws Exception {
        Path jar = Path.of("target/preflight.jar").toAbsolutePath().normalize();
        Path recording = temporaryDirectory.resolve("startup trace,one.jfr");
        String encoded = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(recording.toString().getBytes(StandardCharsets.UTF_8));
        Path java = Path.of(System.getProperty("java.home"))
                .resolve("bin")
                .resolve(Platform.current() == Platform.WINDOWS ? "java.exe" : "java");
        Path testClasses = Path.of("target", "test-classes").toAbsolutePath().normalize();

        // Deliberately not the directory this test runs in. The agent records where the recorded
        // process was, and a test that launched the child here could not tell that apart from a
        // field that reports the analyser's own directory -- which is the mistake the field exists
        // to stop being possible.
        Path launchedFrom = Files.createDirectories(temporaryDirectory.resolve("game home"));

        Process process = new ProcessBuilder(
                java.toString(),
                "-Dpreflight.desktopSmoke=true",
                "-Dpreflight.frameTimes=true",
                "-javaagent:" + jar + "=dest64=" + encoded,
                "-cp",
                testClasses.toString(),
                "com.fs.starfarer.SyntheticLauncher")
                .directory(launchedFrom.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS), output);
        assertEquals(0, process.exitValue(), output);
        assertTrue(Files.isRegularFile(recording), output);
        assertTrue(Files.size(recording) > 0, output);
        Path runtimeProcess = recording.resolveSibling("runtime-process.json");
        assertTrue(Files.isRegularFile(runtimeProcess), output);
        String runtimeIdentity = Files.readString(runtimeProcess);
        assertTrue(runtimeIdentity.contains("\"format\":\"starsector-preflight-runtime-process-v1\""),
                runtimeIdentity);
        assertTrue(runtimeIdentity.contains("\"pid\":" + process.pid()), runtimeIdentity);
        assertTrue(runtimeIdentity.contains("\"state\":\"stopped\""), runtimeIdentity);
        assertTrue(runtimeIdentity.contains("\"startedAt\":"), runtimeIdentity);
        Path runtimeState = recording.resolveSibling("runtime-state.json");
        assertTrue(Files.isRegularFile(runtimeState), output);
        String semanticState = Files.readString(runtimeState);
        assertTrue(semanticState.contains("\"format\":\"starsector-preflight-runtime-state-v1\""),
                semanticState);
        assertTrue(semanticState.contains("\"pid\":" + process.pid()), semanticState);
        assertTrue(semanticState.contains("\"state\":\"stopped\""), semanticState);
        Path frameReport = recording.resolveSibling("runtime-frame-report.json");
        assertTrue(Files.isRegularFile(frameReport), output);
        String frameEvidence = Files.readString(frameReport);
        assertTrue(frameEvidence.contains(
                "\"format\":\"starsector-preflight-runtime-frame-report-v1\""), frameEvidence);
        assertTrue(frameEvidence.contains("\"pid\":" + process.pid()), frameEvidence);
        Path liveHealth = recording.resolveSibling("runtime-adapter-health.json");
        assertTrue(Files.isRegularFile(liveHealth), output);
        String healthEvidence = Files.readString(liveHealth);
        assertTrue(healthEvidence.contains(
                "\"format\":\"starsector-preflight-runtime-adapter-health-v1\""), healthEvidence);
        assertTrue(healthEvidence.contains("\"pid\":" + process.pid()), healthEvidence);
        try (RecordingFile file = new RecordingFile(recording)) {
            assertTrue(file.hasMoreEvents(), output);
            RecordedEvent started = agentStarted(file);
            assertTrue(started != null, output);
            assertTrue(started.hasField("workingDirectory"), "no workingDirectory field: " + output);
            String recorded = started.getString("workingDirectory");
            assertTrue(recorded != null && !recorded.isBlank(),
                    "the agent declared workingDirectory but never set it: " + output);
            assertEquals(
                    launchedFrom.toRealPath(),
                    Path.of(recorded).toRealPath(),
                    "the agent should record where the recorded process ran: " + output);
        }
    }

    /** The event the agent commits as it starts recording, or {@code null} if the dump lost it. */
    private static RecordedEvent agentStarted(RecordingFile file) throws Exception {
        while (file.hasMoreEvents()) {
            RecordedEvent event = file.readEvent();
            if ("preflight.AgentStarted".equals(event.getEventType().getName())) {
                return event;
            }
        }
        return null;
    }
}
