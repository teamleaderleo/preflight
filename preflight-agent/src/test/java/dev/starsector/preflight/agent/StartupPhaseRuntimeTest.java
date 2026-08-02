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
        StartupPhaseRuntime.progress(0.0004f);
        StartupPhaseRuntime.progress(0.052f);
        StartupPhaseRuntime.mark("progress-100");
        StartupPhaseRuntime.specLoaderStart("1:SpecStore.new");
        StartupPhaseRuntime.specLoaderEnd();
        StartupPhaseRuntime.pluginStart(new ExamplePlugin());
        StartupPhaseRuntime.pluginEnd();
        StartupPhaseRuntime.mark("resource-init-complete");

        String json = Files.readString(report);
        assertTrue(json.contains("\"installed\":true"));
        assertTrue(json.contains("\"name\":\"progress-first-render\""));
        assertTrue(json.contains("\"name\":\"progress-5-percent\""));
        assertTrue(json.contains("\"progressPermille\":52"));
        assertTrue(json.contains("\"progressCalls\":2"));
        assertTrue(json.contains("\"name\":\"progress-100\""));
        assertTrue(json.contains("\"name\":\"resource-init-complete\""));
        assertTrue(json.contains("\"label\":\"1:SpecStore.new\""));
        assertTrue(json.contains("StartupPhaseRuntimeTest$ExamplePlugin"));
        assertTrue(json.contains("\"completed\":true"));
    }

    private static final class ExamplePlugin {
    }
}
