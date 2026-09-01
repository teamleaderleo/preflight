package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.ResourceIndexIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopSmokeLaunchTest {
    @Test
    void constructsOnePackagedDirectSmokeLaunchWithoutAShell() {
        Path java = Path.of("runtime/bin/java");
        Path jar = Path.of("dist/preflight.jar");
        Path run = Path.of("runs/smoke");
        Path game = Path.of("game");
        Path launcher = Path.of("game/starsector.sh");

        List<String> command = DesktopSmokeLaunch.command(
                java, jar, scenario("fast", null), run, game, launcher);

        assertEquals(java.toAbsolutePath().normalize().toString(), command.get(0));
        assertTrue(command.get(1).startsWith("-Duser.home="));
        assertEquals(List.of("-jar", jar.toAbsolutePath().normalize().toString(), "run"),
                command.subList(2, 5));
        assertTrue(command.contains("--fast"));
        assertTrue(command.contains("--direct"));
        assertTrue(command.contains("--desktop-smoke"));
        assertEquals(run.toAbsolutePath().normalize().toString(),
                command.get(command.indexOf("--trace-dir") + 1));
        assertEquals(game.toAbsolutePath().normalize().toString(),
                command.get(command.indexOf("--game") + 1));
        assertEquals(launcher.toAbsolutePath().normalize().toString(),
                command.get(command.indexOf("--launcher") + 1));
        assertTrue(command.stream().noneMatch(argument -> argument.contains("Starsector.app")));
    }

    @Test
    void constructsMeasurementOnlyLaunchWithInstrumentationAndNoOptimizations() {
        List<String> command = DesktopSmokeLaunch.command(
                Path.of("java"), Path.of("preflight.jar"), scenario("measurement-only", null),
                Path.of("run"), null, null);

        assertEquals("off", command.get(command.indexOf("--optimization-preset") + 1));
        assertTrue(command.contains("--desktop-smoke"));
        assertFalse(command.contains("--fast"));
    }

    @Test
    void passesExplicitCampaignAndFramePacingDiagnosticsToTheSingleGameLaunch() {
        DesktopSmokeScenario profiled = DesktopSmokeScenario.parse("""
                {
                  "format":"starsector-preflight-smoke-v1",
                  "name":"profiled",
                  "timeoutSeconds":60,
                  "launch":{"preset":"fast","textureStorage":"balanced","profile":null,
                    "campaignTimes":true,"smoothFramePacing":true},
                  "steps":[{"id":"menu","kind":"wait-state","state":"main-menu-ready",
                    "timeoutSeconds":30}]
                }
                """);

        List<String> command = DesktopSmokeLaunch.command(
                Path.of("java"), Path.of("preflight.jar"), profiled,
                Path.of("run"), null, null);

        assertTrue(command.contains("--campaign-times"));
        assertTrue(command.contains("--smooth-frame-pacing"));
    }

    @Test
    void passesSingleChunkSamplingToTheSameControlledGameLaunch() {
        DesktopSmokeScenario sampled = DesktopSmokeScenario.parse("""
                {
                  "format":"starsector-preflight-smoke-v1",
                  "name":"sampled",
                  "timeoutSeconds":60,
                  "launch":{"preset":"fast","textureStorage":"balanced","profile":null,
                    "recording":"sample"},
                  "steps":[{"id":"menu","kind":"wait-state","state":"main-menu-ready",
                    "timeoutSeconds":30}]
                }
                """);

        List<String> command = DesktopSmokeLaunch.command(
                Path.of("java"), Path.of("preflight.jar"), sampled,
                Path.of("run"), null, null);

        assertTrue(command.contains("--profile"));
        assertTrue(command.contains("--single-chunk-recording"));
        assertEquals(1, command.stream().filter("--profile"::equals).count());
    }

    @Test
    void minimalDiskBenchmarkUsesThePreparedProfileContract() {
        List<String> command = DesktopSmokeLaunch.command(
                Path.of("java"), Path.of("preflight.jar"), scenario("fast", "minimal", null),
                Path.of("run"), null, null);

        assertFalse(command.contains("--disable-optimization-domain"));
    }

    @Test
    void minimalDiskBenchmarkRequiresAnExactMinimalPreparation(@TempDir Path temporary)
            throws Exception {
        Path game = temporary.resolve("game");
        Path source = game.resolve("starsector-core/graphics/test.png");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "texture");
        Files.createDirectories(game.resolve("mods"));
        Files.writeString(game.resolve("mods/enabled_mods.json"), "{\"enabledMods\":[]}");
        PreflightHome home = new PreflightHome(
                temporary.resolve("home").resolve(PreflightHome.DIRECTORY_NAME), List.of());
        DesktopSmokeScenario minimal = scenario("fast", "minimal", null);

        assertThrows(IllegalStateException.class,
                () -> DesktopSmokeLaunch.requirePreparedStorage(minimal, home, game));

        ResourceIndexBuilder.BuildResult built = ResourceIndexBuilder.build(game);
        ResourceIndexIO.write(
                ResourceIndexIO.directory(home.cache())
                        .resolve(built.index().profileFingerprint() + ".spfi"),
                built.index());
        MinimalPreparationMarker.write(home.cache(), built.index().profileFingerprint());

        DesktopSmokeLaunch.requirePreparedStorage(minimal, home, game);
    }

    @Test
    void namedProfileLaunchesFailBeforeStartingAProcess() {
        assertThrows(IllegalArgumentException.class, () -> DesktopSmokeLaunch.command(
                Path.of("java"), Path.of("preflight.jar"), scenario("fast", "balanced", "large"),
                Path.of("run"), null, null));
    }

    @Test
    void cancellationRequiresARealRunOwnedMarker(@TempDir Path temporary)
            throws Exception {
        Path marker = temporary.resolve(DesktopSmokeLaunch.CANCELLATION_FILE);
        assertFalse(DesktopSmokeLaunch.cancellationRequested(marker));

        Files.writeString(marker, "cancel\n");

        assertTrue(DesktopSmokeLaunch.cancellationRequested(marker));
    }

    private static DesktopSmokeScenario scenario(String preset, String profile) {
        return scenario(preset, "balanced", profile);
    }

    private static DesktopSmokeScenario scenario(String preset, String textureStorage, String profile) {
        return DesktopSmokeScenario.parse("""
                {
                  "format":"starsector-preflight-smoke-v1",
                  "name":"launch-test",
                  "timeoutSeconds":60,
                  "launch":{
                    "preset":"%s",
                    "textureStorage":"%s",
                    "profile":%s
                  },
                  "steps":[
                    {"id":"menu","kind":"wait-state","state":"main-menu-ready","timeoutSeconds":30}
                  ]
                }
                """.formatted(
                    preset, textureStorage,
                    profile == null ? "null" : "\"" + profile + "\""));
    }
}
