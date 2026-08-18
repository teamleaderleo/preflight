package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.PreparedTexture;
import dev.starsector.preflight.core.PreparedTextureIO;
import dev.starsector.preflight.core.PreparedTexturePack;
import dev.starsector.preflight.core.PreparedTexturePackIO;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceIndexIO;
import dev.starsector.preflight.core.TextureManifest;
import dev.starsector.preflight.core.TextureManifestIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextureCompatibilityPackIntegrityTest {
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
    void packFailureForcesVerifiedLooseFallbackAndQuarantinesLaterCorruption() throws Exception {
        Fixture fixture = fixture();
        TextureManifest manifest = TextureManifestIO.read(fixture.manifest());
        String relative = manifest.entries().firstEntry().getValue().blobRelativePath();
        Path packPath = PreparedTexturePackIO.path(fixture.cache(), manifest.profileFingerprint());
        PreparedTexturePackIO.write(
                packPath, manifest.profileFingerprint(), fixture.cache(), List.of(relative));

        byte[] looseBytes = Files.readAllBytes(fixture.blob());
        looseBytes[looseBytes.length - 32 - 1] ^= 0x44;
        Files.write(fixture.blob(), looseBytes);
        assertThrows(IOException.class, () -> PreparedTextureIO.read(fixture.blob()));

        byte[] packBytes = Files.readAllBytes(packPath);
        packBytes[packBytes.length - 1] ^= 0x40;
        Files.write(packPath, packBytes);
        try (PreparedTexturePack pack = PreparedTexturePackIO.open(
                packPath, manifest.profileFingerprint(), List.of(relative))) {
            assertThrows(IOException.class, () -> pack.readTrusted(relative));
        }

        assertTrue(TextureCompatibilityRuntime.configure(
                fixture.cache(), fixture.manifest(), fixture.index()));
        assertEquals(true, TextureCompatibilityRuntime.telemetry().get("packedStoreAvailable"));

        assertNull(TextureCompatibilityRuntime.load("graphics/test.png"));

        Map<String, Object> telemetry = TextureCompatibilityRuntime.telemetry();
        assertEquals(false, telemetry.get("packedStoreActive"));
        assertEquals(0L, telemetry.get("packHits"));
        assertEquals(1L, telemetry.get("packFailures"));
        assertEquals(0L, telemetry.get("hits"));
        assertEquals(1L, telemetry.get("fallbacks"));
        assertEquals(1L, telemetry.get("corruptions"));
        assertEquals(1L, telemetry.get("quarantined"));
        @SuppressWarnings("unchecked")
        Map<String, Object> reasons = (Map<String, Object>) telemetry.get("fallbackReasons");
        assertEquals(1L, reasons.get("blob-corrupt"));
        assertFalse(Files.exists(fixture.blob()));
        try (var files = Files.list(fixture.cache().resolve("quarantine"))) {
            assertEquals(1, files.count());
        }
    }

    private Fixture fixture() throws Exception {
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

        byte[] bottomUpRgb = {
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
                3,
                0,
                0,
                0,
                bottomUpRgb);
        String blobRelative = "blobs/" + sourceHash.substring(0, 2) + "/" + sourceHash + "-identity.spft";
        Path blob = cache.resolve(blobRelative);
        PreparedTextureIO.write(blob, texture, PreparedTextureIO.StorageCodec.RAW);
        TextureManifest manifest = new TextureManifest(profile, Map.of(
                "graphics/test.png",
                new TextureManifest.Entry(
                        sourceHash,
                        PreparedTexture.Transformation.IDENTITY,
                        blobRelative,
                        2,
                        2,
                        3,
                        bottomUpRgb.length)));
        Path manifestPath = cache.resolve("manifests").resolve(profile + ".spfm");
        TextureManifestIO.write(manifestPath, manifest);
        return new Fixture(cache, indexPath, manifestPath, blob);
    }

    private record Fixture(Path cache, Path index, Path manifest, Path blob) {}
}
