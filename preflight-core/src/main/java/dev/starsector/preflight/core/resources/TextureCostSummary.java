package dev.starsector.preflight.core.resources;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Summary of texture VRAM allocation metrics.
 */
public record TextureCostSummary(
        long textureCount,
        long diskBytes,
        long decodedBaseBytes,
        long residentGpuBytes,
        long paddingWasteBytes,
        long mipChainUpperBoundBytes) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("textureCount", textureCount);
        map.put("diskBytes", diskBytes);
        map.put("decodedBaseBytes", decodedBaseBytes);
        map.put("residentGpuBytes", residentGpuBytes);
        map.put("paddingWasteBytes", paddingWasteBytes);
        map.put("mipChainUpperBoundBytes", mipChainUpperBoundBytes);
        return map;
    }
}
