package dev.starsector.preflight.core.resources;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Top-level profile-wide resource and memory cost summary.
 */
public record ResourceCostSummary(
        int enabledModCount,
        long totalDiskBytes,
        long totalEstimatedMemoryBytes,
        TextureCostSummary textureVram,
        AudioCostSummary audioPcm,
        BytecodeCostSummary bytecode,
        PreparedDataCostSummary preparedData) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabledModCount", enabledModCount);
        map.put("totalDiskBytes", totalDiskBytes);
        map.put("totalEstimatedMemoryBytes", totalEstimatedMemoryBytes);
        map.put("textureVram", textureVram.toMap());
        map.put("audioPcm", audioPcm.toMap());
        map.put("bytecode", bytecode.toMap());
        map.put("preparedData", preparedData.toMap());
        return map;
    }
}
