package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuietLogShutdownTest {
    @BeforeEach
    void reset() {
        org.apache.log4j.LogManager.shutdownCalls = 0;
    }

    @Test
    void closesLog4jOnlyForTheExplicitQuietMode() throws Exception {
        QuietLogShutdown.close(false);
        assertEquals(0, org.apache.log4j.LogManager.shutdownCalls);

        QuietLogShutdown.close(true);
        assertEquals(1, org.apache.log4j.LogManager.shutdownCalls);
    }
}
