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

class TextureSourceGenerationProofIOTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsSortedOpaqueGenerationTokens() throws Exception {
        TextureSourceGenerationProof first = fixture(false);
        TextureSourceGenerationProof second = fixture(true);
        assertArrayEquals(
                TextureSourceGenerationProofIO.toBytes(first),
                TextureSourceGenerationProofIO.toBytes(second));

        Path output = temporaryDirectory.resolve("proofs/profile.sptg");
        TextureSourceGenerationProofIO.write(output, first);
        TextureSourceGenerationProof restored = TextureSourceGenerationProofIO.read(output);

        assertEquals(first.profileFingerprint(), restored.profileFingerprint());
        assertEquals(first.manifestSha256(), restored.manifestSha256());
        assertEquals(first.provider(), restored.provider());
        assertEquals(first.entries(), restored.entries());
        assertTrue(Files.isRegularFile(output));
    }

    @Test
    void rejectsCorruptAndTruncatedProofs() throws Exception {
        byte[] bytes = TextureSourceGenerationProofIO.toBytes(fixture(false));
        byte[] corrupt = bytes.clone();
        corrupt[corrupt.length / 2] ^= 0x22;
        IOException checksum = assertThrows(
                IOException.class,
                () -> TextureSourceGenerationProofIO.fromBytes(corrupt));
        assertTrue(checksum.getMessage().contains("checksum"));
        assertThrows(
                IOException.class,
                () -> TextureSourceGenerationProofIO.fromBytes(
                        Arrays.copyOf(bytes, bytes.length - 3)));
    }

    private static TextureSourceGenerationProof fixture(boolean reverse) {
        Map<String, String> entries = new LinkedHashMap<>();
        if (reverse) {
            entries.put("graphics/beta.png", "opaque-beta-generation");
            entries.put("graphics/alpha.png", "opaque-alpha-generation");
        } else {
            entries.put("graphics/alpha.png", "opaque-alpha-generation");
            entries.put("graphics/beta.png", "opaque-beta-generation");
        }
        return new TextureSourceGenerationProof(
                "profile",
                "ab".repeat(32),
                "test-generation-provider-v1",
                entries);
    }
}
