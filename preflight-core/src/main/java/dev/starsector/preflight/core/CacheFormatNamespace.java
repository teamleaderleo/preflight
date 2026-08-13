package dev.starsector.preflight.core;

import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/** Stable cache-directory naming that lets incompatible on-disk formats coexist. */
public final class CacheFormatNamespace {
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private CacheFormatNamespace() {
    }

    /**
     * Keeps the established public-beta directory name for its original format and gives every
     * later format a distinct suffix. Existing caches therefore remain valid after adopting this
     * helper, while a future format bump cannot overwrite data needed by an older release.
     */
    public static String name(
            String establishedName, int currentFormatVersion, int establishedFormatVersion) {
        Objects.requireNonNull(establishedName, "establishedName");
        if (!SEGMENT.matcher(establishedName).matches()
                || establishedName.equals(".")
                || establishedName.equals("..")) {
            throw new IllegalArgumentException("Cache namespace must be one safe path segment");
        }
        if (establishedFormatVersion <= 0 || currentFormatVersion < establishedFormatVersion) {
            throw new IllegalArgumentException("Cache format versions are invalid");
        }
        return currentFormatVersion == establishedFormatVersion
                ? establishedName
                : establishedName + "-v" + currentFormatVersion;
    }

    public static Path directory(
            Path parent,
            String establishedName,
            int currentFormatVersion,
            int establishedFormatVersion) {
        return Objects.requireNonNull(parent, "parent").resolve(
                name(establishedName, currentFormatVersion, establishedFormatVersion));
    }
}
