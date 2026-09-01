package dev.starsector.preflight.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/** Checked logical texture paths observed at the game's prepared-prefetch seam. */
public final class PreparedTexturePrefetchOrderIO {
    private PreparedTexturePrefetchOrderIO() {
    }

    public static Path path(Path cacheRoot, String profileFingerprint) {
        Path accessOrder = PreparedTextureAccessOrderIO.path(cacheRoot, profileFingerprint);
        String name = accessOrder.getFileName().toString();
        return accessOrder.resolveSibling(
                name.substring(0, name.length() - ".spta".length()) + ".sptp");
    }

    public static void write(
            Path target, String profileFingerprint, Collection<String> logicalPaths)
            throws IOException {
        PreparedTexturePackOrderIO.write(target, profileFingerprint, logicalPaths);
    }

    public static List<String> read(Path source, String expectedProfile) throws IOException {
        return PreparedTexturePackOrderIO.read(source, expectedProfile);
    }
}
