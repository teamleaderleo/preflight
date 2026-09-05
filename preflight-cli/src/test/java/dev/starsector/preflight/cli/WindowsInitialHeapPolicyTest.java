package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowsInitialHeapPolicyTest {
    @TempDir Path root;

    @Test void knownIdentityChangesOnlyInitialHeapInChildOptions() throws Exception {
        LaunchTarget target = fixture();
        var resolution = resolve(target, Map.of(), WindowsInitialHeapPolicyTest::knownHash);
        assertTrue(resolution.active(), resolution.reason());
        assertEquals("-DlaunchDirect=true -Xms2048m", WindowsInitialHeapPolicy.appendOptions(
                "-DlaunchDirect=true", resolution));
        assertEquals("-Xms2048m", WindowsInitialHeapPolicy.appendOptions(null, resolution));
        assertEquals("fixture", Files.readString(target.launcher()));
        assertEquals(true, resolution.toReportValues().get("preservesMaximum"));
    }

    @Test void defaultOtherPlatformsPresetsAndExplicitHeapOptionsRemainUnchanged() throws Exception {
        LaunchTarget target = fixture();
        var off = WindowsInitialHeapPolicy.resolve(Platform.WINDOWS, target,
                OptimizationPreset.RECOMMENDED, Map.of(), false, path -> { fail("read while disabled"); return ""; });
        assertFalse(off.active());
        assertNull(WindowsInitialHeapPolicy.appendOptions(null, off));
        for (Platform platform : List.of(Platform.LINUX, Platform.MAC)) {
            assertFalse(WindowsInitialHeapPolicy.resolve(platform, target,
                    OptimizationPreset.RECOMMENDED, Map.of(), true, WindowsInitialHeapPolicyTest::knownHash).active());
        }
        assertFalse(WindowsInitialHeapPolicy.resolve(Platform.WINDOWS, target,
                OptimizationPreset.CONSERVATIVE, Map.of(), true, WindowsInitialHeapPolicyTest::knownHash).active());
        for (String key : List.of("_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS")) {
            for (String option : List.of("-Xms3g", "-Xmx4g", "-XX:InitialHeapSize=4096m", "-XX:MaxHeapSize=4g", "\"-Xms3g\"", "'-Xmx4g'")) {
                assertFalse(resolve(target, Map.of(key, "-Dfoo=bar " + option), WindowsInitialHeapPolicyTest::knownHash).active());
            }
        }
    }

    @Test void changedMissingOrUnknownFilesDecline() throws Exception {
        LaunchTarget target = fixture();
        for (String changed : List.of("starsector.bat", "java.exe", "Play-Starsector-VM.cmd")) {
            assertFalse(resolve(target, Map.of(), path -> path.getFileName().toString().equals(changed)
                    ? "different" : knownHash(path)).active());
        }
        assertFalse(resolve(target, Map.of(), path -> { throw new java.io.IOException("unreadable"); }).active());
        Files.delete(root.resolve("jre/bin/java.exe"));
        assertFalse(resolve(target, Map.of(), WindowsInitialHeapPolicyTest::knownHash).active());
    }

    private static WindowsInitialHeapPolicy.Resolution resolve(LaunchTarget target,
            Map<String, String> environment, WindowsInitialHeapPolicy.FileHash hash) {
        return WindowsInitialHeapPolicy.resolve(Platform.WINDOWS, target,
                OptimizationPreset.RECOMMENDED, environment, true, hash);
    }

    private static String knownHash(Path path) {
        return switch (path.getFileName().toString()) {
            case "starsector.bat" -> WindowsInitialHeapPolicy.BATCH_SHA;
            case "java.exe" -> WindowsInitialHeapPolicy.JAVA_SHA;
            case "Play-Starsector-VM.cmd" -> WindowsInitialHeapPolicy.WRAPPER_SHA;
            default -> "unknown";
        };
    }

    private LaunchTarget fixture() throws Exception {
        Files.createDirectories(root.resolve("starsector-core"));
        Files.createDirectories(root.resolve("jre/bin"));
        Files.writeString(root.resolve("starsector-core/starsector.bat"), "fixture");
        Files.writeString(root.resolve("jre/bin/java.exe"), "fixture");
        Path launcher = root.resolve("Play-Starsector-VM.cmd");
        Files.writeString(launcher, "fixture");
        return new LaunchTarget(root, launcher, root, List.of(launcher.toString()), "windows-script", 1, "test");
    }
}
