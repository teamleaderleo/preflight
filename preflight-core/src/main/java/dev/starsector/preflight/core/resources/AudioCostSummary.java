package dev.starsector.preflight.core.resources;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Summary of audio PCM memory and disk footprint metrics.
 */
public record AudioCostSummary(
        long soundCount,
        long diskBytes,
        long effectPcmBytes,
        long effectCount,
        long musicDiskBytes,
        long musicCount,
        long unreferencedCount,
        long unreferencedDiskBytes) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("soundCount", soundCount);
        map.put("diskBytes", diskBytes);
        map.put("effectPcmBytes", effectPcmBytes);
        map.put("effectCount", effectCount);
        map.put("musicDiskBytes", musicDiskBytes);
        map.put("musicCount", musicCount);
        map.put("unreferencedCount", unreferencedCount);
        map.put("unreferencedDiskBytes", unreferencedDiskBytes);
        return map;
    }
}
