package dev.starsector.preflight.core;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Immutable tagged JSON trees for vanilla's live projectile-spec constructors. */
public record PreparedProjectileJsonCache(
        String profileIdentitySha256,
        Map<String, byte[]> entries) {
    public static final int FORMAT_VERSION = 2;

    public PreparedProjectileJsonCache {
        Hashes.decodeSha256(profileIdentitySha256);
        profileIdentitySha256 = profileIdentitySha256.toLowerCase(java.util.Locale.ROOT);
        TreeMap<String, byte[]> copy = new TreeMap<>();
        for (Map.Entry<String, byte[]> item : entries.entrySet()) {
            String path = SpecCachePaths.normalizeKey(item.getKey());
            String logical = SpecCachePaths.logicalPath(path);
            boolean weapon = logical.startsWith("data/weapons/proj/");
            boolean shipSystem = logical.startsWith("data/shipsystems/proj/");
            if ((!weapon && !shipSystem) || !logical.endsWith(".proj")) {
                throw new IllegalArgumentException("Not a projectile definition path: " + path);
            }
            byte[] tree = item.getValue();
            if (tree == null || tree.length == 0) {
                throw new IllegalArgumentException("Prepared projectile JSON tree is empty: " + path);
            }
            if (copy.put(path, tree.clone()) != null) {
                throw new IllegalArgumentException("Duplicate prepared projectile path: " + path);
            }
        }
        entries = Collections.unmodifiableNavigableMap(copy);
    }
}
