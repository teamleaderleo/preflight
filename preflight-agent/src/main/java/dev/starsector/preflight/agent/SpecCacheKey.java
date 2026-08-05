package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.SpecCachePaths;
import java.util.List;
import java.util.Locale;

/**
 * Names the cache entry for one spec path, whether the loader named it relatively or absolutely.
 *
 * <p>The four merged-JSON caches used to key on {@link ResourceIndex#normalizeLogicalPath}, which
 * refuses an absolute path outright. That looked harmless and was not: on the measured profile
 * <b>every</b> core-game spec reaches the loader as an absolute path through the install directory,
 * so 895 of them -- 435 variants, 200 hulls, 156 weapons, 104 projectiles -- were structurally
 * invisible to the cache. They were never served and never learned, launch after launch, and the
 * probe that found them measured <b>0.95s</b> of merged reads doing the work again each time.
 *
 * <p><b>An absolute path is not the same request as the relative one.</b> Reading
 * {@code data/variants/hound_Assault.variant} merges every root that provides it; reading
 * {@code <install>/data/variants/hound_Assault.variant} names one file. A cache that folded the two
 * onto one key would serve a mod's overlay where vanilla returned the core file alone. So an
 * absolute path keeps its own key, marked {@code abs:}, and the install prefix is dropped only so
 * that an artifact survives the install being moved.
 *
 * <p>Dropping the prefix means two different absolute paths could in principle land on one key --
 * a mod's {@code data/} directory reached absolutely would collide with the core one. Nothing
 * observed does that, and {@link #ABSOLUTE_PREFIX} exists so a caller can detect it rather than
 * trust it: the runtimes compare a repeat capture against what they already hold and refuse the key
 * outright when the two disagree.
 */
final class SpecCacheKey {
    /** Marks a key that named one file rather than a merge across roots. */
    static final String ABSOLUTE_PREFIX = SpecCachePaths.ABSOLUTE_PREFIX;

    private SpecCacheKey() {
    }

    /**
     * @param rawPath the path the loader was given
     * @param directories the logical directories this cache serves, e.g. {@code data/variants/}
     * @param suffix the file extension this cache serves, e.g. {@code .variant}
     * @return the cache key, or null when this path is not one this cache may answer
     */
    static String of(String rawPath, List<String> directories, String suffix) {
        if (rawPath == null) {
            return null;
        }
        String value = rawPath.replace('\\', '/');
        boolean absolute = value.startsWith("/") || windowsAbsolute(value);
        try {
            if (!absolute) {
                String path = ResourceIndex.normalizeLogicalPath(value);
                return matches(path, directories, suffix) ? path : null;
            }
            String tail = tailFrom(value, directories);
            if (tail == null) {
                return null;
            }
            String path = ResourceIndex.normalizeLogicalPath(tail);
            return matches(path, directories, suffix) ? ABSOLUTE_PREFIX + path : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Equivalent to {@code ^[A-Za-z]:.*} without compiling a regex for every cache lookup. */
    private static boolean windowsAbsolute(String value) {
        if (value.length() < 2 || !asciiLetter(value.charAt(0)) || value.charAt(1) != ':') {
            return false;
        }
        // Java regex dot does not consume line terminators unless DOTALL is enabled. They are not
        // valid path characters here, but retaining that detail keeps classification exact before
        // the normalizer rejects the malformed input.
        for (int index = 2; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\n' || current == '\r' || current == '\u0085'
                    || current == '\u2028' || current == '\u2029') {
                return false;
            }
        }
        return true;
    }

    private static boolean asciiLetter(char value) {
        return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z');
    }

    /**
     * The part of an absolute path from the last directory this cache serves.
     *
     * <p>Last rather than first: a mod called {@code data} would otherwise truncate the path at its
     * own name and produce a key for a file it does not hold.
     */
    private static String tailFrom(String value, List<String> directories) {
        String lower = value.toLowerCase(Locale.ROOT);
        int best = -1;
        for (String directory : directories) {
            int at = lower.lastIndexOf('/' + directory);
            if (at > best) {
                best = at;
            }
        }
        return best < 0 ? null : value.substring(best + 1);
    }

    private static boolean matches(String path, List<String> directories, String suffix) {
        if (!path.endsWith(suffix)) {
            return false;
        }
        for (String directory : directories) {
            if (path.startsWith(directory)) {
                return true;
            }
        }
        return false;
    }
}
