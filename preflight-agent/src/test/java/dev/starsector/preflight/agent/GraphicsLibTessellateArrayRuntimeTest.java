package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
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
        assertEquals(false, telemetry.get("packedReplayEnabled"));
        assertEquals(false, telemetry.get("worldReplayEnabled"));
        assertEquals(true, telemetry.get("installed"));
        assertEquals(2L, telemetry.get("batches"));
        assertEquals(28L, telemetry.get("vertices"));
        assertEquals(2L, telemetry.get("bufferGrows"));
        assertEquals(40L, telemetry.get("largestBufferFloats"));
        assertEquals(14.0, telemetry.get("meanVerticesPerBatch"));
        assertEquals(26L, telemetry.get("estimatedImmediateVertexCallsAvoided"));
    }

    @Test
    void packedReplayCachesLocalFloatsAndBulkFillsWorldCoordinates() {
        GraphicsLibTessellateArrayRuntime.beginSessionForTest(true, true);
        FakeTessData tessData = sampleTessData();
        FloatBuffer buffer = FloatBuffer.allocate(4);

        assertTrue(GraphicsLibTessellateArrayRuntime.fillPacked(
                tessData, buffer, 0.0f, 1.0f, 10.0f, 20.0f));
        buffer.flip();
        assertEquals(8.0f, buffer.get());
        assertEquals(21.0f, buffer.get());
        assertEquals(6.0f, buffer.get());
        assertEquals(17.0f, buffer.get());

        buffer.clear();
        assertTrue(GraphicsLibTessellateArrayRuntime.fillPacked(
                tessData, buffer, 1.0f, 0.0f, 0.0f, 0.0f));

        Map<String, Object> telemetry = GraphicsLibTessellateArrayRuntime.telemetry();
        assertEquals(true, telemetry.get("packedReplayEnabled"));
        assertEquals(false, telemetry.get("worldReplayEnabled"));
        assertEquals(1L, telemetry.get("packedCacheMisses"));
        assertEquals(1L, telemetry.get("packedCacheHits"));
        assertEquals(1L, telemetry.get("packedCacheBuilds"));
        assertEquals(0L, telemetry.get("packedFailures"));
        assertEquals(2L, telemetry.get("packedReplayBatches"));
        assertEquals(4L, telemetry.get("packedReplayVertices"));
        assertEquals(4L, telemetry.get("packedFloatsBuilt"));
        assertEquals(1L, telemetry.get("worldScratchGrows"));
        assertEquals(4L, telemetry.get("largestWorldScratchFloats"));
        assertEquals(0L, telemetry.get("worldReplayHits"));
    }

    @Test
    void worldReplayReusesIdenticalShipTransformAndRefreshesOnMovement() {
        GraphicsLibTessellateArrayRuntime.beginSessionForTest(true, true, true);
        FakeTessData tessData = sampleTessData();
        FloatBuffer buffer = FloatBuffer.allocate(4);

        assertTrue(GraphicsLibTessellateArrayRuntime.fillPacked(
                tessData, buffer, 0.0f, 1.0f, 10.0f, 20.0f));
        buffer.clear();
        assertTrue(GraphicsLibTessellateArrayRuntime.fillPacked(
                tessData, buffer, 0.0f, 1.0f, 10.0f, 20.0f));
        buffer.flip();
        assertEquals(8.0f, buffer.get());
        assertEquals(21.0f, buffer.get());
        assertEquals(6.0f, buffer.get());
        assertEquals(17.0f, buffer.get());

        buffer.clear();
        assertTrue(GraphicsLibTessellateArrayRuntime.fillPacked(
                tessData, buffer, 0.0f, 1.0f, 11.0f, 20.0f));
        buffer.flip();
        assertEquals(9.0f, buffer.get());
        assertEquals(21.0f, buffer.get());

        Map<String, Object> telemetry = GraphicsLibTessellateArrayRuntime.telemetry();
        assertEquals(true, telemetry.get("worldReplayEnabled"));
        assertEquals(1L, telemetry.get("worldReplayHits"));
        assertEquals(2L, telemetry.get("worldReplayMisses"));
        assertEquals(4L, telemetry.get("worldReplayFloatsAvoided"));
        assertEquals(1L, telemetry.get("worldCacheAllocations"));
        assertEquals(4L, telemetry.get("worldCacheFloatsAllocated"));
        assertEquals(0L, telemetry.get("worldScratchGrows"));
    }

    @Test
    void worldReplayRequiresPackedReplay() {
        GraphicsLibTessellateArrayRuntime.beginSessionForTest(true, false, true);
        assertFalse(GraphicsLibTessellateArrayRuntime.worldReplayEnabled());
        assertFalse(GraphicsLibTessellateArrayRuntime.packedReplayEnabled());
    }

    @Test
    void packedReplayFailureClearsBufferForReviewedFallback() {
        GraphicsLibTessellateArrayRuntime.beginSessionForTest(true, true);
        FloatBuffer buffer = FloatBuffer.allocate(4);
        buffer.put(123.0f);

        assertFalse(GraphicsLibTessellateArrayRuntime.fillPacked(
                new Object(), buffer, 1.0f, 0.0f, 0.0f, 0.0f));
        assertEquals(0, buffer.position());
        assertEquals(1L, GraphicsLibTessellateArrayRuntime.telemetry().get("packedFailures"));
    }

    @Test
    void packedReplayRemainsBehindArrayExperimentSwitch() {
        GraphicsLibTessellateArrayRuntime.beginSessionForTest(false, true);
        assertFalse(GraphicsLibTessellateArrayRuntime.packedReplayEnabled());
        assertFalse(GraphicsLibTessellateArrayRuntime.fillPacked(
                new Object(), FloatBuffer.allocate(2), 1.0f, 0.0f, 0.0f, 0.0f));
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

    private static FakeTessData sampleTessData() {
        FakeTessData tessData = new FakeTessData();
        tessData.vertices.add(new FakeVertex(1.0, 2.0));
        tessData.vertices.add(new FakeVertex(-3.0, 4.0));
        return tessData;
    }

    public static final class FakeTessData {
        public final List<FakeVertex> vertices = new ArrayList<>();
    }

    public static final class FakeVertex {
        public final double[] data;

        FakeVertex(double x, double y) {
            data = new double[] {x, y};
        }
    }
}
