package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentJarStagingCleanupTest {
    private static final String OUTSIDE_ASCII = "Ωμέγα";

    @TempDir
    Path temporary;

    @Test
    void wrapperExitRemovesStagedJarBeforeItsPrivateDirectory() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path result = temporary.resolve("staged-path.txt");

        String classpath = System.getProperty(
                "surefire.test.class.path", System.getProperty("java.class.path"));
        Path javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                        ? "java.exe"
                        : "java");
        Process process = new ProcessBuilder(
                javaExecutable.toString(),
                "-cp",
                classpath,
                CleanupProbe.class.getName(),
                "root",
                "staged-path.txt")
                .directory(temporary.toFile())
                .redirectErrorStream(true)
                .start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("staged-agent cleanup probe did not exit");
        }
        assertEquals(0, process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));

        Path staged = Path.of(Files.readString(result, StandardCharsets.UTF_8).trim());
        assertTrue(staged.startsWith(root));
        assertFalse(Files.exists(staged), "the staged agent must be removed when the wrapper exits");
        assertFalse(Files.exists(staged.getParent()),
                "the private staging directory must be removed after its child");
    }

    public static final class CleanupProbe {
        private CleanupProbe() {
        }

        public static void main(String[] args) throws Exception {
            Path source = Path.of(OUTSIDE_ASCII, "preflight.jar").toAbsolutePath();
            Files.createDirectories(source.getParent());
            Files.writeString(source, "not really a jar", StandardCharsets.UTF_8);
            Path staged = AgentJarStaging.readableByTheChildJvm(
                    source, StandardCharsets.US_ASCII, List.of(Path.of(args[0]).toAbsolutePath()));
            Files.writeString(Path.of(args[1]), staged.toString(), StandardCharsets.UTF_8);
        }
    }
}
