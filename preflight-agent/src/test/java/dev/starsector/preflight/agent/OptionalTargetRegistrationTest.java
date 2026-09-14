package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OptionalTargetRegistrationTest {
    @TempDir Path directory;

    @Test
    void registersCombatInputOnlyForAutomation() {
        String previous = System.getProperty("preflight.desktopSmoke");
        try {
            for (boolean enabled : new boolean[] {false, true}) {
                System.setProperty("preflight.desktopSmoke", Boolean.toString(enabled));
                InternalGameControlRuntime.beginSession(directory.resolve("adapter.json"));
                var targets = AdapterTargetRegistry.empty()
                        .withTextureTarget(TextureAdapterMode.PREPARED_PIXELS).targets();
                assertEquals(enabled, targets.stream().anyMatch(target ->
                        target.internalClassName().equals(CombatRuntimeIntegrityPlan.COMBAT_STATE_CLASS)));
                assertEquals(true, targets.stream().anyMatch(target ->
                        target.planId().equals(CombatRuntimeIntegrityRuntime.PLAN_ID)));
            }
        } finally {
            restore("preflight.desktopSmoke", previous);
            InternalGameControlRuntime.beginSession(directory.resolve("adapter.json"));
        }
    }

    @Test
    void registersWindowsScriptProgressOnlyOnWindows() {
        String previousOs = System.getProperty("os.name");
        String previousProgress = System.getProperty(AssetProgressLogRuntime.PROPERTY);
        try {
            System.setProperty(AssetProgressLogRuntime.PROPERTY, "off");
            for (String os : new String[] {"Mac OS X", "Linux", "Windows 11"}) {
                System.setProperty("os.name", os);
                var targets = AdapterTargetRegistry.empty()
                        .withTextureTarget(TextureAdapterMode.PREPARED_PIXELS).targets();
                assertEquals(os.startsWith("Windows"), targets.stream().anyMatch(target ->
                        target.id().equals("vanilla-script-progress-windows-0.98a-rc8")));
            }
        } finally {
            restore("os.name", previousOs);
            restore(AssetProgressLogRuntime.PROPERTY, previousProgress);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }
}
