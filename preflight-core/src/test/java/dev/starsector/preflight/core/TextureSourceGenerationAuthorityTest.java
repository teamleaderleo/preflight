package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        Fixture fixture = fixture("generation-test-profile");
        FileTime originalMtime = Files.getLastModifiedTime(fixture.source());

        TextureSourceGenerationAuthority.SealResult seal =
                TextureSourceGenerationAuthority.sealIfPossible(fixture.manifestPath(), fixture.manifest());
        assertTrue(seal.available(),
                "the hosted filesystem must provide the reviewed source generation primitive: "
                        + seal.problem());

        Path proofPath = TextureSourceGenerationProofIO.path(fixture.cache(), fixture.profile());
        assertTrue(Files.isRegularFile(proofPath), "generation proof must be persisted after sealing");

        TextureSourceGenerationAuthority.Validation before =
                TextureSourceGenerationAuthority.validate(
                        fixture.cache(), fixture.manifestPath(), fixture.manifest(), fixture.index());
        assertTrue(before.valid(), before.problem());

        Files.write(fixture.source(), new byte[] {4, 3, 2, 1});
        Files.setLastModifiedTime(fixture.source(), originalMtime);

        TextureSourceGenerationAuthority.Validation after =
                TextureSourceGenerationAuthority.validate(
                        fixture.cache(), fixture.manifestPath(), fixture.manifest(), fixture.index());
        assertFalse(after.valid(), "same-size/restored-mtime bytes must change the generation proof");
    }

    @Test
    void macGenerationArchiveTokenIsStableAcrossIndependentCaptures() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        assumeTrue(os.contains("mac") || os.contains("darwin"));

        Fixture fixture = fixture("mac-generation-token-stability");
        Path proofPath = TextureSourceGenerationProofIO.path(fixture.cache(), fixture.profile());

        TextureSourceGenerationAuthority.SealResult firstSeal =
                TextureSourceGenerationAuthority.sealIfPossible(fixture.manifestPath(), fixture.manifest());
        assertTrue(firstSeal.available(), firstSeal.problem());
        TextureSourceGenerationProof first = TextureSourceGenerationProofIO.read(proofPath);

        TextureSourceGenerationAuthority.SealResult secondSeal =
                TextureSourceGenerationAuthority.sealIfPossible(fixture.manifestPath(), fixture.manifest());
        assertTrue(secondSeal.available(), secondSeal.problem());
        TextureSourceGenerationProof second = TextureSourceGenerationProofIO.read(proofPath);

        assertEquals(first.provider(), second.provider());
        assertEquals(first.entries(), second.entries(),
                "unchanged Foundation generation identifiers must archive deterministically across helper processes");
    }

    private Fixture fixture(String profile) throws Exception {
        Path sourceRoot = Files.createDirectory(temporaryDirectory.resolve(profile + "-source-root"));
        Path source = sourceRoot.resolve("graphics-test.png");
        Files.write(source, new byte[] {1, 2, 3, 4});
        FileTime mtime = Files.getLastModifiedTime(source);

        ResourceIndex.Provider provider = new ResourceIndex.Provider(
                0,
                source.getFileName().toString(),
                Files.size(source),
                Math.max(0, mtime.toMillis()));
        ResourceIndex index = new ResourceIndex(
                profile,
                List.of(new ResourceIndex.Root("fixture", sourceRoot, false)),
                Map.of("graphics/test.png", List.of(provider)));

        Path cache = Files.createDirectory(temporaryDirectory.resolve(profile + "-cache"));
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
        return new Fixture(profile, cache, source, index, manifest, manifestPath);
    }

    private record Fixture(
            String profile,
            Path cache,
            Path source,
            ResourceIndex index,
            TextureManifest manifest,
            Path manifestPath) {
    }
}
