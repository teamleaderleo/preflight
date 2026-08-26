package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.starsector.preflight.agent.AdapterMode;
import dev.starsector.preflight.agent.AdapterPlanScope;
import dev.starsector.preflight.agent.RecordingMode;
import dev.starsector.preflight.agent.TextureAdapterMode;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CommandLineAdapterTest {
    @Test
    void productPresetsAreTypedAndFastRemainsTheRecommendedAlias() {
        CommandLine fast = CommandLine.parse(new String[] {"run", "--fast"}, 1);
        CommandLine recommended = CommandLine.parse(
                new String[] {"run", "--optimization-preset", "recommended"}, 1);
        CommandLine conservative = CommandLine.parse(
                new String[] {"run", "--optimization-preset", "conservative"}, 1);
        CommandLine off = CommandLine.parse(
                new String[] {"run", "--optimization-preset", "off"}, 1);

        assertEquals(OptimizationPreset.RECOMMENDED, fast.optimizationPreset());
        assertEquals(OptimizationPreset.RECOMMENDED, recommended.optimizationPreset());
        assertEquals(fast, recommended);
        assertEquals(AdapterPlanScope.FULL, recommended.adapterPlanScope());

        assertEquals(OptimizationPreset.CONSERVATIVE, conservative.optimizationPreset());
        assertEquals(AdapterPlanScope.PORTABLE_STARTUP, conservative.adapterPlanScope());
        assertEquals(AdapterMode.ENABLED, conservative.adapterMode());
        assertEquals(TextureAdapterMode.PREPARED_PIXELS, conservative.textureAdapterMode());
        assertEquals(true, conservative.npotDirect());
        assertEquals(false, conservative.unpadded());
        assertEquals(false, conservative.campaignEntityIndex());
        assertEquals(false, conservative.graphicsLibCompactReplay());
        assertEquals(false, conservative.graphicsLibInsigniaManagerCache());

        assertEquals(OptimizationPreset.OFF, off.optimizationPreset());
        assertEquals(AdapterMode.OFF, off.adapterMode());
        assertEquals(RecordingMode.OFF, off.recordingMode());
        assertEquals(false, off.scan());
        assertEquals(false, off.summarize());

        assertThrows(
                IllegalArgumentException.class,
                () -> CommandLine.parse(
                        new String[] {"run", "--optimization-preset", "experimental"}, 1));
    }

    @Test
    void productDomainsDisableTheirCompletePreparedCacheContextsRegardlessOfOrder() {
        CommandLine domainsFirst = CommandLine.parse(new String[] {
                "run",
                "--disable-optimization-domain", "prepared_audio",
                "--disable-optimization-domain", "prepared-textures",
                "--optimization-preset", "recommended"
        }, 1);
        CommandLine presetFirst = CommandLine.parse(new String[] {
                "run",
                "--optimization-preset", "recommended",
                "--disable-optimization-domain", "prepared-textures",
                "--disable-optimization-domain", "prepared-audio"
        }, 1);

        assertEquals(domainsFirst, presetFirst);
        assertEquals(Set.of(
                OptimizationDomain.PREPARED_TEXTURES,
                OptimizationDomain.PREPARED_AUDIO), presetFirst.disabledOptimizationDomains());
        assertEquals(false, presetFirst.textureAuto());
        assertEquals(TextureAdapterMode.COMPATIBILITY, presetFirst.textureAdapterMode());
        assertEquals(false, presetFirst.npotDirect());
        assertEquals(false, presetFirst.unpadded());
        assertEquals(false, presetFirst.preparedAudio());
        assertEquals(true, presetFirst.loadJsonMemo());
        assertEquals(true, presetFirst.campaignEntityIndex());
    }

    @Test
    void productDomainsRejectUnknownNamesCustomLaunchesAndConflictingArtifacts() {
        assertThrows(IllegalArgumentException.class, () -> CommandLine.parse(new String[] {
                "run", "--optimization-preset", "recommended",
                "--disable-optimization-domain", "everything"
        }, 1));
        assertThrows(IllegalArgumentException.class, () -> CommandLine.parse(new String[] {
                "run", "--disable-optimization-domain", "prepared-audio"
        }, 1));
        assertThrows(IllegalArgumentException.class, () -> CommandLine.parse(new String[] {
                "run", "--optimization-preset", "recommended",
                "--disable-optimization-domain", "prepared-audio",
                "--disable-optimization-domain", "prepared-audio"
        }, 1));
        assertThrows(IllegalArgumentException.class, () -> CommandLine.parse(new String[] {
                "run", "--optimization-preset", "recommended",
                "--texture-cache-dir", "cache",
                "--texture-manifest", "manifest.json",
                "--texture-index", "index.json",
                "--disable-optimization-domain", "prepared-textures"
        }, 1));
    }

    @Test
    void conservativeScopeCannotBeMixedWithGameplayOrModSpecificSwitches() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandLine.parse(new String[] {
                        "run", "--optimization-preset", "conservative", "--campaign-entity-index"
                }, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandLine.parse(new String[] {
                        "run", "--optimization-preset", "conservative", "--graphicslib-compact-replay"
                }, 1));
    }

    @Test
    void recordingFlagsSelectTheThreeModesAndDefaultToFull() {
        assertEquals(RecordingMode.FULL, CommandLine.parse(new String[] {"run"}, 1).recordingMode());
        assertEquals(RecordingMode.OFF,
                CommandLine.parse(new String[] {"run", "--no-record"}, 1).recordingMode());
        assertEquals(RecordingMode.SAMPLE,
                CommandLine.parse(new String[] {"run", "--profile"}, 1).recordingMode());
        // The two flags are opposites in effect but not mutually exclusive on the command line;
        // last one wins, so a script appending a flag cannot be defeated by an earlier one.
        assertEquals(RecordingMode.SAMPLE,
                CommandLine.parse(new String[] {"run", "--no-record", "--profile"}, 1).recordingMode());
        assertEquals(RecordingMode.OFF,
                CommandLine.parse(new String[] {"run", "--profile", "--no-record"}, 1).recordingMode());

        CommandLine coherent = CommandLine.parse(
                new String[] {"run", "--profile", "--single-chunk-recording"}, 1);
        assertEquals(RecordingMode.SAMPLE, coherent.recordingMode());
        assertEquals(true, coherent.singleChunkRecording());
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandLine.parse(
                        new String[] {"run", "--single-chunk-recording", "--no-record"}, 1));
    }

    @Test
    void defaultsOffAndParsesProbeOrEnabledModes() {
        CommandLine defaults = CommandLine.parse(new String[] {"run"}, 1);
        assertEquals(AdapterMode.OFF, defaults.adapterMode());
        assertEquals(TextureAdapterMode.COMPATIBILITY, defaults.textureAdapterMode());
        assertEquals(false, defaults.directLaunch());
        assertEquals(false, defaults.fileOnlyLogs());
        assertEquals(false, defaults.quietLogs());
        assertEquals(false, defaults.suppressAssetProgressLogs());
        assertEquals(false, defaults.trustValidatedTextureIndex());
        assertEquals(false, defaults.frameTimes());
        assertEquals(false, defaults.smoothFramePacing());
        assertEquals(false, defaults.desktopSmoke());

        CommandLine direct = CommandLine.parse(
                new String[] {"run", "--direct", "--quiet-logs"}, 1);
        assertEquals(true, direct.directLaunch());
        assertEquals(true, direct.fileOnlyLogs());
        assertEquals(true, direct.quietLogs());

        CommandLine smoke = CommandLine.parse(
                new String[] {"run", "--fast", "--direct", "--desktop-smoke"}, 1);
        assertEquals(true, smoke.desktopSmoke());

        CommandLine frameTimes = CommandLine.parse(
                new String[] {"run", "--fast", "--frame-times"}, 1);
        assertEquals(true, frameTimes.frameTimes());
        assertEquals(false, frameTimes.desktopSmoke());
        assertThrows(IllegalArgumentException.class, () -> CommandLine.parse(
                new String[] {"run", "--optimization-preset", "off", "--frame-times"}, 1));

        CommandLine smoothFramePacing = CommandLine.parse(
                new String[] {"run", "--fast", "--smooth-frame-pacing"}, 1);
        assertEquals(true, smoothFramePacing.smoothFramePacing());
        assertThrows(IllegalArgumentException.class, () -> CommandLine.parse(
                new String[] {"run", "--optimization-preset", "off", "--smooth-frame-pacing"}, 1));

        CommandLine measurement = CommandLine.parse(new String[] {
                "run", "--optimization-preset", "off", "--direct", "--desktop-smoke"
        }, 1);
        assertEquals(true, measurement.desktopSmoke());
        assertEquals(OptimizationPreset.OFF, measurement.optimizationPreset());
        assertEquals(AdapterMode.ENABLED, measurement.adapterMode());
        assertEquals(AdapterPlanScope.MEASUREMENT_ONLY, measurement.adapterPlanScope());
        assertEquals(false, measurement.textureAuto());
        assertEquals(false, measurement.campaignEntityIndex());
        assertEquals(false, measurement.preparedAudio());
        assertEquals(false, measurement.loadJsonMemo());
        assertThrows(IllegalArgumentException.class, () -> CommandLine.parse(new String[] {
                "run", "--optimization-preset", "off", "--desktop-smoke", "--no-adapter"
        }, 1));

        CommandLine probe = CommandLine.parse(
                new String[] {"run", "--adapter-probe", "--adapter-targets", "targets.txt"}, 1);
        assertEquals(AdapterMode.PROBE, probe.adapterMode());
        assertEquals(Path.of("targets.txt"), probe.adapterTargets());

        CommandLine enabled = CommandLine.parse(new String[] {
                "run",
                "--adapter",
                "--campaign-entity-index",
                "--texture-mode", "prepared-pixels",
                "--texture-cache-dir", "cache",
                "--texture-manifest", "cache/manifests/profile.spfm",
                "--texture-index", "cache/indexes/profile.spfi"
        }, 1);
        assertEquals(AdapterMode.ENABLED, enabled.adapterMode());
        assertEquals(TextureAdapterMode.PREPARED_PIXELS, enabled.textureAdapterMode());
        assertEquals(true, enabled.campaignEntityIndex());
        assertEquals(Path.of("cache"), enabled.textureCacheDirectory());
        assertEquals(Path.of("cache/manifests/profile.spfm"), enabled.textureManifest());
        assertEquals(Path.of("cache/indexes/profile.spfi"), enabled.textureIndex());

        CommandLine automatic = CommandLine.parse(new String[] {
                "run", "--adapter", "--texture-auto", "--texture-cache-dir", "cache"
        }, 1);
        assertEquals(true, automatic.textureAuto());
        assertEquals(TextureAdapterMode.COMPATIBILITY, automatic.textureAdapterMode());
        assertEquals(Path.of("cache"), automatic.textureCacheDirectory());
        assertEquals(null, automatic.textureManifest());
        assertEquals(null, automatic.textureIndex());
    }

    @Test
    void fastDropsOnlyTheDuplicateConsoleAndKeepsTheHardCrashLogTail() {
        CommandLine fast = CommandLine.parse(new String[] {"run", "--fast"}, 1);
        CommandLine explicit = CommandLine.parse(
                new String[] {"run", "--fast", "--quiet-logs"}, 1);

        assertEquals(true, fast.fileOnlyLogs());
        assertEquals(false, fast.quietLogs());
        assertEquals(true, fast.suppressAssetProgressLogs());
        assertEquals(true, fast.trustValidatedTextureIndex());
        assertEquals(false, CommandLine.parse(
                new String[] {"run", "--fast", "--full-asset-progress-logs"}, 1)
                .suppressAssetProgressLogs());
        assertEquals(false, CommandLine.parse(
                new String[] {"run", "--fast", "--recheck-texture-sources"}, 1)
                .trustValidatedTextureIndex());
        assertEquals(true, explicit.fileOnlyLogs());
        assertEquals(true, explicit.quietLogs());
    }

    @Test
    void fastRequestsPerEntryTrueSizeTextures() {
        CommandLine fast = CommandLine.parse(new String[] {"run", "--fast"}, 1);

        assertEquals(true, fast.unpadded());
        assertEquals(false, fast.npotDirect());
    }

    @Test
    void fastExcludesTheResourceProbeCacheAfterTheConservativeMemoAlsoLostAFile() {
        CommandLine fast = CommandLine.parse(new String[] {"run", "--fast"}, 1);
        CommandLine explicit = CommandLine.parse(
                new String[] {"run", "--adapter", "--resource-probe-cache"}, 1);

        assertEquals(false, fast.resourceProbeCache());
        assertEquals(true, fast.campaignEntityIndex());
        assertEquals(false, CommandLine.parse(
                new String[] {"run", "--fast", "--no-campaign-entity-index"}, 1)
                .campaignEntityIndex());
        assertEquals(true, explicit.resourceProbeCache());
    }

    @Test
    void startupPhaseProbeRequiresTheEnabledAdapter() {
        CommandLine enabled = CommandLine.parse(
                new String[] {"run", "--adapter", "--startup-phase-probe"}, 1);
        assertEquals(true, enabled.startupPhaseProbe());
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandLine.parse(new String[] {"run", "--startup-phase-probe"}, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandLine.parse(
                        new String[] {"run", "--adapter-probe", "--startup-phase-probe"}, 1));
    }

    @Test
    void graphicsLibCompactReplayRequiresTheEnabledAdapterAndIsIncludedInFast() {
        CommandLine enabled = CommandLine.parse(
                new String[] {"run", "--adapter", "--graphicslib-compact-replay"}, 1);
        assertEquals(true, enabled.graphicsLibCompactReplay());
        assertEquals(true,
                CommandLine.parse(new String[] {"run", "--fast"}, 1).graphicsLibCompactReplay());
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandLine.parse(new String[] {"run", "--graphicslib-compact-replay"}, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandLine.parse(
                        new String[] {"run", "--adapter-probe", "--graphicslib-compact-replay"}, 1));
    }

    @Test
    void janinoBytecodeCacheRequiresTheEnabledAdapterAndIsIncludedInFast() {
        CommandLine enabled = CommandLine.parse(
                new String[] {"run", "--adapter", "--janino-bytecode-cache"}, 1);
        assertEquals(true, enabled.janinoBytecodeCache());
        assertEquals(true,
                CommandLine.parse(new String[] {"run", "--fast"}, 1).janinoBytecodeCache());
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandLine.parse(new String[] {"run", "--janino-bytecode-cache"}, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandLine.parse(
                        new String[] {"run", "--adapter-probe", "--janino-bytecode-cache"}, 1));
    }

    @Test
    void graphicsLibInsigniaCacheRequiresTheEnabledAdapterAndIsIncludedInFast() {
        CommandLine enabled = CommandLine.parse(
                new String[] {"run", "--adapter", "--graphicslib-insignia-cache"}, 1);
        assertEquals(true, enabled.graphicsLibInsigniaManagerCache());
        assertEquals(true,
                CommandLine.parse(new String[] {"run", "--fast"}, 1)
                        .graphicsLibInsigniaManagerCache());
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandLine.parse(new String[] {"run", "--graphicslib-insignia-cache"}, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandLine.parse(
                        new String[] {"run", "--adapter-probe", "--graphicslib-insignia-cache"}, 1));
    }

    @Test
    void textureAutoResolvesTheCacheForEitherTextureMode() {
        // Auto decides *which* manifest and index to use; the mode decides which TextureLoader
        // target reads them. They are independent -- both modes configure from the same call and
        // the same blobs -- and coupling them left prepared-pixels unreachable from the only
        // ergonomic launch path, which is why it never got a real-install pilot.
        CommandLine prepared = CommandLine.parse(new String[] {
                "run", "--adapter", "--texture-auto", "--texture-cache-dir", "cache",
                "--texture-mode", "prepared-pixels"
        }, 1);
        assertEquals(TextureAdapterMode.PREPARED_PIXELS, prepared.textureAdapterMode());
        assertEquals(true, prepared.textureAuto());
        assertEquals(Path.of("cache"), prepared.textureCacheDirectory());
        assertEquals(false, prepared.npotDirect());

        CommandLine npot = CommandLine.parse(new String[] {
                "run", "--adapter", "--texture-auto", "--texture-cache-dir", "cache",
                "--texture-mode", "prepared-pixels", "--prepared-npot"
        }, 1);
        assertEquals(true, npot.npotDirect());

        CommandLine unpadded = CommandLine.parse(new String[] {
                "run", "--adapter", "--texture-auto", "--texture-cache-dir", "cache",
                "--texture-mode", "prepared-pixels", "--prepared-unpadded"
        }, 1);
        assertEquals(true, unpadded.unpadded());
        assertEquals(false, unpadded.npotDirect());

        // Alternatives, not a combination. Together they build a carrier sized for the padded
        // allocation and then supply the true-size buffer -- the documented
        // insufficient-original-buffer failure, which corrupts rendering while every reported
        // number still looks right.
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandLine.parse(new String[] {
                        "run", "--adapter", "--texture-auto", "--texture-cache-dir", "cache",
                        "--texture-mode", "prepared-pixels", "--prepared-npot", "--prepared-unpadded"
                }, 1));
        // Setting it anywhere the bridge will not read it is rejected rather than ignored: a
        // launch that looks configured for non-power-of-two and is not looks exactly like the
        // bridge declining, which is the failure the flag exists to fix.
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandLine.parse(new String[] {
                        "run", "--adapter", "--texture-auto", "--texture-cache-dir", "cache",
                        "--prepared-npot"
                }, 1));
        // Auto still refuses to be handed artifacts it is supposed to resolve itself.
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandLine.parse(new String[] {
                        "run", "--adapter", "--texture-auto",
                        "--texture-mode", "prepared-pixels",
                        "--texture-manifest", "profile.spfm"
                }, 1));
    }

    @Test
    void rejectsConflictingModesAndTargetsWhileOff() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandLine.parse(new String[] {"run", "--adapter", "--adapter-probe"}, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandLine.parse(new String[] {"run", "--adapter-targets", "targets.txt"}, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandLine.parse(new String[] {"run", "--campaign-entity-index"}, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandLine.parse(
                        new String[] {"run", "--adapter-probe", "--campaign-entity-index"}, 1));
    }

    @Test
    void rejectsPartialTextureConfigurationProbeModeAndBareTextureMode() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandLine.parse(new String[] {
                        "run", "--adapter", "--texture-cache-dir", "cache"
                }, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandLine.parse(new String[] {
                        "run",
                        "--adapter-probe",
                        "--texture-cache-dir", "cache",
                        "--texture-manifest", "cache/manifests/profile.spfm",
                        "--texture-index", "cache/indexes/profile.spfi"
                }, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandLine.parse(new String[] {
                        "run", "--adapter", "--texture-mode", "prepared-pixels"
                }, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandLine.parse(new String[] {
                        "run", "--texture-mode", "prepared-pixels"
                }, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandLine.parse(new String[] {
                        "run", "--adapter", "--texture-auto", "--texture-manifest", "profile.spfm"
                }, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandLine.parse(new String[] {"run", "--texture-auto"}, 1));
    }
}
