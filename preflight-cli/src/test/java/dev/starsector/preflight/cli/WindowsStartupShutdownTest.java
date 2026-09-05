package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.WINDOWS)
final class WindowsStartupShutdownTest {
    @Test
    void realCimCollectionsPreserveShutdownDeadlineAndTelemetryWithMockedEffects() throws Exception {
        // Surefire sets basedir to this module even when Maven starts at the reactor root.
        String moduleBasedir = System.getProperty("basedir");
        assertTrue(moduleBasedir != null && !moduleBasedir.isBlank(), "Maven module basedir is required");
        Path scripts = Path.of(moduleBasedir).toAbsolutePath().normalize().resolve("../scripts").normalize();
        Path fixture = scripts.resolve("test_windows_startup_shutdown.ps1");
        Path runner = scripts.resolve("run-windows-startup-cohort.ps1");
        assertTrue(Files.isRegularFile(fixture), "Missing fixture: " + fixture);
        assertTrue(Files.isRegularFile(runner), "Missing runner: " + runner);
        String systemRoot = System.getenv("SystemRoot");
        assertTrue(systemRoot != null && !systemRoot.isBlank(), "Windows SystemRoot is required");
        // Do not accept pwsh (PowerShell 7): singleton CIM behavior must be checked on 5.1.
        Path powershell = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        assertTrue(Files.isRegularFile(powershell), "Missing Windows PowerShell: " + powershell);

        Process process = new ProcessBuilder(
                powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-File", fixture.toString(), "-RunnerPath", runner.toString())
                .directory(Path.of(moduleBasedir).toFile())
                .redirectErrorStream(true)
                .start();
        ExecutorService reader = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "windows-shutdown-fixture-output");
            thread.setDaemon(true);
            return thread;
        });
        Future<String> output = reader.submit(() -> {
            // Continue draining beyond the diagnostic bound so noisy failures cannot deadlock.
            try (InputStream stream = process.getInputStream()) {
                ByteArrayOutputStream captured = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                boolean truncated = false;
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    int keep = Math.min(count, 8000 - captured.size());
                    captured.write(buffer, 0, keep);
                    truncated |= keep < count;
                }
                return captured.toString(StandardCharsets.UTF_8) + (truncated ? "\n[output truncated]" : "");
            }
        });
        try {
            // The fixture's 45-second shutdown deadlines use mocked time, not real sleep.
            boolean finished = process.waitFor(45, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Fixture did not terminate after timeout");
            }
            String diagnostic = output.get(5, TimeUnit.SECONDS);
            assertTrue(finished, "PS5.1 shutdown fixture exceeded 45 seconds:\n" + diagnostic);
            assertEquals(0, process.exitValue(), diagnostic);
            assertTrue(diagnostic.contains("PASS: 10 mocked shutdown cases; PowerShell 5.1."), diagnostic);
        } finally {
            // The fixture mocks all process effects and creates no child processes or files.
            process.destroyForcibly();
            output.cancel(true);
            reader.shutdownNow();
            try (OutputStream stdin = process.getOutputStream();
                    InputStream stdout = process.getInputStream();
                    InputStream stderr = process.getErrorStream()) {
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Fixture process survived cleanup");
            } finally {
                reader.awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }
}
