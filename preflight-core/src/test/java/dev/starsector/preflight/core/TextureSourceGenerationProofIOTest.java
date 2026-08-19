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
    void boundedPathReadRejectsBytesBeyondTheConfiguredLimit() throws Exception {
        byte[] bytes = TextureSourceGenerationProofIO.toBytes(fixture(false));
        Path output = temporaryDirectory.resolve("growing.sptg");
        byte[] oversized = Arrays.copyOf(bytes, bytes.length + 1);
        oversized[oversized.length - 1] = 1;
        Files.write(output, oversized);

        IOException error = assertThrows(
                IOException.class,
                () -> TextureSourceGenerationProofIO.read(output, bytes.length));

        assertTrue(error.getMessage().contains("size is invalid"));
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

    @Test
    void proofPathAcceptsOnlyOnePortableProfileComponent() {
        Path cache = temporaryDirectory.resolve("cache");
        assertEquals(
                cache.resolve("texture-source-generations-v1/profile-01_alpha.beta.sptg"),
                TextureSourceGenerationProofIO.path(cache, "profile-01_alpha.beta"));

        for (String profile : new String[] {
                "../escape",
                "folder/profile",
                "folder\\profile",
                "drive:profile",
                ".",
                "..",
                "profile?query",
                "profile*glob",
                "profile|pipe",
                "profile\u0000nul",
                "prøfile"
        }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TextureSourceGenerationProofIO.path(cache, profile),
                    profile);
        }
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
