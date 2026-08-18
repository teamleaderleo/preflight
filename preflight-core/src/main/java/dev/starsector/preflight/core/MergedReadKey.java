package dev.starsector.preflight.core;

import java.util.List;

/**
 * The name a merged read is cached under.
 *
 * <p>A merged read is not identified by a file. Reading {@code data/hulls/ship_data.csv} concatenates
 * one spreadsheet per enabled root, and the same call made with a different key-column list produces
 * a different array; reading {@code data/config/settings.json} overlays one file per root, and the
 * set of keys the caller asks to be left alone changes the result. So a key here names the whole
 * request -- kind, path, and the arguments that alter the answer -- and two requests that differ in
 * any of them are two entries.
 *
 * <p>Three rules keep a key from ever naming more than it should:
 *
 * <ul>
 *   <li><b>Only {@code data/}.</b> The identity that gates this cache covers every file under
 *       {@code data/} in every root, and nothing else. A request outside it has no identity to make
 *       it safe, so it gets no key and falls through to vanilla.
 *   <li><b>Absolute paths keep their own shape.</b> Reading {@code data/variants/wolf.variant} merges
 *       every root that provides it; reading {@code <install>/data/variants/wolf.variant} names one
 *       file. Folding them together would serve a mod's overlay where vanilla returned the core file
 *       alone, so an absolute path is marked and never shares a key with the relative one.
 *   <li><b>No backslashes.</b> Vanilla checks a mod's full-override list against the path in three
 *       spellings, which is evidence that both separators reach here. Rather than decide which
 *       spellings mean the same request, a path containing a backslash gets no key.
 * </ul>
 *
 * <p>Dropping the install prefix from an absolute path means two files could in principle claim one
 * key. Nothing observed does, and the runtime refuses a key that two different values claim rather
 * than picking a winner, so a collision costs a cache entry instead of serving the wrong data.
 */
public final class MergedReadKey {
    /** Marks a request that named one file rather than a merge across roots. */
    public static final String ABSOLUTE_PREFIX = "abs:";
    /** The only directory this cache has an identity for. */
    public static final String SERVED_PREFIX = "data/";

    public static final String CSV = "csv";
    public static final String JSON = "json";
    public static final String SINGLE_JSON = "single-json";

    /*
     * The game reads settings once before mod resource roots are active and again after registering
     * them. Those calls have identical LoadingUtils arguments but intentionally produce different
     * overlays, so no request-only key can safely name either result.
     */
    private static final String DYNAMIC_SETTINGS = "data/config/settings.json";

    // Written as casts because a unicode escape for either would be decoded before the source
    // is even tokenized, and a raw control character inside a literal is not readable.
    private static final char FIELD = (char) 0;
    private static final char ITEM = (char) 1;
    private static final int MAX_KEY_CHARS = 8192;

    private MergedReadKey() {
    }

    /** The key for one merged JSON read, or null if this cache may not answer for it. */
    public static String json(String rawPath, List<String> mergeKeys) {
        String path = path(rawPath);
        if (path == null || DYNAMIC_SETTINGS.equals(path)) {
            return null;
        }
        String items = items(mergeKeys);
        return items == null ? null : bound(JSON + FIELD + path + FIELD + items);
    }

    /** The key for one unrestricted single-file JSON read after resource initialization. */
    public static String singleJson(String rawPath) {
        // The prepared artifact identity covers logical paths from the enabled resource roots, but
        // this layer does not know those roots and therefore cannot prove an absolute path belongs
        // to one of them. Let vanilla handle absolute single-file reads rather than persisting
        // out-of-profile content under a key made only from its data/ suffix.
        if (rawPath != null && !rawPath.isEmpty() && absolute(rawPath)) {
            return null;
        }
        String path = path(rawPath);
        if (path == null || DYNAMIC_SETTINGS.equals(path)) {
            return null;
        }
        return bound(SINGLE_JSON + FIELD + path + FIELD);
    }

    /** The key for one merged CSV read, or null if this cache may not answer for it. */
    public static String csv(String rawPath, boolean first, boolean second, List<String> columns) {
        String path = path(rawPath);
        if (path == null) {
            return null;
        }
        String items = items(columns);
        if (items == null) {
            return null;
        }
        return bound(CSV + FIELD + path + FIELD + (first ? 'T' : 'F') + (second ? 'T' : 'F')
                + ITEM + items);
    }

