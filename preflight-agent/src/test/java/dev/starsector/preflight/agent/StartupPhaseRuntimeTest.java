package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartupPhaseRuntimeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsEachBoundaryWithoutWaitingForJvmShutdown() throws Exception {
        Path report = temporaryDirectory.resolve("startup-phases.json");
        StartupPhaseRuntime.beginSession(report);
        StartupPhaseRuntime.installed();
        StartupPhaseRuntime.mark("progress-100");
        StartupPhaseRuntime.pluginStart(new ExamplePlugin());
        StartupPhaseRuntime.pluginEnd();
        StartupPhaseRuntime.mark("resource-init-complete");

        String json = Files.readString(report);
        assertTrue(json.contains("\"installed\":true"));
        assertTrue(json.contains("\"name\":\"progress-100\""));
        assertTrue(json.contains("\"name\":\"resource-init-complete\""));
        assertTrue(json.contains("StartupPhaseRuntimeTest$ExamplePlugin"));
        assertTrue(json.contains("\"completed\":true"));
    }

    private static final class ExamplePlugin {
    }
}
