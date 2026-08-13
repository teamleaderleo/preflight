package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartupPhaseRuntimeTest {
    @TempDir
    Path temporaryDirectory;

    /**
     * Puts the probe back down.
     *
     * <p>A destination is what {@code phaseProbeEnabled} reads, and one JVM runs every test class in
     * this module, so leaving it set turns the probe on for everything that follows. That is not an
     * abstract tidiness point: a plan whose transform instruments the class only while the probe is
     * on then emits different bytecode, and a test pinning its SHA-256 fails according to which
     * classes surefire happened to run first.
     */
    @AfterEach
    void endSession() {
        StartupPhaseRuntime.beginSession(null);
    }

    @Test
    void persistsEachBoundaryWithoutWaitingForJvmShutdown() throws Exception {
        Path report = temporaryDirectory.resolve("startup-phases.json");
        StartupPhaseRuntime.beginSession(report);
        StartupPhaseRuntime.installed();
        StartupPhaseRuntime.progress(0.0004f);
        StartupPhaseRuntime.progress(0.052f);
        StartupPhaseRuntime.mark("progress-100");
        StartupPhaseRuntime.specLoaderStart("1:SpecStore.new");
        StartupPhaseRuntime.specSubphaseStart("variant-object-construction");
        StartupPhaseRuntime.specSubphaseEnd();
        StartupPhaseRuntime.specSubphaseStart("variant-object-construction");
        StartupPhaseRuntime.specSubphaseEnd();
        StartupPhaseRuntime.specLoaderEnd();
        long hotCall = StartupPhaseRuntime.hotCallStart();
        StartupPhaseRuntime.hotCallEnd("gfx.autoGenMissingNormals", hotCall);
        StartupPhaseRuntime.hotPath("gfx.normal.cachePath", "cache/a.png");
        StartupPhaseRuntime.hotPath("gfx.normal.cachePath", "cache/a.png");
        StartupPhaseRuntime.hotPath("gfx.normal.cachePath", "cache/b.png");
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
        assertTrue(json.contains("\"label\":\"variant-object-construction\""));
        assertTrue(json.contains("\"calls\":2"));
        assertTrue(json.contains("\"label\":\"gfx.autoGenMissingNormals\""));
        assertTrue(json.contains("\"label\":\"gfx.normal.cachePath\""));
        assertTrue(json.contains("\"distinctPaths\":2"));
        assertTrue(json.contains("StartupPhaseRuntimeTest$ExamplePlugin"));
        assertTrue(json.contains("\"completed\":true"));
    }

    @Test
    void retainsLaterLoaderLabelsAfterTheFirstSixteenCategories() throws Exception {
        Path report = temporaryDirectory.resolve("many-subphases.json");
        StartupPhaseRuntime.beginSession(report);
        StartupPhaseRuntime.installed();
        for (int index = 0; index < 24; index++) {
            StartupPhaseRuntime.specSubphaseStart("loader-category-" + index);
            StartupPhaseRuntime.specSubphaseEnd();
        }
        StartupPhaseRuntime.mark("flush");

        String json = Files.readString(report);
        assertTrue(json.contains("\"label\":\"loader-category-0\""));
        assertTrue(json.contains("\"label\":\"loader-category-23\""));
    }

    private static final class ExamplePlugin {
    }
}
