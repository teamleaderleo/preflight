package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssetLabCommandTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesCappedCopiesOfOversizedTexturesAsADropInMod() throws Exception {
        Path install = fakeInstall();
        Path out = temporaryDirectory.resolve("pack");

        assertEquals(0, PreflightCli.run(new String[] {
                "assets", "shrink",
                "--game", install.toString(),
                "--max-texture-size", "128",
                "--out-dir", out.toString()
        }));

        // 512x256 needs two halvings to fit a 128 cap -> 128x64, the same figure `scan
        // --max-texture-size` projects.
        BufferedImage shrunk = ImageIO.read(out.resolve("graphics/big.png").toFile());
        assertEquals(128, shrunk.getWidth());
        assertEquals(64, shrunk.getHeight());

        // Textures already under the cap are left to their original mod, not copied into the pack.
        assertFalse(Files.exists(out.resolve("graphics/small.png")), "already fits the cap");

        // A JPEG source stays a JPEG at the same logical path -- the game resolves textures by
        // exact path, so swapping the container would simply miss the override.
        BufferedImage photo = ImageIO.read(out.resolve("graphics/photo.jpg").toFile());
        assertEquals(128, photo.getWidth());
        assertEquals(128, photo.getHeight());

        // Header reading sniffs content, so a PNG under a .bmp name is measured and lands in the
        // candidate list. It is still skipped: writing a PNG at a .bmp path would be a silent
        // re-containering, and the game resolves by path.
        assertFalse(Files.exists(out.resolve("graphics/mislabelled.bmp")), "unsupported containers are skipped");

        String modInfo = Files.readString(out.resolve("mod_info.json"));
        assertTrue(modInfo.contains("preflight_shrink_pack"), modInfo);
    }

    @Test
    void dryRunWritesNothing() throws Exception {
        Path install = fakeInstall();
        Path out = temporaryDirectory.resolve("pack");

        assertEquals(0, PreflightCli.run(new String[] {
                "assets", "shrink",
                "--game", install.toString(),
                "--max-texture-size", "128",
                "--out-dir", out.toString(),
                "--dry-run"
        }));

        assertFalse(Files.exists(out), "a dry run must not create the pack directory");
    }

    @Test
    void refusesToWriteIntoANonEmptyDirectoryWithoutForce() throws Exception {
        Path install = fakeInstall();
        Path out = temporaryDirectory.resolve("pack");
        Files.createDirectories(out);
        Path existing = Files.writeString(out.resolve("someones-mod.txt"), "do not clobber me");

        assertThrows(Exception.class, () -> PreflightCli.run(new String[] {
                "assets", "shrink",
                "--game", install.toString(),
                "--max-texture-size", "128",
                "--out-dir", out.toString()
        }));
        assertEquals("do not clobber me", Files.readString(existing));

        assertEquals(0, PreflightCli.run(new String[] {
                "assets", "shrink",
                "--game", install.toString(),
                "--max-texture-size", "128",
                "--out-dir", out.toString(),
                "--force"
        }));
        assertTrue(Files.isRegularFile(out.resolve("graphics/big.png")));
    }

    @Test
    void requiresBothACapAndAnOutputDirectory() {
        assertThrows(IllegalArgumentException.class,
                () -> AssetLabCommand.execute(new String[] {"assets", "shrink", "--out-dir", "x"}, 1));
        assertThrows(IllegalArgumentException.class,
                () -> AssetLabCommand.execute(new String[] {"assets", "shrink", "--max-texture-size", "128"}, 1));
    }

    @Test
    void halvesWithAnExactBoxFilter() {
        BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        source.setRGB(0, 0, 0x000000);
        source.setRGB(1, 0, 0x646464);
        source.setRGB(0, 1, 0xC8C8C8);
        source.setRGB(1, 1, 0xFFFFFF);

        BufferedImage halved = AssetLabCommand.halve(source, false);
        assertEquals(1, halved.getWidth());
        assertEquals(1, halved.getHeight());
        // (0 + 100 + 200 + 255) / 4 = 138.75, rounded to nearest = 139.
        assertEquals(139, halved.getRGB(0, 0) & 0xFF);
    }

    @Test
    void averagesColourPremultipliedSoTransparentPixelsDoNotBleed() {
        BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, 0xFFFF0000);          // opaque red
        source.setRGB(1, 0, 0x00FFFFFF);          // fully transparent white
        source.setRGB(0, 1, 0x00FFFFFF);
        source.setRGB(1, 1, 0x00FFFFFF);

        int pixel = AssetLabCommand.halve(source, true).getRGB(0, 0);
        // A straight RGBA average would wash the red out towards white. Premultiplied, the three
        // transparent neighbours contribute no colour at all: the hue stays pure red and only the
        // coverage drops to a quarter.
        assertEquals(64, pixel >>> 24, "alpha is the mean coverage");
        assertEquals(255, (pixel >> 16) & 0xFF, "red is undiluted");
        assertEquals(0, (pixel >> 8) & 0xFF);
        assertEquals(0, pixel & 0xFF);
    }

    @Test
    void dropsTheTrailingRowAndColumnOfAnOddSizedImage() {
        BufferedImage source = new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 3; x++) {
                // The 2x2 block that survives is all 0x40; everything else is white and must not
                // reach the output.
                source.setRGB(x, y, x < 2 && y < 2 ? 0x404040 : 0xFFFFFF);
            }
        }

        BufferedImage halved = AssetLabCommand.halve(source, false);
        assertEquals(1, halved.getWidth(), "3 >> 1 == 1, matching the census projection");
        assertEquals(1, halved.getHeight());
        assertEquals(0x40, halved.getRGB(0, 0) & 0xFF);
    }

    private Path fakeInstall() throws Exception {
        Path install = temporaryDirectory.resolve("install");
        Path mods = install.resolve("mods");
        Path heavy = mods.resolve("Heavy");
        Files.createDirectories(heavy.resolve("graphics"));
        Files.createDirectories(install.resolve("starsector-core"));
        Files.writeString(heavy.resolve("mod_info.json"), "{\"id\":\"heavy\"}");
        ImageIO.write(new BufferedImage(512, 256, BufferedImage.TYPE_INT_ARGB), "png",
                heavy.resolve("graphics/big.png").toFile());
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB), "png",
                heavy.resolve("graphics/small.png").toFile());
        ImageIO.write(new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB), "jpg",
                heavy.resolve("graphics/photo.jpg").toFile());
        ImageIO.write(new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB), "png",
                heavy.resolve("graphics/mislabelled.bmp").toFile());
        Files.writeString(mods.resolve("enabled_mods.json"), "{\"enabledMods\":[\"heavy\"]}");
        Path launcher = Files.writeString(install.resolve("starsector.sh"), "#!/bin/sh\n");
        launcher.toFile().setExecutable(true);
        return install;
    }
}
