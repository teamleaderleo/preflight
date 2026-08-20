package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceIndexIO;
import dev.starsector.preflight.core.TextureManifest;
import dev.starsector.preflight.core.TextureManifestIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LaunchCacheContextsTest {
    private static final String PARALLEL_SELECTION = "preflight.launch.parallelProfileSelection";

    @TempDir
    Path temporaryDirectory;

    @Test
    void explicitTextureArtifactsArePassedThroughWithoutOpeningAProfile() throws Exception {
        Path cache = temporaryDirectory.resolve("cache");
        Path manifest = temporaryDirectory.resolve("manifest.spfm");
        Path index = temporaryDirectory.resolve("index.spfi");
        CommandLine options = CommandLine.parse(new String[] {
                "run",
                "--adapter",
                "--texture-cache-dir", cache.toString(),
                "--texture-manifest", manifest.toString(),
                "--texture-index", index.toString()
        }, 1);

        LaunchCacheContexts.Result selected =
                LaunchCacheContexts.select(options, target(), false);

        assertEquals(cache.toAbsolutePath().normalize(), selected.texture().cacheDirectory());
        assertEquals(manifest.toAbsolutePath().normalize(), selected.texture().manifest());
        assertEquals(index.toAbsolutePath().normalize(), selected.texture().index());
        assertFalse(selected.texture().automatic());
        assertFalse(selected.texture().sourceGenerationValidated());
        assertNull(selected.texture().sourceGenerationProvider());
        assertNull(selected.texture().sourceGenerationProblem());
        assertNoProfileCaches(selected);
    }

    @Test
    void unavailableJaninoOwnershipDoesNotTriggerAProfileScan() throws Exception {
        CommandLine options = CommandLine.parse(new String[] {
                "run", "--adapter", "--janino-bytecode-cache"
        }, 1);

        LaunchCacheContexts.Result selected =
                LaunchCacheContexts.select(options, target(), false);

        assertNull(selected.texture());
        assertNoProfileCaches(selected);
    }

    @Test
    void disabledAdapterSuppressesEvenAnOwnedJaninoCache() throws Exception {
        LaunchCacheContexts.Result selected = LaunchCacheContexts.select(
                CommandLine.parse(new String[] {"run"}, 1), target(), true);

        assertNull(selected.texture());
        assertNoProfileCaches(selected);
    }

    @Test
    void serialAndParallelSelectionProduceTheSameTypedContexts() throws Exception {
        Path game = temporaryDirectory.resolve("synthetic-game");
        Path source = game.resolve("starsector-core/graphics/test.png");
        Path mods = game.resolve("mods");
        Files.createDirectories(source.getParent());
        Files.createDirectories(mods);
        Files.writeString(source, "texture");
        Files.writeString(mods.resolve("enabled_mods.json"), "{\"enabledMods\":[]}");
        try (JarOutputStream ignored = new JarOutputStream(
                Files.newOutputStream(game.resolve("starsector-core/starfarer_obf.jar")))) {
            // A valid empty jar is enough for the synthetic identity fixture.
        }

        ResourceIndex current = ResourceIndexBuilder.build(game).index();
        Path cache = temporaryDirectory.resolve("automatic-cache");
        Path index = cache.resolve("resource-indexes/" + current.profileFingerprint() + ".spfi");
        Path manifest = cache.resolve("manifests/" + current.profileFingerprint() + ".spfm");
        ResourceIndexIO.write(index, current);
        TextureManifestIO.write(manifest, new TextureManifest(current.profileFingerprint(), Map.of()));
        CommandLine options = CommandLine.parse(new String[] {
                "run", "--adapter", "--texture-auto", "--texture-cache-dir", cache.toString()
        }, 1);
        LaunchTarget target = target(game);

        String previous = System.getProperty(PARALLEL_SELECTION);
        try {
            System.setProperty(PARALLEL_SELECTION, "false");
            LaunchCacheContexts.Result serial = LaunchCacheContexts.select(options, target, false);
            System.setProperty(PARALLEL_SELECTION, "true");
            LaunchCacheContexts.Result parallel = LaunchCacheContexts.select(options, target, false);

            assertTrue(serial.texture().sourceGenerationValidated(), serial.texture().sourceGenerationProblem());
            assertEquivalent(serial, parallel);
        } finally {
            if (previous == null) {
                System.clearProperty(PARALLEL_SELECTION);
            } else {
                System.setProperty(PARALLEL_SELECTION, previous);
            }
        }
    }

    private LaunchTarget target() {
        Path missingInstall = temporaryDirectory.resolve("missing-install");
        return target(missingInstall);
    }

    private LaunchTarget target(Path install) {
        Path launcher = install.resolve("launcher");
        return new LaunchTarget(
                install,
                launcher,
                install,
                List.of(launcher.toString()),
                "fixture",
                0,
                "test");
    }

    private static void assertNoProfileCaches(LaunchCacheContexts.Result selected) {
        assertNull(selected.variantJson());
        assertNull(selected.weaponJson());
        assertNull(selected.projectileJson());
        assertNull(selected.hullJson());
        assertNull(selected.rulesCsv());
        assertNull(selected.ruleCommand());
        assertNull(selected.mergedRead());
        assertNull(selected.janino());
        assertNull(selected.preparedAudio());
    }

    private static void assertEquivalent(
            LaunchCacheContexts.Result serial, LaunchCacheContexts.Result parallel) {
        assertEquals(serial.texture().cacheDirectory(), parallel.texture().cacheDirectory());
        assertEquals(serial.texture().manifest(), parallel.texture().manifest());
        assertEquals(serial.texture().index(), parallel.texture().index());
        assertEquals(serial.texture().automatic(), parallel.texture().automatic());
        assertEquals(serial.texture().profileFingerprint(), parallel.texture().profileFingerprint());
        assertEquals(serial.texture().manifestSha256(), parallel.texture().manifestSha256());
        assertEquals(serial.texture().indexSha256(), parallel.texture().indexSha256());
        assertEquals(serial.texture().checkedProviders(), parallel.texture().checkedProviders());
        assertTrue(serial.texture().indexBuildMillis() >= 0);
        assertTrue(parallel.texture().indexBuildMillis() >= 0);
        assertEquals(serial.texture().sourceGenerationValidated(), parallel.texture().sourceGenerationValidated());
        assertEquals(serial.texture().sourceGenerationProvider(), parallel.texture().sourceGenerationProvider());
        assertEquals(serial.texture().sourceGenerationEntries(), parallel.texture().sourceGenerationEntries());
        assertEquals(serial.texture().sourceGenerationBytes(), parallel.texture().sourceGenerationBytes());
        assertTrue(serial.texture().sourceGenerationValidationMillis() >= 0);
        assertTrue(parallel.texture().sourceGenerationValidationMillis() >= 0);
        assertEquals(serial.texture().sourceGenerationProblem(), parallel.texture().sourceGenerationProblem());
        assertEquals(serial.texture().resourceIndex().roots(), parallel.texture().resourceIndex().roots());
        assertEquals(serial.texture().resourceIndex().entries(), parallel.texture().resourceIndex().entries());
        assertEquals(serial.variantJson(), parallel.variantJson());
        assertEquals(serial.weaponJson(), parallel.weaponJson());
        assertEquals(serial.projectileJson(), parallel.projectileJson());
        assertEquals(serial.hullJson(), parallel.hullJson());
        assertEquals(serial.rulesCsv(), parallel.rulesCsv());
        assertEquals(serial.ruleCommand(), parallel.ruleCommand());
        assertEquals(serial.mergedRead(), parallel.mergedRead());
        assertEquals(serial.janino(), parallel.janino());
        assertEquals(serial.preparedAudio(), parallel.preparedAudio());
    }
}
