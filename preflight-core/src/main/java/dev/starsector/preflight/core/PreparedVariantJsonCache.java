package dev.starsector.preflight.core;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Immutable merged-JSON inputs for vanilla's live ship-variant constructor. */
public record PreparedVariantJsonCache(
        String profileIdentitySha256,
        Map<String, String> entries) {
    public static final int FORMAT_VERSION = 1;

    public PreparedVariantJsonCache {
        Hashes.decodeSha256(profileIdentitySha256);
        profileIdentitySha256 = profileIdentitySha256.toLowerCase(java.util.Locale.ROOT);
        TreeMap<String, String> copy = new TreeMap<>();
        for (Map.Entry<String, String> item : entries.entrySet()) {
            String path = SpecCachePaths.normalizeKey(item.getKey());
            String logical = SpecCachePaths.logicalPath(path);
            if (!logical.startsWith("data/variants/") || !logical.endsWith(".variant")) {
                throw new IllegalArgumentException("Not a ship variant path: " + path);
            }
            String json = item.getValue();
            if (json == null || json.isBlank()) {
                throw new IllegalArgumentException("Prepared variant JSON is empty: " + path);
            }
            if (copy.put(path, json) != null) {
                throw new IllegalArgumentException("Duplicate prepared variant path: " + path);
            }
        }
        entries = Collections.unmodifiableNavigableMap(copy);
    }
}
