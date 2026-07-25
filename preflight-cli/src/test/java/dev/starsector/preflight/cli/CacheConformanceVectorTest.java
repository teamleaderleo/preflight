package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheConformanceVectorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void exportsACacheAsAVectorTheCProbeCanRead() throws Exception {
        Path cache = bakedCache();
        Path vector = temporaryDirectory.resolve("vector.bin");

        assertEquals(0, PreflightCli.run(new String[] {
                "assets", "cache-conformance", "--cache-dir", cache.toString(), "--out", vector.toString()
        }));

        // Parsed the way block-conformance-probe.c parses it: magic, version, case count, then each
        // case. Decoding it here rather than trusting the writer is the point -- the C reader is the
        // consumer that matters and it is not exercised by any Java test.
        ByteBuffer bytes = ByteBuffer.wrap(Files.readAllBytes(vector)).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[4];
        bytes.get(magic);
        assertEquals("SPFV", new String(magic, StandardCharsets.UTF_8));
        assertEquals(1, bytes.getInt(), "format version");
        int cases = bytes.getInt();
        assertTrue(cases > 0);

        BlockCacheManifest manifest = BlockCacheManifestIO.read(cache.resolve("blocks.spfc"));
        for (int i = 0; i < cases; i++) {
            byte[] name = new byte[bytes.getInt()];
            bytes.get(name);
            String logicalPath = new String(name, StandardCharsets.UTF_8);
            BlockCacheManifest.Entry entry = manifest.entry(logicalPath).orElseThrow();
            BlockTexture texture = BlockTextureIO.read(cache.resolve(entry.blobRelativePath()));

            assertEquals(entry.format().glInternalFormat(), bytes.getInt(), logicalPath + " GL format");
            assertEquals(entry.width(), bytes.getInt());
            assertEquals(entry.height(), bytes.getInt());

            byte[] blocks = new byte[bytes.getInt()];
            bytes.get(blocks);
            // The blocks in the vector must be the blocks in the cache. A vector that re-encoded would
            // ask the driver about bytes nobody is going to upload.
            assertArrayEqualsBytes(texture.level(0), blocks, logicalPath);

            byte[] expected = new byte[bytes.getInt()];
            bytes.get(expected);
            assertEquals(4 * entry.width() * entry.height(), expected.length);
            assertRgbaMatchesDecode(blocks, texture, expected, logicalPath);
        }
        assertEquals(0, bytes.remaining(), "the C reader stops at the last case, so nothing may follow");
    }

    @Test
    void refusesToWriteAVectorNothingFitsIn() throws Exception {
        Path cache = bakedCache();

        // The budget exists because the vector is dominated by uncompressed expected images and gets
        // shipped to a rented GPU as a function argument. An impossible budget has to fail loudly
        // rather than write an empty vector that would pass every check by having nothing in it.
        IOException error = assertThrows(IOException.class, () -> CacheConformanceVector.write(
                cache, temporaryDirectory.resolve("tiny.bin"), 8, 64));
        assertTrue(error.getMessage().contains("budget"), error.getMessage());
    }

    @Test
    void requiresACacheAndAnOutput() {
        assertThrows(IllegalArgumentException.class,
                () -> AssetLabCommand.execute(new String[] {"assets", "cache-conformance", "--out", "x"}, 1));
        assertThrows(IllegalArgumentException.class,
                () -> AssetLabCommand.execute(new String[] {"assets", "cache-conformance", "--cache-dir", "x"}, 1));
    }

    /** What the driver is expected to return: RGBA bytes, from decoding the blocks in this build. */
    private static void assertRgbaMatchesDecode(byte[] blocks, BlockTexture texture, byte[] rgba, String path) {
        int[] decoded = BlockCompressor.decode(
                blocks, texture.width(), texture.height(), texture.format().withAlpha());
        for (int i = 0; i < decoded.length; i++) {
            assertEquals((decoded[i] >> 16) & 0xff, rgba[i * 4] & 0xff, path + " red at " + i);
            assertEquals((decoded[i] >> 8) & 0xff, rgba[i * 4 + 1] & 0xff, path + " green at " + i);
            assertEquals(decoded[i] & 0xff, rgba[i * 4 + 2] & 0xff, path + " blue at " + i);
            assertEquals(decoded[i] >>> 24, rgba[i * 4 + 3] & 0xff, path + " alpha at " + i);
        }
    }

    private static void assertArrayEqualsBytes(byte[] expected, byte[] actual, String path) {
        assertEquals(expected.length, actual.length, path + " block length");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], path + " block byte " + i);
        }
    }

    private Path bakedCache() throws Exception {
        Path install = temporaryDirectory.resolve("install");
        Path mod = install.resolve("mods/Heavy");
        Files.createDirectories(mod.resolve("graphics/ships"));
        Files.createDirectories(install.resolve("starsector-core"));
        Files.writeString(mod.resolve("mod_info.json"), "{\"id\":\"heavy\"}");
        ImageIO.write(flatFills(64, 64), "png", mod.resolve("graphics/ships/hull.png").toFile());
        ImageIO.write(flatFills(32, 48), "png", mod.resolve("graphics/ships/wing.png").toFile());
        Files.writeString(install.resolve("mods/enabled_mods.json"), "{\"enabledMods\":[\"heavy\"]}");
        Path launcher = Files.writeString(install.resolve("starsector.sh"), "#!/bin/sh\n");
        launcher.toFile().setExecutable(true);

        Path cache = temporaryDirectory.resolve("cache");
        assertEquals(0, PreflightCli.run(new String[] {
                "assets", "bake-blocks", "--game", install.toString(), "--out-dir", cache.toString()
        }));
        return cache;
    }

    private static BufferedImage flatFills(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(24, 40, 88));
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(new Color(200, 96, 32));
        graphics.fillRect(0, 0, width / 2, height / 2);
        graphics.dispose();
        return image;
    }
}
