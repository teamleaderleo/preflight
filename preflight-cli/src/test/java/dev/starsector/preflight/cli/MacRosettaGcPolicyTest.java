package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MacRosettaGcPolicyTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void onlyRecommendedOnTheReviewedMacLauncherAndRuntimeActivates() throws Exception {
        Path app = fixture(true, true);
        LaunchTarget target = target(app);

        MacRosettaGcPolicy.Resolution active = MacRosettaGcPolicy.resolve(
                Platform.MAC, target, OptimizationPreset.RECOMMENDED, Map.of());
        assertTrue(active.active(), active.reason());
        assertTrue(active.reason().contains("matched"), active.reason());

        assertFalse(MacRosettaGcPolicy.resolve(
                Platform.LINUX, target, OptimizationPreset.RECOMMENDED, Map.of()).active());
        assertFalse(MacRosettaGcPolicy.resolve(
                Platform.MAC, target, OptimizationPreset.CONSERVATIVE, Map.of()).active());
        assertFalse(MacRosettaGcPolicy.resolve(
                Platform.MAC, target, OptimizationPreset.OFF, Map.of()).active());
    }

    @Test
    void unknownLauncherOrRuntimeFailsClosed() throws Exception {
        MacRosettaGcPolicy.Resolution launcher = MacRosettaGcPolicy.resolve(
                Platform.MAC, target(fixture(false, true)),
                OptimizationPreset.RECOMMENDED, Map.of());
        assertFalse(launcher.active());
        assertTrue(launcher.reason().contains("launcher"), launcher.reason());

        MacRosettaGcPolicy.Resolution runtime = MacRosettaGcPolicy.resolve(
                Platform.MAC, target(fixture(true, false)),
                OptimizationPreset.RECOMMENDED, Map.of());
        assertFalse(runtime.active());
        assertTrue(runtime.reason().contains("runtime"), runtime.reason());
    }

    @Test
    void explicitEnvironmentChoiceAndKillSwitchArePreserved() throws Exception {
        LaunchTarget target = target(fixture(true, true));
        assertFalse(MacRosettaGcPolicy.resolve(
                Platform.MAC, target, OptimizationPreset.RECOMMENDED,
                Map.of("_JAVA_OPTIONS", "-XX:+UseSerialGC")).active());
        assertFalse(MacRosettaGcPolicy.resolve(
                Platform.MAC, target, OptimizationPreset.RECOMMENDED,
                Map.of("JAVA_TOOL_OPTIONS", "-XX:-UseShenandoahGC -XX:+UseG1GC")).active());
        assertFalse(MacRosettaGcPolicy.resolve(
                Platform.MAC, target, OptimizationPreset.RECOMMENDED,
                Map.of(MacRosettaGcPolicy.DISABLE_ENVIRONMENT, "yes")).active());
    }

    @Test
    void optionsAreLastWinsAndIdempotent() throws Exception {
        MacRosettaGcPolicy.Resolution active = MacRosettaGcPolicy.resolve(
                Platform.MAC, target(fixture(true, true)),
                OptimizationPreset.RECOMMENDED, Map.of());
        String options = MacRosettaGcPolicy.appendOptions("-Dexisting=true", active);

        assertTrue(options.contains(MacRosettaGcPolicy.DISABLE_SHENANDOAH));
        assertTrue(options.contains(MacRosettaGcPolicy.ENABLE_G1));
        assertTrue(options.contains(MacRosettaGcPolicy.DEFER_HEAP_COMMIT));
        assertTrue(options.contains(MacRosettaGcPolicy.MODE_PROPERTY));
        assertEquals(options, MacRosettaGcPolicy.appendOptions(options, active));
        assertNull(MacRosettaGcPolicy.appendOptions(
                null, MacRosettaGcPolicy.Resolution.inactive("test")));
    }

    private Path fixture(boolean reviewedLauncher, boolean reviewedRuntime) throws IOException {
        Path app = temporaryDirectory.resolve("fixture-" + reviewedLauncher + "-" + reviewedRuntime);
        Path mac = Files.createDirectories(app.resolve("Contents/MacOS"));
        Files.createDirectories(app.resolve("Contents/Home"));
        Files.writeString(mac.resolve("starsector_mac.sh"), reviewedLauncher
                ? String.join(" ",
                        "-XX:+UnlockExperimentalVMOptions",
                        "-XX:+UseShenandoahGC",
                        "-XX:ShenandoahGCMode=iu",
                        "-XX:ShenandoahGCHeuristics=compact",
                        "-XX:ShenandoahGuaranteedGCInterval=0")
                : "java com.fs.starfarer.StarfarerLauncher");
        Files.writeString(app.resolve("Contents/Home/release"), reviewedRuntime
                ? String.join("\n",
                        "IMPLEMENTOR=\"Azul Systems, Inc.\"",
                        "JAVA_RUNTIME_VERSION=\"17.0.10+7-LTS\"",
                        "OS_ARCH=\"x86_64\"",
                        "OS_NAME=\"Darwin\"")
                : "IMPLEMENTOR=\"unknown\"");
        return app;
    }

    private static LaunchTarget target(Path app) {
        Path launcher = app.resolve("Contents/MacOS/starsector_mac.sh");
        return new LaunchTarget(app, launcher, launcher.getParent(),
                java.util.List.of(launcher.toString()), "shell-script", 1, "test");
    }
}
