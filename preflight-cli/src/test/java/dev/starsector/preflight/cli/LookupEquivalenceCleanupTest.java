package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class LookupEquivalenceCleanupTest {
    @Test
    void preservesPrimaryFailureAndSuppressesEveryCloseFailure() throws Exception {
        IllegalStateException primary = new IllegalStateException("lookup failed");
        IOException first = new IOException("first close failed");
        IOException second = new IOException("second close failed");

        LookupEquivalence.closeAll(
                List.of(failing(first), failing(second)),
                primary);

        assertEquals(2, primary.getSuppressed().length);
        assertSame(first, primary.getSuppressed()[0]);
        assertSame(second, primary.getSuppressed()[1]);
    }

    @Test
    void throwsFirstCleanupFailureWhenTheLookupSucceeded() {
        IOException first = new IOException("first close failed");
        IOException second = new IOException("second close failed");

        IOException thrown = assertThrows(IOException.class, () -> LookupEquivalence.closeAll(
                List.of(failing(first), failing(second)),
                null));

        assertSame(first, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(second, thrown.getSuppressed()[0]);
    }

    @Test
    void closesEveryArchiveAfterAnEarlierCloseFailure() throws Exception {
        boolean[] closed = {false};
        Closeable successful = () -> closed[0] = true;

        assertThrows(IOException.class, () -> LookupEquivalence.closeAll(
                List.of(failing(new IOException("close failed")), successful),
                null));

        assertTrue(closed[0]);
    }

    private static Closeable failing(IOException failure) {
        return () -> {
            throw failure;
        };
    }
}
