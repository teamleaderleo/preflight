package dev.starsector.preflight.core.resources;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Summary of Preflight acceleration caches footprint.
 */
public record PreparedDataCostSummary(
        long preparedTextureBytes,
        long preparedAudioBytes,
        long janinoBytecodeBytes,
        long specCacheBytes) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("preparedTextureBytes", preparedTextureBytes);
        map.put("preparedAudioBytes", preparedAudioBytes);
        map.put("janinoBytecodeBytes", janinoBytecodeBytes);
        map.put("specCacheBytes", specCacheBytes);
        return map;
    }
}
