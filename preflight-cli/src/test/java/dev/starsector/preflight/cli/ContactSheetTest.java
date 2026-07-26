package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContactSheetTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void drawsEveryDispositionAndNamesItInTheIndex() throws Exception {
        Path install = fakeInstall();
        Path sheet = temporaryDirectory.resolve("sheet.png");

        String report = runSheet(install, sheet, "--samples", "8");

        assertTrue(Files.isRegularFile(sheet));
        BufferedImage image = ImageIO.read(sheet.toFile());
        assertTrue(image.getWidth() > 0 && image.getHeight() > 0);

        // The three dispositions the fixture is built to produce must all appear. A sheet that showed
        // only what passed could not explain what was refused, which is most of what it is for.
        assertTrue(report.contains("\"CACHED\""), report);
        assertTrue(report.contains("\"OVER_GATE\""), report);
        assertTrue(report.contains("\"SHADER_MAP\""), report);
        assertTrue(report.contains("graphics/ships/hull.png"), report);
        assertTrue(report.contains("graphics/ships/hull_normal.png"), report);
    }

    @Test
    void classifiesExactlyAsTheBakerDoes() throws Exception {
        // The sheet's whole claim is that it shows the decision the baker made. If the two ever
        // classified differently the sheet would certify something that never happened, and nothing
        // else in the suite would notice.
        for (String path : new String[] {
                "graphics/ships/hull.png",
                "graphics/ships/hull_normal.png",
                "graphics/shaders/thing.png",
                "data/maps/terrain.png",
                "graphics/fx/glow.png",
                "graphics/fx/engine_glow.png"}) {
            assertEquals(
                    TextureKind.of(path) == TextureKind.SHADER_MAP,
                    path.contains("_normal") || path.contains("/shaders/")
                            || path.contains("/maps/") || path.contains("_glow"),
                    path);
        }
    }

    @Test
    void reportsTheSamePanelDigestForTheSameProfile() throws Exception {
        Path install = fakeInstall();

        String first = runSheet(install, temporaryDirectory.resolve("a.png"), "--samples", "8");
        String second = runSheet(install, temporaryDirectory.resolve("b.png"), "--samples", "8");

        // The digest covers the panel data rather than the PNG, because captions are drawn with a
        // platform font. It is the thing a regression check can pin.
        assertEquals(digestOf(first), digestOf(second));
        assertNotEquals("", digestOf(first));
    }

    @Test
    void changesThePanelDigestWhenTheArtChanges() throws Exception {
        Path install = fakeInstall();
        String before = digestOf(runSheet(install, temporaryDirectory.resolve("before.png"), "--samples", "8"));

        BufferedImage altered = flatFills(64, 64);
        altered.setRGB(1, 1, 0xffff00ff);
        ImageIO.write(altered, "png", install.resolve("mods/Heavy/graphics/ships/hull.png").toFile());

        String after = digestOf(runSheet(install, temporaryDirectory.resolve("after.png"), "--samples", "8"));
        assertNotEquals(before, after);
    }

    @Test
    void refusesOptionsThatCannotProduceAReadableSheet() {
        assertThrows(IllegalArgumentException.class, () -> new ContactSheet.Options(0, 1.0, 128, 4));
        assertThrows(IllegalArgumentException.class, () -> new ContactSheet.Options(8, 0, 128, 4));
        assertThrows(IllegalArgumentException.class, () -> new ContactSheet.Options(8, 1.0, 8, 4));
        assertThrows(IllegalArgumentException.class, () -> new ContactSheet.Options(8, 1.0, 128, 0));
    }

    @Test
    void requiresAnOutput() {
        assertThrows(IllegalArgumentException.class,
                () -> AssetLabCommand.execute(new String[] {"assets", "contact-sheet", "--samples", "4"}, 1));
    }

    private String runSheet(Path install, Path sheet, String... extra) throws Exception {
        String[] head = {
                "assets", "contact-sheet", "--game", install.toString(), "--out", sheet.toString()};
        String[] args = new String[head.length + extra.length];
        System.arraycopy(head, 0, args, 0, head.length);
        System.arraycopy(extra, 0, args, head.length, extra.length);

        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, "UTF-8"));
            assertEquals(0, PreflightCli.run(args));
        } finally {
            System.setOut(original);
        }
        return captured.toString("UTF-8");
    }

    private static String digestOf(String report) {
        int at = report.indexOf("\"panelsSha256\"");
        assertTrue(at >= 0, report);
        int start = report.indexOf('"', report.indexOf(':', at)) + 1;
        return report.substring(start, report.indexOf('"', start));
    }

    private Path fakeInstall() throws Exception {
        Path install = temporaryDirectory.resolve("install");
        Path mod = install.resolve("mods/Heavy");
        Files.createDirectories(mod.resolve("graphics/ships"));
        Files.createDirectories(mod.resolve("graphics/fx"));
        Files.createDirectories(install.resolve("starsector-core"));
        Files.writeString(mod.resolve("mod_info.json"), "{\"id\":\"heavy\"}");

        // Flat fills encode exactly, so they clear the gate.
        ImageIO.write(flatFills(64, 64), "png", mod.resolve("graphics/ships/hull.png").toFile());
        // Per-pixel noise is the worst case for a block encoder, so it lands over the gate.
        ImageIO.write(noise(64, 64), "png", mod.resolve("graphics/ships/wing.png").toFile());
        // Never encoded regardless of how it measures, because of its name alone.
        ImageIO.write(flatFills(64, 64), "png", mod.resolve("graphics/ships/hull_normal.png").toFile());
        ImageIO.write(noise(32, 32), "png", mod.resolve("graphics/fx/engine_glow.png").toFile());

        Files.writeString(install.resolve("mods/enabled_mods.json"), "{\"enabledMods\":[\"heavy\"]}");
        Path launcher = Files.writeString(install.resolve("starsector.sh"), "#!/bin/sh\n");
        launcher.toFile().setExecutable(true);
        return install;
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

    private static BufferedImage noise(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(20260726L);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0xffffff));
            }
        }
        return image;
    }
}
