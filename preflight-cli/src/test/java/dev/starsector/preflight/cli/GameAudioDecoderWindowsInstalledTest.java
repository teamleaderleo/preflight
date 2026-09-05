package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;

class GameAudioDecoderWindowsInstalledTest {
    @Test @Timeout(60)
    void preparesTheExactInstalledWindowsDecoderOutput() throws Exception {
        String configured = System.getProperty("preflight.windows.audio.fixtures", "");
        Assumptions.assumeFalse(configured.isBlank(), "Supply private installed Windows audio fixtures");
        Path root = Path.of(configured);
        URL[] urls = new URL[5];
        int index = 0;
        for (String name : List.of("fs.sound_obf.jar", "jogg-0.0.7.jar", "jorbis-0.0.15.jar", "log4j-1.2.9.jar", "lwjgl.jar")) {
            urls[index++] = root.resolve(name).toUri().toURL();
        }
        try (var game = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            GameAudioDecoder decoder = GameAudioDecoder.boundTo(game);
            Class<?> type = game.loadClass("sound.O0oO");
            for (String name : List.of("small.ogg", "medium.ogg", "large.ogg")) {
                byte[] encoded = Files.readAllBytes(root.resolve(name));
                Object stock = type.getMethod("super", InputStream.class).invoke(
                        type.getConstructor().newInstance(), new ByteArrayInputStream(encoded));
                ByteBuffer buffer = (ByteBuffer) stock.getClass().getField("Object").get(stock);
                byte[] expected = new byte[buffer.remaining()];
                buffer.get(expected);
                var actual = decoder.decode(encoded);
                assertNotNull(actual, name);
                assertArrayEquals(expected, actual.samples(), name);
                assertEquals(stock.getClass().getField("o00000").getInt(stock), actual.channels());
                assertEquals(stock.getClass().getField("Ò00000").getInt(stock), actual.sampleRate());
            }
        }
    }
}
