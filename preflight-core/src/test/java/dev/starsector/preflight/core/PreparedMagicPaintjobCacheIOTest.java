package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class PreparedMagicPaintjobCacheIOTest {
    private static final String PROFILE = "12".repeat(32);

    @Test
    void roundTripsChecksummedPayload() throws Exception {
        PreparedMagicPaintjobCache expected =
                new PreparedMagicPaintjobCache(PROFILE, new byte[] {1, 2, 3, 4});

        PreparedMagicPaintjobCache actual = PreparedMagicPaintjobCacheIO.fromBytes(
                PreparedMagicPaintjobCacheIO.toBytes(expected));

        assertEquals(PROFILE, actual.profileIdentitySha256());
        assertArrayEquals(expected.payload(), actual.payload());
    }

    @Test
    void rejectsCorruptedPayload() throws Exception {
        byte[] bytes = PreparedMagicPaintjobCacheIO.toBytes(
                new PreparedMagicPaintjobCache(PROFILE, new byte[] {1, 2, 3, 4}));
        bytes[bytes.length - 33] ^= 1;

        assertThrows(IOException.class, () -> PreparedMagicPaintjobCacheIO.fromBytes(bytes));
    }
}
