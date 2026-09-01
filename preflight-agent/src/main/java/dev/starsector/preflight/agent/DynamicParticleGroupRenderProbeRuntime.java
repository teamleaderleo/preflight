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

/** Low-overhead aggregate timing for vanilla DynamicParticleGroup.render(float,float). */
public final class DynamicParticleGroupRenderProbeRuntime {
    static final String ENABLED_PROPERTY = "preflight.dynamicParticleGroupProbe";
    static final String REPORT_PROPERTY = "preflight.dynamicParticleGroupProbe.report";

    private static volatile boolean initialized;
    private static volatile boolean enabled;
    private static volatile Path reportPath;
    private static boolean shutdownHookInstalled;
    private static boolean installed;
    private static int returnSites;
    private static int glBeginSites;
    private static int glEndSites;
    private static int vertexSites;
    private static int texCoordSites;
    private static int colorSites;
    private static int bindTextureSites;
    private static int blendFuncSites;

    // render() is owned by Starsector's presentation/render thread. Plain counters keep the probe
    // cheaper than synchronized/atomic accounting on every particle-group render.
    private static long calls;
    private static long totalNanos;
    private static long maximumNanos;
    private static long over250Micros;
    private static long over500Micros;
    private static long over1Millis;
    private static long over2Millis;
    private static long over5Millis;

    private DynamicParticleGroupRenderProbeRuntime() {
    }

    static boolean enabled() {
        initializeFromProperties();
        return enabled;
    }

    /** Called immediately before the exact-target render method body. */
    public static long begin() {
        return System.nanoTime();
    }

    /** Called immediately before each normal return from the exact-target render method. */
    public static void end(long startNanos) {
        long elapsed = System.nanoTime() - startNanos;
        if (elapsed < 0L) {
            return;
        }
        calls++;
        totalNanos += elapsed;
        maximumNanos = Math.max(maximumNanos, elapsed);
        if (elapsed > 250_000L) over250Micros++;
        if (elapsed > 500_000L) over500Micros++;
        if (elapsed > 1_000_000L) over1Millis++;
        if (elapsed > 2_000_000L) over2Millis++;
        if (elapsed > 5_000_000L) over5Millis++;
    }

    static void installed(
            int returns,
            int begins,
            int ends,
            int vertices,
            int texCoords,
            int colors,
            int bindTextures,
            int blendFuncs) {
        installed = true;
        returnSites = returns;
        glBeginSites = begins;
        glEndSites = ends;
        vertexSites = vertices;
        texCoordSites = texCoords;
        colorSites = colors;
        bindTextureSites = bindTextures;
        blendFuncSites = blendFuncs;
    }

    static synchronized Map<String, Object> telemetry() {
        initializeFromProperties();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", enabled);
        values.put("installed", installed);
        values.put("calls", calls);
        values.put("totalMillis", totalNanos / 1_000_000.0);
        values.put("meanMicros", calls == 0L ? null : totalNanos / 1_000.0 / calls);
        values.put("maximumMicros", calls == 0L ? null : maximumNanos / 1_000.0);
        values.put("over250Micros", over250Micros);
        values.put("over500Micros", over500Micros);
        values.put("over1Millis", over1Millis);
        values.put("over2Millis", over2Millis);
        values.put("over5Millis", over5Millis);
        values.put("returnSites", returnSites);
        values.put("glBeginSites", glBeginSites);
        values.put("glEndSites", glEndSites);
        values.put("vertexSites", vertexSites);
        values.put("texCoordSites", texCoordSites);
        values.put("colorSites", colorSites);
        values.put("bindTextureSites", bindTextureSites);
        values.put("blendFuncSites", blendFuncSites);
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
        if (initialized) return;
        synchronized (DynamicParticleGroupRenderProbeRuntime.class) {
            if (initialized) return;
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
                    DynamicParticleGroupRenderProbeRuntime::writeReport,
                    "Preflight-DynamicParticleGroup-Probe-Report"));
        }
    }

    private static void clearCounters() {
        returnSites = 0;
        glBeginSites = 0;
        glEndSites = 0;
        vertexSites = 0;
        texCoordSites = 0;
        colorSites = 0;
        bindTextureSites = 0;
        blendFuncSites = 0;
        calls = 0L;
        totalNanos = 0L;
        maximumNanos = 0L;
        over250Micros = 0L;
        over500Micros = 0L;
        over1Millis = 0L;
        over2Millis = 0L;
        over5Millis = 0L;
    }

    private static Path readPath(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Path.of(raw).toAbsolutePath().normalize();
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    private static void writeReport() {
        Path destination = reportPath;
        if (destination == null) return;
        try {
            Path parent = destination.getParent();
            if (parent != null) Files.createDirectories(parent);
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
