package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.PreparedTextureIO;
import dev.starsector.preflight.core.PreparedTexturePack;
import dev.starsector.preflight.core.PreparedTexturePackIO;
import dev.starsector.preflight.core.PreparedTexturePackOrderIO;
import dev.starsector.preflight.core.ResourceIndex;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparationStoragePlannerPackOrderTest {
    private static final long GIB = 1024L * 1024 * 1024;

    @TempDir
    Path temporaryDirectory;

    @Test
    void setEqualWrongOrderPackIsAMissUntilTheBuilderReordersIt() throws Exception {
        Path root = temporaryDirectory.resolve("root");
        Path first = root.resolve("graphics/first.png");
        Path second = root.resolve("graphics/second.png");
        writeImage(first, 0xff336699);
        writeImage(second, 0xff996633);
        String profile = "ab".repeat(32);
        ResourceIndex index = index(root, profile, List.of("graphics/first.png", "graphics/second.png"));
        Path cache = temporaryDirectory.resolve("cache");
        TextureBatchBuilder.Options options = new TextureBatchBuilder.Options(
                1, 16L * 1024 * 1024, PreparedTextureIO.StorageCodec.RAW, false);

        TextureBatchBuilder.Result initial = TextureBatchBuilder.build(index, cache, options);
        assertFalse(initial.packHit());
        List<String> logicalOrder = initial.manifest().entries().values().stream()
                .map(entry -> entry.blobRelativePath())
                .distinct()
                .toList();
        assertEquals(2, logicalOrder.size());
        try (PreparedTexturePack pack = PreparedTexturePackIO.open(initial.packPath(), profile, logicalOrder)) {
            assertTrue(pack.hasEntryOrder(logicalOrder));
        }

        List<String> preferredOrder = List.of(logicalOrder.get(1), logicalOrder.get(0));
        PreparedTexturePackOrderIO.write(
                PreparedTexturePackOrderIO.path(cache, profile), profile, preferredOrder);

        PreparationStoragePlanner.Plan before = PreparationStoragePlanner.plan(
                index, cache, TextureStoragePolicy.FASTEST, 1, () -> 20 * GIB);
        assertFalse(before.packHit());
        assertTrue(before.predictedPackBytes() > 0);

        TextureBatchBuilder.Result rebuilt = TextureBatchBuilder.build(index, cache, options);
        assertFalse(rebuilt.packHit());
        try (PreparedTexturePack pack = PreparedTexturePackIO.open(rebuilt.packPath(), profile, preferredOrder)) {
            assertTrue(pack.hasEntryOrder(preferredOrder));
        }

        PreparationStoragePlanner.Plan after = PreparationStoragePlanner.plan(
                index, cache, TextureStoragePolicy.FASTEST, 1, () -> 20 * GIB);
        assertTrue(after.packHit());
        assertEquals(0, after.predictedPackBytes());

        TextureBatchBuilder.Result warm = TextureBatchBuilder.build(index, cache, options);
        assertTrue(warm.packHit());
    }

    private static ResourceIndex index(Path root, String fingerprint, List<String> paths) throws Exception {
        Map<String, List<ResourceIndex.Provider>> entries = new LinkedHashMap<>();
        for (String relative : paths) {
            Path file = root.resolve(relative);
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            entries.put(relative, List.of(new ResourceIndex.Provider(
                    0, relative, attributes.size(), attributes.lastModifiedTime().toMillis())));
        }
        return new ResourceIndex(
                fingerprint,
                List.of(new ResourceIndex.Root("root", root, false)),
                entries);
    }

    private static void writeImage(Path path, int argb) throws Exception {
        Files.createDirectories(path.getParent());
        BufferedImage image = new BufferedImage(10, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, argb ^ (x << 8) ^ y);
            }
        }
        assertTrue(ImageIO.write(image, "png", path.toFile()));
    }
}
