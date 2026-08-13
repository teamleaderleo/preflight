package dev.starsector.preflight.core;

import java.nio.file.Path;
import java.util.Objects;

/** Co-versioned classpath profile and archive-index directories. */
public final class ClasspathCacheDirectories {
    private ClasspathCacheDirectories() {
    }

    public static Path root(Path cacheRoot) {
        Path root = Objects.requireNonNull(cacheRoot, "cacheRoot");
        if (ClasspathProfileIndex.FORMAT_VERSION == 1 && JarArchiveIndex.FORMAT_VERSION == 1) {
            return root.resolve("classpath");
        }
        return root.resolve("classpath-v" + ClasspathProfileIndex.FORMAT_VERSION
                + "-archives-v" + JarArchiveIndex.FORMAT_VERSION);
    }

    public static Path profiles(Path cacheRoot) {
        return root(cacheRoot).resolve("profiles");
    }

    public static Path archives(Path cacheRoot) {
        return root(cacheRoot).resolve("archives");
    }
}
