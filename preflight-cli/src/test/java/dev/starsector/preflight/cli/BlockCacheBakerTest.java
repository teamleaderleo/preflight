package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.BlockCacheManifest;
import dev.starsector.preflight.core.BlockCacheManifestIO;
import dev.starsector.preflight.core.BlockCompressor;
import dev.starsector.preflight.core.BlockTexture;
import dev.starsector.preflight.core.BlockTextureIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlockCacheBakerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void bakesAProfileIntoAReadableCache() throws Exception {
        Path install = fakeInstall();
        Path cache = temporaryDirectory.resolve("cache");

        assertEquals(0, PreflightCli.run(new String[] {
                "assets", "bake-blocks", "--game", install.toString(), "--out-dir", cache.toString()
        }));

        Path manifestFile = cache.resolve("blocks.spfc");
        assertTrue(Files.isRegularFile(manifestFile), "the manifest is what makes the cache readable");
        BlockCacheManifest manifest = BlockCacheManifestIO.read(manifestFile);
        assertEquals(BlockCompressor.CODEC_VERSION, manifest.codecVersion());
        assertTrue(manifest.matchesCurrentCodec());
        assertTrue(manifest.entryCount() > 0);

        // Every entry must point at a blob that exists and holds what the entry claims. The manifest is
        // read without opening the blobs, so a disagreement here would reach the driver.
        for (BlockCacheManifest.Entry entry : manifest.entries().values()) {
            BlockTexture texture = BlockTextureIO.read(cache.resolve(entry.blobRelativePath()));
            assertEquals(entry.blockBytes(), texture.blockBytes(), entry.blobRelativePath());
            assertEquals(entry.width(), texture.width());
            assertEquals(entry.format(), texture.format());
            assertEquals(entry.sourceSha256(), texture.sourceSha256());
        }
        assertTrue(manifest.uncompressedBytes() > manifest.blockBytes(), "a block cache that grew would be pointless");
    }

    @Test
    void keepsShaderMapsOutOfAPerceptuallyGatedCache() throws Exception {
        Path install = fakeInstall();
        Path cache = temporaryDirectory.resolve("cache");

        assertEquals(0, PreflightCli.run(new String[] {
                "assets", "bake-blocks", "--game", install.toString(), "--out-dir", cache.toString()
        }));

        // A normal map stores vectors, not colour. Admitting it because its Delta-E looked low would be
        // admitting it on the strength of a number that does not describe it.
        BlockCacheManifest manifest = BlockCacheManifestIO.read(cache.resolve("blocks.spfc"));
        assertFalse(manifest.entry("graphics/ships/hull_normal.png").isPresent());
        assertTrue(manifest.entry("graphics/ships/hull.png").isPresent());
    }

    @Test
    void leavesTexturesOverTheGateOnTheOrdinaryPath() throws Exception {
        Path install = fakeInstall();
        Path strict = temporaryDirectory.resolve("strict");
        Path loose = temporaryDirectory.resolve("loose");

        // Per-pixel noise is the case two endpoints cannot approximate, so at the default gate it is
        // left on the ordinary decode path -- a normal outcome, not an error. Raising the gate admits
        // it, which is what makes this the gate's decision rather than an incidental failure.
        assertEquals(0, PreflightCli.run(new String[] {
                "assets", "bake-blocks", "--game", install.toString(), "--out-dir", strict.toString()
        }));
        BlockCacheManifest gated = BlockCacheManifestIO.read(strict.resolve("blocks.spfc"));
        assertFalse(gated.entry("graphics/ships/turret.png").isPresent(), "noise is over the gate");
        assertTrue(gated.entry("graphics/ships/hull.png").isPresent(), "flat fills are exact");

        assertEquals(0, PreflightCli.run(new String[] {
                "assets", "bake-blocks", "--game", install.toString(),
                "--out-dir", loose.toString(), "--max-delta-e", "100"
        }));
        assertTrue(BlockCacheManifestIO.read(loose.resolve("blocks.spfc"))
                .entry("graphics/ships/turret.png").isPresent(), "a loose gate admits it");
    }

    @Test
    void bakesAMipChainOnRequestAndNotOtherwise() throws Exception {
        Path install = fakeInstall();
        Path flat = temporaryDirectory.resolve("flat");
        Path chained = temporaryDirectory.resolve("chained");

        assertEquals(0, PreflightCli.run(new String[] {
                "assets", "bake-blocks", "--game", install.toString(), "--out-dir", flat.toString()
        }));
        assertEquals(0, PreflightCli.run(new String[] {
                "assets", "bake-blocks", "--game", install.toString(), "--out-dir", chained.toString(), "--mips"
        }));

        BlockCacheManifest without = BlockCacheManifestIO.read(flat.resolve("blocks.spfc"));
        BlockCacheManifest with = BlockCacheManifestIO.read(chained.resolve("blocks.spfc"));
        assertEquals(1, without.entries().values().iterator().next().levelCount());
        assertTrue(with.entries().values().iterator().next().levelCount() > 1);
        assertTrue(with.blockBytes() > without.blockBytes(), "a chain costs about a third more");
    }

    @Test
    void dryRunMeasuresAndWritesNothing() throws Exception {
        Path install = fakeInstall();
        Path cache = temporaryDirectory.resolve("cache");

        assertEquals(0, PreflightCli.run(new String[] {
                "assets", "bake-blocks", "--game", install.toString(),
                "--out-dir", cache.toString(), "--dry-run"
        }));

        assertFalse(Files.exists(cache), "a dry run must not create the cache directory");
    }

    @Test
    void reBakesOverItsOwnCacheButNotOverSomeoneElsesDirectory() throws Exception {
        Path install = fakeInstall();
        Path cache = temporaryDirectory.resolve("cache");

        assertEquals(0, PreflightCli.run(new String[] {
                "assets", "bake-blocks", "--game", install.toString(), "--out-dir", cache.toString()
        }));
        // Its own cache is recognised by its manifest and re-baked without ceremony.
        assertEquals(0, PreflightCli.run(new String[] {
                "assets", "bake-blocks", "--game", install.toString(), "--out-dir", cache.toString()
        }));

        Path occupied = temporaryDirectory.resolve("occupied");
        Files.createDirectories(occupied);
        Path theirs = Files.writeString(occupied.resolve("notes.txt"), "do not clobber me");
        assertThrows(Exception.class, () -> PreflightCli.run(new String[] {
                "assets", "bake-blocks", "--game", install.toString(), "--out-dir", occupied.toString()
        }));
        assertEquals("do not clobber me", Files.readString(theirs));
    }

    @Test
    void requiresAnOutputDirectoryAndAPositiveGate() {
        assertThrows(IllegalArgumentException.class,
                () -> AssetLabCommand.execute(new String[] {"assets", "bake-blocks"}, 1));
        assertThrows(IllegalArgumentException.class, () -> AssetLabCommand.execute(
                new String[] {"assets", "bake-blocks", "--out-dir", "x", "--max-delta-e", "0"}, 1));
    }

    /**
     * A minimal install holding one texture of each outcome: flat-fill art that BC1 represents
     * exactly, dense noise that no two endpoints can approximate, and a normal map that is excluded
     * on principle rather than on its number.
     *
     * <p>The art is flat fills rather than a smooth gradient on purpose. A synthetic gradient is
     * <em>harder</em> for BC1 than real art, not easier: when a block's own colour range is a couple
     * of levels wide, the RGB565 quantisation of the endpoints dominates the error, and a 64x64
     * diagonal gradient measures p99 Delta-E 3.1 — over the default gate. Real photographic art
     * varies more within a block, which is why the profile measurements come in near 0.8.
     */
    private Path fakeInstall() throws Exception {
        Path install = temporaryDirectory.resolve("install");
        Path mod = install.resolve("mods/Heavy");
        Files.createDirectories(mod.resolve("graphics/ships"));
        Files.createDirectories(install.resolve("starsector-core"));
        Files.writeString(mod.resolve("mod_info.json"), "{\"id\":\"heavy\"}");

        ImageIO.write(flatFills(64, 64), "png", mod.resolve("graphics/ships/hull.png").toFile());
        ImageIO.write(noise(64, 64), "png", mod.resolve("graphics/ships/turret.png").toFile());
        ImageIO.write(flatFills(32, 32), "png", mod.resolve("graphics/ships/hull_normal.png").toFile());
        Files.writeString(install.resolve("mods/enabled_mods.json"), "{\"enabledMods\":[\"heavy\"]}");
        Path launcher = Files.writeString(install.resolve("starsector.sh"), "#!/bin/sh\n");
        launcher.toFile().setExecutable(true);
        return install;
    }

    /** Solid blocks of colour: two endpoints describe any 4x4 of this exactly, so the loss is zero. */
    private static BufferedImage flatFills(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(24, 40, 88));
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(new Color(200, 96, 32));
        graphics.fillRect(0, 0, width / 2, height / 2);
        graphics.setColor(new Color(240, 240, 232));
        graphics.fillRect(width / 2, height / 2, width / 2, height / 2);
        graphics.dispose();
        return image;
    }

    /** Dense per-pixel noise: sixteen unrelated colours per block, which two endpoints cannot span. */
    private static BufferedImage noise(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new java.util.Random(20260726L);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt() & 0xffffff);
            }
        }
        return image;
    }
}
