package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class HomeIsolationIT {
    @Test
    void integrationJvmAndSpawnedJavaUseTheBuildLocalHome() throws Exception {
        Path expectedHome = Path.of("target", "test-user-home").toAbsolutePath().normalize();
        Path currentHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();

        assertEquals(expectedHome, currentHome);
        assertEquals(
                expectedHome.resolve(PreflightHome.DIRECTORY_NAME),
                PreflightHome.current().root());

        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        Process child = new ProcessBuilder(
                        java.toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        HomeIsolationProbe.class.getName())
                .start();
        boolean completed = child.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            child.destroyForcibly();
            child.waitFor();
        }
        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();

        assertTrue(completed, "spawned Java did not finish");
        assertEquals(0, child.exitValue(), new String(child.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(expectedHome.toString(), output);
    }
}

final class HomeIsolationProbe {
    private HomeIsolationProbe() {
    }

    public static void main(String[] arguments) {
        System.out.print(Path.of(System.getProperty("user.home")).toAbsolutePath().normalize());
    }
}
