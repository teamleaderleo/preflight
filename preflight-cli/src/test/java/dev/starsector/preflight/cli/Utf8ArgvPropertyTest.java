package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class Utf8ArgvPropertyTest {
    private static final long SEED = 0x5052_4546_4c49_4748L;

    @Test
    void seededValidUnicodeVectorsRoundTripExactlyAndCanonically() {
        Random random = new Random(SEED);
        for (int iteration = 0; iteration < 2_000; iteration++) {
            String[] original = randomVector(random);
            String[] encoded = Utf8Argv.encode(original);

            assertArrayEquals(original, Utf8Argv.decode(encoded), "iteration " + iteration);
            assertArrayEquals(encoded, Utf8Argv.encode(original), "encoding must be canonical");

            if (Utf8Argv.isAscii(original)) {
                assertArrayEquals(original, encoded);
            } else {
                assertEquals(Utf8Argv.SENTINEL, encoded[0]);
                assertTrue(Utf8Argv.isAscii(encoded), "encoded transport must stay ASCII");
                for (int index = 1; index < encoded.length; index++) {
                    assertTrue(
                            encoded[index].chars().allMatch(Utf8ArgvPropertyTest::isUrlSafeBase64Character),
                            () -> "encoded argument was not URL-safe Base64: " + encoded[index]);
                }
            }
        }
    }

    @Test
    void seededCorruptPayloadsRejectDeterministically() {
        Random random = new Random(SEED ^ 0x434f_5252_5550_54L);
        char[] invalid = {'!', '*', ' ', ':', '/', '='};
        for (int iteration = 0; iteration < 500; iteration++) {
            String corrupt = randomAscii(random, 1 + random.nextInt(24))
                    + invalid[random.nextInt(invalid.length)]
                    + randomAscii(random, random.nextInt(24));
            String[] encoded = {Utf8Argv.SENTINEL, corrupt};
            IllegalArgumentException first =
                    assertThrows(IllegalArgumentException.class, () -> Utf8Argv.decode(encoded));
            IllegalArgumentException second =
                    assertThrows(IllegalArgumentException.class, () -> Utf8Argv.decode(encoded));
            assertEquals(first.getMessage(), second.getMessage());
            assertTrue(first.getMessage().contains("Base64"));
        }
    }

    private static String[] randomVector(Random random) {
        int size = random.nextInt(9);
        String[] vector = new String[size];
        for (int index = 0; index < size; index++) {
            vector[index] = randomUnicode(random, random.nextInt(65));
        }
        return vector;
    }

    private static String randomUnicode(Random random, int codePoints) {
        StringBuilder value = new StringBuilder(codePoints);
        for (int index = 0; index < codePoints; index++) {
            int bucket = random.nextInt(10);
            int codePoint;
            if (bucket < 5) {
                // Ordinary command-line text, including spaces and punctuation.
                codePoint = 0x20 + random.nextInt(0x7f - 0x20);
            } else if (bucket < 8) {
                // Valid non-surrogate BMP scalar values.
                do {
                    codePoint = 0x80 + random.nextInt(0xffff - 0x80 + 1);
                } while (Character.isSurrogate((char) codePoint));
            } else {
                // Supplementary Unicode planes.
                codePoint = 0x10000 + random.nextInt(Character.MAX_CODE_POINT - 0x10000 + 1);
            }
            value.appendCodePoint(codePoint);
        }
        return value.toString();
    }

    private static String randomAscii(Random random, int length) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        StringBuilder value = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return value.toString();
    }

    private static boolean isUrlSafeBase64Character(int value) {
        return (value >= 'a' && value <= 'z')
                || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9')
                || value == '-'
                || value == '_';
    }
}
