package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WindowsPcmCopyRuntimeTest {
    @AfterEach void reset() {
        System.clearProperty(WindowsPcmCopyRuntime.PROPERTY);
        WindowsPcmCopyRuntime.beginSession();
    }

    @Test void bulkCopyPreservesRefillsHighBytesAndStockEofSentinel() throws Exception {
        for (int length : new int[] {0, 1, 8191, 8192, 8193, 25013}) {
            for (boolean earlyEof : new boolean[] {false, true}) {
                Stream stock = new Stream(length, earlyEof, false);
                Stream fast = new Stream(length, earlyEof, false);
                ByteArrayOutputStream expected = new ByteArrayOutputStream(), actual = new ByteArrayOutputStream();
                while (!stock.end()) expected.write(stock.read());
                assertTrue(WindowsPcmCopyRuntime.drain(fast, actual, fast));
                assertArrayEquals(expected.toByteArray(), actual.toByteArray());
                assertEquals(stock.refills, fast.refills);
                assertEquals(stock.buffer.position(), fast.buffer.position());
                assertEquals(stock.offset, fast.offset);
                assertEquals(stock.closed, fast.closed);
            }
        }
        assertTrue((long) WindowsPcmCopyRuntime.report().get("bulkBytes") > 0);
    }

    @Test void refillFailurePreservesTheOutputPrefixAndDoesNotCloseTheStream() {
        Stream stock = new Stream(9000, false, true), fast = new Stream(9000, false, true);
        ByteArrayOutputStream expected = new ByteArrayOutputStream(), actual = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> { while (!stock.end()) expected.write(stock.read()); });
        assertThrows(IOException.class, () -> WindowsPcmCopyRuntime.drain(fast, actual, fast));
        assertArrayEquals(expected.toByteArray(), actual.toByteArray());
        assertFalse(fast.closed);
    }

    @Test void unknownStreamsAreUntouched() throws Exception {
        System.setProperty(WindowsPcmCopyRuntime.PROPERTY, "true");
        Stream stream = new Stream(42, false, false);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertFalse(WindowsPcmCopyRuntime.copy(stream, output));
        assertEquals(0, output.size());
        assertEquals(0, stream.refills);
    }

    /** Models the reviewed separate producer position / consumer offset and one-byte refill. */
    private static final class Stream extends InputStream implements WindowsPcmCopyRuntime.BufferedAccess {
        final int length;
        final boolean earlyEof, fail;
        ByteBuffer buffer = ByteBuffer.allocateDirect(10000);
        int offset, produced, refills;
        boolean eof, closed;
        Stream(int length, boolean earlyEof, boolean fail) {
            this.length = length; this.earlyEof = earlyEof; this.fail = fail;
        }
        public boolean end() { return eof && offset >= buffer.position(); }
        public ByteBuffer buffer() { return buffer; }
        public int offset() { return offset; }
        public void offset(int next) { offset = next; }
        @Override public int read() throws IOException {
            if (offset >= buffer.position()) {
                if (fail && refills > 0) throw new IOException("refill");
                refills++;
                buffer.clear(); offset = 0;
                int count = Math.min(buffer.capacity(), length - produced);
                for (int i = 0; i < count; i++) buffer.put((byte) (produced++ * 31));
                eof = count == 0 || (earlyEof && produced == length);
            }
            return offset >= buffer.position() ? -1 : Byte.toUnsignedInt(buffer.get(offset++));
        }
        @Override public void close() { closed = true; }
    }
}
