package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CombatJvmSafeguardTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void nonMacAndExplicitDisableRemainInactive() {
        LaunchTarget target = target(temporaryDirectory.resolve("missing"));
        assertFalse(CombatJvmSafeguard.resolve(Platform.LINUX, target, Map.of()).active());
        assertFalse(CombatJvmSafeguard.resolve(
                Platform.MAC, target,
                Map.of(CombatJvmSafeguard.DISABLE_ENVIRONMENT, "true")).active());
    }

    @Test
    void incompleteLauncherFingerprintFailsClosed() throws Exception {
        Path app = fixture(false);
        CombatJvmSafeguard.Resolution result = CombatJvmSafeguard.resolve(
                Platform.MAC, target(app), Map.of());

        assertFalse(result.active());
        assertTrue(result.reason().contains("launcher"), result.reason());
    }

    @Test
    void reviewedStructureReachesExactClassGateAndOptionsAreIdempotent() throws Exception {
        Path app = fixture(true);
        CombatJvmSafeguard.Resolution result = CombatJvmSafeguard.resolve(
                Platform.MAC, target(app), Map.of());

        // The synthetic class bytes deliberately do not equal the production fingerprint. This
        // proves every structural gate is crossed before the final exact-class gate fails closed.
        assertFalse(result.active());
        assertEquals("combat class differs from the reviewed build", result.reason());
        assertTrue(result.shipClassSha256() != null && !result.shipClassSha256().isBlank());

        var active = new CombatJvmSafeguard.Resolution(
                true, "test", result.gameJar(), CombatJvmSafeguard.REVIEWED_SHIP_SHA256);
        String options = CombatJvmSafeguard.appendOptions("-Dexisting=true", active);
        assertTrue(options.contains(CombatJvmSafeguard.COMPILE_EXCLUSION));
        assertTrue(options.contains(CombatJvmSafeguard.MODE_PROPERTY));
        assertEquals(options, CombatJvmSafeguard.appendOptions(options, active));

        assertNull(CombatJvmSafeguard.appendOptions(null,
                CombatJvmSafeguard.Resolution.inactive("test")));
    }

    @Test
    void existingDiagnosticModeIsPreserved() {
        var active = new CombatJvmSafeguard.Resolution(true, "test", null, null);
        String existing = CombatJvmSafeguard.COMPILE_EXCLUSION
                + " -Dpreflight.combatIntegrity.jvmMode=manual-test";
        assertEquals(existing, CombatJvmSafeguard.appendOptions(existing, active));
    }

    private Path fixture(boolean riskyLauncher) throws IOException {
        Path app = temporaryDirectory.resolve("Starsector.app");
        Path mac = Files.createDirectories(app.resolve("Contents/MacOS"));
        Path java = Files.createDirectories(app.resolve("Contents/Resources/Java"));
        Files.createDirectories(app.resolve("Contents/Home"));
        Files.writeString(mac.resolve("starsector_mac.sh"), riskyLauncher
                ? String.join(" ",
                        "-XX:+UnlockExperimentalVMOptions",
                        "-XX:+AlwaysCompileLoopMethods",
                        "-XX:TieredStopAtLevel=4",
                        "-XX:UseAVX=3",
                        "-XX:CompilerDirectivesFile=../../MacOS/compiler_directives.txt")
                : "java com.fs.starfarer.StarfarerLauncher");
        Files.writeString(app.resolve("Contents/Home/release"), String.join("\n",
                "IMPLEMENTOR=\"Azul Systems, Inc.\"",
                "JAVA_RUNTIME_VERSION=\"17.0.10+7-LTS\"",
                "OS_ARCH=\"x86_64\"",
                "OS_NAME=\"Darwin\""));
        Files.writeString(mac.resolve("compiler_directives.txt"), """
                [{ match: ["com/fs/starfarer/combat/*.*"],
                   c1: { Enable: true, Exclude: false, },
                   c2: { Enable: true, Exclude: true, }, }]
                """);
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(java.resolve("starfarer_obf.jar")))) {
            zip.putNextEntry(new ZipEntry(CombatJvmSafeguard.SHIP_CLASS));
            zip.write(new byte[] {0x01, 0x23, 0x45});
            zip.closeEntry();
        }
        return app;
    }

    private static LaunchTarget target(Path app) {
        Path launcher = app.resolve("Contents/MacOS/starsector_mac.sh");
        return new LaunchTarget(app, launcher, launcher.getParent(),
                java.util.List.of(launcher.toString()), "shell-script", 1, "test");
    }
}
