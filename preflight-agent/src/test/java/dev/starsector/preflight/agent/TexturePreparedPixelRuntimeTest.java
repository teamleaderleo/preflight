package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.PreparedTexture;
import dev.starsector.preflight.core.PreparedTextureAccessOrderIO;
import dev.starsector.preflight.core.PreparedTextureIO;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceIndexIO;
import dev.starsector.preflight.core.TextureManifest;
import dev.starsector.preflight.core.TextureManifestIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TexturePreparedPixelRuntimeTest {
    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void resetRuntime() {
        System.clearProperty(TexturePaddingRuntime.UNPADDED_PROPERTY);
        System.clearProperty(TexturePaddingRuntime.MAX_UNPADDED_DIMENSION_PROPERTY);
        TexturePaddingRuntime.reset();
        System.clearProperty(TexturePreparedStagingRuntime.ENABLED_PROPERTY);
        TexturePreparedStagingRuntime.beginSession();
        TextureAccessLearningRuntime.beginSession();
        TexturePreparedPixelRuntime.beginSession();
        TextureCompatibilityRuntime.beginSession();
    }

    @Test
    void stagedCarrierIsConsumedWithoutWaitingOrChangingPreparedFallbacks() throws Exception {
        Fixture fixture = fixture();
        configure(fixture);
        String profile = TextureManifestIO.read(fixture.manifest()).profileFingerprint();
        PreparedTextureAccessOrderIO.write(
                PreparedTextureAccessOrderIO.path(fixture.cache(), profile),
                profile,
                List.of("graphics/test.png"));
        assertTrue(TextureAccessLearningRuntime.configure(fixture.cache(), profile));
        System.setProperty(TexturePreparedStagingRuntime.ENABLED_PROPERTY, "true");
        TexturePreparedStagingRuntime.beginSession();

        TexturePreparedStagingRuntime.start();
        long deadline = System.nanoTime() + 2_000_000_000L;
        while ((int) TexturePreparedStagingRuntime.telemetry().get("queuedEntries") == 0
                && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }

        BufferedImage image = TexturePreparedPixelRuntime.prefetchLoad("graphics/test.png");
        assertNotNull(image);
        Map<String, Object> staging = TexturePreparedStagingRuntime.telemetry();
        assertEquals(1L, staging.get("stagedHits"));
        assertEquals(0L, staging.get("ordinaryMisses"));
        assertEquals(0, staging.get("queuedEntries"));
        assertEquals(0L, staging.get("queuedBytes"));
        assertEquals(1L, TexturePreparedPixelRuntime.telemetry().get("prefetchPreparedHits"));
        assertEquals(0L, TexturePreparedPixelRuntime.telemetry().get("prefetchOriginalDecodes"));
    }

    @Test
    void servesANonPowerOfTwoTextureAtItsTrueSizeWhenPaddingRemovalIsOn() throws Exception {
        int width = 3;
        int height = 3;
        int channels = 3;
        byte[] source = sequential(width * height * channels);
        configure(fixture(width, height, channels, source));
        System.setProperty(TexturePaddingRuntime.UNPADDED_PROPERTY, "true");
        TexturePaddingRuntime.foldBypassInstalled();

        TexturePreparedPixelRuntime.PreparedPixel prepared = TexturePreparedPixelRuntime.prepare(
                TexturePreparedPixelRuntime.load("graphics/test.png"));

        // Without the gate this exact texture is declined; that is asserted separately below.
        assertNotNull(prepared, "padding removal must serve what the padded path refuses");
        assertEquals(source.length, prepared.buffer().remaining());
        assertArrayEquals(source, bytes(prepared.buffer()),
                "an unpadded buffer is the stored pixels verbatim, with no rows or columns invented");

        // The dimensions must be the source ones. The wrapper writes them onto the texture object,
        // whose setters recompute the texture-coordinate ratio as source/stored -- reporting 4x4 here
        // would leave every UV scaled for an allocation that is no longer being made.
        assertEquals(width, prepared.width());
        assertEquals(height, prepared.height());
        assertEquals(width * height * channels, prepared.pixelBytes());
    }

    @Test
    void declinesTheSameTextureWhileThePaddingGateIsOff() throws Exception {
        int width = 3;
        int height = 3;
        int channels = 3;
        configure(fixture(width, height, channels, sequential(width * height * channels)));

        // The default has to stay a decline, because a host that has not observed non-power-of-two
        // support on its own context must keep Starsector's original padded path.
        assertNull(TexturePreparedPixelRuntime.prepare(
                TexturePreparedPixelRuntime.load("graphics/test.png")));
        assertEquals(0L, TexturePaddingRuntime.report().get("texturesServedUnpadded"));
    }

    @Test
    void accountsForThePaddingItDidNotAllocate() throws Exception {
        int width = 3;
        int height = 3;
        int channels = 3;
        configure(fixture(width, height, channels, sequential(width * height * channels)));
        System.setProperty(TexturePaddingRuntime.UNPADDED_PROPERTY, "true");
        TexturePaddingRuntime.foldBypassInstalled();

        TexturePreparedPixelRuntime.prepare(TexturePreparedPixelRuntime.load("graphics/test.png"));

        // 3x3 pads to 4x4, so seven pixels of three channels are what this texture stops allocating.
        // Summed over a profile this is the number the roadmap states as 1.86 GiB.
        assertEquals(1L, TexturePaddingRuntime.report().get("texturesServedUnpadded"));
        assertEquals((long) (4 * 4 - 3 * 3) * channels,
                TexturePaddingRuntime.report().get("paddingBytesAvoided"));

        // The padded-upload telemetry must not also claim the padding, or the two reports would
        // disagree about whether those bytes were written.
        assertEquals(0L, TexturePreparedPixelRuntime.telemetry().get("paddingBytes"));
    }

    @Test
    void keepsTexturesAboveTheDiagnosticDimensionCeilingOnTheOriginalPath() throws Exception {
        int width = 3;
        int height = 3;
        int channels = 3;
        configure(fixture(width, height, channels, sequential(width * height * channels)));
        System.setProperty(TexturePaddingRuntime.UNPADDED_PROPERTY, "true");
        System.setProperty(TexturePaddingRuntime.MAX_UNPADDED_DIMENSION_PROPERTY, "2");
        TexturePaddingRuntime.foldBypassInstalled();

        assertNull(TexturePreparedPixelRuntime.prepare(
                TexturePreparedPixelRuntime.load("graphics/test.png")));
        assertEquals(1L, TexturePaddingRuntime.report().get("dimensionCeilingDeclines"));
        assertEquals(2, TexturePaddingRuntime.report().get("maxUnpaddedDimension"));
        assertEquals(0L, TexturePaddingRuntime.report().get("texturesServedUnpadded"));
    }

    @Test
    void leavesPowerOfTwoTexturesCompletelyAloneWhenPaddingRemovalIsOn() throws Exception {
        Fixture fixture = fixture();
        configure(fixture);
        System.setProperty(TexturePaddingRuntime.UNPADDED_PROPERTY, "true");

        TexturePreparedPixelRuntime.PreparedPixel prepared = TexturePreparedPixelRuntime.prepare(
                TexturePreparedPixelRuntime.load("graphics/test.png"));

        // A texture that never needed padding must be byte-identical with the gate on or off, so
        // that turning the lever on cannot be blamed for a change it did not make.
        assertNotNull(prepared);
        assertArrayEquals(fixture.pixels(), bytes(prepared.buffer()));
        assertEquals(2, prepared.width());
        assertEquals(2, prepared.height());
        assertEquals(0L, TexturePaddingRuntime.report().get("texturesServedUnpadded"));
    }

    @Test
    void suppliesPowerOfTwoPixelsStoredColorsAndBoundedOwnership() throws Exception {
        Fixture fixture = fixture();
        configure(fixture);

        BufferedImage carrier = TexturePreparedPixelRuntime.load("graphics/test.png");
        assertTrue(TexturePreparedPixelRuntime.isCarrier(carrier));
        assertEquals(2, carrier.getWidth());
        assertEquals(2, carrier.getHeight());
        assertEquals("graphics/test.png", TexturePreparedPixelRuntime.originalPath(carrier));

        TexturePreparedPixelRuntime.PreparedPixel first = TexturePreparedPixelRuntime.prepare(carrier);
        TexturePreparedPixelRuntime.PreparedPixel second = TexturePreparedPixelRuntime.prepare(carrier);
        assertNotNull(first);
        assertNotNull(second);
        assertTrue(first.buffer().isDirect());
        assertTrue(second.buffer().isDirect());
        assertNotSame(first.buffer(), second.buffer());
        assertEquals(0, first.buffer().position());
        assertEquals(fixture.pixels().length, first.buffer().limit());
        assertArrayEquals(fixture.pixels(), bytes(first.buffer()));
        assertEquals(first.pixelBytes(), first.buffer().remaining());
        assertEquals(first.width() * first.height() * first.channels(), first.buffer().remaining());
        assertEquals(2, first.width());
        assertEquals(2, first.height());
        assertEquals(0xff0a141e, first.color0().getRGB());
        assertEquals(0xff28323c, first.color1().getRGB());
        assertEquals(0xff46505a, first.color2().getRGB());

        Map<String, Object> active = TexturePreparedPixelRuntime.telemetry();
        assertEquals(2L, active.get("hits"));
        assertEquals(0L, active.get("npotProbeFallbacks"));
        assertEquals(2, active.get("activeBuffers"));
        assertEquals(0, active.get("pendingBuffers"));
        assertEquals(24L, active.get("activeDirectBytes"));
        assertEquals(24L, active.get("peakDirectBytes"));
        assertEquals(24L, active.get("bytesBypassed"));
        assertEquals(24L, active.get("uploadBytesSupplied"));

        TexturePreparedPixelRuntime.release(first.buffer());
        TexturePreparedPixelRuntime.release(second.buffer());
        Map<String, Object> released = TexturePreparedPixelRuntime.telemetry();
        assertEquals(0, released.get("activeBuffers"));
        assertEquals(0L, released.get("activeDirectBytes"));
        assertEquals(2L, released.get("releases"));
        assertEquals(24L, released.get("releasedBytes"));
    }

    @Test
    void npotPreparedPayloadFallsBackBeforeDirectAllocation() throws Exception {
        byte[] source = sequential(3 * 3 * 3);
        Fixture fixture = fixture(3, 3, 3, source);
        configure(fixture);

        BufferedImage carrier = TexturePreparedPixelRuntime.load("graphics/test.png");
        assertNotNull(carrier);
        assertNull(TexturePreparedPixelRuntime.prepare(carrier));

        Map<String, Object> telemetry = TexturePreparedPixelRuntime.telemetry();
        assertEquals(1L, telemetry.get("fallbacks"));
        assertEquals(1L, telemetry.get("npotProbeFallbacks"));
        assertEquals(0L, telemetry.get("hits"));
        assertEquals(0L, telemetry.get("paddedUploads"));
        assertEquals(0L, telemetry.get("paddingBytes"));
        assertEquals(0, telemetry.get("activeBuffers"));
        assertEquals(0L, telemetry.get("activeDirectBytes"));
        assertEquals(0L, telemetry.get("uploadBytesSupplied"));
    }

    @Test
    void classifiesOriginalUpperPlacementWithoutMutatingBuffer() throws Exception {
        int width = 3;
        int height = 3;
        int channels = 3;
        byte[] source = sequential(width * height * channels);
        Fixture fixture = fixture(width, height, channels, source);
        configure(fixture);
        BufferedImage carrier = TexturePreparedPixelRuntime.load("graphics/test.png");
        assertNull(TexturePreparedPixelRuntime.prepare(carrier));

        ByteBuffer original = ByteBuffer.allocateDirect(4 * 4 * channels);
        int uploadStride = 4 * channels;
        original.position(uploadStride);
        for (int row = 0; row < height; row++) {
            original.put(source, row * width * channels, width * channels);
            original.put(new byte[channels]);
        }
        original.flip();
        int position = original.position();
        int limit = original.limit();

        TexturePreparedPixelRuntime.observeOriginalFallback(carrier, original);

        assertEquals(position, original.position());
        assertEquals(limit, original.limit());
        Map<String, Object> observation = firstObservation();
        assertEquals("classified", observation.get("status"));
        @SuppressWarnings("unchecked")
        List<String> matches = (List<String>) observation.get("candidateMatches");
        assertTrue(matches.contains("zero-rows-then-row-pad-source"), matches.toString());
        assertFalse(matches.contains("row-pad-source-then-zero-rows"), matches.toString());
        assertEquals(0L, TexturePreparedPixelRuntime.telemetry().get("layoutObservationErrors"));
    }

    @Test
    void classifiesTheFailedLowerPlacementCandidate() throws Exception {
        int width = 3;
        int height = 3;
        int channels = 4;
        byte[] source = sequential(width * height * channels);
        Fixture fixture = fixture(width, height, channels, source);
        configure(fixture);
        BufferedImage carrier = TexturePreparedPixelRuntime.load("graphics/test.png");
        assertNull(TexturePreparedPixelRuntime.prepare(carrier));

        ByteBuffer original = ByteBuffer.allocateDirect(4 * 4 * channels);
        for (int row = 0; row < height; row++) {
            original.put(source, row * width * channels, width * channels);
            original.put(new byte[channels]);
        }
        original.put(new byte[4 * channels]);
        original.flip();

        TexturePreparedPixelRuntime.observeOriginalFallback(carrier, original);

        @SuppressWarnings("unchecked")
        List<String> matches = (List<String>) firstObservation().get("candidateMatches");
        assertTrue(matches.contains("row-pad-source-then-zero-rows"), matches.toString());
        assertFalse(matches.contains("zero-rows-then-row-pad-source"), matches.toString());
    }

    @Test
    void recordsInsufficientOriginalBufferWithoutChangingFallback() throws Exception {
        Fixture fixture = fixture(597, 373, 3, new byte[597 * 373 * 3]);
        configure(fixture);
        BufferedImage carrier = TexturePreparedPixelRuntime.load("graphics/test.png");
        assertNull(TexturePreparedPixelRuntime.prepare(carrier));
        ByteBuffer original = ByteBuffer.allocateDirect(668_043);

        TexturePreparedPixelRuntime.observeOriginalFallback(carrier, original);

        Map<String, Object> observation = firstObservation();
        assertEquals("insufficient-original-buffer", observation.get("status"));
        assertEquals(668_043, observation.get("bufferRemaining"));
        assertEquals(1_572_864, observation.get("uploadBytes"));
    }

    @Test
    void declinesUnexpectedPrePaddedBlobContractBeforeCreatingCarrier() throws Exception {
        Fixture fixture = fixture(3, 5, 4, 4, 8, new byte[4 * 8 * 4]);
        configure(fixture);

        assertNull(TexturePreparedPixelRuntime.load("graphics/test.png"));
        Map<String, Object> telemetry = TexturePreparedPixelRuntime.telemetry();
        assertEquals(1L, telemetry.get("fallbacks"));
        assertEquals(1L, telemetry.get("dimensionFallbacks"));
        assertEquals(0L, telemetry.get("directAttempts"));
        assertEquals(0, telemetry.get("activeBuffers"));
        assertEquals(0L, telemetry.get("activeDirectBytes"));
    }

    @Test
    void exceptionalCallerReleaseReturnsPowerOfTwoAccountingToZero() throws Exception {
        Fixture fixture = fixture();
        configure(fixture);

        TexturePreparedPixelRuntime.PreparedPixel prepared = TexturePreparedPixelRuntime.prepare(
                TexturePreparedPixelRuntime.load("graphics/test.png"));
        assertNotNull(prepared);
        assertEquals(1, TexturePreparedPixelRuntime.telemetry().get("activeBuffers"));
        TexturePreparedPixelRuntime.releaseCurrentThreadBuffer();
        TexturePreparedPixelRuntime.releaseCurrentThreadBuffer();

        Map<String, Object> telemetry = TexturePreparedPixelRuntime.telemetry();
        assertEquals(0, telemetry.get("activeBuffers"));
        assertEquals(0L, telemetry.get("activeDirectBytes"));
        assertEquals(1L, telemetry.get("releases"));
        assertEquals((long) prepared.pixelBytes(), telemetry.get("releasedBytes"));
    }

    @Test
    void compatibilitySelectionNeverReportsOrServesPreparedPixels() throws Exception {
        Fixture fixture = fixture();
        assertTrue(TextureCompatibilityRuntime.configure(
                fixture.cache(), fixture.manifest(), fixture.index()));
        TexturePreparedPixelRuntime.select(TextureAdapterMode.COMPATIBILITY);

        assertFalse(TexturePreparedPixelRuntime.ready());
        assertFalse(AdapterTransformationRegistry.hasPlan(TexturePreparedPixelRuntime.PLAN_ID));
        assertNull(TexturePreparedPixelRuntime.load("graphics/test.png"));
        assertEquals(Boolean.FALSE, TexturePreparedPixelRuntime.telemetry().get("ready"));
        assertEquals(0L, TextureCompatibilityRuntime.telemetry().get("attempts"));

        TexturePreparedPixelRuntime.select(TextureAdapterMode.PREPARED_PIXELS);
        assertTrue(TexturePreparedPixelRuntime.ready());
        assertTrue(AdapterTransformationRegistry.hasPlan(TexturePreparedPixelRuntime.PLAN_ID));

        TexturePreparedPixelRuntime.beginSession();
        assertFalse(TexturePreparedPixelRuntime.ready());
    }

    private Map<String, Object> firstObservation() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> observations =
                (List<Map<String, Object>>) TexturePreparedPixelRuntime.telemetry().get("originalLayoutObservations");
        assertEquals(1, observations.size());
        return observations.get(0);
    }

    private void configure(Fixture fixture) {
        TexturePreparedPixelRuntime.beginSession();
        assertTrue(TextureCompatibilityRuntime.configure(
                fixture.cache(), fixture.manifest(), fixture.index()));
        TexturePreparedPixelRuntime.select(TextureAdapterMode.PREPARED_PIXELS);
    }

    private Fixture fixture() throws Exception {
        byte[] pixels = {
                0, 0, (byte) 255,
                (byte) 255, (byte) 255, (byte) 255,
                (byte) 255, 0, 0,
                0, (byte) 255, 0
        };
        return fixture(2, 2, 3, pixels);
    }

    private Fixture fixture(int width, int height, int channels, byte[] pixels) throws Exception {
        return fixture(width, height, channels, width, height, pixels);
    }

    private Fixture fixture(
            int originalWidth,
            int originalHeight,
            int channels,
            int uploadWidth,
            int uploadHeight,
            byte[] pixels) throws Exception {
        Path cache = temporaryDirectory.resolve("cache-" + System.nanoTime());
        Path sourceRoot = temporaryDirectory.resolve("game-" + System.nanoTime());
        Path source = sourceRoot.resolve("graphics/test.png");
        Files.createDirectories(source.getParent());
        byte[] encoded = {1, 2, 3, 4};
        Files.write(source, encoded);
        String sourceHash = Hashes.sha256(encoded);
        String profile = "cd".repeat(32);
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
                originalWidth,
                originalHeight,
                uploadWidth,
                uploadHeight,
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
                        uploadWidth,
                        uploadHeight,
                        channels,
                        pixels.length)));
        Path manifestPath = cache.resolve("manifests").resolve(profile + ".spfm");
        TextureManifestIO.write(manifestPath, manifest);
        return new Fixture(cache, indexPath, manifestPath, pixels);
    }

    private static byte[] sequential(int length) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < length; index++) {
            bytes[index] = (byte) (index + 1);
        }
        return bytes;
    }

    private static byte[] bytes(ByteBuffer source) {
        ByteBuffer copy = source.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }

    private record Fixture(Path cache, Path index, Path manifest, byte[] pixels) {
        private Fixture {
            pixels = pixels.clone();
        }

        @Override
        public byte[] pixels() {
            return pixels.clone();
        }
    }
}
