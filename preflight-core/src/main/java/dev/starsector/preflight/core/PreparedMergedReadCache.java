package dev.starsector.preflight.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Merged reads the game already performed, stored as tagged trees under the exact profile that
 * produced them.
 *
 * <p>Unlike the five per-loader caches, this one is not about any loader. It keys on the request --
 * which path was merged, and with which arguments -- because the two methods it serves are the two
 * every merged read in the game funnels through, whoever the caller is. That is what lets it answer
 * for the loaders nobody has pinned and for whatever a mod's callback decides to read.
 *
 * <p>A key names a request, not a file. {@link MergedReadKey} builds it, and the only thing this
 * record insists on is that keys are well formed and values decode as trees, so an artifact that has
 * been truncated or edited fails to load rather than serving something the game did not ask for.
 */
public record PreparedMergedReadCache(String profileIdentitySha256, Map<String, byte[]> entries) {
    public static final int FORMAT_VERSION = 1;

    public PreparedMergedReadCache {
        Hashes.decodeSha256(profileIdentitySha256);
        Map<String, byte[]> copy = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> item : entries.entrySet()) {
            String key = item.getKey();
            if (!MergedReadKey.wellFormed(key)) {
                throw new IllegalArgumentException("Not a merged read cache key: " + key);
            }
            byte[] value = item.getValue();
            if (value == null || value.length == 0) {
                throw new IllegalArgumentException("Merged read cache value is empty: " + key);
            }
            copy.put(key, value.clone());
        }
        entries = Map.copyOf(copy);
    }
}
