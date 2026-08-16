package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.starsector.preflight.agent.AdapterMode;
import dev.starsector.preflight.agent.AdapterPlanScope;
import dev.starsector.preflight.agent.RecordingMode;
import dev.starsector.preflight.agent.TextureAdapterMode;
import java.nio.file.Path;
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
}
