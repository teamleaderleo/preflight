package dev.starsector.preflight.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/** Exact-profile results of Starsector's faction priority-table walks. */
public record PreparedFactionPriorityCache(
        String profileIdentitySha256,
        Map<String, List<String>> entries) {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_ENTRIES = 64;
    public static final int MAX_TOTAL_IDS = 1_000_000;
    public static final int MAX_STRING_BYTES = 16 * 1024;

    public PreparedFactionPriorityCache {
        Hashes.decodeSha256(profileIdentitySha256);
        profileIdentitySha256 = profileIdentitySha256.toLowerCase(Locale.ROOT);
        if (entries == null || entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Prepared faction-priority entries are invalid");
        }
        int totalIds = 0;
        Map<String, List<String>> ordered = new LinkedHashMap<>();
        for (String key : new TreeSet<>(entries.keySet())) {
            requireString(key, "key");
            List<String> ids = entries.get(key);
            if (ids == null) {
                throw new IllegalArgumentException("Prepared faction-priority ids are missing");
            }
            totalIds = Math.addExact(totalIds, ids.size());
            if (totalIds > MAX_TOTAL_IDS) {
                throw new IllegalArgumentException("Prepared faction-priority cache has too many ids");
            }
            List<String> copy = List.copyOf(ids);
            for (String id : copy) requireString(id, "id");
            ordered.put(key, copy);
        }
        entries = Map.copyOf(ordered);
    }

    private static void requireString(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Prepared faction-priority " + label + " is empty");
        }
        if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException(
                    "Prepared faction-priority " + label + " exceeds its safety limit");
        }
    }
}
