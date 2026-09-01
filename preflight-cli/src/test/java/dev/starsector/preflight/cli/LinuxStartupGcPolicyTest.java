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

class LinuxStartupGcPolicyTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void exactRecommendedLinuxLauncherAndRuntimeActivate() throws Exception {
        LaunchTarget target = target(fixture(true, true));
        LinuxStartupGcPolicy.Resolution active = LinuxStartupGcPolicy.resolve(
                Platform.LINUX, target, OptimizationPreset.RECOMMENDED, Map.of());

        assertTrue(active.active(), active.reason());
        assertFalse(LinuxStartupGcPolicy.resolve(
                Platform.MAC, target, OptimizationPreset.RECOMMENDED, Map.of()).active());
        assertFalse(LinuxStartupGcPolicy.resolve(
                Platform.LINUX, target, OptimizationPreset.CONSERVATIVE, Map.of()).active());
    }

    @Test
    void unknownInputsExplicitCollectorAndKillSwitchFailClosed() throws Exception {
        assertFalse(LinuxStartupGcPolicy.resolve(
                Platform.LINUX, target(fixture(false, true)),
                OptimizationPreset.RECOMMENDED, Map.of()).active());
        assertFalse(LinuxStartupGcPolicy.resolve(
                Platform.LINUX, target(fixture(true, false)),
                OptimizationPreset.RECOMMENDED, Map.of()).active());
        LaunchTarget exact = target(fixture(true, true));
        assertFalse(LinuxStartupGcPolicy.resolve(
                Platform.LINUX, exact, OptimizationPreset.RECOMMENDED,
                Map.of("_JAVA_OPTIONS", "-XX:+UseSerialGC")).active());
        assertFalse(LinuxStartupGcPolicy.resolve(
                Platform.LINUX, exact, OptimizationPreset.RECOMMENDED,
                Map.of(LinuxStartupGcPolicy.DISABLE_ENVIRONMENT, "true")).active());
    }

    @Test
    void optionsAreLastWinsAndIdempotent() throws Exception {
        LinuxStartupGcPolicy.Resolution active = LinuxStartupGcPolicy.resolve(
                Platform.LINUX, target(fixture(true, true)),
                OptimizationPreset.RECOMMENDED, Map.of());
        String options = LinuxStartupGcPolicy.appendOptions("-Dexisting=true", active);

        assertTrue(options.contains(LinuxStartupGcPolicy.DISABLE_SHENANDOAH));
        assertTrue(options.contains(LinuxStartupGcPolicy.ENABLE_G1));
        assertTrue(options.contains(LinuxStartupGcPolicy.DEFER_HEAP_COMMIT));
        assertTrue(options.contains(LinuxStartupGcPolicy.MODE_PROPERTY));
        assertEquals(options, LinuxStartupGcPolicy.appendOptions(options, active));
        assertNull(LinuxStartupGcPolicy.appendOptions(
                null, LinuxStartupGcPolicy.Resolution.inactive("test")));
    }

    private Path fixture(boolean reviewedLauncher, boolean reviewedRuntime) throws IOException {
        Path root = temporaryDirectory.resolve("fixture-" + reviewedLauncher + "-" + reviewedRuntime);
        Files.createDirectories(root.resolve("jre_linux"));
        String launcher = reviewedLauncher
                ? "./jre_linux/bin/java -XX:+AlwaysPreTouch -XX:+UseShenandoahGC "
                        + "-XX:ShenandoahGCMode=iu -XX:ShenandoahGCHeuristics=compact "
                        + "-XX:ShenandoahGuaranteedGCInterval=0"
                : "./jre_linux/bin/java -XX:+UseG1GC";
        Files.writeString(root.resolve("starsector.sh"), launcher);
        String release = reviewedRuntime
                ? "IMPLEMENTOR=\"Azul Systems, Inc.\"\n"
                        + "JAVA_RUNTIME_VERSION=\"17.0.10+7-LTS\"\n"
                        + "OS_ARCH=\"x86_64\"\nOS_NAME=\"Linux\"\n"
                : "IMPLEMENTOR=\"Other\"\nOS_NAME=\"Linux\"\n";
        Files.writeString(root.resolve("jre_linux/release"), release);
        return root;
    }

    private static LaunchTarget target(Path root) {
        return new LaunchTarget(
                root, root.resolve("starsector.sh"), root,
                java.util.List.of(root.resolve("starsector.sh").toString()),
                "shell-script", 1, "test");
    }
}
