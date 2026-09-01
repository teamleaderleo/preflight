package dev.starsector.preflight.core.resources;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Summary of mod bytecode and class count metrics.
 */
public record BytecodeCostSummary(
        long jarCount,
        long diskBytes,
        long uncompressedBytecodeBytes,
        long classCount,
        long duplicateClasses) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("jarCount", jarCount);
        map.put("diskBytes", diskBytes);
        map.put("uncompressedBytecodeBytes", uncompressedBytecodeBytes);
        map.put("classCount", classCount);
        map.put("duplicateClasses", duplicateClasses);
        return map;
    }
}
