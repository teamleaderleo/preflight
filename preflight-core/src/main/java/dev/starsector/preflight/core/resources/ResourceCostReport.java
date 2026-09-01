package dev.starsector.preflight.core.resources;

import dev.starsector.preflight.core.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Root resource cost inspection report adhering to schema starsector-preflight-resource-cost-v1.
 */
public record ResourceCostReport(
        String format,
        String generatedAt,
        String installRoot,
        String profileFingerprint,
        double scanDurationMs,
        ResourceCostSummary summary,
        List<ModResourceCost> mods,
        LargestAllocations largestAllocations,
        List<String> diagnostics) {

    public static final String FORMAT_VERSION = "starsector-preflight-resource-cost-v1";

    public ResourceCostReport {
        mods = List.copyOf(mods);
        diagnostics = List.copyOf(diagnostics);
    }

    public String toJson() {
        return Json.object(toMap());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("format", format);
        map.put("generatedAt", generatedAt);
        map.put("installRoot", installRoot);
        map.put("profileFingerprint", profileFingerprint);
        map.put("scanDurationMs", scanDurationMs);
        map.put("summary", summary.toMap());
        map.put("mods", mods.stream().map(ModResourceCost::toMap).toList());
        map.put("largestAllocations", largestAllocations.toMap());
        map.put("diagnostics", diagnostics);
        return map;
    }
}
