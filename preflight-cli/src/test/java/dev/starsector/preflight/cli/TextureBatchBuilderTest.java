package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.PreparedTexture;
import dev.starsector.preflight.core.PreparedTextureIO;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.TextureManifestIO;
import dev.starsector.preflight.core.TextureManifestValidator;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextureBatchBuilderTest {
    private static final long MIB = 1024L * 1024L;

    @TempDir
    Path temporaryDirectory;

    @Test
    void deduplicatesContentReusesBlobsAndQuarantinesCorruption() throws Exception {
        Path root = temporaryDirectory.resolve("root");
        Path a = root.resolve("graphics/a.png");
        Path b = root.resolve("graphics/b.png");
        Path c = root.resolve("graphics/c.png");
        writeImage(a, Color.RED);
        Files.createDirectories(b.getParent());
        Files.copy(a, b);
        writeImage(c, Color.BLUE);
        ResourceIndex index = index(root, "profile-one", List.of("graphics/a.png", "graphics/b.png", "graphics/c.png"));
        Path cache = temporaryDirectory.resolve("cache");

        TextureBatchBuilder.Result first = TextureBatchBuilder.build(
                index,
                cache,
                new TextureBatchBuilder.Options(2, 16 * MIB));
        assertEquals(3, first.candidateEntries());
        assertEquals(2, first.uniqueContent());
        assertEquals(2, first.builtBlobs());
        assertEquals(0, first.cacheHitBlobs());
        assertEquals(1, first.deduplicatedEntries());
        assertEquals(3, first.manifest().entryCount());
        assertTrue(TextureManifestValidator.validate(cache, first.manifest()).valid());
        assertEquals(
                first.manifest().entry("graphics/a.png").orElseThrow().blobRelativePath(),
                first.manifest().entry("graphics/b.png").orElseThrow().blobRelativePath());

        Path builtBlob = cache.resolve(
                first.manifest().entry("graphics/a.png").orElseThrow().blobRelativePath());
        PreparedTexture builtTexture = PreparedTextureIO.read(builtBlob);
        PreparedTexture referenceTexture = ReferenceTexturePreprocessor.prepare(
                a,
                PreparedTexture.Transformation.IDENTITY);
        assertEquals(referenceTexture, builtTexture);

        TextureBatchBuilder.Result second = TextureBatchBuilder.build(
                index,
                cache,
                new TextureBatchBuilder.Options(2, 16 * MIB));
        assertEquals(2, second.cacheHitBlobs());
        assertEquals(0, second.builtBlobs());

        Path corruptBlob = cache.resolve(
                second.manifest().entry("graphics/a.png").orElseThrow().blobRelativePath());
        Files.write(corruptBlob, new byte[] {1, 2, 3, 4});
        TextureBatchBuilder.Result repaired = TextureBatchBuilder.build(
                index,
                cache,
                new TextureBatchBuilder.Options(2, 16 * MIB));
        assertEquals(1, repaired.quarantinedBlobs());
        assertEquals(1, repaired.builtBlobs());
        assertEquals(1, repaired.cacheHitBlobs());
        assertTrue(TextureManifestValidator.validate(cache, repaired.manifest()).valid());
        try (var files = Files.list(cache.resolve("quarantine"))) {
            assertTrue(files.findAny().isPresent());
        }
    }

    @Test
    void manifestsStayDeterministicAcrossWorkerCountsAndDropRemovedResources() throws Exception {
        Path root = temporaryDirectory.resolve("root");
        writeImage(root.resolve("graphics/a.png"), Color.RED);
        writeImage(root.resolve("graphics/b.png"), Color.GREEN);
        ResourceIndex index = index(root, "profile", List.of("graphics/a.png", "graphics/b.png"));

        TextureBatchBuilder.Result single = TextureBatchBuilder.build(
                index,
                temporaryDirectory.resolve("single"),
                new TextureBatchBuilder.Options(1, 16 * MIB));
        TextureBatchBuilder.Result parallel = TextureBatchBuilder.build(
                index,
                temporaryDirectory.resolve("parallel"),
                new TextureBatchBuilder.Options(4, 32 * MIB));
        assertArrayEquals(
                TextureManifestIO.toBytes(single.manifest()),
                TextureManifestIO.toBytes(parallel.manifest()));

        ResourceIndex reduced = index(root, "profile-reduced", List.of("graphics/a.png"));
        TextureBatchBuilder.Result reducedResult = TextureBatchBuilder.build(
                reduced,
                temporaryDirectory.resolve("single"),
                new TextureBatchBuilder.Options(1, 16 * MIB));
        assertEquals(1, reducedResult.manifest().entryCount());
        assertTrue(reducedResult.manifest().entry("graphics/b.png").isEmpty());
    }

    @Test
    void changingOneSourceBuildsOneNewBlob() throws Exception {
        Path root = temporaryDirectory.resolve("root");
        Path a = root.resolve("graphics/a.png");
        Path b = root.resolve("graphics/b.png");
        writeImage(a, Color.RED);
        writeImage(b, Color.BLUE);
        Path cache = temporaryDirectory.resolve("cache");

        ResourceIndex firstIndex = index(root, "profile-one", List.of("graphics/a.png", "graphics/b.png"));
        TextureBatchBuilder.Result first = TextureBatchBuilder.build(
                firstIndex,
                cache,
                new TextureBatchBuilder.Options(2, 16 * MIB));
        assertEquals(2, first.builtBlobs());

        writeImage(a, Color.WHITE);
        ResourceIndex changedIndex = index(root, "profile-two", List.of("graphics/a.png", "graphics/b.png"));
        TextureBatchBuilder.Result changed = TextureBatchBuilder.build(
                changedIndex,
                cache,
                new TextureBatchBuilder.Options(2, 16 * MIB));
        assertEquals(1, changed.builtBlobs());
        assertEquals(1, changed.cacheHitBlobs());
    }

    @Test
    void rejectsSourceChangedAfterContentGrouping() throws Exception {
        Path root = temporaryDirectory.resolve("root");
        Path source = root.resolve("graphics/a.png");
        writeImage(source, Color.RED);
        ResourceIndex index = index(root, "profile", List.of("graphics/a.png"));
        byte[] changedBytes = Files.readAllBytes(source);
        changedBytes[changedBytes.length - 1] ^= 1;

        TextureBatchBuilder.Result result = TextureBatchBuilder.build(
                index,
                temporaryDirectory.resolve("cache"),
                new TextureBatchBuilder.Options(1, 16 * MIB),
                (path, expectedBytes, maximumBytes) -> {
                    Files.write(path, changedBytes);
                    return BulkTexturePreprocessor.readSnapshot(path, expectedBytes, maximumBytes);
                });

        assertEquals(0, result.builtBlobs());
        assertEquals(1, result.failedBlobs());
        assertEquals(0, result.manifest().entryCount());
        assertTrue(result.diagnostics().stream()
                .anyMatch(message -> message.contains("Source snapshot SHA-256 mismatch")));
    }

    @Test
    void cacheHitsDoNotReadEncodedSnapshots() throws Exception {
        Path root = temporaryDirectory.resolve("root");
        writeImage(root.resolve("graphics/a.png"), Color.RED);
        ResourceIndex index = index(root, "profile", List.of("graphics/a.png"));
        Path cache = temporaryDirectory.resolve("cache");
        TextureBatchBuilder.Options options = new TextureBatchBuilder.Options(1, 16 * MIB);

        TextureBatchBuilder.Result first = TextureBatchBuilder.build(index, cache, options);
        assertEquals(1, first.builtBlobs());
        AtomicInteger snapshotReads = new AtomicInteger();

        TextureBatchBuilder.Result second = TextureBatchBuilder.build(
                index,
                cache,
                options,
                (path, expectedBytes, maximumBytes) -> {
                    snapshotReads.incrementAndGet();
                    throw new AssertionError("Cache hit attempted to allocate an encoded snapshot");
                });

        assertEquals(1, second.cacheHitBlobs());
        assertEquals(0, second.builtBlobs());
        assertEquals(0, snapshotReads.get());
    }

    @Test
    void encodedSourceLargerThanWorkerBudgetFailsBeforeDecode() throws Exception {
        Path root = temporaryDirectory.resolve("root");
        Path source = root.resolve("graphics/oversized.png");
        Files.createDirectories(source.getParent());
        try (RandomAccessFile file = new RandomAccessFile(source.toFile(), "rw")) {
            file.setLength(16 * MIB + 1);
        }
        ResourceIndex index = index(root, "profile", List.of("graphics/oversized.png"));

        TextureBatchBuilder.Result result = TextureBatchBuilder.build(
                index,
                temporaryDirectory.resolve("cache"),
                new TextureBatchBuilder.Options(1, 16 * MIB));

        assertEquals(0, result.builtBlobs());
        assertEquals(1, result.failedBlobs());
        assertEquals(0, result.manifest().entryCount());
        assertTrue(result.diagnostics().stream()
                .anyMatch(message -> message.contains("exceeding the texture worker memory budget")));
    }

    @Test
    void unsupportedImageFormatIsSkippedWithoutFailingPreparedFallbacks() throws Exception {
        Path root = temporaryDirectory.resolve("root");
        Path source = root.resolve("graphics/unsupported.tga");
        Files.createDirectories(source.getParent());
        Files.write(source, new byte[] {0, 0, 0, 0});
        ResourceIndex index = index(root, "profile", List.of("graphics/unsupported.tga"));

        TextureBatchBuilder.Result result = TextureBatchBuilder.build(
                index,
                temporaryDirectory.resolve("cache"),
                new TextureBatchBuilder.Options(1, 16 * MIB));

        assertEquals(0, result.builtBlobs());
        assertEquals(0, result.failedBlobs());
        assertEquals(1, result.skippedUnsupportedBlobs());
        assertEquals(0, result.manifest().entryCount());
        assertTrue(result.diagnostics().stream()
                .anyMatch(message -> message.contains("Skipped unsupported texture")));
    }

    @Test
    void preparesValidWebpThroughThePureJavaImageIoProvider() throws Exception {
        Path root = temporaryDirectory.resolve("root");
        Path source = root.resolve("graphics/icon.webp");
        Files.createDirectories(source.getParent());
        Files.write(source, Base64.getDecoder().decode(
                "UklGRiwAAABXRUJQVlA4TB8AAAAvAUAAAB8gEEjeHzqN+RcQFPwf3fxHZA/gBgwR/Q8BAA=="));
        ResourceIndex index = index(root, "profile", List.of("graphics/icon.webp"));

        TextureBatchBuilder.Result result = TextureBatchBuilder.build(
                index,
                temporaryDirectory.resolve("cache"),
                new TextureBatchBuilder.Options(1, 16 * MIB));

        assertEquals(1, result.builtBlobs());
        assertEquals(0, result.failedBlobs());
        assertEquals(0, result.skippedUnsupportedBlobs());
        assertTrue(result.manifest().entry("graphics/icon.webp").isPresent());
    }

    private static ResourceIndex index(Path root, String fingerprint, List<String> paths) throws Exception {
        Map<String, List<ResourceIndex.Provider>> entries = new LinkedHashMap<>();
        for (String relative : paths) {
            Path file = root.resolve(relative);
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            entries.put(relative, List.of(new ResourceIndex.Provider(
                    0,
                    relative,
                    attributes.size(),
                    Math.max(0, attributes.lastModifiedTime().toMillis()))));
        }
        return new ResourceIndex(
                fingerprint,
                List.of(new ResourceIndex.Root("root", root, false)),
                entries);
    }

    private static void writeImage(Path path, Color color) throws Exception {
        Files.createDirectories(path.getParent());
        BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        assertTrue(ImageIO.write(image, "png", path.toFile()));
    }
}
