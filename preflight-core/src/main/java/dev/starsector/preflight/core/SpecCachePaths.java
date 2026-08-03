package dev.starsector.preflight.core;

/**
 * The key shape the merged-JSON spec caches store, which is a logical path or one marked absolute.
 *
 * <p>A spec loader is given some paths relatively and some absolutely, and the two are not the same
 * request: reading {@code data/variants/wolf.variant} merges every root that provides it, while
 * reading {@code <install>/data/variants/wolf.variant} names one file. The caches keep both, under
 * keys that cannot be confused, and this is the one place that says what such a key looks like so
 * the writer and the reader cannot drift apart.
 *
 * <p>The install prefix is dropped from an absolute key so an artifact survives the install being
 * moved; what remains is the logical tail with {@link #ABSOLUTE_PREFIX} in front of it.
 */
public final class SpecCachePaths {
    /** Marks a key that named one file rather than a merge across roots. */
    public static final String ABSOLUTE_PREFIX = "abs:";

    private SpecCachePaths() {
    }

    /**
     * Normalizes a stored key, keeping the absolute marker if it carries one.
     *
     * @throws IllegalArgumentException if the path is not a usable logical path
     */
    public static String normalizeKey(String rawKey) {
        if (rawKey != null && rawKey.startsWith(ABSOLUTE_PREFIX)) {
            return ABSOLUTE_PREFIX
                    + ResourceIndex.normalizeLogicalPath(rawKey.substring(ABSOLUTE_PREFIX.length()));
        }
        return ResourceIndex.normalizeLogicalPath(rawKey);
    }

    /** The logical path a key names, with the absolute marker removed if it had one. */
    public static String logicalPath(String key) {
        return key.startsWith(ABSOLUTE_PREFIX) ? key.substring(ABSOLUTE_PREFIX.length()) : key;
    }
}
