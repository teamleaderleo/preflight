package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunCommandTest {
    @Test
    void directLaunchOptionsAppendWithoutDiscardingExistingLauncherOptions() {
        var options = List.of(
                "-DlaunchDirect=true",
                "-DstartRes=1440x932",
                "-DstartFS=false",
                "-DstartSound=true");
        assertEquals(
                String.join(" ", options),
                RunCommand.appendJavaOptions(null, options));
        assertEquals(
                "-Dexisting=true " + String.join(" ", options),
                RunCommand.appendJavaOptions("-Dexisting=true", options));
    }

    @Test
    void defaultRunDirectoriesRemainDistinctWithinOneMillisecond() {
        Path home = Path.of("synthetic-home");
        Instant started = Instant.parse("2026-07-21T10:42:03.123Z");

        Path first = RunCommand.defaultRunDirectory(home, started, "aaaaaaaa");
        Path second = RunCommand.defaultRunDirectory(home, started, "bbbbbbbb");

        assertNotEquals(first, second);
        assertTrue(first.getFileName().toString().startsWith("20260721-104203-123-"));
        assertTrue(second.getFileName().toString().startsWith("20260721-104203-123-"));
    }

    @Test
    void defaultRunDirectoryRejectsUnsafeNonceText() {
        assertThrows(IllegalArgumentException.class, () -> RunCommand.defaultRunDirectory(
                Path.of("synthetic-home"),
                Instant.EPOCH,
                "../escape"));
    }

    @Test
    void onlyAnAutomaticExactTextureContextActivatesTheValidatedSnapshot() {
        CommandLine fast = CommandLine.parse(new String[] {"run", "--fast"}, 1);

        assertEquals(true, RunCommand.trustsLauncherValidatedTextureIndex(
                fast, textureContext(true, true)));
        assertEquals(false, RunCommand.trustsLauncherValidatedTextureIndex(
                fast, textureContext(false, true)));
        assertEquals(false, RunCommand.trustsLauncherValidatedTextureIndex(
                fast, textureContext(true, false)));
        assertEquals(false, RunCommand.trustsLauncherValidatedTextureIndex(fast, null));
    }

    @Test
    void launchReadinessDescribesTheRequestedPresetInsteadOfGuessing() {
        assertEquals(
                "Off / troubleshooting is selected. The next launch does not use prepared data.",
                RunCommand.launchReadinessSummary(OptimizationPreset.OFF, health("ready")));
        assertEquals(
                "No product optimization preset is selected. Use `--optimization-preset recommended` "
                        + "to check an optimized launch or `off` for the troubleshooting baseline.",
                RunCommand.launchReadinessSummary(OptimizationPreset.CUSTOM, health("ready")));
        assertEquals(
                "The next Recommended launch can use this profile's prepared data.",
                RunCommand.launchReadinessSummary(OptimizationPreset.RECOMMENDED, health("ready")));
        assertEquals(
                "Prepare this profile before a Conservative launch. `--optimization-preset off` "
                        + "remains available and does not use prepared data.",
                RunCommand.launchReadinessSummary(OptimizationPreset.CONSERVATIVE, health("cold")));
        assertEquals(
                "Rebuild prepared data before a Recommended launch. `--optimization-preset off` "
                        + "remains available and does not use prepared data.",
                RunCommand.launchReadinessSummary(OptimizationPreset.RECOMMENDED, health("repair-needed")));
        assertEquals(
                "Resolve the prepared-data issue before a Recommended launch. `--optimization-preset off` "
                        + "remains available and does not use prepared data.",
                RunCommand.launchReadinessSummary(OptimizationPreset.RECOMMENDED, health("unsafe")));
        assertEquals(
                "Resolve the prepared-data issue before a Recommended launch. `--optimization-preset off` "
                        + "remains available and does not use prepared data.",
                RunCommand.launchReadinessSummary(OptimizationPreset.RECOMMENDED, health("unknown")));
    }

    private static CacheHealth.Report health(String status) {
        return new CacheHealth.Report(status, "profile", null, null, null, false, List.of(), List.of());
    }

    private static LaunchCacheContexts.Texture textureContext(boolean automatic, boolean prepared) {
        return new LaunchCacheContexts.Texture(
                Path.of("cache"),
                Path.of("cache/manifest.spfm"),
                Path.of("cache/index.spfi"),
                null,
                automatic,
                "profile",
                "manifest-sha",
                "index-sha",
                1,
                1,
                prepared);
    }
}
