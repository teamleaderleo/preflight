package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Causal counters for GraphicsLib's cached-tessellation array replay experiment. */
public final class GraphicsLibTessellateArrayRuntime {
    static final String ENABLED_PROPERTY = "preflight.graphicsLibTessellateArray";
    static final String PACKED_REPLAY_PROPERTY = "preflight.graphicsLibTessellatePackedReplay";
    static final String REPORT_PROPERTY = "preflight.graphicsLibTessellateArray.report";

    private static final ThreadLocal<WeakHashMap<Object, float[]>> PACKED_LOCAL =
            ThreadLocal.withInitial(WeakHashMap::new);
    private static final ThreadLocal<float[]> WORLD_SCRATCH =
            ThreadLocal.withInitial(() -> new float[0]);

    private static volatile boolean initialized;
    private static volatile boolean enabled;
    private static volatile boolean packedReplayEnabled;
    private static volatile Path reportPath;
    private static boolean shutdownHookInstalled;
    private static boolean installed;
    private static long batches;
    private static long vertices;
    private static long bufferGrows;
    private static long largestBufferFloats;
    private static long packedCacheHits;
    private static long packedCacheMisses;
    private static long packedCacheBuilds;
    private static long packedFailures;
    private static long packedReplayBatches;
    private static long packedReplayVertices;
    private static long packedFloatsBuilt;
    private static long worldScratchGrows;
    private static long largestWorldScratchFloats;

    private GraphicsLibTessellateArrayRuntime() {
    }

    static boolean enabled() {
        initializeFromProperties();
        return enabled;
    }

    static boolean packedReplayEnabled() {
        initializeFromProperties();
        return enabled && packedReplayEnabled;
    }

    static void installed() {
        installed = true;
    }

    /** Called from GraphicsLib's render thread after one cached polygon is submitted. */
    public static void batch(int vertexCount) {
        if (vertexCount <= 0) {
            return;
        }
        batches++;
        vertices += vertexCount;
    }

    /** Called only when the reusable direct vertex buffer has to grow. */
    public static void bufferGrow(int floatCapacity) {
        if (floatCapacity <= 0) {
            return;
        }
        bufferGrows++;
        largestBufferFloats = Math.max(largestBufferFloats, floatCapacity);
    }

    /**
     * Fill the existing direct vertex buffer from a primitive cache tied to one GraphicsLib
     * TessData instance. A false result leaves the buffer cleared so the generated helper can
     * execute its original reviewed list/iterator replay as a fallback.
     */
    public static boolean fillPacked(
            Object tessData,
            FloatBuffer buffer,
            float cos,
            float sin,
            float locationX,
            float locationY) {
        if (!packedReplayEnabled() || tessData == null || buffer == null) {
            return false;
        }

        try {
            WeakHashMap<Object, float[]> cache = PACKED_LOCAL.get();
            float[] local = cache.get(tessData);
            if (local == null) {
                packedCacheMisses++;
                local = packLocalVertices(tessData);
                cache.put(tessData, local);
                packedCacheBuilds++;
                packedFloatsBuilt += local.length;
            } else {
                packedCacheHits++;
            }

            float[] world = WORLD_SCRATCH.get();
            if (world.length < local.length) {
                world = new float[local.length];
                WORLD_SCRATCH.set(world);
                worldScratchGrows++;
                largestWorldScratchFloats = Math.max(largestWorldScratchFloats, world.length);
            }

            for (int i = 0; i < local.length; i += 2) {
                float x = local[i];
                float y = local[i + 1];
                world[i] = x * cos - y * sin + locationX;
                world[i + 1] = x * sin + y * cos + locationY;
            }

            buffer.put(world, 0, local.length);
            packedReplayBatches++;
            packedReplayVertices += local.length / 2L;
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            packedFailures++;
            buffer.clear();
            return false;
        }
    }

