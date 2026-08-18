package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class TextureManifestUtf8CanonicalityTest {
    @Test
    void rejectsUnpairedSurrogateInProfileFingerprint() {
        TextureManifest manifest = new TextureManifest("profile-\uD800", Map.of());

        assertThrows(IOException.class, () -> TextureManifestIO.toBytes(manifest));
    }

    @Test
    void rejectsUnpairedSurrogateInLogicalPath() {
        TextureManifest.Entry entry = new TextureManifest.Entry(
                "00".repeat(32),
                PreparedTexture.Transformation.IDENTITY,
                "blobs/00.spft",
                1,
                1,
                4,
                4);
        TextureManifest manifest = new TextureManifest(
                "profile",
                Map.of("mods/\uD800.png", entry));

        assertThrows(IOException.class, () -> TextureManifestIO.toBytes(manifest));
    }
}
