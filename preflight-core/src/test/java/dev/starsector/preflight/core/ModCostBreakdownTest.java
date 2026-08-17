package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModCostBreakdownTest {

    @Test
    void serializesPublicMapWithoutExposingPhysicalPaths() {
        ModCostBreakdown.ClassCounts classes = new ModCostBreakdown.ClassCounts(
                120, 10_000_000L,
                15, 5_000_000L,
                40, 200_000L,
                2, 3_000_000L,
                0, 0L,
                5, 50_000L);
        ModCostBreakdown.OverlapCounts overlaps = new ModCostBreakdown.OverlapCounts(4, 8, 2);
        ModCostBreakdown.ModFootprint mod = new ModCostBreakdown.ModFootprint(
                "mod_alpha",
                "Alpha Mod",
                "1.2.0",
                18_250_000L,
                182,
                classes,
                32_000_000L,
                6_500_000L,
                overlaps,
                4096L);

        ModCostBreakdown.Report report = new ModCostBreakdown.Report(
                ModCostBreakdown.Report.FORMAT,
                "profile_abc123",
                18_250_000L,
                182,
                32_000_000L,
                6_500_000L,
                List.of(mod));

        Map<String, Object> publicMap = report.toPublicMap();
        assertEquals("preflight-mod-cost-breakdown-v1", publicMap.get("format"));
        assertEquals("profile_abc123", publicMap.get("profileFingerprint"));
        assertEquals(18_250_000L, publicMap.get("totalInstalledBytes"));
        assertEquals(182, publicMap.get("totalFiles"));
        assertEquals(32_000_000L, publicMap.get("totalUncompressedTextureGpuBytes"));
        assertEquals(6_500_000L, publicMap.get("totalPreparedCacheBytes"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mods = (List<Map<String, Object>>) publicMap.get("mods");
        assertEquals(1, mods.size());
        Map<String, Object> modMap = mods.get(0);
        assertEquals("mod_alpha", modMap.get("modId"));
        assertEquals("Alpha Mod", modMap.get("displayName"));
        assertEquals("1.2.0", modMap.get("version"));
        assertEquals(4096L, modMap.get("declaredMemoryMb"));

        // Verify physical paths are absent from the entire serialized output
        String serialized = Json.object(publicMap);
        assertFalse(serialized.contains("/Users/"));
        assertFalse(serialized.contains("C:\\"));
        assertFalse(serialized.contains("directory"));
        assertFalse(serialized.contains("path"));
    }

    @Test
    void rejectsNegativeValues() {
        ModCostBreakdown.ClassCounts classes = new ModCostBreakdown.ClassCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        ModCostBreakdown.OverlapCounts overlaps = new ModCostBreakdown.OverlapCounts(0, 0, 0);

        assertThrows(IllegalArgumentException.class, () -> new ModCostBreakdown.ModFootprint(
                "mod_x", null, null, -1L, 0, classes, 0L, 0L, overlaps, null));
        assertThrows(IllegalArgumentException.class, () -> new ModCostBreakdown.ModFootprint(
                "mod_x", null, null, 0L, -1, classes, 0L, 0L, overlaps, null));
        assertThrows(IllegalArgumentException.class, () -> new ModCostBreakdown.ModFootprint(
                "mod_x", null, null, 0L, 0, classes, -1L, 0L, overlaps, null));
        assertThrows(IllegalArgumentException.class, () -> new ModCostBreakdown.ModFootprint(
                "mod_x", null, null, 0L, 0, classes, 0L, -1L, overlaps, null));
    }
}
