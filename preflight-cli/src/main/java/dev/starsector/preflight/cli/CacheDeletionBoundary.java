package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;

/** Fail-closed deletion boundary for cache-prune operations. */
final class CacheDeletionBoundary {
    private CacheDeletionBoundary() {
    }

    /**
     * Refuses a prune when any existing path inside the cache is symbolic.
     *
     * <p>Prune is destructive and has no reason to traverse links. Refusing the whole tree keeps a
     * user-created or compromised cache namespace from redirecting enumeration outside Preflight's
     * owned cache root.
     */
    static void requireSafeTree(Path cache) throws IOException {
        Path root = cache.toAbsolutePath().normalize();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        requireRealDirectory(root, "cache root");
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(directory)) {
                    throw unsafe("cache directory is symbolic", directory);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(file)) {
                    throw unsafe("cache entry is symbolic", file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Deletes one planned regular file only while every ancestor is still a real cache directory. */
    static boolean deleteOwnedRegularFile(Path cache, Path candidate) throws IOException {
        if (!Files.exists(candidate.toAbsolutePath().normalize(), LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        Path file = requireOwnedPath(cache, candidate, false);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return false;
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw unsafe("planned cache file changed before deletion", file);
        }
        return Files.deleteIfExists(file);
    }

    /** Deletes an empty cache directory only while its complete ancestry remains non-symbolic. */
    static void deleteOwnedEmptyDirectory(Path cache, Path candidate) throws IOException {
        Path directory = candidate.toAbsolutePath().normalize();
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        requireOwnedPath(cache, directory, true);
        Files.deleteIfExists(directory);
    }

    private static Path requireOwnedPath(Path cache, Path candidate, boolean directory)
            throws IOException {
        Path requestedRoot = cache.toAbsolutePath().normalize();
        Path requestedTarget = candidate.toAbsolutePath().normalize();
        requireRealDirectory(requestedRoot, "cache root");
        Path root = requestedRoot.toRealPath();
        Path target = directory
                ? requestedTarget.toRealPath()
                : requestedTarget.getParent().toRealPath().resolve(requestedTarget.getFileName());
        if (!target.startsWith(root) || target.equals(root)) {
            throw unsafe("planned deletion is outside the cache root", target);
        }

        Path relative = root.relativize(target);
        Path current = root;
        int directoryParts = relative.getNameCount() - (directory ? 0 : 1);
        for (int index = 0; index < directoryParts; index++) {
            current = current.resolve(relative.getName(index));
            requireRealDirectory(current, "cache ancestor");
        }
        if (directory) requireRealDirectory(target, "cache directory");
        return target;
    }

    private static void requireRealDirectory(Path directory, String label) throws IOException {
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw unsafe(label + " is not a real directory", directory);
        }
    }

    private static IOException unsafe(String reason, Path path) {
        return new IOException(reason + ": " + path.toAbsolutePath().normalize());
    }
}
