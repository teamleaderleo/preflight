package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DirectLaunchSettingsTest {
    private static DirectLaunchSettings.Lookup store(String... pairs) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put(pairs[i], pairs[i + 1]);
        }
        return values::get;
    }

    @Test
    void carriesTheSettingsAClickedLaunchWouldHaveUsed() {
        DirectLaunchSettings.Availability availability = DirectLaunchSettings.resolve(
                store("serial", "REDACTED", "resolution", "1440x932", "fullscreen", "false"));

        assertTrue(availability.available(), availability.reason());
        assertNull(availability.reason());
        DirectLaunchSettings settings = availability.settings();
        assertEquals("1440x932", settings.resolution());
        assertEquals("1440", settings.width());
        assertEquals("932", settings.height());
        assertFalse(settings.fullscreen());
        assertEquals(
                java.util.List.of(
                        "-DlaunchDirect=true",
                        "-DstartRes=1440x932",
                        "-DstartFS=false",
                        "-DstartSound=true"),
                settings.javaOptions());
    }

    @Test
    void soundIsOnWhenTheLauncherHasNeverBeenToldOtherwise() {
        // The property reaches the game through Boolean.parseBoolean, where absent reads as false.
        // Letting it fall through would launch the game muted, which skips the audio subsystem
        // entirely -- a different measurement, and the loss of the exact signal the operator uses to
        // decide the game finished loading.
        DirectLaunchSettings settings = DirectLaunchSettings
                .resolve(store("serial", "REDACTED", "resolution", "1920x1080"))
                .settings();
        assertTrue(settings.sound());
        assertTrue(settings.javaOptions().contains("-DstartSound=true"));

        DirectLaunchSettings muted = DirectLaunchSettings
                .resolve(store("serial", "REDACTED", "resolution", "1920x1080", "sound", "false"))
                .settings();
        assertFalse(muted.sound());
        assertTrue(muted.javaOptions().contains("-DstartSound=false"));
    }

    @Test
    void fullscreenFollowsTheSavedSettingInBothDirections() {
        assertTrue(DirectLaunchSettings
                .resolve(store("serial", "S", "resolution", "1920x1080", "fullscreen", "true"))
                .settings()
                .fullscreen());
        assertFalse(DirectLaunchSettings
                .resolve(store("serial", "S", "resolution", "1920x1080", "fullscreen", "false"))
                .settings()
                .fullscreen());
        assertFalse(DirectLaunchSettings
                .resolve(store("serial", "S", "resolution", "1920x1080"))
                .settings()
                .fullscreen());
    }

    @Test
    void refusesAnUnregisteredCopyBecauseTheGameWouldExitWithoutLaunching() {
        // Starsector checks its serial before honouring launchDirect and returns from the
        // constructor when it fails -- no UI, no game, no log line. A harness would read that as a
        // launch that timed out. Naming the real cause is the difference between a fixable message
        // and ten minutes of waiting.
        DirectLaunchSettings.Availability availability =
                DirectLaunchSettings.resolve(store("resolution", "1440x932"));

        assertFalse(availability.available());
        assertNull(availability.settings());
        assertNotNull(availability.reason());
        assertTrue(availability.reason().contains("serial"), availability.reason());
    }

    @Test
    void refusesWhenNoResolutionHasEverBeenSaved() {
        DirectLaunchSettings.Availability availability =
                DirectLaunchSettings.resolve(store("serial", "S"));

        assertFalse(availability.available());
        assertNull(availability.settings());
        assertTrue(availability.reason().contains("resolution"), availability.reason());
    }

    @Test
    void refusesAResolutionTheGameWouldSplitIntoSomethingUnusable() {
        // The game does startRes.split("x")[1] with no validation, inside a try whose handler is a
        // modal native dialog. Every one of these would hang a launch rather than fail it, so they
        // have to be refused here, where refusing costs nothing.
        for (String malformed : new String[] {
                "1920", "x1080", "1920x", "1920x1080x60", "widthxheight", "0x1080", "1920x0",
                "-1920x1080", "1920 x 1080", " ", "99999999999x1080"}) {
            DirectLaunchSettings.Availability availability =
                    DirectLaunchSettings.resolve(store("serial", "S", "resolution", malformed));
            assertFalse(availability.available(), "should have refused: " + malformed);
            assertNull(availability.settings(), malformed);
            assertTrue(availability.reason().contains(malformed.trim()) || malformed.isBlank(),
                    availability.reason());
        }
    }

    @Test
    void anEmptyStoreIsRefusedRatherThanDefaulted() {
        // A machine that has never run the game has no preferences at all. Defaulting to some
        // common resolution would produce a launch that succeeds while measuring a configuration
        // nobody chose.
        DirectLaunchSettings.Availability availability =
                DirectLaunchSettings.resolve(new HashMap<String, String>()::get);

        assertFalse(availability.available());
        assertNull(availability.settings());
    }

    @Test
    void theReportedDocumentSaysWhyWhenItSaysNo() {
        Map<String, Object> refused = LaunchSettingsCommand.describe(
                DirectLaunchSettings.resolve(store("serial", "S")));
        assertEquals(false, refused.get("directLaunchAvailable"));
        assertNull(refused.get("settings"));
        assertNotNull(refused.get("reason"));

        Map<String, Object> allowed = LaunchSettingsCommand.describe(
                DirectLaunchSettings.resolve(store("serial", "S", "resolution", "800x600")));
        assertEquals(true, allowed.get("directLaunchAvailable"));
        assertNull(allowed.get("reason"));
        assertNotNull(allowed.get("settings"));
    }
}
