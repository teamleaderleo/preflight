package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StelnetMarketUpdaterRuntimeTest {
    @AfterEach
    void reset() {
        StelnetMarketUpdaterRuntime.reset();
    }

    @Test
    void servesEveryMarketOnceThenStopsUntilInvalidated() {
        List<String> markets = List.of("alpha", "beta", "gamma");
        StelnetMarketUpdaterRuntime.observePaused(true);
        Set<Object> served = new HashSet<>();
        while (StelnetMarketUpdaterRuntime.hasPending() && served.size() < markets.size()) {
            served.add(StelnetMarketUpdaterRuntime.nextMarket(markets));
        }
        assertEquals(Set.copyOf(markets), served);
        assertFalse(StelnetMarketUpdaterRuntime.hasPending());

        StelnetMarketUpdaterRuntime.invalidate();
        assertTrue(StelnetMarketUpdaterRuntime.hasPending());
        assertTrue(markets.contains(StelnetMarketUpdaterRuntime.nextMarket(markets)));
        assertEquals(4L, StelnetMarketUpdaterRuntime.telemetry().get("marketsServed"));
        assertEquals(1L, StelnetMarketUpdaterRuntime.telemetry().get("invalidations"));
    }

    @Test
    void unpauseStartsAFreshInterval() {
        List<String> markets = List.of("alpha");
        StelnetMarketUpdaterRuntime.observePaused(true);
        assertEquals("alpha", StelnetMarketUpdaterRuntime.nextMarket(markets));
        assertFalse(StelnetMarketUpdaterRuntime.hasPending());
        StelnetMarketUpdaterRuntime.observePaused(false);
        StelnetMarketUpdaterRuntime.observePaused(true);
        assertTrue(StelnetMarketUpdaterRuntime.hasPending());
        assertEquals("alpha", StelnetMarketUpdaterRuntime.nextMarket(markets));
        assertEquals(2L, StelnetMarketUpdaterRuntime.telemetry().get("pauseIntervals"));
    }

    @Test
    void queueFailureSelectsPreservedOriginalFallback() {
        List<Object> broken = new AbstractList<>() {
            @Override
            public Object get(int index) {
                throw new IllegalStateException("cannot snapshot");
            }

            @Override
            public int size() {
                return 1;
            }
        };
        Object result = StelnetMarketUpdaterRuntime.nextMarket(broken);
        assertTrue(StelnetMarketUpdaterRuntime.isFallback(result));
        assertEquals(1L, StelnetMarketUpdaterRuntime.telemetry().get("delegated"));
        assertEquals(1L, StelnetMarketUpdaterRuntime.telemetry().get("failures"));
        assertTrue(StelnetMarketUpdaterRuntime.hasPending());
    }
}
