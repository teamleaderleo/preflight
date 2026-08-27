package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FrameCpuTimeRuntimeTest {
    @AfterEach
    void reset() {
        FrameCpuTimeRuntime.reset();
    }

    @Test
    void separatesActiveCpuFromOffCpuTimeForAcceptedFrames() {
        FrameCpuTimeRuntime.beginSession(true);
        FrameCpuTimeRuntime.recordBoundary(10_000_000L, 3_000_000L);
        FrameCpuTimeRuntime.recordBoundary(30_000_000L, 8_000_000L);

        Map<String, Object> telemetry = FrameCpuTimeRuntime.telemetry();
        Map<String, Object> cpu = map(telemetry.get("cpuActive"));
        Map<String, Object> offCpu = map(telemetry.get("offCpuApprox"));

        assertEquals(1L, telemetry.get("samples"));
        assertEquals(5_000.0, cpu.get("meanMicros"));
        assertEquals(5_000L, cpu.get("p99Micros"));
        assertEquals(25.0, cpu.get("meanFrameSharePercent"));
        assertEquals(15_000.0, offCpu.get("meanMicros"));
        assertEquals(15_000L, offCpu.get("p99Micros"));
        assertEquals(75.0, offCpu.get("meanFrameSharePercent"));
    }

    @Test
    void dropsIntervalsThatCrossFocusLoss() {
        FrameCpuTimeRuntime.beginSession(true);
        FrameCpuTimeRuntime.recordBoundary(0L, 0L);

        FrameCpuTimeRuntime.observeActive(false);
        FrameCpuTimeRuntime.recordBoundary(10_000_000L, 1_000_000L);
        FrameCpuTimeRuntime.observeActive(true);
        FrameCpuTimeRuntime.recordBoundary(20_000_000L, 2_000_000L);
        FrameCpuTimeRuntime.recordBoundary(30_000_000L, 3_000_000L);

        Map<String, Object> telemetry = FrameCpuTimeRuntime.telemetry();
        assertEquals(1L, telemetry.get("samples"));
        assertEquals(2L, telemetry.get("inactiveIntervalsDropped"));
        assertEquals(1_000.0, map(telemetry.get("cpuActive")).get("meanMicros"));
        assertEquals(9_000.0, map(telemetry.get("offCpuApprox")).get("meanMicros"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
