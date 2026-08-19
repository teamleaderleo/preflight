package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextureSourceGenerationAuthorityTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void sameSizeRestoredMtimeMutationInvalidatesSealedGeneration() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        assumeTrue(os.contains("mac") || os.contains("darwin")
                || os.contains("win") || os.contains("linux") || os.contains("unix"));

        Path sourceRoot = Files.createDirectory(temporaryDirectory.resolve("source-root"));
        Path source = sourceRoot.resolve("graphics-test.png");
        Files.write(source, new byte[] {1, 2, 3, 4});
        FileTime originalMtime = Files.getLastModifiedTime(source);

        String profile = "generation-test-profile";
        ResourceIndex.Provider provider = new ResourceIndex.Provider(
                0,
                source.getFileName().toString(),
                Files.size(source),
                Math.max(0, originalMtime.toMillis()));
        ResourceIndex index = new ResourceIndex(
                profile,
                List.of(new ResourceIndex.Root("fixture", sourceRoot, false)),
                Map.of("graphics/test.png", List.of(provider)));

        Path cache = Files.createDirectory(temporaryDirectory.resolve("cache"));
        Path indexPath = ResourceIndexIO.directory(cache).resolve(profile + ".spfi");
        ResourceIndexIO.write(indexPath, index);

        TextureManifest manifest = new TextureManifest(
                profile,
                Map.of("graphics/test.png", new TextureManifest.Entry(
                        Hashes.sha256(source),
                        PreparedTexture.Transformation.IDENTITY,
                        "blobs/fixture.spft",
                        1,
                        1,
                        4,
                        4)));
        Path manifestPath = TextureManifestIO.directory(cache).resolve(profile + ".spfm");
        TextureManifestIO.write(manifestPath, manifest);

        TextureSourceGenerationAuthority.SealResult seal =
                TextureSourceGenerationAuthority.sealIfPossible(manifestPath, manifest);
        assertTrue(seal.available(),
                "the hosted filesystem must provide the reviewed source generation primitive: "
                        + seal.problem());

        Path proofPath = TextureSourceGenerationProofIO.path(cache, profile);
        assertTrue(Files.isRegularFile(proofPath), "generation proof must be persisted after sealing");

        TextureSourceGenerationAuthority.Validation before =
                TextureSourceGenerationAuthority.validate(cache, manifestPath, manifest, index);
        assertTrue(before.valid(), before.problem());

        Files.write(source, new byte[] {4, 3, 2, 1});
        Files.setLastModifiedTime(source, originalMtime);

        TextureSourceGenerationAuthority.Validation after =
                TextureSourceGenerationAuthority.validate(cache, manifestPath, manifest, index);
        assertFalse(after.valid(), "same-size/restored-mtime bytes must change the generation proof");
    }
}
