package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextureManifestIOTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsAndSerializesSortedEntries() throws Exception {
        TextureManifest first = fixture(false);
        TextureManifest second = fixture(true);
        assertArrayEquals(TextureManifestIO.toBytes(first), TextureManifestIO.toBytes(second));

        Path output = temporaryDirectory.resolve("manifests/profile.spfm");
        TextureManifestIO.write(output, first);
        TextureManifest restored = TextureManifestIO.read(output);

        assertEquals(first.profileFingerprint(), restored.profileFingerprint());
        assertEquals(first.entries(), restored.entries());
        assertTrue(Files.isRegularFile(output));
    }

    @Test
    void rejectsCorruptAndTruncatedManifests() throws Exception {
        byte[] bytes = TextureManifestIO.toBytes(fixture(false));
        byte[] corrupt = bytes.clone();
        corrupt[corrupt.length / 2] ^= 0x22;
        IOException checksum = assertThrows(IOException.class, () -> TextureManifestIO.fromBytes(corrupt));
        assertTrue(checksum.getMessage().contains("checksum"));
        assertThrows(IOException.class, () -> TextureManifestIO.fromBytes(Arrays.copyOf(bytes, bytes.length - 3)));
    }

    @Test
    void readStopsAtConfiguredBoundBeforeParsing() throws Exception {
        TextureManifest manifest = fixture(false);
        byte[] bytes = TextureManifestIO.toBytes(manifest);
        Path file = temporaryDirectory.resolve("bounded.spfm");
        Files.write(file, bytes);

        assertEquals(manifest.entries(), TextureManifestIO.read(file, bytes.length).entries());
        IOException oversized = assertThrows(
                IOException.class, () -> TextureManifestIO.read(file, bytes.length - 1));
        assertTrue(oversized.getMessage().contains((bytes.length - 1) + " byte safety limit"));
        assertThrows(IllegalArgumentException.class, () -> TextureManifestIO.read(file, 32));
    }

    private static TextureManifest fixture(boolean reverse) {
        TextureManifest.Entry alpha = new TextureManifest.Entry(
                "aa".repeat(32),
                PreparedTexture.Transformation.IDENTITY,
                "blobs/aa/aa-identity.spft",
                2,
                2,
                4,
                16);
        TextureManifest.Entry beta = new TextureManifest.Entry(
                "bb".repeat(32),
                PreparedTexture.Transformation.IDENTITY,
                "blobs/bb/bb-identity.spft",
                1,
                1,
                3,
                3);
        Map<String, TextureManifest.Entry> entries = new LinkedHashMap<>();
        if (reverse) {
            entries.put("graphics/beta.png", beta);
            entries.put("graphics/alpha.png", alpha);
        } else {
            entries.put("graphics/alpha.png", alpha);
            entries.put("graphics/beta.png", beta);
        }
        return new TextureManifest("profile", entries);
    }
}
