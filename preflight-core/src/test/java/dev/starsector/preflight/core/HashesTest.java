package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HashesTest {
    @Test
    void streamHashLeavesInputOwnershipWithTheCaller() throws Exception {
        byte[] content = "provider-bytes".getBytes(StandardCharsets.UTF_8);
        TrackingInputStream input = new TrackingInputStream(content);

        assertEquals(Hashes.sha256(content), Hashes.sha256(input));
        assertFalse(input.closed, "hashing a caller-owned stream must leave it open");

        input.close();
        assertTrue(input.closed);
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private TrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
