package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.starsector.preflight.agent.AdapterMode;
import dev.starsector.preflight.agent.AdapterPlanScope;
import dev.starsector.preflight.agent.RecordingMode;
import dev.starsector.preflight.agent.TextureAdapterMode;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class AgentLaunchConfigTest {
    @Test
    void namedConfigurationPreservesTheExistingEncoderByteForByte() {
        Path agent = Path.of("agent.jar");
        Path recording = Path.of("run/startup.jfr");
        Path report = Path.of("run/adapter.json");
        Path targets = Path.of("targets.json");
        Path textureCache = Path.of("cache/textures");
        Path textureManifest = Path.of("cache/textures/manifest.sptm");
        Path textureIndex = Path.of("cache/resource-indexes/profile.spfi");
        Path variant = Path.of("cache/variant.json");
        Path weapon = Path.of("cache/weapon.json");
        Path projectile = Path.of("cache/projectile.json");
        Path hull = Path.of("cache/hull.json");
        Path rules = Path.of("cache/rules.json");
        Path ruleCommands = Path.of("cache/rule-commands.json");
        Path audio = Path.of("cache/audio");
        Path audioManifest = Path.of("cache/audio/profile.spam");
        Path mergedRead = Path.of("cache/merged-read.json");
        Path janino = Path.of("cache/janino");

        String positional = AgentInjection.append(
                "-Dexisting=true",
                agent,
                recording,
                AdapterMode.ENABLED,
                report,
                targets,
                textureCache,
                textureManifest,
                textureIndex,
                TextureAdapterMode.PREPARED_PIXELS,
                true,
                RecordingMode.SAMPLE,
                true,
                true,
                true,
                true,
                true,
                variant,
                weapon,
                projectile,
                hull,
                rules,
                true,
                ruleCommands,
                true,
                true,
                audio,
                "decoder-id",
                audioManifest,
                "manifest-id",
                mergedRead,
                true,
                true,
                janino,
                "janino-context",
                true,
                AdapterPlanScope.PORTABLE_STARTUP);

        String named = AgentLaunchConfig.builder(agent, recording)
                .adapterMode(AdapterMode.ENABLED)
                .adapterReport(report)
                .adapterTargets(targets)
                .textureCacheDirectory(textureCache)
                .textureManifest(textureManifest)
                .textureIndex(textureIndex)
                .textureAdapterMode(TextureAdapterMode.PREPARED_PIXELS)
                .exhaustiveFileReads(true)
                .recordingMode(RecordingMode.SAMPLE)
                .npotDirect(true)
                .unpadded(true)
                .singleChunkRecording(true)
                .campaignEntityIndex(true)
                .startupPhaseProbe(true)
                .variantJsonCache(variant)
                .weaponJsonCache(weapon)
                .projectileJsonCache(projectile)
                .hullJsonCache(hull)
                .rulesCsvCache(rules)
                .ruleTokenCache(true)
                .ruleCommandClassCache(ruleCommands)
                .resourceProbeCache(true)
                .loadJsonMemo(true)
                .preparedAudioCache(audio)
                .audioDecoderIdentity("decoder-id")
                .preparedAudioManifest(audioManifest)
                .preparedAudioManifestIdentity("manifest-id")
                .mergedReadCache(mergedRead)
                .quietLogs(true)
                .graphicsLibCompactReplay(true)
                .janinoBytecodeCache(janino)
                .janinoBytecodeContext("janino-context")
                .graphicsLibInsigniaManagerCache(true)
                .adapterPlanScope(AdapterPlanScope.PORTABLE_STARTUP)
                .build()
                .appendTo("-Dexisting=true");

        assertEquals(positional, named);
    }

    @Test
    void defaultsMatchTheExistingMinimalAgentContract() {
        Path agent = Path.of("agent.jar");
        Path recording = Path.of("run/startup.jfr");

        assertEquals(
                AgentInjection.append("-Dexisting=true", agent, recording),
                AgentLaunchConfig.builder(agent, recording).build().appendTo("-Dexisting=true"));
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
}
