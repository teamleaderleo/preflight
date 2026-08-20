package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GameConfigurationAuthorityTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void projectsPreferenceAndHeapAuthorityWithoutAbsolutePathsOrSerial() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put(GameLaunchPreferences.RESOLUTION, "1920x1080");
        raw.put(GameLaunchPreferences.FULLSCREEN, "true");
        raw.put(GameLaunchPreferences.AA_SAMPLES, "4");
        raw.put(GameLaunchPreferences.SCREEN_SCALE, "1.25");
        raw.put(GameLaunchPreferences.GAMEPLAY_SETTINGS, "{\"battleSize\":400}");
        GameLaunchPreferences.Generation generation = new GameLaunchPreferences.Generation(raw);
        Path heapSource = temporaryDirectory.resolve("starsector-core/starsector.vmparams");
        JvmMemorySettings.Snapshot memory = new JvmMemorySettings.Snapshot(
                true, true, 6144, 4096, heapSource, "VM-parameter file", null, List.of());

        GameConfigurationAuthority.Result result =
                GameConfigurationAuthority.project(temporaryDirectory, generation, memory);

        assertEquals("starsector-configuration-authority-v1", result.values().get("evidenceKind"));
        assertEquals("/com/fs/starfarer", result.values().get("preferenceNode"));
        assertTrue((Boolean) result.values().get("memoryAuthorityResolved"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> settings = (List<Map<String, Object>>) result.values().get("settings");
        Map<String, Object> resolution = setting(settings, "resolution");
        assertEquals("1920x1080", resolution.get("effectiveValue"));
        assertEquals("java-preferences", resolution.get("authority"));
        assertEquals("resolution", resolution.get("rawKey"));
        assertTrue((Boolean) resolution.get("rawPresent"));

        Map<String, Object> sound = setting(settings, "sound");
        assertEquals(true, sound.get("effectiveValue"));
        assertFalse((Boolean) sound.get("rawPresent"));
        assertEquals("vanilla-default-true", sound.get("derivation"));

        Map<String, Object> battle = setting(settings, "battleSize");
        assertEquals(400, battle.get("effectiveValue"));
        assertEquals("gameplaySettings", battle.get("rawKey"));
        assertEquals("battleSize", battle.get("valuePath"));

        Map<String, Object> maxHeap = setting(settings, "maxHeapMiB");
        assertEquals(6144, maxHeap.get("effectiveValue"));
        assertEquals("launcher-heap-flags", maxHeap.get("authority"));
        assertEquals("starsector-core/starsector.vmparams", maxHeap.get("sourceRelativePath"));
        assertEquals("starsector.vmparams", maxHeap.get("sourceFileName"));
        assertTrue((Boolean) maxHeap.get("sourceInsideSelectedInstall"));

        String json = result.toJson();
        assertFalse(json.contains(temporaryDirectory.toAbsolutePath().toString()));
        assertFalse(json.contains("serial"));
    }

    @Test
    void outsideInstallHeapSourceFallsBackToFileNameWithoutLeakingItsParent() {
        GameLaunchPreferences.Generation generation = new GameLaunchPreferences.Generation(Map.of());
        Path outside = temporaryDirectory.getParent().resolve("private-secret/hidden.vmparams").toAbsolutePath();
        JvmMemorySettings.Snapshot memory = new JvmMemorySettings.Snapshot(
                true, false, 8192, null, outside, "launcher response file", null, List.of("diagnostic"));

        GameConfigurationAuthority.Result result =
                GameConfigurationAuthority.project(temporaryDirectory, generation, memory);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> settings = (List<Map<String, Object>>) result.values().get("settings");
        Map<String, Object> maxHeap = setting(settings, "maxHeapMiB");
        assertNull(maxHeap.get("sourceRelativePath"));
        assertEquals("hidden.vmparams", maxHeap.get("sourceFileName"));
        assertFalse((Boolean) maxHeap.get("sourceInsideSelectedInstall"));
        assertEquals(1, result.values().get("memoryDiagnosticCount"));
        assertFalse(result.toJson().contains(outside.getParent().toString()));
    }

    @Test
    void malformedSavedPreferencesRemainAttributedButProduceTypedDiagnostics() {
        GameLaunchPreferences.Generation generation = new GameLaunchPreferences.Generation(Map.of(
                GameLaunchPreferences.AA_SAMPLES, "many",
                GameLaunchPreferences.SCREEN_SCALE, "NaN",
                GameLaunchPreferences.GAMEPLAY_SETTINGS, "not-json"));
        JvmMemorySettings.Snapshot memory = JvmMemorySettings.Snapshot.unavailable("not available");

        GameConfigurationAuthority.Result result =
                GameConfigurationAuthority.project(temporaryDirectory, generation, memory);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> settings = (List<Map<String, Object>>) result.values().get("settings");
        assertNull(setting(settings, "antialiasingSamples").get("effectiveValue"));
        assertNull(setting(settings, "uiScale").get("effectiveValue"));
        assertNull(setting(settings, "battleSize").get("effectiveValue"));
        assertTrue((Boolean) setting(settings, "antialiasingSamples").get("rawPresent"));
        assertFalse((Boolean) result.values().get("memoryAuthorityResolved"));

        @SuppressWarnings("unchecked")
        List<String> diagnostics = (List<String>) result.values().get("launcherPreferenceDiagnostics");
        assertEquals(3, diagnostics.size());
        assertTrue(diagnostics.stream().allMatch(message -> !message.contains("many") && !message.contains("not-json")));
    }

    private static Map<String, Object> setting(List<Map<String, Object>> settings, String name) {
        return settings.stream()
                .filter(value -> name.equals(value.get("name")))
                .findFirst()
                .orElseThrow();
    }
}
