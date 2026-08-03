package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import org.junit.jupiter.api.Test;

class PreparedTextureTest {
    private static final String HASH = "00".repeat(32);

    @Test
    void defensivelyCopiesPixelsAndPackedColors() {
        byte[] source = {1, 2, 3, 4};
        int color = PreparedTexture.rgba(10, 20, 30, 255);
        PreparedTexture texture = new PreparedTexture(
                HASH,
                PreparedTexture.Transformation.IDENTITY,
                1,
                1,
                1,
                1,
                4,
                color,
                color,
                color,
                source);

        source[0] = 99;
        byte[] returned = texture.pixels();
        returned[1] = 99;

        assertArrayEquals(new byte[] {1, 2, 3, 4}, texture.pixels());
        assertEquals(10, PreparedTexture.red(color));
        assertEquals(20, PreparedTexture.green(color));
        assertEquals(30, PreparedTexture.blue(color));
        assertEquals(255, PreparedTexture.alpha(color));
    }

    @Test
    void rejectsPayloadsThatDoNotMatchDimensions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PreparedTexture(
                        HASH,
                        PreparedTexture.Transformation.IDENTITY,
                        2,
                        2,
                        2,
                        2,
                        4,
                        0,
                        0,
                        0,
                        new byte[15]));
    }

    @Test
    void rejectsInvalidHashesChannelsAndDimensions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PreparedTexture(
                        "bad",
                        PreparedTexture.Transformation.IDENTITY,
                        1,
                        1,
                        1,
                        1,
                        4,
                        0,
                        0,
                        0,
                        new byte[4]));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PreparedTexture(
                        HASH,
                        PreparedTexture.Transformation.IDENTITY,
                        1,
                        1,
                        1,
                        1,
                        2,
                        0,
                        0,
                        0,
                        new byte[2]));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PreparedTexture(
                        HASH,
                        PreparedTexture.Transformation.IDENTITY,
                        0,
                        1,
                        1,
                        1,
                        4,
                        0,
                        0,
                        0,
                        new byte[4]));
    }

    @Test
    void theUncopiedViewReadsTheSamePixelsAndCannotWriteThem() {
        byte[] source = {9, 8, 7, 6};
        PreparedTexture texture = new PreparedTexture(
                HASH, PreparedTexture.Transformation.IDENTITY, 1, 1, 1, 1, 4, 0, 0, 0, source);

        ByteBuffer view = texture.pixelsView();

        assertTrue(view.isReadOnly(), "a writable view would let a caller corrupt a cached texture");
        assertEquals(4, view.remaining());
        byte[] read = new byte[4];
        view.get(read);
        assertArrayEquals(new byte[] {9, 8, 7, 6}, read);
        assertThrows(ReadOnlyBufferException.class, () -> texture.pixelsView().put(0, (byte) 0));
        assertArrayEquals(new byte[] {9, 8, 7, 6}, texture.pixels());
    }

    @Test
    void eachViewHasItsOwnPositionSoTwoReadersDoNotDisturbEachOther() {
        PreparedTexture texture = new PreparedTexture(
                HASH, PreparedTexture.Transformation.IDENTITY, 2, 1, 2, 1, 4, 0, 0, 0,
                new byte[] {1, 2, 3, 4, 5, 6, 7, 8});

        ByteBuffer first = texture.pixelsView();
        first.position(4);
        ByteBuffer second = texture.pixelsView();

        assertEquals(0, second.position());
        assertEquals(4, first.position());
    }

    @Test
    void theAdoptingFactoryKeepsTheArrayItWasGivenAndTheConstructorDoesNot() {
        // The serving path hands over an array it just read and never touches again, so copying it
        // would duplicate the whole texture for nothing. Nothing else may take that shortcut.
        byte[] adopted = {1, 2, 3, 4};
        PreparedTexture sharing = PreparedTexture.adopting(
                HASH, PreparedTexture.Transformation.IDENTITY, 1, 1, 1, 1, 4, 0, 0, 0, adopted);
        byte[] copied = {1, 2, 3, 4};
        PreparedTexture copying = new PreparedTexture(
                HASH, PreparedTexture.Transformation.IDENTITY, 1, 1, 1, 1, 4, 0, 0, 0, copied);

        adopted[0] = 99;
        copied[0] = 99;

        assertEquals(99, sharing.pixels()[0]);
        assertEquals(1, copying.pixels()[0]);
    }
}
