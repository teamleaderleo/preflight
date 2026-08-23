package dev.starsector.preflight.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/** Checked logical texture paths observed during real game launches. */
public final class PreparedTextureAccessOrderIO {
    private PreparedTextureAccessOrderIO() {
    }

    public static Path path(Path cacheRoot, String profileFingerprint) {
        Path packOrder = PreparedTexturePackOrderIO.path(cacheRoot, profileFingerprint);
        String name = packOrder.getFileName().toString();
        return packOrder.resolveSibling(
                name.substring(0, name.length() - ".spfo".length()) + ".spta");
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
