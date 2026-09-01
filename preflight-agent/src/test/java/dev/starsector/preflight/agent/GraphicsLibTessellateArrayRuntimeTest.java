package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GraphicsLibTessellateArrayRuntimeTest {
    @AfterEach
    void reset() {
        GraphicsLibTessellateArrayRuntime.resetForTest();
    }

    @Test
    void publishesBatchVertexAndBufferGrowthCounters() {
        GraphicsLibTessellateArrayRuntime.beginSessionForTest(true);
        GraphicsLibTessellateArrayRuntime.installed();
        GraphicsLibTessellateArrayRuntime.bufferGrow(16);
        GraphicsLibTessellateArrayRuntime.bufferGrow(40);
        GraphicsLibTessellateArrayRuntime.batch(8);
        GraphicsLibTessellateArrayRuntime.batch(20);

        Map<String, Object> telemetry = GraphicsLibTessellateArrayRuntime.telemetry();
        assertEquals(true, telemetry.get("enabled"));
        assertEquals(true, telemetry.get("installed"));
        assertEquals(2L, telemetry.get("batches"));
        assertEquals(28L, telemetry.get("vertices"));
        assertEquals(2L, telemetry.get("bufferGrows"));
        assertEquals(40L, telemetry.get("largestBufferFloats"));
        assertEquals(14.0, telemetry.get("meanVerticesPerBatch"));
        assertEquals(26L, telemetry.get("estimatedImmediateVertexCallsAvoided"));
    }

    @Test
    void ignoresEmptyBatchAndGrowthSignals() {
        GraphicsLibTessellateArrayRuntime.beginSessionForTest(true);
        GraphicsLibTessellateArrayRuntime.batch(0);
        GraphicsLibTessellateArrayRuntime.bufferGrow(-1);
        Map<String, Object> telemetry = GraphicsLibTessellateArrayRuntime.telemetry();
        assertEquals(0L, telemetry.get("batches"));
        assertEquals(0L, telemetry.get("bufferGrows"));
    }
}
