package dev.starsector.preflight.core.resources;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Breakdown of resource costs attributed to a single mod or core.
 */
public record ModResourceCost(
        String id,
        String name,
        String version,
        int order,
        boolean enabled,
        long totalDiskBytes,
        long estimatedMemoryBytes,
        ModTextureCost texture,
        ModAudioCost audio,
        ModBytecodeCost bytecode,
        ModPreparedCost preparedData,
        ModShadowedCost shadowedByOverrides) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("version", version);
        map.put("order", order);
        map.put("enabled", enabled);
        map.put("totalDiskBytes", totalDiskBytes);
        map.put("estimatedMemoryBytes", estimatedMemoryBytes);
        map.put("texture", texture.toMap());
        map.put("audio", audio.toMap());
        map.put("bytecode", bytecode.toMap());
        map.put("preparedData", preparedData.toMap());
        if (shadowedByOverrides != null) {
            map.put("shadowedByOverrides", shadowedByOverrides.toMap());
        }
        return map;
    }

    public record ModTextureCost(
            int count,
            long diskBytes,
            long decodedBytes,
            long residentBytes,
            long paddingWasteBytes,
            int unmeasuredCount) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("count", count);
            map.put("diskBytes", diskBytes);
            map.put("decodedBytes", decodedBytes);
            map.put("residentBytes", residentBytes);
            map.put("paddingWasteBytes", paddingWasteBytes);
            map.put("unmeasuredCount", unmeasuredCount);
            return map;
        }
    }

    public record ModAudioCost(
            int count,
            long diskBytes,
            long effectPcmBytes,
            long musicBytes,
            long unreferencedBytes) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("count", count);
            map.put("diskBytes", diskBytes);
            map.put("effectPcmBytes", effectPcmBytes);
            map.put("musicBytes", musicBytes);
            map.put("unreferencedBytes", unreferencedBytes);
            return map;
        }
    }

    public record ModBytecodeCost(
            int jarCount,
            long diskBytes,
            long uncompressedBytecodeBytes,
            int classCount,
            int duplicateClassCount) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("jarCount", jarCount);
            map.put("diskBytes", diskBytes);
            map.put("uncompressedBytecodeBytes", uncompressedBytecodeBytes);
            map.put("classCount", classCount);
            map.put("duplicateClassCount", duplicateClassCount);
            return map;
        }
    }

    public record ModPreparedCost(
            long textureCacheBytes,
            long audioCacheBytes,
            long specCacheBytes) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("textureCacheBytes", textureCacheBytes);
            map.put("audioCacheBytes", audioCacheBytes);
            map.put("specCacheBytes", specCacheBytes);
            return map;
        }
    }

    public record ModShadowedCost(
            int texturesOverridden,
            long vramShadowedBytes) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("texturesOverridden", texturesOverridden);
            map.put("vramShadowedBytes", vramShadowedBytes);
            return map;
        }
    }
}