    /**
     * The stored form of a request path, or null if this cache may not answer for it.
     *
     * <p>Case is left alone. Two spellings that differ only in case are two entries, which costs a
     * little space and cannot serve one file's contents for another's on a case-sensitive disk.
     */
    public static String path(String rawPath) {
        if (rawPath == null || rawPath.isEmpty() || rawPath.indexOf('\\') >= 0
                || rawPath.indexOf(FIELD) >= 0 || rawPath.indexOf(ITEM) >= 0) {
            return null;
        }
        String path;
        if (absolute(rawPath)) {
            int served = rawPath.lastIndexOf('/' + SERVED_PREFIX);
            if (served < 0) {
                return null;
            }
            path = ABSOLUTE_PREFIX + rawPath.substring(served + 1);
        } else if (rawPath.startsWith(SERVED_PREFIX)) {
            path = rawPath;
        } else {
            return null;
        }
        // A traversal cannot be reasoned about from the string alone, and vanilla resolves it against
        // roots this cache never sees, so it does not get a key.
        return path.contains("/../") || path.contains("/./") || path.endsWith("/..") ? null : path;
    }

    /** Whether a stored key could have been produced by this class. */
    public static boolean wellFormed(String key) {
        if (key == null || key.isEmpty() || key.length() > MAX_KEY_CHARS) {
            return false;
        }
        int first = key.indexOf(FIELD);
        if (first < 0) {
            return false;
        }
        int second = key.indexOf(FIELD, first + 1);
        if (second < 0 || key.indexOf(FIELD, second + 1) >= 0) {
            return false;
        }
        String kind = key.substring(0, first);
        if (!CSV.equals(kind) && !JSON.equals(kind) && !SINGLE_JSON.equals(kind)) {
            return false;
        }
        if (SINGLE_JSON.equals(kind) && second != key.length() - 1) {
            return false;
        }
        String path = key.substring(first + 1, second);
        String logical = path.startsWith(ABSOLUTE_PREFIX)
                ? path.substring(ABSOLUTE_PREFIX.length()) : path;
        return logical.startsWith(SERVED_PREFIX) && logical.indexOf('\\') < 0
                && !logical.contains("/../") && !logical.contains("/./") && !logical.endsWith("/..");
    }

    /** Whether one of the four dedicated spec JSON caches owns this merged-read key. */
    public static boolean ownedBySpecJsonCache(String key) {
        if (!wellFormed(key)) {
            return false;
        }
        int first = key.indexOf(FIELD);
        int second = key.indexOf(FIELD, first + 1);
        if (!JSON.equals(key.substring(0, first))) {
            return false;
        }
        String path = key.substring(first + 1, second);
        String logical = path.startsWith(ABSOLUTE_PREFIX)
                ? path.substring(ABSOLUTE_PREFIX.length()) : path;
        return logical.startsWith("data/variants/") && logical.endsWith(".variant")
                || logical.startsWith("data/hulls/") && logical.endsWith(".ship")
                || (logical.startsWith("data/weapons/")
                        || logical.startsWith("data/shipsystems/wpn/"))
                        && logical.endsWith(".wpn")
                || (logical.startsWith("data/weapons/proj/")
                        || logical.startsWith("data/shipsystems/proj/"))
                        && logical.endsWith(".proj");
    }

    private static boolean absolute(String path) {
        if (path.charAt(0) == '/') {
            return true;
        }
        return path.length() > 2 && path.charAt(1) == ':'
                && Character.isLetter(path.charAt(0));
    }

    /**
     * Joins the arguments that alter a merge, or null if they cannot be named.
     *
     * <p>A null list means the caller could not read what vanilla was handed, which is not the same
     * as vanilla being handed nothing: an empty list is a request this cache can name, and an
     * unreadable one has to fall through rather than share the empty list's key.
     */
    private static String items(List<String> values) {
        if (values == null) {
            return null;
        }
        if (values.isEmpty()) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        for (String value : values) {
            if (value == null || value.indexOf(FIELD) >= 0 || value.indexOf(ITEM) >= 0) {
                return null;
            }
            if (joined.length() > 0) {
                joined.append(ITEM);
            }
            joined.append(value);
        }
        return joined.toString();
    }

    private static String bound(String key) {
        return key.length() > MAX_KEY_CHARS ? null : key;
    }
}
