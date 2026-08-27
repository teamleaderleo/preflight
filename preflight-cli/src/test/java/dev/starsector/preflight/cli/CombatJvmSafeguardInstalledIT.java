package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Exact installed check for the reviewed macOS/Rosetta interpreter safeguard. */
class CombatJvmSafeguardInstalledIT {
    @Test
    void installedRiskFingerprintActivatesEveryReviewedExclusion() {
        String configured = System.getProperty("preflight.starsector.app", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.app=<Starsector.app>");
        Path app = Path.of(configured).toAbsolutePath().normalize();
        Path launcher = app.resolve("Contents/MacOS/starsector_mac.sh");
        Assumptions.assumeTrue(Files.isRegularFile(launcher));
        LaunchTarget target = new LaunchTarget(
                app,
                launcher,
                launcher.getParent(),
                java.util.List.of(launcher.toString()),
                "shell-script",
                1,
                "installed-test");

        CombatJvmSafeguard.Resolution resolution =
                CombatJvmSafeguard.resolve(Platform.MAC, target, Map.of());

        assertTrue(resolution.active(), resolution.reason());
        assertEquals(CombatJvmSafeguard.REVIEWED_SHIP_SHA256,
                resolution.shipClassSha256());
        assertEquals(CombatJvmSafeguard.REVIEWED_FLEET_ABILITY_RENDERER_SHA256,
                resolution.fleetAbilityRendererClassSha256());
        String options = CombatJvmSafeguard.appendOptions("", resolution);
        for (String exclusion : CombatJvmSafeguard.COMPILE_EXCLUSIONS) {
            assertTrue(options.contains(exclusion));
        }
    }
}
