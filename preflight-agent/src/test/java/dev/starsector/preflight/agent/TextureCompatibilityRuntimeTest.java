package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.PreparedTexture;
import dev.starsector.preflight.core.PreparedTextureIO;
import dev.starsector.preflight.core.PreparedTexturePackIO;
import dev.starsector.preflight.core.PreparedTexturePackOrderIO;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceIndexIO;
import dev.starsector.preflight.core.TextureManifest;
import dev.starsector.preflight.core.TextureManifestIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextureCompatibilityRuntimeTest {
    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void resetRuntime() {
        TextureCompatibilityRuntime.beginSession();
        System.clearProperty(TextureCompatibilityRuntime.VERIFY_SOURCE_HASH_PROPERTY);
        System.clearProperty(TextureCompatibilityRuntime.VERIFY_BLOB_CHECKSUM_PROPERTY);
        System.clearProperty(TextureCompatibilityRuntime.TRUST_VALIDATED_INDEX_PROPERTY);
    }

    @Test
    void validHitReconstructsPreparedPixelsAndReportsBytes() throws Exception {
        Fixture fixture = fixture();
        assertTrue(TextureCompatibilityRuntime.configure(
                fixture.cache(), fixture.manifest(), fixture.index()));

        BufferedImage image = TextureCompatibilityRuntime.load("graphics/test.png");

        assertEquals(2, image.getWidth());
        assertEquals(2, image.getHeight());
        assertEquals(0xffff0000, image.getRGB(0, 0));
        assertEquals(0xff00ff00, image.getRGB(1, 0));
        assertEquals(0xff0000ff, image.getRGB(0, 1));
        assertEquals(0xffffffff, image.getRGB(1, 1));
        Map<String, Object> telemetry = TextureCompatibilityRuntime.telemetry();
        assertEquals(1L, telemetry.get("attempts"));
        assertEquals(1L, telemetry.get("hits"));
        assertEquals(0L, telemetry.get("fallbacks"));
        assertEquals(12L, telemetry.get("bytesServed"));
    }

    @Test
    void menuAndSessionEndCannotCountAsTwoIndependentPackOrderObservations() throws Exception {
        Fixture fixture = fixture();
        TextureManifest manifest = TextureManifestIO.read(fixture.manifest());
        String relative = manifest.entries().firstEntry().getValue().blobRelativePath();
        Path pack = PreparedTexturePackIO.path(fixture.cache(), manifest.profileFingerprint());
        Path order = PreparedTexturePackOrderIO.path(fixture.cache(), manifest.profileFingerprint());
        PreparedTexturePackIO.write(pack, manifest.profileFingerprint(), fixture.cache(), List.of(relative));
        String previous = System.getProperty(TextureCompatibilityRuntime.PACK_ORDER_SNAPSHOT_PROPERTY);
        System.setProperty(TextureCompatibilityRuntime.PACK_ORDER_SNAPSHOT_PROPERTY, "true");
        try {
            assertTrue(TextureCompatibilityRuntime.configure(fixture.cache(), fixture.manifest(), fixture.index()));
            assertNotNull(TextureCompatibilityRuntime.load("graphics/test.png"));
            TextureCompatibilityRuntime.completePackOrder();
            TextureCompatibilityRuntime.completePackOrder();
            assertEquals(true, TextureCompatibilityRuntime.telemetry().get("packOrderPersisted"));
            TextureCompatibilityRuntime.beginSession();
            assertEquals(List.of(), PreparedTexturePackOrderIO.read(order, manifest.profileFingerprint()));
            assertTrue(TextureCompatibilityRuntime.configure(fixture.cache(), fixture.manifest(), fixture.index()));
            assertNotNull(TextureCompatibilityRuntime.load("graphics/test.png"));
            TextureCompatibilityRuntime.completePackOrder();
            assertEquals(List.of(relative), PreparedTexturePackOrderIO.read(order, manifest.profileFingerprint()));
        } finally {
            if (previous == null) System.clearProperty(TextureCompatibilityRuntime.PACK_ORDER_SNAPSHOT_PROPERTY);
            else System.setProperty(TextureCompatibilityRuntime.PACK_ORDER_SNAPSHOT_PROPERTY, previous);
        }
    }

    @Test
    void validProfilePackServesWithoutOpeningTheLooseBlob() throws Exception {
        Fixture fixture = fixture();
        TextureManifest manifest = TextureManifestIO.read(fixture.manifest());
        String relative = manifest.entries().firstEntry().getValue().blobRelativePath();
        Path pack = PreparedTexturePackIO.path(fixture.cache(), manifest.profileFingerprint());
        PreparedTexturePackIO.write(
                pack, manifest.profileFingerprint(), fixture.cache(), List.of(relative));
        Files.delete(fixture.blob());

        assertTrue(TextureCompatibilityRuntime.configure(
                fixture.cache(), fixture.manifest(), fixture.index()));
        assertNotNull(TextureCompatibilityRuntime.load("graphics/test.png"));

        Map<String, Object> telemetry = TextureCompatibilityRuntime.telemetry();
        assertEquals(true, telemetry.get("packedStoreAvailable"));
        assertEquals(true, telemetry.get("packedStoreActive"));
        assertEquals(1L, telemetry.get("packHits"));
        assertEquals(12L, telemetry.get("packBytes"));
        assertEquals(0L, telemetry.get("packFailures"));
        assertEquals(0L, telemetry.get("packSingleReadLz4Hits"));

        TextureCompatibilityRuntime.beginSession();
        assertEquals(
                List.of(),
                PreparedTexturePackOrderIO.read(
                        PreparedTexturePackOrderIO.path(
                                fixture.cache(), manifest.profileFingerprint()),
                        manifest.profileFingerprint()));

        assertTrue(TextureCompatibilityRuntime.configure(
                fixture.cache(), fixture.manifest(), fixture.index()));
        assertNotNull(TextureCompatibilityRuntime.load("graphics/test.png"));
        TextureCompatibilityRuntime.beginSession();
        assertEquals(
                List.of(relative),
                PreparedTexturePackOrderIO.read(
                        PreparedTexturePackOrderIO.path(
                                fixture.cache(), manifest.profileFingerprint()),
                        manifest.profileFingerprint()));
    }

    @Test
    void packReadFailureDisablesItAndFallsBackToTheLooseBlob() throws Exception {
        Fixture fixture = fixture();
        TextureManifest manifest = TextureManifestIO.read(fixture.manifest());
        String relative = manifest.entries().firstEntry().getValue().blobRelativePath();
        Path pack = PreparedTexturePackIO.path(fixture.cache(), manifest.profileFingerprint());
        PreparedTexturePackIO.write(
                pack, manifest.profileFingerprint(), fixture.cache(), List.of(relative));

        assertTrue(TextureCompatibilityRuntime.configure(
                fixture.cache(), fixture.manifest(), fixture.index()));
        Files.write(pack, new byte[] {1});

        assertNotNull(TextureCompatibilityRuntime.load("graphics/test.png"));
        Map<String, Object> telemetry = TextureCompatibilityRuntime.telemetry();
        assertEquals(true, telemetry.get("packedStoreAvailable"));
        assertEquals(false, telemetry.get("packedStoreActive"));
        assertEquals(0L, telemetry.get("packHits"));
        assertEquals(1L, telemetry.get("packFailures"));
        assertEquals(1L, telemetry.get("hits"));
        assertEquals(0L, telemetry.get("fallbacks"));
    }

    /**
     * The served image shares its backing array with the caller instead of being copied through
     * {@link BufferedImage#setRGB}. That is only safe while it stays indistinguishable from the
     * copying form, and the part a refactor could quietly break is the reported type: a colour model
     * whose masks drift lands on {@code TYPE_CUSTOM}, which the game may treat differently. This
     * asserts against images built the old way rather than against literals, so the two cannot
     * diverge without failing here.
     */
    @Test
    void servedImageIsIndistinguishableFromTheCopyingConstruction() throws Exception {
        for (int channels : new int[] {3, 4}) {
            TextureCompatibilityRuntime.beginSession();
            Fixture fixture = fixture(channels);
            assertTrue(TextureCompatibilityRuntime.configure(
                    fixture.cache(), fixture.manifest(), fixture.index()));

            BufferedImage image = TextureCompatibilityRuntime.load("graphics/test.png");

            // Derived from the fixture's bottom-up bytes, not from the image under test: the stored
            // rows arrive flipped, so the row written last is the top one. The 4-channel case keeps
            // a transparent and a half-transparent pixel, which is where a premultiplying colour
            // model would part company with the copying form.
            int[] expected = channels == 4
                    ? new int[] {0xffff0000, 0x0000ff00, 0xff0000ff, 0x80ffffff}
                    : new int[] {0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffffff};
            int expectedType = channels == 4 ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;

            BufferedImage reference = new BufferedImage(2, 2, expectedType);
            reference.setRGB(0, 0, 2, 2, expected, 0, 2);

            assertEquals(expectedType, image.getType(), "channels=" + channels);
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) {
                    assertEquals(expected[y * 2 + x], image.getRGB(x, y),
                            "channels=" + channels + " at " + x + "," + y);
                    assertEquals(reference.getRGB(x, y), image.getRGB(x, y),
                            "channels=" + channels + " at " + x + "," + y);
                }
            }
        }
    }

    @Test
    void coldMissAndChangedSourceReturnNull() throws Exception {
        Fixture fixture = fixture();
        assertTrue(TextureCompatibilityRuntime.configure(
                fixture.cache(), fixture.manifest(), fixture.index()));

        assertNull(TextureCompatibilityRuntime.load("graphics/missing.png"));
        // A different length, so this is decided by the size comparison rather than by whether the
        // filesystem's timestamp resolution happened to tick between the two writes.
        Files.write(fixture.source(), new byte[] {9, 9, 9, 9, 9});
        assertNull(TextureCompatibilityRuntime.load("graphics/test.png"));

        Map<String, Object> telemetry = TextureCompatibilityRuntime.telemetry();
        assertEquals(2L, telemetry.get("misses"));
        assertEquals(2L, telemetry.get("fallbacks"));
        @SuppressWarnings("unchecked")
        Map<String, Object> reasons = (Map<String, Object>) telemetry.get("fallbackReasons");
        assertEquals(1L, reasons.get("entry-missing"));
        assertEquals(1L, reasons.get("source-changed"));
    }

    @Test
    void aSourceTouchedSinceTheIndexWasBuiltIsRejected() throws Exception {
        Fixture fixture = fixture();
        assertTrue(TextureCompatibilityRuntime.configure(
                fixture.cache(), fixture.manifest(), fixture.index()));
        assertNotNull(TextureCompatibilityRuntime.load("graphics/test.png"));

        // Same length and same bytes; only the modification time moves. This is the half of the
        // staleness test that catches an in-place edit the size cannot.
        Files.setLastModifiedTime(
                fixture.source(),
                FileTime.fromMillis(Files.getLastModifiedTime(fixture.source()).toMillis() + 5_000));

        assertNull(TextureCompatibilityRuntime.load("graphics/test.png"));
        @SuppressWarnings("unchecked")
        Map<String, Object> reasons =
                (Map<String, Object>) TextureCompatibilityRuntime.telemetry().get("fallbackReasons");
        assertEquals(1L, reasons.get("source-changed"));
    }

    @Test
    void diagnosticValidatedIndexSnapshotSkipsOnlyPostConfigureSourceChanges() throws Exception {
        Fixture fixture = fixture();
        assertTrue(TextureCompatibilityRuntime.configure(
                fixture.cache(), fixture.manifest(), fixture.index()));
        System.setProperty(TextureCompatibilityRuntime.TRUST_VALIDATED_INDEX_PROPERTY, "true");
        Files.write(fixture.source(), new byte[] {9, 9, 9, 9, 9});

        assertNotNull(TextureCompatibilityRuntime.load("graphics/test.png"));
        assertEquals(true, TextureCompatibilityRuntime.telemetry().get("trustedValidatedIndex"));
    }

    /**
     * The cost of taking SHA-256 off the loading thread, stated as a test rather than left in a
     * comment. An edit that preserves both a file's length and its modification time to the
     * millisecond is not detected by default; it is detected under
     * {@code -Dpreflight.texture.verifySourceHash=true}. Every real mod update changes at least one
     * of the two, and hashing 1.34 GB of sources per launch cost 41% of the loading thread's CPU
     * under Rosetta 2, where the JVM's SHA-256 intrinsic cannot apply.
     */
    @Test
    void anEditThatPreservesSizeAndTimestampIsCaughtOnlyByTheOptInHash() throws Exception {
        Fixture fixture = fixture();
        assertTrue(TextureCompatibilityRuntime.configure(
                fixture.cache(), fixture.manifest(), fixture.index()));
        FileTime original = Files.getLastModifiedTime(fixture.source());
        Files.write(fixture.source(), new byte[] {4, 3, 2, 1});
        Files.setLastModifiedTime(fixture.source(), original);

        assertNotNull(TextureCompatibilityRuntime.load("graphics/test.png"));

        System.setProperty(TextureCompatibilityRuntime.VERIFY_SOURCE_HASH_PROPERTY, "true");
        try {
            assertNull(TextureCompatibilityRuntime.load("graphics/test.png"));
        } finally {
            System.clearProperty(TextureCompatibilityRuntime.VERIFY_SOURCE_HASH_PROPERTY);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> reasons =
                (Map<String, Object>) TextureCompatibilityRuntime.telemetry().get("fallbackReasons");
        assertEquals(1L, reasons.get("source-changed"));
    }

    @Test
    void corruptBlobIsQuarantinedAndReturnsNull() throws Exception {
        Fixture fixture = fixture();
        assertTrue(TextureCompatibilityRuntime.configure(
                fixture.cache(), fixture.manifest(), fixture.index()));
        Files.write(fixture.blob(), new byte[] {1, 2, 3});

        assertNull(TextureCompatibilityRuntime.load("graphics/test.png"));

        Map<String, Object> telemetry = TextureCompatibilityRuntime.telemetry();
        assertEquals(1L, telemetry.get("corruptions"));
        assertEquals(1L, telemetry.get("quarantined"));
        assertFalse(Files.exists(fixture.blob()));
        try (var files = Files.list(fixture.cache().resolve("quarantine"))) {
            assertEquals(1, files.count());
        }
    }

    /**
     * Prepared blobs are content-addressed, written atomically by Preflight, and structurally
     * checked while being read. The default runtime path therefore does not spend the loading
     * thread hashing all of their pixels again. Full checksum verification remains available for
     * diagnostics and continues to be the default everywhere outside this in-game hot path.
     */
    @Test
    void sameLengthPixelCorruptionIsCaughtOnlyByTheOptInBlobChecksum() throws Exception {
        Fixture fixture = fixture();
        assertTrue(TextureCompatibilityRuntime.configure(
                fixture.cache(), fixture.manifest(), fixture.index()));
        byte[] blob = Files.readAllBytes(fixture.blob());
        blob[blob.length - 32 - 1] ^= 0x44;
        Files.write(fixture.blob(), blob);

        assertNotNull(TextureCompatibilityRuntime.load("graphics/test.png"));
        assertEquals(false, TextureCompatibilityRuntime.telemetry().get("blobChecksumVerification"));

        TextureCompatibilityRuntime.beginSession();
        System.setProperty(TextureCompatibilityRuntime.VERIFY_BLOB_CHECKSUM_PROPERTY, "true");
        assertTrue(TextureCompatibilityRuntime.configure(
                fixture.cache(), fixture.manifest(), fixture.index()));
        assertNull(TextureCompatibilityRuntime.load("graphics/test.png"));

        Map<String, Object> telemetry = TextureCompatibilityRuntime.telemetry();
        assertEquals(true, telemetry.get("blobChecksumVerification"));
        assertEquals(1L, telemetry.get("corruptions"));
        assertEquals(1L, telemetry.get("quarantined"));
        assertFalse(Files.exists(fixture.blob()));
    }

    @Test
    void staleIndexDisablesPilotBeforeAnyClassRewrite() throws Exception {
        Fixture fixture = fixture();
        Files.write(fixture.source(), new byte[] {7});

        assertFalse(TextureCompatibilityRuntime.configure(
                fixture.cache(), fixture.manifest(), fixture.index()));
        assertFalse(TextureCompatibilityRuntime.ready());

        @SuppressWarnings("unchecked")
        List<String> reasons = (List<String>) TextureCompatibilityRuntime.telemetry().get("disableReasons");
        assertTrue(reasons.contains("index-stale"), reasons.toString());
    }

    @Test
    void launcherValidatedSnapshotDoesNotWalkEveryProviderAgain() throws Exception {
        Fixture fixture = fixture();
        Files.write(fixture.index(), new byte[] {7});
        System.setProperty(TextureCompatibilityRuntime.TRUST_VALIDATED_INDEX_PROPERTY, "true");

        assertTrue(TextureCompatibilityRuntime.configure(
                fixture.cache(), fixture.manifest(), fixture.index()));
        assertTrue(TextureCompatibilityRuntime.ready());
        assertNotNull(TextureCompatibilityRuntime.load("graphics/test.png"));
        assertEquals(true, TextureCompatibilityRuntime.telemetry().get("trustedValidatedIndex"));
    }

    @Test
    void configurationRejectsMetadataLinksOutsideTheCache() throws Exception {
        Fixture fixture = fixture();
        Path externalManifest = temporaryDirectory.resolve("external.spfm");
        Files.move(fixture.manifest(), externalManifest);
        createSymbolicLinkOrSkip(fixture.manifest(), externalManifest);

        assertFalse(TextureCompatibilityRuntime.configure(
                fixture.cache(), fixture.manifest(), fixture.index()));

        @SuppressWarnings("unchecked")
        List<String> reasons = (List<String>) TextureCompatibilityRuntime.telemetry().get("disableReasons");
        assertTrue(reasons.contains("invalid-configuration"), reasons.toString());
    }

    @Test
    void lookupRejectsSourceLinkRetargetedOutsideItsRoot() throws Exception {
        Fixture fixture = fixture();
        assertTrue(TextureCompatibilityRuntime.configure(
                fixture.cache(), fixture.manifest(), fixture.index()));
        Path externalSource = temporaryDirectory.resolve("external-source.png");
        Files.move(fixture.source(), externalSource);
        createSymbolicLinkOrSkip(fixture.source(), externalSource);

        assertNull(TextureCompatibilityRuntime.load("graphics/test.png"));

        @SuppressWarnings("unchecked")
        Map<String, Object> reasons =
                (Map<String, Object>) TextureCompatibilityRuntime.telemetry().get("fallbackReasons");
        assertEquals(1L, reasons.get("path-invalid"));
    }

    @Test
    void lookupRejectsBlobLinkRetargetedOutsideTheCache() throws Exception {
        Fixture fixture = fixture();
        assertTrue(TextureCompatibilityRuntime.configure(
                fixture.cache(), fixture.manifest(), fixture.index()));
        Path externalBlob = temporaryDirectory.resolve("external.spft");
        Files.move(fixture.blob(), externalBlob);
        createSymbolicLinkOrSkip(fixture.blob(), externalBlob);

        assertNull(TextureCompatibilityRuntime.load("graphics/test.png"));

        @SuppressWarnings("unchecked")
        Map<String, Object> reasons =
                (Map<String, Object>) TextureCompatibilityRuntime.telemetry().get("fallbackReasons");
        assertEquals(1L, reasons.get("blob-missing"));
    }

    private Fixture fixture() throws Exception {
        return fixture(3);
    }

    private Fixture fixture(int channels) throws Exception {
        Path cache = temporaryDirectory.resolve("cache");
        Path sourceRoot = temporaryDirectory.resolve("game");
        Path source = sourceRoot.resolve("graphics/test.png");
        Files.createDirectories(source.getParent());
        byte[] encoded = {1, 2, 3, 4};
        Files.write(source, encoded);
        String sourceHash = Hashes.sha256(encoded);
        String profile = "ab".repeat(32);
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

        byte[] bottomUpRgb = channels == 4
                ? new byte[] {
                        0, 0, (byte) 255, (byte) 255,
                        (byte) 255, (byte) 255, (byte) 255, (byte) 128,
                        (byte) 255, 0, 0, (byte) 255,
                        0, (byte) 255, 0, 0
                }
                : new byte[] {
                        0, 0, (byte) 255,
                        (byte) 255, (byte) 255, (byte) 255,
                        (byte) 255, 0, 0,
                        0, (byte) 255, 0
                };
        PreparedTexture texture = new PreparedTexture(
                sourceHash,
                PreparedTexture.Transformation.IDENTITY,
                2,
                2,
                2,
                2,
                channels,
                0,
                0,
                0,
                bottomUpRgb);
        String blobRelative = "blobs/" + sourceHash.substring(0, 2) + "/" + sourceHash + "-identity.spft";
        Path blob = cache.resolve(blobRelative);
        PreparedTextureIO.write(blob, texture);
        TextureManifest manifest = new TextureManifest(profile, Map.of(
                "graphics/test.png",
                new TextureManifest.Entry(
                        sourceHash,
                        PreparedTexture.Transformation.IDENTITY,
                        blobRelative,
                        2,
                        2,
                        channels,
                        bottomUpRgb.length)));
        Path manifestPath = cache.resolve("manifests").resolve(profile + ".spfm");
        TextureManifestIO.write(manifestPath, manifest);
        return new Fixture(cache, source, indexPath, manifestPath, blob);
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target.toAbsolutePath());
        } catch (UnsupportedOperationException | SecurityException | IOException error) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable: " + error.getMessage());
        }
    }

    private record Fixture(Path cache, Path source, Path index, Path manifest, Path blob) {
    }
}
