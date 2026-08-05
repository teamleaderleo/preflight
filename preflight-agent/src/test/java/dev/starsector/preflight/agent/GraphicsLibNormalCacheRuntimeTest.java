package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphicsLibNormalCacheRuntimeTest {
    @TempDir
    Path temporary;

    @AfterEach
    void reset() {
        GraphicsLibNormalCacheRuntime.clearTestConfiguration();
    }

    @Test
    void completePngReturnsRecognizableMarker() throws Exception {
        Path cache = Files.createDirectory(temporary.resolve("cache"));
        Path png = cache.resolve("valid_normal.png");
        assertTrue(ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB), "png",
                png.toFile()));
        GraphicsLibNormalCacheRuntime.configureForTest(cache, TestSprite.class);

        Object marker = GraphicsLibNormalCacheRuntime.lazySprite("cache/valid_normal.png");
        assertNotNull(marker);
        assertTrue(marker instanceof TestSprite);
        assertTrue(GraphicsLibNormalCacheRuntime.isLazySprite(marker));
        assertEquals(1.0f, ((TestSprite) marker).getHeight());
        assertEquals(1L, GraphicsLibNormalCacheRuntime.telemetry().get("hits"));
        assertEquals(2L, GraphicsLibNormalCacheRuntime.telemetry().get("metadataProbes"));
        assertEquals(Files.size(png), GraphicsLibNormalCacheRuntime.telemetry().get("validatedBytes"));
    }

    @Test
    void corruptMissingAndEscapingFilesFallBack() throws Exception {
        Path cache = Files.createDirectory(temporary.resolve("cache"));
        Path png = cache.resolve("corrupt_normal.png");
        assertTrue(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png",
                png.toFile()));
        byte[] corrupt = Files.readAllBytes(png);
        corrupt[corrupt.length / 2] ^= 0x40;
        Files.write(png, corrupt);
        GraphicsLibNormalCacheRuntime.configureForTest(cache, TestSprite.class);

        assertNull(GraphicsLibNormalCacheRuntime.lazySprite("cache/corrupt_normal.png"));
        assertNull(GraphicsLibNormalCacheRuntime.lazySprite("cache/missing_normal.png"));
        assertNull(GraphicsLibNormalCacheRuntime.lazySprite("cache/../outside_normal.png"));
        assertNull(GraphicsLibNormalCacheRuntime.lazySprite("other/corrupt_normal.png"));
        assertEquals(4L, GraphicsLibNormalCacheRuntime.telemetry().get("fallbacks"));
        assertFalse(GraphicsLibNormalCacheRuntime.isLazySprite(new Object()));
    }

    @Test
    void persistentJournalSkipsUnchangedPngAndRevalidatesChangedPng() throws Exception {
        Path cache = Files.createDirectory(temporary.resolve("cache"));
        Path journal = Files.createDirectory(temporary.resolve("journal"));
        Path png = cache.resolve("valid_normal.png");
        assertTrue(ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB), "png",
                png.toFile()));

        GraphicsLibNormalCacheRuntime.configureForTest(cache, journal, TestSprite.class);
        assertNotNull(GraphicsLibNormalCacheRuntime.lazySprite("cache/valid_normal.png"));
        assertEquals(1L, GraphicsLibNormalCacheRuntime.telemetry().get("journalMisses"));
        GraphicsLibNormalCacheRuntime.flushJournalForTest();

        GraphicsLibNormalCacheRuntime.configureForTest(cache, journal, TestSprite.class);
        assertNotNull(GraphicsLibNormalCacheRuntime.lazySprite("cache/valid_normal.png"));
        assertEquals(1L, GraphicsLibNormalCacheRuntime.telemetry().get("journalHits"));
        assertEquals(1L, GraphicsLibNormalCacheRuntime.telemetry().get("metadataProbes"));
        assertEquals(0L, GraphicsLibNormalCacheRuntime.telemetry().get("validatedBytes"));

        assertTrue(ImageIO.write(new BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB), "png",
                png.toFile()));
        GraphicsLibNormalCacheRuntime.configureForTest(cache, journal, TestSprite.class);
        assertNotNull(GraphicsLibNormalCacheRuntime.lazySprite("cache/valid_normal.png"));
        assertEquals(1L, GraphicsLibNormalCacheRuntime.telemetry().get("journalMisses"));
        assertEquals(Files.size(png), GraphicsLibNormalCacheRuntime.telemetry().get("validatedBytes"));
    }

    @Test
    void malformedJournalFailsClosedToFullValidation() throws Exception {
        Path cache = Files.createDirectory(temporary.resolve("cache"));
        Path journal = Files.createDirectory(temporary.resolve("journal"));
        Path png = cache.resolve("valid_normal.png");
        assertTrue(ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB), "png",
                png.toFile()));
        Files.write(journal.resolve("graphicslib-normal-validation-v1.bin"),
                new byte[] {0x01, 0x02, 0x03});

        GraphicsLibNormalCacheRuntime.configureForTest(cache, journal, TestSprite.class);
        assertNotNull(GraphicsLibNormalCacheRuntime.lazySprite("cache/valid_normal.png"));
        assertEquals(1L, GraphicsLibNormalCacheRuntime.telemetry().get("journalLoadFailures"));
        assertEquals(1L, GraphicsLibNormalCacheRuntime.telemetry().get("journalMisses"));
        assertEquals(Files.size(png), GraphicsLibNormalCacheRuntime.telemetry().get("validatedBytes"));
    }

    private interface TestSprite {
        float getHeight();

        float getWidth();

        float getTextureHeight();

        float getTextureWidth();

        int getTextureId();
    }
}
