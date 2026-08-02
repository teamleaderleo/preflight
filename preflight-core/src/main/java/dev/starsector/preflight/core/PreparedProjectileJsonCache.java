package dev.starsector.preflight.core;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Immutable merged-JSON inputs for vanilla's live projectile-spec constructors. */
public record PreparedProjectileJsonCache(
        String profileIdentitySha256,
        Map<String, String> entries) {
    public static final int FORMAT_VERSION = 1;

    public PreparedProjectileJsonCache {
        Hashes.decodeSha256(profileIdentitySha256);
        profileIdentitySha256 = profileIdentitySha256.toLowerCase(java.util.Locale.ROOT);
        TreeMap<String, String> copy = new TreeMap<>();
        for (Map.Entry<String, String> item : entries.entrySet()) {
            String path = ResourceIndex.normalizeLogicalPath(item.getKey());
            boolean weapon = path.startsWith("data/weapons/proj/");
            boolean shipSystem = path.startsWith("data/shipsystems/proj/");
            if ((!weapon && !shipSystem) || !path.endsWith(".proj")) {
                throw new IllegalArgumentException("Not a projectile definition path: " + path);
            }
            String json = item.getValue();
            if (json == null || json.isBlank()) {
                throw new IllegalArgumentException("Prepared projectile JSON is empty: " + path);
            }
            if (copy.put(path, json) != null) {
                throw new IllegalArgumentException("Duplicate prepared projectile path: " + path);
            }
        }
        entries = Collections.unmodifiableNavigableMap(copy);
    }
}
