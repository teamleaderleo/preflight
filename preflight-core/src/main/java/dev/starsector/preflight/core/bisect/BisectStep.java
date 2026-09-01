package dev.starsector.preflight.core.bisect;

import dev.starsector.preflight.core.Json;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Historical record of an executed partition test step in a bisect session.
 */
public record BisectStep(
        int step,
        Instant timestamp,
        List<String> testedSubset,
        String verdict,
        String notes
) {
    public BisectStep {
        testedSubset = testedSubset == null ? List.of() : List.copyOf(testedSubset);
        verdict = verdict == null ? "" : verdict;
        notes = notes == null ? "" : notes;
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("step", step);
        map.put("timestamp", timestamp.toString());
        map.put("testedSubset", new ArrayList<>(testedSubset));
        map.put("verdict", verdict);
        map.put("notes", notes);
        return map;
    }

    public String toJson() {
        return Json.object(toMap());
    }

    @SuppressWarnings("unchecked")
    public static BisectStep fromMap(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        Number stepNum = map.get("step") instanceof Number n ? n : 0;
        String tsStr = map.get("timestamp") instanceof String s ? s : null;
        Instant ts = tsStr != null ? Instant.parse(tsStr) : Instant.now();
        List<String> tested = new ArrayList<>();
        if (map.get("testedSubset") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s) {
                    tested.add(s);
                }
            }
        }
        String verdict = map.get("verdict") instanceof String s ? s : "";
        String notes = map.get("notes") instanceof String s ? s : "";
        return new BisectStep(stepNum.intValue(), ts, tested, verdict, notes);
    }
}
