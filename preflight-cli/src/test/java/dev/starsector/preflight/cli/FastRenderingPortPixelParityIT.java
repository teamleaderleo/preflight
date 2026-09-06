package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.starsector.preflight.core.PreparedTexture;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Executes only image conversion from locally reviewed archives; no GL context or game launch. */
class FastRenderingPortPixelParityIT {
    @Test
    void preparedPixelsAndColorsMatchBothReleasedBuilders() throws Exception {
        String directory = System.getProperty("preflight.fastRendering.portDirectory", "");
        String lwjgl = System.getProperty("preflight.fastRendering.lwjglJar", "");
        Assumptions.assumeTrue(!directory.isBlank() && !lwjgl.isBlank());
        for (String platform : new String[] {"linux", "mac"}) {
            try (var loader = new URLClassLoader(new URL[] {
                    Path.of(directory, platform, "fr.jar").toUri().toURL(),
                    Path.of(lwjgl).toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                var convert = loader.loadClass("com.genir.renderer.overrides.loading.textures.TextureBuilder")
                        .getMethod("readAndAnalyzeImage", BufferedImage.class, boolean.class);
                for (int imageType : new int[] {BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_ARGB,
                        BufferedImage.TYPE_3BYTE_BGR, BufferedImage.TYPE_4BYTE_ABGR}) {
                    BufferedImage image = new BufferedImage(3, 5, imageType);
                    for (int y = 0; y < image.getHeight(); y++) {
                        for (int x = 0; x < image.getWidth(); x++) {
                            int alpha = (x + y) % 3 == 1 ? 117 : 255;
                            image.setRGB(x, y, new Color(19 + x * 67, 23 + y * 43, 251 - x * 37, alpha).getRGB());
                        }
                    }
                    PreparedTexture prepared = ReferenceTexturePreprocessor.prepare(image, "1".repeat(64),
                            PreparedTexture.Transformation.IDENTITY);
                    Object actual = convert.invoke(null, image, false);
                    Class<?> type = actual.getClass();
                    String context = platform + "/" + imageType;
                    assertEquals(prepared.pixelsView(), type.getField("buffer").get(actual), context);
                    assertEquals(prepared.originalWidth(), type.getField("imageWidth").getInt(actual), context);
                    assertEquals(prepared.originalHeight(), type.getField("imageHeight").getInt(actual), context);
                    assertEquals(prepared.uploadWidth(), type.getField("width").getInt(actual), context);
                    assertEquals(prepared.uploadHeight(), type.getField("height").getInt(actual), context);
                    assertEquals(prepared.hasAlpha(), type.getField("hasAlpha").getBoolean(actual), context);
                    assertEquals(color(prepared.color0Rgba()), type.getField("mean").get(actual), context);
                    assertEquals(color(prepared.color1Rgba()), type.getField("weighted").get(actual), context);
                    assertEquals(color(prepared.color2Rgba()), type.getField("median").get(actual), context);
                }
                // Preserve the excluded zero-alpha discrepancy: the released integer-ARGB
                // loop retains alpha from a previous scanline. The port bridge declines it.
                BufferedImage excluded = new BufferedImage(1, 2, BufferedImage.TYPE_INT_ARGB);
                excluded.setRGB(0, 1, 0xff123456);
                excluded.setRGB(0, 0, 0x00123456);
                Object actual = convert.invoke(null, excluded, false);
                var buffer = (java.nio.ByteBuffer) actual.getClass().getField("buffer").get(actual);
                assertEquals(255, Byte.toUnsignedInt(buffer.get(7)), "released stale alpha behavior");
                PreparedTexture correct = ReferenceTexturePreprocessor.prepare(excluded, "2".repeat(64),
                        PreparedTexture.Transformation.IDENTITY);
                assertEquals(0, correct.pixelsView().get(7), "prepared pixels retain true transparency");
            }
        }
    }

    private static Color color(int rgba) {
        return new Color(PreparedTexture.red(rgba), PreparedTexture.green(rgba),
                PreparedTexture.blue(rgba), PreparedTexture.alpha(rgba));
    }
}
