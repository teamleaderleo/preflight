package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.starsector.preflight.agent.AdapterMode;
import dev.starsector.preflight.agent.RecordingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class AgentLaunchConfigTest {
    @Test
    void defaultsEncodeTheReviewedMinimalAgentContract() {
        Path agent = Path.of("agent.jar");
        Path recording = Path.of("run/startup.jfr");

        String expected = "-Dexisting=true -javaagent:"
                + agent.toAbsolutePath().normalize()
                + "=dest64=" + encodedPath(recording)
                + ",adapter=off"
                + ",planScope=full"
                + ",textureMode=compatibility";

        assertEquals(
                expected,
                AgentLaunchConfig.builder(agent, recording)
                        .build()
                        .appendTo("-Dexisting=true"));
    }

    @Test
    void namedConfigurationRejectsInvalidDependenciesBeforeEncoding() {
        Path cache = Path.of("cache/value");

        assertInvalid(
                "Janino bytecode cache and compilation context must be supplied together",
                builder -> builder.janinoBytecodeCache(cache));
        assertInvalid(
                "Janino bytecode cache requires the enabled adapter",
                builder -> builder
                        .janinoBytecodeCache(cache)
                        .janinoBytecodeContext("context"));
        assertInvalid(
                "Merged read cache requires the enabled adapter",
                builder -> builder.mergedReadCache(cache));
        assertInvalid(
                "Prepared audio cache and decoder identity must be supplied together",
                builder -> builder.preparedAudioCache(cache));
        assertInvalid(
                "Prepared audio cache and decoder identity must be supplied together",
                builder -> builder.audioDecoderIdentity("decoder"));
        assertInvalid(
                "Prepared audio requires the enabled adapter",
                builder -> builder
                        .preparedAudioCache(cache)
                        .audioDecoderIdentity("decoder"));
        assertInvalid(
                "Prepared audio manifest and identity must be supplied together",
                builder -> builder
                        .adapterMode(AdapterMode.ENABLED)
                        .preparedAudioCache(cache)
                        .audioDecoderIdentity("decoder")
                        .preparedAudioManifest(cache));
        assertInvalid(
                "Prepared audio manifest requires the audio cache",
                builder -> builder
                        .adapterMode(AdapterMode.ENABLED)
                        .preparedAudioManifest(cache)
                        .preparedAudioManifestIdentity("manifest"));
        assertInvalid(
                "loadJSON memo requires the enabled adapter",
                builder -> builder.loadJsonMemo(true));
        assertInvalid(
                "Resource probe cache requires the enabled adapter",
                builder -> builder.resourceProbeCache(true));
        assertInvalid(
                "Rule command class cache requires the enabled adapter",
                builder -> builder.ruleCommandClassCache(cache));
        assertInvalid(
                "Rule token cache requires the enabled adapter",
                builder -> builder.ruleTokenCache(true));
        assertInvalid(
                "Single-chunk recording requires recording to be enabled",
                builder -> builder
                        .recordingMode(RecordingMode.OFF)
                        .singleChunkRecording(true));
        assertInvalid(
                "Campaign entity index requires the enabled adapter",
                builder -> builder.campaignEntityIndex(true));
        assertInvalid(
                "Startup phase probe requires the enabled adapter",
                builder -> builder.startupPhaseProbe(true));
        assertInvalid(
                "Variant JSON cache requires the enabled adapter",
                builder -> builder.variantJsonCache(cache));
        assertInvalid(
                "Weapon JSON cache requires the enabled adapter",
                builder -> builder.weaponJsonCache(cache));
        assertInvalid(
                "Projectile JSON cache requires the enabled adapter",
                builder -> builder.projectileJsonCache(cache));
        assertInvalid(
                "Hull JSON cache requires the enabled adapter",
                builder -> builder.hullJsonCache(cache));
        assertInvalid(
                "Rules CSV cache requires the enabled adapter",
                builder -> builder.rulesCsvCache(cache));
        assertInvalid(
                "GraphicsLib compact replay requires the enabled adapter",
                builder -> builder.graphicsLibCompactReplay(true));
        assertInvalid(
                "GraphicsLib insignia cache requires the enabled adapter",
                builder -> builder.graphicsLibInsigniaManagerCache(true));
    }

    private static void assertInvalid(
            String expectedMessage,
            Consumer<AgentLaunchConfig.Builder> configure) {
        AgentLaunchConfig.Builder builder =
                AgentLaunchConfig.builder(Path.of("agent.jar"), Path.of("run/startup.jfr"));
        configure.accept(builder);

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, builder::build);
        assertEquals(expectedMessage, error.getMessage());
    }

    private static String encodedPath(Path value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
    }
}
