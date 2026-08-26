package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.PreparedTextureIO;
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

final class PreparationStoragePlannerSelectedReuseTest {
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

    @TempDir
    Path temporaryDirectory;

    @Test
    void selectedReuseExcludesCompatibleAlternateEncoding() throws Exception {
        Path root = temporaryDirectory.resolve("root");
        Path source = root.resolve("graphics/noisy.png");
        writeNoisyImage(source);
        ResourceIndex index = index(root, "ab".repeat(32), List.of("graphics/noisy.png"));
        Path cache = temporaryDirectory.resolve("cache");
        TextureBatchBuilder.Options balanced = new TextureBatchBuilder.Options(
                1, 32 * MIB, PreparedTextureIO.StorageCodec.LZ4, true);

        TextureBatchBuilder.Result built = TextureBatchBuilder.build(index, cache, balanced);
        String selectedPath = built.manifest().entry("graphics/noisy.png")
                .orElseThrow().blobRelativePath();
        assertFalse(selectedPath.endsWith("-lz4.spft"));
        String lz4Path = selectedPath.substring(0, selectedPath.length() - ".spft".length())
                + "-lz4.spft";
        assertTrue(Files.isRegularFile(cache.resolve(selectedPath)));
        assertTrue(Files.isRegularFile(cache.resolve(lz4Path)));

        PreparationStoragePlanner.Plan plan = PreparationStoragePlanner.plan(
                index, cache, TextureStoragePolicy.BALANCED, 1, () -> 20 * GIB);

        long selectedBytes = Files.size(cache.resolve(selectedPath));
        long retainedCompatibleBytes = selectedBytes + Files.size(cache.resolve(lz4Path));
        assertEquals(selectedBytes, plan.selectedReusableLooseBytes());
        assertEquals(retainedCompatibleBytes, plan.reusableLooseBytes());
        assertTrue(plan.reusableLooseBytes() > plan.selectedReusableLooseBytes());
        assertEquals(
                selectedBytes,
                ((Number) plan.toMap().get("selectedReusableLooseBytes")).longValue());
    }

    @Test
    void rawOnlyBalancedCacheIsRetainedButWillBeRewrittenAfterLz4Measurement() throws Exception {
        Path root = temporaryDirectory.resolve("raw-only-root");
        Path source = root.resolve("graphics/noisy.png");
        writeNoisyImage(source);
        String profile = "cd".repeat(32);
        ResourceIndex index = index(root, profile, List.of("graphics/noisy.png"));
        Path cache = temporaryDirectory.resolve("raw-only-cache");
        TextureBatchBuilder.build(
                index,
                cache,
                new TextureBatchBuilder.Options(
                        1, 32 * MIB, PreparedTextureIO.StorageCodec.RAW, false));
        Files.deleteIfExists(dev.starsector.preflight.core.PreparedTexturePackIO.path(cache, profile));

        PreparationStoragePlanner.Plan plan = PreparationStoragePlanner.plan(
                index, cache, TextureStoragePolicy.BALANCED, 1, () -> 20 * GIB);

        assertTrue(plan.reusableLooseBytes() > 0);
        assertEquals(0, plan.selectedReusableLooseBytes());
        assertTrue(plan.predictedLooseBytes() > 0);
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

    private static void writeNoisyImage(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        java.util.Random random = new java.util.Random(0x5eedL);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, random.nextInt());
            }
        }
        assertTrue(ImageIO.write(image, "png", path.toFile()));
    }
}
