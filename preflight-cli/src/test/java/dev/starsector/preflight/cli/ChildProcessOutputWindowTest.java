package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * What the retained window keeps, and what it promises about what it dropped.
 *
 * <p>A capture that fits must come back byte-identical and unmarked, because that is what lets the
 * marker mean something when it does appear.
 */
class ChildProcessOutputWindowTest {
    @Test
    void outputThatFitsIsReproducedExactlyAndCarriesNoMarker() {
        byte[] stream = "the game started, then it stopped\n".getBytes(StandardCharsets.UTF_8);
        ChildProcessOutput.HeadTailBuffer buffer = new ChildProcessOutput.HeadTailBuffer(8, 64);

        buffer.append(stream, 0, stream.length);

        assertFalse(buffer.truncated());
        assertEquals(0, buffer.droppedBytes());
        assertArrayEquals(stream, buffer.bytes());
    }

    @Test
    void aStreamSplitAcrossManyWritesIsStillReproducedExactly() {
        byte[] stream = "abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8);
        ChildProcessOutput.HeadTailBuffer buffer = new ChildProcessOutput.HeadTailBuffer(4, 40);

        for (int index = 0; index < stream.length; index++) {
            buffer.append(stream, index, 1);
        }

        assertFalse(buffer.truncated());
        assertArrayEquals(stream, buffer.bytes());
    }

    @Test
    void anOverlongStreamKeepsBothEndsAndSaysWhatItDropped() {
        // The head is what identifies the launch; a tail-only window would lose it entirely.
        ChildProcessOutput.HeadTailBuffer buffer = new ChildProcessOutput.HeadTailBuffer(10, 10);
        byte[] opening = "STARTUP-01".getBytes(StandardCharsets.UTF_8);
        byte[] middle = new byte[500];
        java.util.Arrays.fill(middle, (byte) 'x');
        byte[] ending = "FATAL-LAST".getBytes(StandardCharsets.UTF_8);

        buffer.append(opening, 0, opening.length);
        buffer.append(middle, 0, middle.length);
        buffer.append(ending, 0, ending.length);

        assertTrue(buffer.truncated());
        assertEquals(500, buffer.droppedBytes());
        String retained = new String(buffer.bytes(), StandardCharsets.UTF_8);
        assertTrue(retained.startsWith("STARTUP-01"), retained);
        assertTrue(retained.endsWith("FATAL-LAST"), retained);
        assertTrue(retained.contains("omitted 500 bytes"), retained);
        assertEquals(520, buffer.totalBytes());
    }

    @Test
    void theTailKeepsTheEndEvenWhenItWrapsRepeatedly() {
        ChildProcessOutput.HeadTailBuffer buffer = new ChildProcessOutput.HeadTailBuffer(4, 6);
        byte[] stream = "0123456789ABCDEF".getBytes(StandardCharsets.UTF_8);

        buffer.append(stream, 0, stream.length);

        String retained = new String(buffer.bytes(), StandardCharsets.UTF_8);
        assertTrue(retained.startsWith("0123"), retained);
        assertTrue(retained.endsWith("ABCDEF"), retained);
        assertEquals(6, buffer.droppedBytes());
    }

    @Test
    void theMarkerCannotPushTheFilePastTheBoundItDescribes() {
        // The reserve exists so that "console.txt is at most a megabyte" stays true once the
        // omission line is added. Sized against the widest counts the line can render.
        ChildProcessOutput.HeadTailBuffer buffer = new ChildProcessOutput.HeadTailBuffer(
                ChildProcessOutput.MAX_HEAD_BYTES,
                ChildProcessOutput.MAX_CAPTURE_BYTES
                        - ChildProcessOutput.MAX_HEAD_BYTES
                        - ChildProcessOutput.ELISION_RESERVE_BYTES);
        byte[] chunk = new byte[64 * 1024];
        java.util.Arrays.fill(chunk, (byte) 'y');
        for (int written = 0; written < 4 * ChildProcessOutput.MAX_CAPTURE_BYTES; written += chunk.length) {
            buffer.append(chunk, 0, chunk.length);
        }

        assertTrue(buffer.truncated());
        assertTrue(
                buffer.bytes().length <= ChildProcessOutput.MAX_CAPTURE_BYTES,
                "retained " + buffer.bytes().length);
    }
}
