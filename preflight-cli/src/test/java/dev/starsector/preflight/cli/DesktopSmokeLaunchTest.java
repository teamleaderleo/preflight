package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(List.of("-jar", jar.toAbsolutePath().normalize().toString(), "run"),
                command.subList(1, 4));
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
    void unsupportedBaselineAndNamedProfileLaunchesFailBeforeStartingAProcess() {
        assertThrows(IllegalArgumentException.class, () -> DesktopSmokeLaunch.command(
                Path.of("java"), Path.of("preflight.jar"), scenario("vanilla", null),
                Path.of("run"), null, null));
        assertThrows(IllegalArgumentException.class, () -> DesktopSmokeLaunch.command(
                Path.of("java"), Path.of("preflight.jar"), scenario("fast", "large"),
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
        return DesktopSmokeScenario.parse("""
                {
                  "format":"starsector-preflight-smoke-v1",
                  "name":"launch-test",
                  "timeoutSeconds":60,
                  "launch":{
                    "preset":"%s",
                    "textureStorage":"balanced",
                    "profile":%s
                  },
                  "steps":[
                    {"id":"menu","kind":"wait-state","state":"main-menu-ready","timeoutSeconds":30}
                  ]
                }
                """.formatted(preset, profile == null ? "null" : "\"" + profile + "\""));
    }
}
