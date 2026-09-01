package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Causal counters for GraphicsLib's cached-tessellation array replay experiment. */
public final class GraphicsLibTessellateArrayRuntime {
    static final String ENABLED_PROPERTY = "preflight.graphicsLibTessellateArray";
    static final String REPORT_PROPERTY = "preflight.graphicsLibTessellateArray.report";

    private static volatile boolean initialized;
    private static volatile boolean enabled;
    private static volatile Path reportPath;
    private static boolean shutdownHookInstalled;
    private static boolean installed;
    private static long batches;
    private static long vertices;
    private static long bufferGrows;
    private static long largestBufferFloats;

    private GraphicsLibTessellateArrayRuntime() {
    }

    static boolean enabled() {
        initializeFromProperties();
        return enabled;
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

    static synchronized Map<String, Object> telemetry() {
        initializeFromProperties();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", enabled);
        values.put("installed", installed);
        values.put("batches", batches);
        values.put("vertices", vertices);
        values.put("bufferGrows", bufferGrows);
        values.put("largestBufferFloats", largestBufferFloats);
        values.put("meanVerticesPerBatch", batches == 0 ? 0.0 : vertices / (double) batches);
        values.put("estimatedImmediateVertexCallsAvoided", Math.max(0L, vertices - batches));
        values.put("reportPath", reportPath == null ? "" : reportPath.toString());
        return values;
    }

    static synchronized void beginSessionForTest(boolean requested) {
        configure(requested, null, false);
    }

    static synchronized void resetForTest() {
        initialized = false;
        enabled = false;
        reportPath = null;
        installed = false;
        clearCounters();
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
                    readPath(System.getProperty(REPORT_PROPERTY)),
                    true);
        }
    }

    private static void configure(boolean requested, Path report, boolean hook) {
        enabled = requested;
        reportPath = report;
        installed = false;
        clearCounters();
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
