package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceIndexIO;
import dev.starsector.preflight.core.TextureManifest;
import dev.starsector.preflight.core.TextureManifestIO;
import dev.starsector.preflight.core.TextureSourceGenerationProofIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Keeps doctor/cache health aligned with the automatic launch generation gate. */
class CacheHealthGenerationProofTest {
    @TempDir
    Path directory;

    @Test
    void missingOrCorruptGenerationProofIsRepairNeededInsteadOfReady() throws Exception {
        PreflightHome home = PreflightHome.resolve(Platform.OTHER, directory, Map.of());
        String profile = "a".repeat(64);
        Path sourceRoot = Files.createDirectories(directory.resolve("game"));
        Path index = ResourceIndexIO.directory(home.cache()).resolve(profile + ".spfi");
        Path manifest = TextureManifestIO.directory(home.cache()).resolve(profile + ".spfm");
        ResourceIndexIO.write(index, new ResourceIndex(
                profile,
                List.of(new ResourceIndex.Root("core", sourceRoot, true)),
                Map.of()));
        TextureManifest prepared = new TextureManifest(profile, Map.of());
        TextureManifestIO.write(manifest, prepared);

        Path proof = TextureSourceGenerationProofIO.path(home.cache(), profile);
        assumeTrue(Files.isRegularFile(proof),
                "the host filesystem does not expose the reviewed generation provider");
        assertEquals("ready", CacheHealth.inspect(home, profile).status());

        Files.delete(proof);
        CacheHealth.Report missing = CacheHealth.inspect(home, profile);
        assertEquals("repair-needed", missing.status());
        assertTrue(missing.issues().get(0).summary().contains("source-generation proof is missing"));

        // Re-publishing the same manifest reseals it through the preparation persistence boundary.
        TextureManifestIO.write(manifest, prepared);
        byte[] corrupt = Files.readAllBytes(proof);
        corrupt[corrupt.length - 1] ^= 0x55;
        Files.write(proof, corrupt);

        CacheHealth.Report damaged = CacheHealth.inspect(home, profile);
        assertEquals("repair-needed", damaged.status());
        assertTrue(damaged.issues().get(0).summary().contains("source-generation proof is stale or unavailable"));
        assertEquals(
                Set.of("resource-index", "texture-manifest", "texture-source-generation-proof"),
                damaged.targets().stream()
                        .map(CacheHealth.Target::artifact)
                        .collect(Collectors.toSet()));
    }
}
