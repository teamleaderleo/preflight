package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceIndexIO;
import dev.starsector.preflight.core.TextureManifest;
import dev.starsector.preflight.core.TextureManifestIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CurrentTextureCacheTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesAndHashesOnlyTheExactCurrentProfileArtifacts() throws Exception {
        Fixture fixture = fixture();

        CurrentTextureCache.Resolution resolution = CurrentTextureCache.resolve(fixture.game(), fixture.cache());

        assertEquals(fixture.index().toRealPath(), resolution.index());
        assertEquals(fixture.manifest().toRealPath(), resolution.manifest());
        ResourceIndex stored = ResourceIndexIO.read(fixture.index());
        assertEquals(stored.profileFingerprint(), resolution.resourceIndex().profileFingerprint());
        assertEquals(stored.roots(), resolution.resourceIndex().roots());
        assertEquals(stored.entries(), resolution.resourceIndex().entries());
        assertEquals(fixture.profile(), resolution.profileFingerprint());
        assertEquals(Hashes.sha256(fixture.index()), resolution.indexSha256());
        assertEquals(Hashes.sha256(fixture.manifest()), resolution.manifestSha256());
        assertEquals(1L, resolution.checkedProviders());
        assertTrue(resolution.indexBuildMillis() >= 0);
    }

    @Test
    void changedCurrentProfileFailsClosedInsteadOfSelectingAnOlderCache() throws Exception {
        Fixture fixture = fixture();
        Files.writeString(fixture.source(), "changed-size");

        IOException error = assertThrows(
                IOException.class,
                () -> CurrentTextureCache.resolve(fixture.game(), fixture.cache()));

        assertTrue(error.getMessage().contains("No prepared texture index matches"), error.getMessage());
    }

    @Test
    void degradedProviderCannotAuthorizeExactTextureReuse() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("degraded-root"));
        Path source = Files.writeString(root.resolve("test.png"), "texture");
        ResourceIndex degraded = new ResourceIndex(
                "d".repeat(64),
                List.of(new ResourceIndex.Root("mod", root, false)),
                Map.of("test.png", List.of(new ResourceIndex.Provider(
                        0,
                        "test.png",
                        Files.size(source),
                        Files.getLastModifiedTime(source).toMillis()))));

        CurrentTextureCache.GenerationAuthorityUnavailableException error = assertThrows(
                CurrentTextureCache.GenerationAuthorityUnavailableException.class,
                () -> CurrentTextureCache.requireExactGenerationAuthority(degraded));

        assertTrue(error.getMessage().contains("1 resource provider"), error.getMessage());
    }

    private Fixture fixture() throws Exception {
        Path game = temporaryDirectory.resolve("Starsector.app");
        Path core = game.resolve("starsector-core");
        Path mods = game.resolve("mods");
        Path source = core.resolve("graphics/test.png");
        Files.createDirectories(source.getParent());
        Files.createDirectories(mods);
        Files.writeString(source, "texture");
        Files.writeString(mods.resolve("enabled_mods.json"), "{\"enabledMods\":[]}");

        ResourceIndex current = ResourceIndexBuilder.build(game).index();
        Path cache = temporaryDirectory.resolve("cache");
        Path index = ResourceIndexIO.directory(cache)
                .resolve(current.profileFingerprint() + ".spfi");
        Path manifest = cache.resolve("manifests")
                .resolve(current.profileFingerprint() + ".spfm");
        ResourceIndexIO.write(index, current);
        TextureManifestIO.write(manifest, new TextureManifest(current.profileFingerprint(), Map.of()));
        return new Fixture(game, cache, source, index, manifest, current.profileFingerprint());
    }

    private record Fixture(
            Path game,
            Path cache,
            Path source,
            Path index,
            Path manifest,
            String profile) {
    }
}
