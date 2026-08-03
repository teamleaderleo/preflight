package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.starsector.preflight.agent.AdapterMode;
import dev.starsector.preflight.agent.RecordingMode;
import dev.starsector.preflight.agent.TextureAdapterMode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CommandLineAdapterTest {
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
        assertEquals(false, defaults.quietLogs());

        CommandLine direct = CommandLine.parse(
                new String[] {"run", "--direct", "--quiet-logs"}, 1);
        assertEquals(true, direct.directLaunch());
        assertEquals(true, direct.quietLogs());

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
    void fastDoesNotSilentlyTradeAwayTheHardCrashLogTail() {
        CommandLine fast = CommandLine.parse(new String[] {"run", "--fast"}, 1);
        CommandLine explicit = CommandLine.parse(
                new String[] {"run", "--fast", "--quiet-logs"}, 1);

        assertEquals(false, fast.quietLogs());
        assertEquals(true, explicit.quietLogs());
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
    void graphicsLibCompactReplayRequiresTheEnabledAdapterAndStaysOutOfFastForBeta() {
        CommandLine enabled = CommandLine.parse(
                new String[] {"run", "--adapter", "--graphicslib-compact-replay"}, 1);
        assertEquals(true, enabled.graphicsLibCompactReplay());
        assertEquals(false,
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
    void graphicsLibInsigniaCacheRequiresTheEnabledAdapterAndStaysOutOfFastForBeta() {
        CommandLine enabled = CommandLine.parse(
                new String[] {"run", "--adapter", "--graphicslib-insignia-cache"}, 1);
        assertEquals(true, enabled.graphicsLibInsigniaManagerCache());
        assertEquals(false,
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
