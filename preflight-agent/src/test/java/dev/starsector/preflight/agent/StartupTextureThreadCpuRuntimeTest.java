package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StartupTextureThreadCpuRuntimeTest {
    @AfterEach
    void resetRuntime() {
        System.clearProperty(StartupPhaseRuntime.TEXTURE_THREAD_CPU_PROPERTY);
        StartupPhaseRuntime.beginSession(null);
    }

    @Test
    void separatesCursorFromOrdinaryTexturesAndAccountsExactly() {
        FakeClock clock = new FakeClock(true, true);
        StartupPhaseRuntime.configureTextureThreadCpuForTests(true, clock);
        long calibratedReads = clock.reads;
        assertEquals(Long.MIN_VALUE,
                StartupPhaseRuntime.textureThreadCpuStart(TextureType.SPRITE));
        assertEquals(calibratedReads, clock.reads);

        StartupPhaseRuntime.recordTextureThreadCpuForTests(
                StartupPhaseRuntime.CURSOR_TEXTURE_PATH,
                10_000_000L,
                1_000_000L,
                4_000_000L);
        StartupPhaseRuntime.recordTextureThreadCpuForTests(
                "graphics/ships/one.png", 5_000_000L, 10_000_000L, 12_000_000L);
        StartupPhaseRuntime.recordTextureThreadCpuForTests(
                "graphics/ships/two.png", 7_000_000L, 20_000_000L, 24_000_000L);

        Map<String, Object> timing = textureThreadCpu();
        assertEquals(true, timing.get("available"));
        assertEquals("available", timing.get("status"));
        assertEquals(0L, timing.get("cpuReadFailures"));
        assertEquals(0L, timing.get("negativeOrSkewCount"));
        assertEquals(Map.of(
                        "calls", 1L,
                        "wallMillis", 10L,
                        "threadCpuMillis", 3L,
                        "inferredOffCpuMillis", 7L),
                timing.get("cursor"));
        assertEquals(Map.of(
                        "calls", 2L,
                        "wallMillis", 12L,
                        "threadCpuMillis", 6L,
                        "inferredOffCpuMillis", 6L),
                timing.get("other"));

        Map<String, Object> overhead = map(timing.get("clockReadOverhead"));
        assertEquals(10_000L, overhead.get("samples"));
        assertTrue((Long) overhead.get("totalNanos") >= 0L);
        assertTrue((Long) overhead.get("maximumNanos") >= 0L);
    }

    @Test
    void reportsUnsupportedAndRuntimeDisabledCpuClocksWithoutReadingThem() {
        FakeClock unsupported = new FakeClock(false, false);
        StartupPhaseRuntime.configureTextureThreadCpuForTests(true, unsupported);
        assertCpuUnavailable("unsupported");
        assertEquals(0L, unsupported.reads);

        FakeClock disabled = new FakeClock(true, false);
        StartupPhaseRuntime.configureTextureThreadCpuForTests(true, disabled);
        assertCpuUnavailable("disabled-by-runtime");
        assertEquals(0L, disabled.reads);
    }

    @Test
    void containsCpuReadFailureAndLeavesWallTimingUsable() {
        FakeClock clock = new FakeClock(true, true);
        StartupPhaseRuntime.configureTextureThreadCpuForTests(true, clock);
        clock.failReads = true;

        long cpuToken = StartupPhaseRuntime.textureThreadCpuStart(TextureType.TEXTURE);
        long wallToken = StartupPhaseRuntime.hotCallStart();
        StartupPhaseRuntime.resourceLoadEnd(
                TextureType.TEXTURE, "graphics/ships/example.png", 1, wallToken, cpuToken);

        Map<String, Object> timing = textureThreadCpu();
        assertFalse((Boolean) timing.get("available"));
        assertTrue(((String) timing.get("status")).startsWith("read-failed:"));
        assertEquals(1L, timing.get("cpuReadFailures"));
        Map<String, Object> other = map(timing.get("other"));
        assertEquals(1L, other.get("calls"));
        assertNull(other.get("threadCpuMillis"));
        assertNull(other.get("inferredOffCpuMillis"));
        assertEquals(1L, map(resourceLoads()).get("calls"));
    }

    @Test
    void rejectsNegativeCpuAndCountsCpuGreaterThanWallAsSkew() {
        StartupPhaseRuntime.configureTextureThreadCpuForTests(true, new FakeClock(true, true));

        StartupPhaseRuntime.recordTextureThreadCpuForTests(
                "graphics/ships/negative.png", 5_000_000L, 8_000_000L, 7_000_000L);
        StartupPhaseRuntime.recordTextureThreadCpuForTests(
                StartupPhaseRuntime.CURSOR_TEXTURE_PATH,
                1_000_000L,
                1_000_000L,
                3_000_000L);

        Map<String, Object> timing = textureThreadCpu();
        assertEquals(2L, timing.get("negativeOrSkewCount"));
        Map<String, Object> other = map(timing.get("other"));
        assertEquals(1L, other.get("calls"));
        assertNull(other.get("threadCpuMillis"));
        assertNull(other.get("inferredOffCpuMillis"));
        assertEquals(Map.of(
                        "calls", 1L,
                        "wallMillis", 1L,
                        "threadCpuMillis", 2L,
                        "inferredOffCpuMillis", 0L),
                timing.get("cursor"));
    }

    @Test
    void infersOffCpuOnceFromAggregateWhenAClockTickExceedsOneShortCall() {
        StartupPhaseRuntime.configureTextureThreadCpuForTests(true, new FakeClock(true, true));
        StartupPhaseRuntime.recordTextureThreadCpuForTests(
                "graphics/ships/crosses-tick.png",
                1_000_000L,
                1_000_000L,
                3_000_000L);
        StartupPhaseRuntime.recordTextureThreadCpuForTests(
                "graphics/ships/no-tick.png",
                9_000_000L,
                5_000_000L,
                5_000_000L);

        Map<String, Object> timing = textureThreadCpu();
        assertEquals(1L, timing.get("negativeOrSkewCount"));
        assertEquals(Map.of(
                        "calls", 2L,
                        "wallMillis", 10L,
                        "threadCpuMillis", 2L,
                        "inferredOffCpuMillis", 8L),
                timing.get("other"));
    }

    @Test
    void disabledProbeDoesNotReadOrAggregateCpu() {
        FakeClock clock = new FakeClock(true, true);
        StartupPhaseRuntime.configureTextureThreadCpuForTests(false, clock);

        assertEquals(Long.MIN_VALUE,
                StartupPhaseRuntime.textureThreadCpuStart(TextureType.TEXTURE));
        long wallToken = StartupPhaseRuntime.hotCallStart();
        StartupPhaseRuntime.resourceLoadEnd(
                TextureType.TEXTURE, "graphics/ships/example.png", 1, wallToken, Long.MIN_VALUE);

        Map<String, Object> timing = textureThreadCpu();
        assertFalse((Boolean) timing.get("available"));
        assertEquals("disabled", timing.get("status"));
        assertEquals(0L, clock.reads);
        assertEquals(0L, map(timing.get("other")).get("calls"));
        assertEquals(1L, map(resourceLoads()).get("calls"));
    }

    private static void assertCpuUnavailable(String status) {
        Map<String, Object> timing = textureThreadCpu();
        assertFalse((Boolean) timing.get("available"));
        assertEquals(status, timing.get("status"));
        assertEquals(0L, timing.get("cpuReadFailures"));
        assertEquals(0L, map(timing.get("clockReadOverhead")).get("samples"));
    }

    private static Object resourceLoads() {
        return StartupPhaseRuntime.telemetry().get("resourceLoads");
    }

    private static Map<String, Object> textureThreadCpu() {
        return map(map(resourceLoads()).get("textureThreadCpu"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private enum TextureType {
        TEXTURE,
        SPRITE
    }

    private static final class FakeClock implements StartupPhaseRuntime.ThreadCpuClock {
        private final boolean supported;
        private final boolean enabled;
        private long reads;
        private boolean failReads;

        private FakeClock(boolean supported, boolean enabled) {
            this.supported = supported;
            this.enabled = enabled;
        }

        @Override
        public boolean supported() {
            return supported;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public long read() {
            reads++;
            if (failReads) {
                throw new IllegalStateException("synthetic read failure");
            }
            return reads;
        }
    }
}
