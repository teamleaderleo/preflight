package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdapterAgentIT {
    @TempDir
    Path temporaryDirectory;

    @Test
    void packagedAgentProbesCandidateAndPreservesOriginalClass() throws Exception {
        Path recording = temporaryDirectory.resolve("startup.jfr");
        Path adapterReport = temporaryDirectory.resolve("adapter.json");
        Path codeReport = temporaryDirectory.resolve("adapter-code-loader-signatures.json");
        Path janinoReport = temporaryDirectory.resolve("adapter-janino-loader-contract.json");
        Path audioReport = temporaryDirectory.resolve("adapter-audio-decoder-signatures.json");
        Path soundReport = temporaryDirectory.resolve("adapter-sound-loader-contract.json");
        Path textureReport = temporaryDirectory.resolve("adapter-texture-loader-contract.json");
        String agentArguments = "dest64=" + encoded(recording)
                + ",adapter=probe,adapterReport64=" + encoded(adapterReport);

        assertAsmIsRelocated();
        ProcessResult result = launch(agentArguments);

        assertTrue(result.completed(), result.output());
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("synthetic-starsector-launcher"), result.output());
        assertTrue(Files.isRegularFile(recording), result.output());
        assertTrue(Files.isRegularFile(adapterReport), result.output());
        assertTrue(Files.isRegularFile(codeReport), result.output());
        assertTrue(Files.isRegularFile(janinoReport), result.output());
        assertTrue(Files.isRegularFile(audioReport), result.output());
        assertTrue(Files.isRegularFile(soundReport), result.output());
        assertTrue(Files.isRegularFile(textureReport), result.output());
        String json = Files.readString(adapterReport);
        assertTrue(json.contains("\"mode\":\"PROBE\""), json);
        assertTrue(json.contains("com/fs/starfarer/SyntheticLauncher"), json);
        assertTrue(json.contains("\"transformationsApplied\":0"), json);
        assertTrue(json.contains("\"containedFailures\":0"), json);

        String codeJson = Files.readString(codeReport);
        assertTrue(codeJson.contains("starsector-preflight-code-loader-signatures-v1"), codeJson);
        assertTrue(codeJson.contains("org/codehaus/janino/JavaSourceClassLoader"), codeJson);
        assertTrue(codeJson.contains("\"retainedIdentities\":0"), codeJson);
        assertTrue(codeJson.contains("\"liveTransformationEligible\":false"), codeJson);
        assertTrue(codeJson.contains("\"requiresHumanReview\":true"), codeJson);

        String janinoJson = Files.readString(janinoReport);
        assertTrue(janinoJson.contains("starsector-preflight-bytecode-shape-v1"), janinoJson);
        assertTrue(janinoJson.contains("installed-janino-complete-map-shape-v1"), janinoJson);
        assertTrue(janinoJson.contains("\"captured\":false"), janinoJson);
        assertTrue(janinoJson.contains("\"classBytesIncluded\":false"), janinoJson);
        assertTrue(janinoJson.contains("\"transformationPlanGenerated\":false"), janinoJson);
        assertTrue(janinoJson.contains("\"cacheReadsEnabled\":false"), janinoJson);
        assertTrue(janinoJson.contains("\"cacheWritesEnabled\":false"), janinoJson);
        assertTrue(janinoJson.contains("\"requiresHumanReview\":true"), janinoJson);

        String audioJson = Files.readString(audioReport);
        assertTrue(audioJson.contains("starsector-preflight-audio-decoder-signatures-v1"), audioJson);
        assertTrue(audioJson.contains("org/newdawn/slick/openal/OggDecoder"), audioJson);
        assertTrue(audioJson.contains("com/jcraft/jorbis/Info"), audioJson);
        assertTrue(audioJson.contains("\"originalClassBytesRetained\":true"), audioJson);
        assertTrue(audioJson.contains("\"decoderEquivalenceEstablished\":false"), audioJson);
        assertTrue(audioJson.contains("\"preparedAudioWritesEligible\":false"), audioJson);

        String soundJson = Files.readString(soundReport);
        assertTrue(soundJson.contains("starsector-preflight-sound-loader-contract-v1"), soundJson);
        assertTrue(soundJson.contains("\"retainedIdentities\":6"), soundJson);
        assertTrue(soundJson.contains("sound/J"), soundJson);
        assertTrue(soundJson.contains("sound/F"), soundJson);
        assertTrue(soundJson.contains("sound/ooOO"), soundJson);
        assertTrue(soundJson.contains("sound/D"), soundJson);
        assertTrue(soundJson.contains("sound/Sound"), soundJson);
        assertTrue(soundJson.contains("com/fs/starfarer/loading/A"), soundJson);
        assertTrue(soundJson.contains("\"primarySeam\":true"), soundJson);
        assertTrue(soundJson.contains("\"consumerCandidate\":true"), soundJson);
        assertTrue(soundJson.contains("\"kind\":\"jogg-jorbis-call\""), soundJson);
        assertTrue(soundJson.contains("\"kind\":\"call-returning-sound-f\""), soundJson);
        assertTrue(soundJson.contains("\"kind\":\"constructor-consuming-sound-f\""), soundJson);
        assertTrue(soundJson.contains("\"originalClassBytesRetained\":true"), soundJson);
        assertTrue(soundJson.contains("\"transformationPlanGenerated\":false"), soundJson);
        assertTrue(soundJson.contains("\"transformRegistered\":false"), soundJson);
        assertTrue(soundJson.contains("\"cacheReadsEnabled\":false"), soundJson);
        assertTrue(soundJson.contains("\"cacheWritesEnabled\":false"), soundJson);
        assertTrue(soundJson.contains("\"requiresHumanReview\":true"), soundJson);
        assertFalse(soundJson.contains("packaged-repository-owned-sound-contract-literal"), soundJson);

        String textureJson = Files.readString(textureReport);
        assertTrue(textureJson.contains("starsector-preflight-bytecode-shape-v1"), textureJson);
        assertTrue(textureJson.contains("vanilla-texture-loader-upload-shape-v1"), textureJson);
        assertTrue(textureJson.contains("\"captured\":false"), textureJson);
        assertTrue(textureJson.contains("\"classBytesIncluded\":false"), textureJson);
        assertTrue(textureJson.contains("\"stringConstantsIncluded\":false"), textureJson);
        assertTrue(textureJson.contains("\"automaticRewriteGenerated\":false"), textureJson);
        assertTrue(textureJson.contains("\"requiresHumanReview\":true"), textureJson);
    }

    @Test
    void profilerOnlyLaunchDoesNotCreateAdapterReport() throws Exception {
        Path recording = temporaryDirectory.resolve("profile-only.jfr");
        Path adapterReport = temporaryDirectory.resolve("adapter.json");
        Path codeReport = temporaryDirectory.resolve("adapter-code-loader-signatures.json");
        Path janinoReport = temporaryDirectory.resolve("adapter-janino-loader-contract.json");
        Path audioReport = temporaryDirectory.resolve("adapter-audio-decoder-signatures.json");
        Path soundReport = temporaryDirectory.resolve("adapter-sound-loader-contract.json");
        Path textureReport = temporaryDirectory.resolve("adapter-texture-loader-contract.json");
        String agentArguments = "dest64=" + encoded(recording)
                + ",adapterReport64=" + encoded(adapterReport);

        ProcessResult result = launch(agentArguments);

        assertTrue(result.completed(), result.output());
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(Files.isRegularFile(recording), result.output());
        assertFalse(Files.exists(adapterReport), result.output());
        assertFalse(Files.exists(codeReport), result.output());
        assertFalse(Files.exists(janinoReport), result.output());
        assertFalse(Files.exists(audioReport), result.output());
        assertFalse(Files.exists(soundReport), result.output());
        assertFalse(Files.exists(textureReport), result.output());
    }

    @Test
    void flushZeroLiveStopRetainsTheStoppingEvent() throws Exception {
        Path recording = temporaryDirectory.resolve("single-chunk.jfr");
        Path request = temporaryDirectory.resolve("single-chunk.stop-request");
        Path complete = temporaryDirectory.resolve("single-chunk.stop-complete");
        String agentArguments = "dest64=" + encoded(recording) + ",record=sample,flush=0";

        ProcessResult result = launch(agentArguments, List.of(
                "-Dpreflight.test.recordingStopRequest=" + request,
                "-Dpreflight.test.recordingStopComplete=" + complete));

        assertTrue(result.completed(), result.output());
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("recording-stop-complete:ok"), result.output());
        assertTrue(result.output().contains("Wrote startup recording to " + recording), result.output());
        assertEquals("ok\n", Files.readString(complete));
        assertTrue(Files.size(recording) > 0L, result.output());
        boolean stopping = false;
        try (RecordingFile events = new RecordingFile(recording)) {
            while (events.hasMoreEvents()) {
                if ("preflight.AgentStopping".equals(events.readEvent().getEventType().getName())) {
                    stopping = true;
                }
            }
        }
        assertTrue(stopping, "the manually closed chunk must include the shutdown boundary");
    }

    private static void assertAsmIsRelocated() throws Exception {
        Path agent = Path.of("target", "preflight.jar").toAbsolutePath().normalize();
        try (JarFile jar = new JarFile(agent.toFile())) {
            assertTrue(jar.getEntry("dev/starsector/preflight/internal/asm/ClassReader.class") != null);
            assertTrue(jar.getEntry("org/objectweb/asm/ClassReader.class") == null);
        }
    }

    private ProcessResult launch(String agentArguments) throws Exception {
        return launch(agentArguments, List.of());
    }

    private ProcessResult launch(String agentArguments, List<String> jvmArguments) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", executable("java"));
        Path agent = Path.of("target", "preflight.jar").toAbsolutePath().normalize();
        Path testClasses = Path.of("target", "test-classes").toAbsolutePath().normalize();
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-javaagent:" + agent + "=" + agentArguments);
        command.addAll(jvmArguments);
        command.add("-cp");
        command.add(testClasses.toString());
        command.add("com.fs.starfarer.SyntheticLauncher");
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        boolean completed = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(completed, completed ? process.exitValue() : -1, output);
    }

    private static String encoded(Path path) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(path.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String executable(String name) {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? name + ".exe" : name;
    }

    private record ProcessResult(boolean completed, int exitCode, String output) {
    }
}