    private static float[] packLocalVertices(Object tessData) throws ReflectiveOperationException {
        Field verticesField = tessData.getClass().getField("vertices");
        Object rawVertices = verticesField.get(tessData);
        if (!(rawVertices instanceof List<?> vertexList)) {
            throw new IllegalArgumentException("TessData.vertices is not a List");
        }

        float[] packed = new float[Math.multiplyExact(vertexList.size(), 2)];
        Field dataField = null;
        Class<?> vertexClass = null;
        int offset = 0;
        for (Object vertex : vertexList) {
            if (vertex == null) {
                throw new IllegalArgumentException("TessData.vertices contains null");
            }
            if (dataField == null || vertexClass != vertex.getClass()) {
                vertexClass = vertex.getClass();
                dataField = vertexClass.getField("data");
            }
            Object rawData = dataField.get(vertex);
            if (!(rawData instanceof double[] data) || data.length < 2) {
                throw new IllegalArgumentException("VertexDataV2.data is not a coordinate array");
            }
            packed[offset++] = (float) data[0];
            packed[offset++] = (float) data[1];
        }
        return packed;
    }

    static synchronized Map<String, Object> telemetry() {
        initializeFromProperties();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", enabled);
        values.put("packedReplayEnabled", packedReplayEnabled);
        values.put("installed", installed);
        values.put("batches", batches);
        values.put("vertices", vertices);
        values.put("bufferGrows", bufferGrows);
        values.put("largestBufferFloats", largestBufferFloats);
        values.put("meanVerticesPerBatch", batches == 0 ? 0.0 : vertices / (double) batches);
        values.put("estimatedImmediateVertexCallsAvoided", Math.max(0L, vertices - batches));
        values.put("packedCacheHits", packedCacheHits);
        values.put("packedCacheMisses", packedCacheMisses);
        values.put("packedCacheBuilds", packedCacheBuilds);
        values.put("packedFailures", packedFailures);
        values.put("packedReplayBatches", packedReplayBatches);
        values.put("packedReplayVertices", packedReplayVertices);
        values.put("packedFloatsBuilt", packedFloatsBuilt);
        values.put("worldScratchGrows", worldScratchGrows);
        values.put("largestWorldScratchFloats", largestWorldScratchFloats);
        values.put("reportPath", reportPath == null ? "" : reportPath.toString());
        return values;
    }

    static synchronized void beginSessionForTest(boolean requested) {
        configure(requested, false, null, false);
    }

    static synchronized void beginSessionForTest(boolean requested, boolean packedRequested) {
        configure(requested, packedRequested, null, false);
    }

    static synchronized void resetForTest() {
        initialized = false;
        enabled = false;
        packedReplayEnabled = false;
        reportPath = null;
        installed = false;
        clearCounters();
        PACKED_LOCAL.remove();
        WORLD_SCRATCH.remove();
    }

    private static void initializeFromProperties() {
        if (initialized) {
            return;
        }
        synchronized (GraphicsLibTessellateArrayRuntime.class) {
            if (initialized) {
                return;
            }
            configure(
                    Boolean.getBoolean(ENABLED_PROPERTY),
                    Boolean.getBoolean(PACKED_REPLAY_PROPERTY),
                    readPath(System.getProperty(REPORT_PROPERTY)),
                    true);
        }
    }

    private static void configure(
            boolean requested, boolean packedRequested, Path report, boolean hook) {
        enabled = requested;
        packedReplayEnabled = requested && packedRequested;
        reportPath = report;
        installed = false;
        clearCounters();
        PACKED_LOCAL.remove();
        WORLD_SCRATCH.remove();
        initialized = true;
        if (hook && report != null && !shutdownHookInstalled) {
            shutdownHookInstalled = true;
            Runtime.getRuntime().addShutdownHook(new Thread(
                    GraphicsLibTessellateArrayRuntime::writeReport,
                    "Preflight-GraphicsLib-Tessellate-Report"));
        }
    }

    private static void clearCounters() {
        batches = 0L;
        vertices = 0L;
        bufferGrows = 0L;
        largestBufferFloats = 0L;
        packedCacheHits = 0L;
        packedCacheMisses = 0L;
        packedCacheBuilds = 0L;
        packedFailures = 0L;
        packedReplayBatches = 0L;
        packedReplayVertices = 0L;
        packedFloatsBuilt = 0L;
        worldScratchGrows = 0L;
        largestWorldScratchFloats = 0L;
    }

    private static Path readPath(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Path.of(raw).toAbsolutePath().normalize();
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    private static void writeReport() {
        Path destination = reportPath;
        if (destination == null) {
            return;
        }
        try {
            Path parent = destination.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    destination,
                    Json.object(telemetry()) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException ignored) {
            // Diagnostic output is optional; rendering must survive report failures.
        }
    }
}
