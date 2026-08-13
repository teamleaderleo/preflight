package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoadingUtilsReaderRuntimeTest {
    @BeforeEach
    void begin() {
        LoadingUtilsReaderRuntime.beginSession();
    }

    @AfterEach
    void reset() {
        LoadingUtilsReaderRuntime.beginSession();
    }

    @Test
    void decodesOnceStripsCarriageReturnsAndCloses() throws Exception {
        CloseObserved input = new CloseObserved("alpha\r\nβeta\r".getBytes(StandardCharsets.UTF_8));
        assertEquals("alpha\nβeta", LoadingUtilsReaderRuntime.readUtf8(input));
        assertTrue(input.closed);
        assertEquals(1L, LoadingUtilsReaderRuntime.telemetry().get("calls"));
        assertEquals(2L, LoadingUtilsReaderRuntime.telemetry().get("removedCarriageReturns"));
    }

    private static final class CloseObserved extends ByteArrayInputStream {
        private boolean closed;

        private CloseObserved(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
