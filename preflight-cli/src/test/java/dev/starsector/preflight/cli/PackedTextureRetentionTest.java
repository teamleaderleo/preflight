package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.PreparedTexturePackIO;
import dev.starsector.preflight.core.ResourceIndex;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackedTextureRetentionTest {
    private static final long MIB = 1024L * 1024L;

    @TempDir
    Path directory;

    @Test
    void exactPackReplacesLooseCopyWithoutLosingReadablePixels() throws Exception {
        Path root = directory.resolve("root");
        Path source = root.resolve("graphics/ship.png");
        writeImage(source);
        Path cache = directory.resolve("cache");
        TextureBatchBuilder.Result built = TextureBatchBuilder.build(
                index(root, "a".repeat(64), source), cache,
                new TextureBatchBuilder.Options(1, 16 * MIB));
        String blobPath = built.manifest().entries().values().iterator().next().blobRelativePath();
        Path blob = cache.resolve(blobPath);
        assertTrue(Files.isRegularFile(blob));

        PackedTextureRetention.Result released =
                PackedTextureRetention.release(cache, built.manifest());

        assertEquals(1, released.releasedBlobs());
        assertTrue(released.releasedBytes() > 0);
        assertFalse(Files.exists(blob));
        try (var pack = PreparedTexturePackIO.open(
                built.packPath(), built.manifest().profileFingerprint(),
                PackedTextureRetention.blobPaths(built.manifest()))) {
            assertEquals(4, pack.readTrusted(blobPath).channels());
        }
    }

    @Test
    void exactPackAlsoReleasesAnUnreferencedCodecCandidate() throws Exception {
        Path root = directory.resolve("loser-root");
        Path source = root.resolve("graphics/ship.png");
        writeImage(source);
        Path cache = directory.resolve("loser-cache");
        TextureBatchBuilder.Result built = TextureBatchBuilder.build(
                index(root, "7".repeat(64), source), cache,
                new TextureBatchBuilder.Options(1, 16 * MIB));
        String selected = built.manifest().entries().values().iterator().next().blobRelativePath();
        Path selectedBlob = cache.resolve(selected);
        Path loser = cache.resolve("blobs/ff/unreferenced-codec-candidate.spft");
        Files.createDirectories(loser.getParent());
        Files.copy(selectedBlob, loser);
        long expectedBytes = Files.size(selectedBlob) + Files.size(loser);

        PackedTextureRetention.Result released =
                PackedTextureRetention.release(cache, built.manifest());

        assertEquals(2, released.releasedBlobs());
        assertEquals(expectedBytes, released.releasedBytes());
        assertFalse(Files.exists(selectedBlob));
        assertFalse(Files.exists(loser));
    }

    @Test
    void looseCopyStaysWhenAnotherProfileHasNoUsablePack() throws Exception {
        Path root = directory.resolve("shared-root");
        Path source = root.resolve("graphics/shared.png");
        writeImage(source);
        Path cache = directory.resolve("shared-cache");
        TextureBatchBuilder.Result other = TextureBatchBuilder.build(
                index(root, "b".repeat(64), source), cache,
                new TextureBatchBuilder.Options(1, 16 * MIB));
        TextureBatchBuilder.Result current = TextureBatchBuilder.build(
                index(root, "c".repeat(64), source), cache,
                new TextureBatchBuilder.Options(1, 16 * MIB));
        Files.delete(other.packPath());
        Path blob = cache.resolve(
                current.manifest().entries().values().iterator().next().blobRelativePath());

        PackedTextureRetention.Result released =
                PackedTextureRetention.release(cache, current.manifest());

        assertEquals(0, released.releasedBlobs());
        assertEquals(1, released.protectedBlobs());
        assertTrue(Files.isRegularFile(blob));
    }

    @Test
    void damagedCurrentPackCannotDiscardLooseCopy() throws Exception {
        Path root = directory.resolve("damaged-root");
        Path source = root.resolve("graphics/damaged.png");
        writeImage(source);
        Path cache = directory.resolve("damaged-cache");
        TextureBatchBuilder.Result built = TextureBatchBuilder.build(
                index(root, "d".repeat(64), source), cache,
                new TextureBatchBuilder.Options(1, 16 * MIB));
        Path blob = cache.resolve(
                built.manifest().entries().values().iterator().next().blobRelativePath());
        Files.write(built.packPath(), new byte[] {1, 2, 3});

        assertThrows(IOException.class,
                () -> PackedTextureRetention.release(cache, built.manifest()));
        assertTrue(Files.isRegularFile(blob));
    }

    @Test
    void packOnlyHitDoesNotHashOrDecodeTheInstalledSourceAgain() throws Exception {
        Path root = directory.resolve("warm-root");
        Path source = root.resolve("graphics/warm.png");
        writeImage(source);
        Path cache = directory.resolve("warm-cache");
        ResourceIndex index = index(root, "e".repeat(64), source);
        TextureBatchBuilder.Result built = TextureBatchBuilder.build(
                index, cache, new TextureBatchBuilder.Options(1, 16 * MIB));
        PackedTextureRetention.release(cache, built.manifest());

        TextureBatchBuilder.Result reused = TextureBatchBuilder.build(
                index,
                cache,
                new TextureBatchBuilder.Options(1, 16 * MIB),
                (path, expectedBytes, maximumBytes) -> {
                    throw new AssertionError("pack-only hit decoded its installed source");
                },
                path -> {
                    throw new AssertionError("pack-only hit hashed its installed source");
                });

        assertTrue(reused.packHit());
        assertEquals(0, reused.builtBlobs());
        assertEquals(1, reused.cacheHitBlobs());
    }

    @Test
    void packOnlyHitProbesButDoesNotRebuildAnUnsupportedCandidate() throws Exception {
        Path root = directory.resolve("unsupported-warm-root");
        Path source = root.resolve("graphics/warm.png");
        Path unsupported = root.resolve("graphics/unsupported.tga");
        writeImage(source);
        Files.write(unsupported, new byte[] {0, 0, 0, 0});
        Path cache = directory.resolve("unsupported-warm-cache");
        ResourceIndex index = index(root, "f".repeat(64), List.of(source, unsupported));
        TextureBatchBuilder.Result built = TextureBatchBuilder.build(
                index, cache, new TextureBatchBuilder.Options(1, 16 * MIB));
        PackedTextureRetention.release(cache, built.manifest());

        TextureBatchBuilder.Result reused = TextureBatchBuilder.build(
                index,
                cache,
                new TextureBatchBuilder.Options(1, 16 * MIB),
                (path, expectedBytes, maximumBytes) -> {
                    throw new AssertionError("pack-only hit decoded a supported installed source");
                },
                path -> {
                    throw new AssertionError("pack-only hit hashed an installed source");
                });

        assertTrue(reused.packHit());
        assertEquals(0, reused.builtBlobs());
        assertEquals(1, reused.cacheHitBlobs());
        assertEquals(1, reused.skippedUnsupportedBlobs());
    }

    @Test
    void formerlyUnsupportedCandidateBecomingDecodableForcesAFullRebuild() throws Exception {
        Path root = directory.resolve("newly-supported-root");
        Path source = root.resolve("graphics/warm.png");
        Path formerlyUnsupported = root.resolve("graphics/formerly-unsupported.tga");
        writeImage(source);
        Files.write(formerlyUnsupported, new byte[] {0, 0, 0, 0});
        Path cache = directory.resolve("newly-supported-cache");
        ResourceIndex index = index(root, "9".repeat(64), List.of(source, formerlyUnsupported));
        TextureBatchBuilder.Result built = TextureBatchBuilder.build(
                index, cache, new TextureBatchBuilder.Options(1, 16 * MIB));
        PackedTextureRetention.release(cache, built.manifest());

        // ImageIO sniffs the content instead of trusting the extension. Once an omitted path is
        // decodable, the old manifest is incomplete and the exact-pack shortcut must fail closed.
        writeImage(formerlyUnsupported);
        AtomicInteger hashes = new AtomicInteger();
        TextureBatchBuilder.Result rebuilt = TextureBatchBuilder.build(
                index,
                cache,
                new TextureBatchBuilder.Options(1, 16 * MIB),
                (path, expectedBytes, maximumBytes) -> Files.readAllBytes(path),
                path -> {
                    hashes.incrementAndGet();
                    return Hashes.sha256(path);
                });

        assertEquals(2, hashes.get());
        assertEquals(2, rebuilt.hashedEntries());
        assertEquals(2, rebuilt.manifest().entryCount());
        assertEquals(0, rebuilt.skippedUnsupportedBlobs());
    }

    private static ResourceIndex index(Path root, String fingerprint, Path source) throws Exception {
        return index(root, fingerprint, List.of(source));
    }

    private static ResourceIndex index(Path root, String fingerprint, List<Path> sources) throws Exception {
        Map<String, List<ResourceIndex.Provider>> entries = new LinkedHashMap<>();
        for (Path source : sources) {
            BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class);
            entries.put("graphics/" + source.getFileName(), List.of(new ResourceIndex.Provider(
                    0,
                    "graphics/" + source.getFileName(),
                    attributes.size(),
                    attributes.lastModifiedTime().toMillis())));
        }
        return new ResourceIndex(
                fingerprint,
                List.of(new ResourceIndex.Root("root", root, false)),
                entries);
    }

    private static void writeImage(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, new Color(x * 20, y * 20, 80, 180).getRGB());
            }
        }
        assertTrue(ImageIO.write(image, "png", path.toFile()));
    }
}
