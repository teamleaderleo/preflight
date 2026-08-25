package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Canonicalizes a prepared-data root without accepting a symbolic root entry. */
final class CacheRootBoundary {
    private CacheRootBoundary() {
    }

    static Path canonical(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) return absolute;
        if (Files.isSymbolicLink(absolute)
                || !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("cache root isn't a real directory: " + absolute);
        }
        return absolute.toRealPath();
    }
}
