package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.PreparedTexture;
import dev.starsector.preflight.core.PreparedTextureIO;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceIndexIO;
import dev.starsector.preflight.core.TextureManifest;
import dev.starsector.preflight.core.TextureManifestIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.lwjgl.opengl.GLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TexturePreparedPixelCoherentCarrierTest {
    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void resetRuntime() {
        System.clearProperty(TexturePreparedPixelRuntime.COHERENT_ORIGINAL_CONVERT_PROPERTY);
        System.clearProperty(TexturePreparedPixelRuntime.COHERENT_DIRECT_PROPERTY);
        System.clearProperty(TexturePaddingRuntime.UNPADDED_PROPERTY);
        System.clearProperty(TexturePaddingRuntime.MAX_UNPADDED_DIMENSION_PROPERTY);
        TexturePreparedPixelRuntime.beginSession();
        TexturePaddingRuntime.beginSession();
        TexturePaddingRuntime.reset();
        TextureCompatibilityRuntime.beginSession();
        GLContext.reset();
    }

    @Test
    void ordinaryWindowsCeilingRetainsPreparedPixelsForTheOriginalConverter() throws Exception {
        String originalOs = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");
            System.setProperty(TexturePaddingRuntime.UNPADDED_PROPERTY, "true");
            int width = 1735;
            int height = 1014;
            byte[] pixels = new byte[width * height * 3];
            pixels[0] = (byte) 255; // bottom-left is red in bottom-up SPFT storage
            pixels[(height - 1) * width * 3 + 1] = (byte) 255; // top-left is green
            configure(fixture(width, height, 3, pixels));
            TexturePaddingRuntime.foldBypassInstalled();

            assertTrue(TexturePaddingRuntime.availableFor(1024, 1024));
            assertFalse(TexturePaddingRuntime.availableFor(1025, 3));
            assertFalse(TexturePaddingRuntime.availableFor(3, 1025));
            BufferedImage carrier = TexturePreparedPixelRuntime.load("graphics/test.png");
            assertNotNull(carrier);
            assertEquals(0xffff0000, carrier.getRGB(0, height - 1));
            assertEquals(0xff00ff00, carrier.getRGB(0, 0));
            assertNull(TexturePreparedPixelRuntime.prepare(carrier));
            assertTrue(TexturePreparedPixelRuntime.useCarrierForOriginalFallback(carrier));
            assertFalse(TexturePaddingRuntime.unpadded());
            assertEquals(1024, TexturePaddingRuntime.report().get("maxUnpaddedDimension"));
            assertEquals(0, TexturePreparedPixelRuntime.telemetry().get("activeBuffers"));
            assertEquals(0L, TexturePreparedPixelRuntime.telemetry().get("conversionCallsBypassed"));
        } finally {
            System.setProperty("os.name", originalOs);
        }
    }

    @Test
    void windowsDefaultCeilingDoesNotChangeOtherPlatformsOrExplicitDiagnosticSettings() {
        String originalOs = System.getProperty("os.name");
        try {
            System.setProperty(TexturePaddingRuntime.UNPADDED_PROPERTY, "true");
            for (String platform : List.of("Linux", "Mac OS X", "Darwin", "unknown")) {
                System.setProperty("os.name", platform);
                assertEquals(0, TexturePaddingRuntime.report().get("maxUnpaddedDimension"));
                assertFalse(TexturePaddingRuntime.originalConversionForWindowsCeiling(1735, 1014));
            }
            System.setProperty("os.name", "Windows 11");
            assertTrue(TexturePaddingRuntime.originalConversionForWindowsCeiling(1735, 1014));
            System.setProperty(TexturePaddingRuntime.MAX_UNPADDED_DIMENSION_PROPERTY, "512");
            assertTrue(TexturePaddingRuntime.originalConversionForWindowsCeiling(513, 3));
            System.setProperty(TexturePaddingRuntime.MAX_UNPADDED_DIMENSION_PROPERTY, "0");
            assertFalse(TexturePaddingRuntime.originalConversionForWindowsCeiling(1735, 1014));
            assertEquals(0, TexturePaddingRuntime.report().get("maxUnpaddedDimension"));
            System.setProperty(TexturePaddingRuntime.MAX_UNPADDED_DIMENSION_PROPERTY, "invalid");
            assertEquals(1024, TexturePaddingRuntime.report().get("maxUnpaddedDimension"));
        } finally {
            System.setProperty("os.name", originalOs);
        }
    }

    @Test
    void optInNpotCarrierHasCoherentPixelsAndUsesOriginalConverterFallback() throws Exception {
        int width = 2;
        int height = 3;
        byte[] bottomUpRgb = {
                0, 0, (byte) 255,
                (byte) 255, (byte) 255, (byte) 255,
                0, 0, 0,
                (byte) 255, (byte) 255, 0,
                (byte) 255, 0, 0,
                0, (byte) 255, 0
        };
        Fixture fixture = fixture(width, height, 3, bottomUpRgb);
        System.setProperty(TexturePreparedPixelRuntime.COHERENT_ORIGINAL_CONVERT_PROPERTY, "true");
        configure(fixture);

        BufferedImage carrier = TexturePreparedPixelRuntime.load("graphics/test.png");
        assertTrue(TexturePreparedPixelRuntime.isCarrier(carrier));
        assertEquals(width, carrier.getWidth());
        assertEquals(height, carrier.getHeight());
        assertEquals(width, carrier.getWidth(null));
        assertEquals(height, carrier.getHeight(null));
        assertEquals(width, carrier.getRaster().getWidth());
        assertEquals(height, carrier.getRaster().getHeight());
        assertEquals(width, carrier.getSampleModel().getWidth());
        assertEquals(height, carrier.getSampleModel().getHeight());
        assertEquals(DataBuffer.TYPE_BYTE, carrier.getRaster().getDataBuffer().getDataType());
        assertFalse(carrier.getColorModel().hasAlpha());

        assertEquals(0xffff0000, carrier.getRGB(0, 0));
        assertEquals(0xff00ff00, carrier.getRGB(1, 0));
        assertEquals(0xff000000, carrier.getRGB(0, 1));
        assertEquals(0xffffff00, carrier.getRGB(1, 1));
        assertEquals(0xff0000ff, carrier.getRGB(0, 2));
        assertEquals(0xffffffff, carrier.getRGB(1, 2));

        assertNull(TexturePreparedPixelRuntime.prepare(carrier));
        assertTrue(TexturePreparedPixelRuntime.useCarrierForOriginalFallback(carrier));

        Map<String, Object> telemetry = TexturePreparedPixelRuntime.telemetry();
        assertEquals(1L, telemetry.get("carriers"));
        assertEquals(1L, telemetry.get("coherentCarriers"));
        assertEquals(18L, telemetry.get("coherentCarrierBytes"));
        assertEquals(0L, telemetry.get("coherentDirectCarriers"));
        assertEquals(1L, telemetry.get("fallbacks"));
        assertEquals(1L, telemetry.get("npotProbeFallbacks"));
        assertEquals(1L, telemetry.get("coherentOriginalConvertFallbacks"));
        assertEquals(1L, telemetry.get("coherentOriginalDecodeBypasses"));
        assertEquals(1L, telemetry.get("imageDecodesBypassed"));
        assertEquals(0L, telemetry.get("conversionCallsBypassed"));
        assertEquals(0L, telemetry.get("hits"));
        assertEquals(0, telemetry.get("activeBuffers"));
        assertEquals(0L, telemetry.get("activeDirectBytes"));

        Map<String, Object> cache = TextureCompatibilityRuntime.telemetry();
        assertEquals(1L, cache.get("attempts"));
        assertEquals(1L, cache.get("hits"));
        assertEquals(18L, cache.get("bytesServed"));
    }

    @Test
    void optInCoherentDirectNpotSuppliesExactObservedPaddedBuffer() throws Exception {
        int width = 3;
        int height = 3;
        int channels = 3;
        byte[] source = sequential(width * height * channels);
        Fixture fixture = fixture(width, height, channels, source);
        System.setProperty(TexturePreparedPixelRuntime.COHERENT_ORIGINAL_CONVERT_PROPERTY, "true");
        System.setProperty(TexturePreparedPixelRuntime.COHERENT_DIRECT_PROPERTY, "true");
        configure(fixture);

        BufferedImage carrier = TexturePreparedPixelRuntime.load("graphics/test.png");
        assertEquals(width, carrier.getRaster().getWidth());
        assertEquals(height, carrier.getRaster().getHeight());
        assertEquals(width, carrier.getSampleModel().getWidth());
        assertEquals(height, carrier.getSampleModel().getHeight());
        assertFalse(TexturePreparedPixelRuntime.useCarrierForOriginalFallback(carrier));

        TexturePreparedPixelRuntime.PreparedPixel prepared = TexturePreparedPixelRuntime.prepare(carrier);
        assertNotNull(prepared);
        assertEquals(4, prepared.width());
        assertEquals(4, prepared.height());
        assertEquals(channels, prepared.channels());
        assertEquals(48, prepared.pixelBytes());
        assertArrayEquals(rowPadded3x3Rgb(source), bytes(prepared.buffer()));
        assertEquals(0xff0a141e, prepared.color0().getRGB());
        assertEquals(0xff28323c, prepared.color1().getRGB());
        assertEquals(0xff46505a, prepared.color2().getRGB());

        Map<String, Object> active = TexturePreparedPixelRuntime.telemetry();
        assertEquals(Boolean.TRUE, active.get("coherentDirectEnabled"));
        assertEquals(1L, active.get("carriers"));
        assertEquals(1L, active.get("coherentCarriers"));
        assertEquals(1L, active.get("coherentDirectCarriers"));
        assertEquals(1L, active.get("coherentDirectHits"));
        assertEquals(1L, active.get("hits"));
        assertEquals(0L, active.get("fallbacks"));
        assertEquals(0L, active.get("npotProbeFallbacks"));
        assertEquals(1L, active.get("paddedUploads"));
        assertEquals(21L, active.get("paddingBytes"));
        assertEquals(27L, active.get("bytesBypassed"));
        assertEquals(48L, active.get("uploadBytesSupplied"));
        assertEquals(1, active.get("activeBuffers"));
        assertEquals(48L, active.get("activeDirectBytes"));

        TexturePreparedPixelRuntime.release(prepared.buffer());
        Map<String, Object> released = TexturePreparedPixelRuntime.telemetry();
        assertEquals(0, released.get("activeBuffers"));
        assertEquals(0L, released.get("activeDirectBytes"));
        assertEquals(1L, released.get("releases"));
        assertEquals(48L, released.get("releasedBytes"));
    }

    @Test
    void trueSizeFoldIsScopedToOneVerifiedPreparedUpload() throws Exception {
        int width = 3;
        int height = 3;
        int channels = 3;
        Fixture fixture = fixture(width, height, channels, sequential(width * height * channels));
        GLContext.setCapabilities(true, false);
        System.setProperty(TexturePaddingRuntime.UNPADDED_PROPERTY, "true");
        TexturePaddingRuntime.foldBypassInstalled();
        configure(fixture);

        assertFalse(TexturePreparedPixelRuntime.currentThreadHasTrueSizeUpload());
        assertFalse(TexturePaddingRuntime.unpadded(),
                "a cache miss or original decode has no prepared allocation permit");

        BufferedImage carrier = TexturePreparedPixelRuntime.load("graphics/test.png");
        TexturePreparedPixelRuntime.PreparedPixel prepared = TexturePreparedPixelRuntime.prepare(carrier);

        assertNotNull(prepared);
        assertEquals(width, prepared.width());
        assertEquals(height, prepared.height());
        assertEquals(width * height * channels, prepared.pixelBytes());
        assertTrue(TexturePreparedPixelRuntime.currentThreadHasTrueSizeUpload());
        assertTrue(TexturePaddingRuntime.unpadded());
        assertTrue(TexturePaddingRuntime.unpadded());
        assertFalse(TexturePreparedPixelRuntime.currentThreadHasTrueSizeUpload());
        assertFalse(TexturePaddingRuntime.unpadded(),
                "the exact loader has only two allocation-dimension folds per upload");

        TexturePreparedPixelRuntime.release(prepared.buffer());
        assertFalse(TexturePreparedPixelRuntime.currentThreadHasTrueSizeUpload());
        assertFalse(TexturePaddingRuntime.unpadded(),
                "cleanup restores the original fold before the next texture is considered");

        Map<String, Object> padding = TexturePaddingRuntime.report();
        assertEquals(2L, padding.get("dimensionsBypassed"));
        assertEquals(3L, padding.get("dimensionsFolded"));
        assertEquals(1L, padding.get("texturesServedUnpadded"));
        assertEquals(21L, padding.get("paddingBytesAvoided"));
    }

    @Test
    void optInNpotRgbaCarrierPreservesTopDownColorAndAlpha() throws Exception {
        byte[] bottomUpRgba = {
                1, 2, 3, 4,
                5, 6, 7, 8,
                9, 10, 11, 12,
                13, 14, 15, 16,
                17, 18, 19, 20,
                21, 22, 23, 24
        };
        Fixture fixture = fixture(2, 3, 4, bottomUpRgba);
        System.setProperty(TexturePreparedPixelRuntime.COHERENT_ORIGINAL_CONVERT_PROPERTY, "true");
        configure(fixture);

        BufferedImage carrier = TexturePreparedPixelRuntime.load("graphics/test.png");
        assertTrue(carrier.getColorModel().hasAlpha());
        assertEquals(2, carrier.getRaster().getWidth());
        assertEquals(3, carrier.getRaster().getHeight());
        assertEquals(0x14111213, carrier.getRGB(0, 0));
        assertEquals(0x18151617, carrier.getRGB(1, 0));
        assertEquals(0x0c090a0b, carrier.getRGB(0, 1));
        assertEquals(0x100d0e0f, carrier.getRGB(1, 1));
        assertEquals(0x04010203, carrier.getRGB(0, 2));
        assertEquals(0x08050607, carrier.getRGB(1, 2));
    }

    @Test
    void defaultNpotPathKeepsOriginalDecodeFallbackButNoLongerHandsOutATokenCarrier() throws Exception {
        // Until 2026-08-01 this path produced a 1x1 raster under real reported dimensions, and this
        // test asserted it. The NPOT *upload* policy below is unchanged -- prepare() still declines,
        // and the original converter still runs. What changed is that the image handed back is now
        // readable, which is what lets prepared-pixel mode take the prefetch bypass at all.
        Fixture fixture = fixture(2, 3, 3, new byte[18]);
        configure(fixture);

        BufferedImage carrier = TexturePreparedPixelRuntime.load("graphics/test.png");
        assertEquals(2, carrier.getWidth());
        assertEquals(3, carrier.getHeight());
        assertEquals(2, carrier.getWidth(null));
        assertEquals(3, carrier.getHeight(null));
        assertEquals(2, carrier.getRaster().getWidth());
        assertEquals(3, carrier.getRaster().getHeight());
        assertNull(TexturePreparedPixelRuntime.prepare(carrier));
        assertFalse(TexturePreparedPixelRuntime.useCarrierForOriginalFallback(carrier));

        Map<String, Object> telemetry = TexturePreparedPixelRuntime.telemetry();
        // The policy counters still count only what the NPOT flags select; the raster bytes count
        // every carrier, because every carrier now materialises one.
        assertEquals(0L, telemetry.get("coherentCarriers"));
        assertEquals(0L, telemetry.get("coherentCarrierBytes"));
        assertEquals(18L, telemetry.get("carrierRasterBytes"));
        assertEquals(0L, telemetry.get("coherentDirectCarriers"));
        assertEquals(0L, telemetry.get("coherentDirectHits"));
        assertEquals(0L, telemetry.get("coherentOriginalConvertFallbacks"));
        assertEquals(0L, telemetry.get("coherentOriginalDecodeBypasses"));
        assertEquals(0L, telemetry.get("paddedUploads"));
    }

    @Test
    void everyCarrierSurvivesAConsumerThatWalksItsWholeRaster() throws Exception {
        // The invariant that replaced servesUnreadableCarriers(). com.fs.graphics.oO0O is a
        // greyscale-to-alpha mask converter that loops 0..getWidth() x 0..getHeight() calling
        // raster.getPixel; against a 1x1 carrier that reported real dimensions it threw
        // ArrayIndexOutOfBoundsException and killed the load at 23.6s on 2026-08-01. Both a
        // power-of-two texture and an NPOT one are checked, because the crash came from the POT
        // minority -- the NPOT majority already took the readable path.
        for (int[] size : new int[][] {{4, 4}, {2, 3}}) {
            int width = size[0];
            int height = size[1];
            Fixture fixture = fixture(width, height, 4, sequential(width * height * 4));
            configure(fixture);

            BufferedImage carrier = TexturePreparedPixelRuntime.load("graphics/test.png");
            assertTrue(TexturePreparedPixelRuntime.isCarrier(carrier));
            assertEquals(width, carrier.getWidth());
            assertEquals(height, carrier.getHeight());

            int[] pixel = new int[4];
            for (int x = 0; x < carrier.getWidth(); x++) {
                for (int y = 0; y < carrier.getHeight(); y++) {
                    carrier.getRaster().getPixel(x, y, pixel);
                    carrier.getRGB(x, y);
                }
            }
            assertEquals(width * height, carrier.getData().getWidth() * carrier.getData().getHeight());
        }
    }

    @Test
    void directUploadDoesNotMaterializeTheReadableCarrierUntilAnotherConsumerAsks() throws Exception {
        byte[] bottomUpRgb = sequential(12);
        Fixture fixture = fixture(2, 2, 3, bottomUpRgb);
        configure(fixture);

        BufferedImage carrier = TexturePreparedPixelRuntime.load("graphics/test.png");
        assertTrue(TexturePreparedPixelRuntime.isCarrier(carrier));
        assertEquals(0xff070809, carrier.getRGB(0, 0));
        assertEquals(0xff0a0b0c, carrier.getRGB(1, 0));

        Map<String, Object> beforeUpload = TexturePreparedPixelRuntime.telemetry();
        assertEquals(0L, beforeUpload.get("carrierRasterMaterializations"));
        assertEquals(0L, beforeUpload.get("carrierRasterBytes"));

        TexturePreparedPixelRuntime.PreparedPixel prepared = TexturePreparedPixelRuntime.prepare(carrier);
        assertNotNull(prepared);
        assertArrayEquals(bottomUpRgb, bytes(prepared.buffer()));
        TexturePreparedPixelRuntime.release(prepared.buffer());

        Map<String, Object> afterUpload = TexturePreparedPixelRuntime.telemetry();
        assertEquals(0L, afterUpload.get("carrierRasterMaterializations"));
        assertEquals(0L, afterUpload.get("carrierRasterBytes"));

        assertTrue(carrier.getRaster().getDataBuffer() instanceof java.awt.image.DataBufferByte);
        Map<String, Object> afterRasterAccess = TexturePreparedPixelRuntime.telemetry();
        assertEquals(1L, afterRasterAccess.get("carrierRasterMaterializations"));
        assertEquals(12L, afterRasterAccess.get("carrierRasterBytes"));
    }

    private void configure(Fixture fixture) {
        TexturePreparedPixelRuntime.beginSession();
        assertTrue(TextureCompatibilityRuntime.configure(
                fixture.cache(), fixture.manifest(), fixture.index()));
        TexturePreparedPixelRuntime.select(TextureAdapterMode.PREPARED_PIXELS);
    }

    @Test
    void unpackScopeRequiresExactOwnedBufferAndHonorsOptOut() throws Exception {
        configure(fixture(2, 2, 3, sequential(12)));
        var prepared = TexturePreparedPixelRuntime.prepare(TexturePreparedPixelRuntime.load("graphics/test.png"));
        assertNotNull(prepared);
        ByteBuffer buffer = prepared.buffer();
        assertTrue(TexturePreparedPixelRuntime.requiresTightRgbUnpack(buffer, 2, 2, 6407, 5121));
        assertFalse(TexturePreparedPixelRuntime.requiresTightRgbUnpack(buffer.duplicate(), 2, 2, 6407, 5121));
        assertFalse(TexturePreparedPixelRuntime.requiresTightRgbUnpack(buffer, 2, 2, 6408, 5121));
        assertFalse(TexturePreparedPixelRuntime.requiresTightRgbUnpack(buffer, 2, 2, 6407, 5123));
        buffer.position(1);
        assertFalse(TexturePreparedPixelRuntime.requiresTightRgbUnpack(buffer, 2, 2, 6407, 5121));
        buffer.position(0);
        System.setProperty(TexturePreparedPixelRuntime.SCOPED_UNPACK_PROPERTY, "false");
        try {
            assertFalse(TexturePreparedPixelRuntime.requiresTightRgbUnpack(buffer, 2, 2, 6407, 5121));
        } finally {
            System.clearProperty(TexturePreparedPixelRuntime.SCOPED_UNPACK_PROPERTY);
        }
        assertTrue(TexturePreparedPixelRuntime.rgbAlignmentNeedsOverride(410, 4));
        assertFalse(TexturePreparedPixelRuntime.rgbAlignmentNeedsOverride(410, 2));
        assertFalse(TexturePreparedPixelRuntime.rgbAlignmentNeedsOverride(410, 1));
        assertFalse(TexturePreparedPixelRuntime.rgbAlignmentNeedsOverride(410, 0));
        TexturePreparedPixelRuntime.release(buffer);
        assertFalse(TexturePreparedPixelRuntime.requiresTightRgbUnpack(buffer, 2, 2, 6407, 5121));
    }

    @Test
    void packedConverterImagePreservesPixelsAndDeclinesExposedOrUnknownImages() throws Exception {
        BufferedImage ordinary = new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB);
        org.junit.jupiter.api.Assertions.assertSame(ordinary,
                TexturePreparedPixelRuntime.packedOriginalConverterImage(ordinary));
        for (int channels : new int[] {3, 4}) {
            configure(fixture(2, 3, channels, sequential(6 * channels)));
            BufferedImage carrier = TexturePreparedPixelRuntime.load("graphics/test.png");
            BufferedImage packed = TexturePreparedPixelRuntime.packedOriginalConverterImage(carrier);
            assertEquals(channels == 4 ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB, packed.getType());
            for (int y=0; y<3; y++) for (int x=0; x<2; x++) {
                assertEquals(carrier.getRGB(x, y), packed.getRGB(x, y));
            }
            org.junit.jupiter.api.Assertions.assertSame(packed.getRaster(),
                    TexturePreparedPixelRuntime.originalConverterRaster(packed));
            org.junit.jupiter.api.Assertions.assertNotSame(packed.getRaster(), packed.getData());
            org.junit.jupiter.api.Assertions.assertNotSame(ordinary.getRaster(),
                    TexturePreparedPixelRuntime.originalConverterRaster(ordinary));
            int original = carrier.getRGB(0, 0);
            packed.setRGB(0, 0, 0xffabcdef);
            assertEquals(original, carrier.getRGB(0, 0));
            carrier.getRaster().setSample(0, 0, 0, 77);
            org.junit.jupiter.api.Assertions.assertSame(carrier,
                    TexturePreparedPixelRuntime.packedOriginalConverterImage(carrier));
        }
    }

    @Test
    void stagedPackedSurfaceIsBoundedConsumedOnceAndInvalidatedOnExposure() throws Exception {
        String originalOs = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");
            System.setProperty(TexturePaddingRuntime.UNPADDED_PROPERTY, "true");
            System.setProperty(TexturePreparedResourceRuntime.PROPERTY, "true");
            for (int channels : new int[] {3, 4}) {
                int width = 1025, height = 3;
                configure(fixture(width, height, channels, sequential(width * height * channels)));
                BufferedImage carrier = TexturePreparedPixelRuntime.load("graphics/test.png");
                long raw = (long) width * height * channels;
                long total = raw + (long) width * height * Integer.BYTES;
                assertEquals(raw, TexturePreparedPixelRuntime.stageOriginalConverterImage(carrier, total - 1));
                java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
                Thread producer = new Thread(() -> {
                    try { assertEquals(total, TexturePreparedPixelRuntime.stageOriginalConverterImage(carrier, total)); }
                    catch (Throwable error) { failure.set(error); }
                });
                producer.start();
                producer.join(5000);
                assertFalse(producer.isAlive());
                assertNull(failure.get());
                assertEquals(total, TexturePreparedPixelRuntime.stageOriginalConverterImage(carrier, total));
                BufferedImage packed = TexturePreparedPixelRuntime.packedOriginalConverterImage(carrier);
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    assertEquals(carrier.getRGB(x, y), packed.getRGB(x, y));
                }
                assertEquals(1L, TexturePreparedPixelRuntime.telemetry().get("stagedPackedConverterUses"));
                org.junit.jupiter.api.Assertions.assertNotSame(packed,
                        TexturePreparedPixelRuntime.packedOriginalConverterImage(carrier));
                assertEquals(1L, TexturePreparedPixelRuntime.telemetry().get("stagedPackedConverterUses"));
                assertEquals(total, TexturePreparedPixelRuntime.stageOriginalConverterImage(carrier, total));
                carrier.getRaster().setSample(0, 0, 0, 77);
                org.junit.jupiter.api.Assertions.assertSame(carrier,
                        TexturePreparedPixelRuntime.packedOriginalConverterImage(carrier));
                assertEquals(raw, TexturePreparedPixelRuntime.stageOriginalConverterImage(carrier, total));
                assertEquals(1L, TexturePreparedPixelRuntime.telemetry().get("stagedPackedConverterUses"));
            }
        } finally {
            System.setProperty("os.name", originalOs);
            System.clearProperty(TexturePreparedResourceRuntime.PROPERTY);
        }
    }

    private Fixture fixture(int width, int height, int channels, byte[] pixels) throws Exception {
        Path cache = temporaryDirectory.resolve("cache-" + System.nanoTime());
        Path sourceRoot = temporaryDirectory.resolve("game-" + System.nanoTime());
        Path source = sourceRoot.resolve("graphics/test.png");
        Files.createDirectories(source.getParent());
        byte[] encoded = {1, 2, 3, 4};
        Files.write(source, encoded);
        String sourceHash = Hashes.sha256(encoded);
        String profile = "ac".repeat(32);
        ResourceIndex index = new ResourceIndex(
                profile,
                List.of(new ResourceIndex.Root("core", sourceRoot, true)),
                Map.of("graphics/test.png", List.of(new ResourceIndex.Provider(
                        0,
                        "graphics/test.png",
                        Files.size(source),
                        Files.getLastModifiedTime(source).toMillis()))));
        Path indexPath = cache.resolve("indexes").resolve(profile + ".spfi");
        ResourceIndexIO.write(indexPath, index);

        PreparedTexture texture = new PreparedTexture(
                sourceHash,
                PreparedTexture.Transformation.IDENTITY,
                width,
                height,
                width,
                height,
                channels,
                PreparedTexture.rgba(10, 20, 30, 255),
                PreparedTexture.rgba(40, 50, 60, 255),
                PreparedTexture.rgba(70, 80, 90, 255),
                pixels);
        String blobRelative = "blobs/" + sourceHash.substring(0, 2) + "/" + sourceHash + "-identity.spft";
        Path blob = cache.resolve(blobRelative);
        PreparedTextureIO.write(blob, texture);
        TextureManifest manifest = new TextureManifest(profile, Map.of(
                "graphics/test.png",
                new TextureManifest.Entry(
                        sourceHash,
                        PreparedTexture.Transformation.IDENTITY,
                        blobRelative,
                        width,
                        height,
                        channels,
                        pixels.length)));
        Path manifestPath = cache.resolve("manifests").resolve(profile + ".spfm");
        TextureManifestIO.write(manifestPath, manifest);
        return new Fixture(cache, indexPath, manifestPath);
    }

    private static byte[] sequential(int length) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < length; index++) {
            bytes[index] = (byte) (index + 1);
        }
        return bytes;
    }

    private static byte[] rowPadded3x3Rgb(byte[] source) {
        byte[] upload = new byte[4 * 4 * 3];
        for (int row = 0; row < 3; row++) {
            System.arraycopy(source, row * 9, upload, row * 12, 9);
        }
        return upload;
    }

    private static byte[] bytes(ByteBuffer source) {
        ByteBuffer copy = source.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }

    private record Fixture(Path cache, Path index, Path manifest) {
    }
}
