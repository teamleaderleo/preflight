package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AgentOptionsPropertyTest {
    private static final long SEED = 0x4147_454e_544f_5054L;

    @Test
    void seededEncodedPathsPreserveUnicodeSpacesAndCommas() {
        Random random = new Random(SEED);
        for (int iteration = 0; iteration < 1_000; iteration++) {
            Path destination = randomPath(random, "trace", ".jfr");
            Path textureCache = randomPath(random, "textures", "");
            Path preparedAudioCache = randomPath(random, "audio", "");
            Path janinoCache = randomPath(random, "janino", "");

            AgentOptions options = AgentOptions.parse(
                    "adapter=enabled"
                            + ",dest64=" + encoded(destination)
                            + ",textureCache64=" + encoded(textureCache)
                            + ",preparedAudioCache64=" + encoded(preparedAudioCache)
                            + ",janinoBytecodeCache64=" + encoded(janinoCache));

            assertEquals(destination, options.destination(), "destination iteration " + iteration);
            assertEquals(textureCache, options.textureCacheDirectory(), "texture iteration " + iteration);
            assertEquals(preparedAudioCache, options.preparedAudioCache(), "audio iteration " + iteration);
            assertEquals(janinoCache, options.janinoBytecodeCache(), "janino iteration " + iteration);
            assertEquals(
                    destination.resolveSibling("adapter.json"),
                    options.adapterReport(),
                    "default report must follow the decoded destination");
        }
    }

    @Test
    void seededCorruptEncodedPathsRejectDeterministically() {
        Random random = new Random(SEED ^ 0x434f_5252_5550_54L);
        String[] keys = {
            "dest64", "adapterReport64", "textureCache64", "preparedAudioCache64", "janinoBytecodeCache64"
        };
        for (int iteration = 0; iteration < 500; iteration++) {
            String key = keys[random.nextInt(keys.length)];
            String corrupt = randomBase64(random, 1 + random.nextInt(20)) + "!" + randomBase64(random, random.nextInt(20));
            IllegalArgumentException first = assertThrows(
                    IllegalArgumentException.class, () -> AgentOptions.parse(key + "=" + corrupt));
            IllegalArgumentException second = assertThrows(
                    IllegalArgumentException.class, () -> AgentOptions.parse(key + "=" + corrupt));
            assertEquals(first.getMessage(), second.getMessage());
            assertTrue(first.getMessage().contains(key), first.getMessage());
        }
    }

    private static Path randomPath(Random random, String stem, String suffix) {
        String segment = randomSegment(random, 1 + random.nextInt(20));
        return Path.of("build", segment + ", spaced", stem + "-" + random.nextInt(10_000) + suffix);
    }

    private static String randomSegment(Random random, int codePoints) {
        int[] alphabet = {
            'a', 'Z', '0', '-', '_', ' ', ',', '.',
            0x03a9, // Ω
            0x0416, // Ж
            0x65e5, // 日
            0x754c, // 界
            0x1f680, // 🚀
            0x1f9ea // 🧪
        };
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < codePoints; index++) {
            value.appendCodePoint(alphabet[random.nextInt(alphabet.length)]);
        }
        return value.toString();
    }

    private static String encoded(Path path) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(path.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String randomBase64(Random random, int length) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        StringBuilder value = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return value.toString();
    }
}
