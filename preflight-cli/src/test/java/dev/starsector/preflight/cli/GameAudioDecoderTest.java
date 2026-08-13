package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GameAudioDecoderTest {
    @Test
    void acceptsOrdinaryInterleavedPcmShapes() {
        assertTrue(GameAudioDecoder.validPcmShape(4, 1, 22_050));
        assertTrue(GameAudioDecoder.validPcmShape(8, 2, 44_100));
    }

    @Test
    void rejectsHugeChannelCountsWithoutIntOverflow() {
        assertFalse(GameAudioDecoder.validPcmShape(8, Integer.MAX_VALUE, 44_100));
        assertEquals(0L,
                new GameAudioDecoder.Decoded(new byte[8], Integer.MAX_VALUE, 44_100).frames());
    }

    @Test
    void rejectsIncompleteFramesAndInvalidMetadata() {
        assertFalse(GameAudioDecoder.validPcmShape(6, 2, 44_100));
        assertFalse(GameAudioDecoder.validPcmShape(4, 0, 44_100));
        assertFalse(GameAudioDecoder.validPcmShape(4, 1, 0));
        assertFalse(GameAudioDecoder.validPcmShape(0, 1, 44_100));
    }
}
