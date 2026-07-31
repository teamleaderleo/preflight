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
    }

    @Test
    void defaultsOffAndParsesProbeOrEnabledModes() {
        CommandLine defaults = CommandLine.parse(new String[] {"run"}, 1);
        assertEquals(AdapterMode.OFF, defaults.adapterMode());
        assertEquals(TextureAdapterMode.COMPATIBILITY, defaults.textureAdapterMode());

        CommandLine probe = CommandLine.parse(
                new String[] {"run", "--adapter-probe", "--adapter-targets", "targets.txt"}, 1);
        assertEquals(AdapterMode.PROBE, probe.adapterMode());
        assertEquals(Path.of("targets.txt"), probe.adapterTargets());

        CommandLine enabled = CommandLine.parse(new String[] {
                "run",
                "--adapter",
                "--texture-mode", "prepared-pixels",
                "--texture-cache-dir", "cache",
                "--texture-manifest", "cache/manifests/profile.spfm",
                "--texture-index", "cache/indexes/profile.spfi"
        }, 1);
        assertEquals(AdapterMode.ENABLED, enabled.adapterMode());
        assertEquals(TextureAdapterMode.PREPARED_PIXELS, enabled.textureAdapterMode());
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
    void rejectsConflictingModesAndTargetsWhileOff() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandLine.parse(new String[] {"run", "--adapter", "--adapter-probe"}, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> CommandLine.parse(new String[] {"run", "--adapter-targets", "targets.txt"}, 1));
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
                        "run", "--adapter", "--texture-auto", "--texture-mode", "prepared-pixels"
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
