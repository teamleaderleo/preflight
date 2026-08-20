package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.PreparedTextureIO;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceIndexIO;
import java.awt.Color;
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

class PreparationStoragePlannerTest {
    private static final long GIB = 1024L * 1024 * 1024;

    @TempDir
    Path temporaryDirectory;

    @Test
    void plansDecodedPixelsDeduplicationAndAReserveWithoutWriting() throws Exception {
        Path root = temporaryDirectory.resolve("root");
        Path first = root.resolve("graphics/first.png");
        Path duplicate = root.resolve("graphics/duplicate.png");
        writeImage(first, true);
        Files.copy(first, duplicate);
        ResourceIndex index = index(root, "ab".repeat(32),
                List.of("graphics/first.png", "graphics/duplicate.png"));
        Path cache = temporaryDirectory.resolve("missing-cache");

        PreparationStoragePlanner.Plan plan = PreparationStoragePlanner.plan(
                index, cache, TextureStoragePolicy.FASTEST, 2, () -> 20 * GIB);

        assertEquals(2, plan.candidateEntries());
        assertEquals(1, plan.uniqueContent());
        assertEquals(10L * 8 * 4, plan.uniquePixelBytes());
        assertTrue(plan.predictedLooseBytes() > plan.uniquePixelBytes());
        assertTrue(plan.predictedPackBytes() > plan.uniquePixelBytes());
        assertEquals(GIB, plan.safetyReserveBytes());
        assertTrue(plan.safeToPrepare());
        assertFalse(Files.exists(cache));
    }

    @Test
    void refusesBeforeWritingWhenTheConservativeBoundDoesNotFit() throws Exception {
        Path root = temporaryDirectory.resolve("root-low");
        Path image = root.resolve("graphics/image.png");
        writeImage(image, false);
        ResourceIndex index = index(root, "cd".repeat(32), List.of("graphics/image.png"));
        Path cache = temporaryDirectory.resolve("low-cache");

        PreparationStoragePlanner.Plan plan = PreparationStoragePlanner.plan(
                index, cache, TextureStoragePolicy.BALANCED, 1, () -> 1);

        assertFalse(plan.safeToPrepare());
        assertNotNull(plan.refusalReason());
        assertTrue(plan.requiredFreeBytes() > plan.usableBytes());
        assertFalse(Files.exists(cache));
    }

    @Test
    void balancedUpperBoundIncludesRawFallbackWhenHeuristicPredictsEffectiveCompression() throws Exception {
        Path root = temporaryDirectory.resolve("root-balanced");
        Path image = root.resolve("graphics/image.png");
        Files.createDirectories(image.getParent());
        assertTrue(ImageIO.write(new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB),
                "png", image.toFile()));
        ResourceIndex index = index(root, "de".repeat(32), List.of("graphics/image.png"));

        PreparationStoragePlanner.Plan plan = PreparationStoragePlanner.plan(
                index, temporaryDirectory.resolve("balanced-cache"),
                TextureStoragePolicy.BALANCED, 1, () -> 20 * GIB);

        long rawFileBytes = PreparedTextureIO.rawFileBytes(plan.uniquePixelBytes());
        assertTrue(plan.predictedLooseBytes() < rawFileBytes);
        assertTrue(plan.upperLooseBytes() >= 2 * rawFileBytes);
    }

    @Test
    void recognizesAReusableLooseSetAndExactProfilePack() throws Exception {
        Path root = temporaryDirectory.resolve("root-warm");
        Path image = root.resolve("graphics/image.png");
        writeImage(image, true);
        String profile = "ef".repeat(32);
        ResourceIndex index = index(root, profile, List.of("graphics/image.png"));
        Path cache = temporaryDirectory.resolve("warm-cache");
        TextureBatchBuilder.build(index, cache, new TextureBatchBuilder.Options(
                1, 16L * 1024 * 1024,
                dev.starsector.preflight.core.PreparedTextureIO.StorageCodec.RAW, false));
        Path resourceIndex = ResourceIndexIO.directory(cache).resolve(profile + ".spfi");
        Files.createDirectories(resourceIndex.getParent());
        Files.writeString(resourceIndex, "present");

        PreparationStoragePlanner.Plan plan = PreparationStoragePlanner.plan(
                index, cache, TextureStoragePolicy.FASTEST, 1, () -> 20 * GIB);

        assertTrue(plan.packHit());
        assertEquals(0, plan.predictedLooseBytes());
        assertEquals(0, plan.predictedPackBytes());
        assertEquals(0, plan.predictedMetadataBytes());
        assertTrue(plan.reusableLooseBytes() > 0);
    }

    private static ResourceIndex index(Path root, String fingerprint, List<String> paths) throws Exception {
        Map<String, List<ResourceIndex.Provider>> entries = new LinkedHashMap<>();
        for (String relative : paths) {
            Path file = root.resolve(relative);
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            OpenedFileGenerationAuthority.Generation generation = OpenedFileGenerationAuthority.capture(file);
            entries.put(relative, List.of(new ResourceIndex.Provider(
                    0,
                    relative,
                    attributes.size(),
                    attributes.lastModifiedTime().toMillis(),
                    generation.provider(),
                    generation.token())));
        }
        return new ResourceIndex(
                fingerprint,
                List.of(new ResourceIndex.Root("root", root, false)),
                entries);
    }

    private static void writeImage(Path path, boolean alpha) throws Exception {
        Files.createDirectories(path.getParent());
        BufferedImage image = new BufferedImage(
                10, 8, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, new Color(x * 20, y * 20, 80, alpha ? 150 : 255).getRGB());
            }
        }
        assertTrue(ImageIO.write(image, "png", path.toFile()));
    }
}
