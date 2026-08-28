package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client-side cache for five unit-independent GL11 enable bits Fast Rendering tracks without a
 * getter stall. GL_TEXTURE_2D remains native because its enable state is per active texture unit.
 * Unknown state always falls through to LWJGL's original glIsEnabled implementation.
 */
public final class GlIsEnabledStateCacheRuntime {
    static final String ENABLED_PROPERTY = "preflight.glIsEnabledCache";
    static final String REPORT_PROPERTY = "preflight.glIsEnabledCache.report";

    private static final int GL_LIGHTING = 0x0B50;
    private static final int GL_ALPHA_TEST = 0x0BC0;
    private static final int GL_BLEND = 0x0BE2;
    private static final int GL_STENCIL_TEST = 0x0B90;
    private static final int GL_SCISSOR_TEST = 0x0C11;

    private static final int GL_LIGHTING_BIT = 0x00000040;
    private static final int GL_STENCIL_BUFFER_BIT = 0x00000400;
    private static final int GL_ENABLE_BIT = 0x00002000;
    private static final int GL_COLOR_BUFFER_BIT = 0x00004000;
    private static final int GL_SCISSOR_BIT = 0x00080000;

    private static final byte UNKNOWN = -1;
    private static final String[] CAP_NAMES = {
        "stencilTest", "alphaTest", "blend", "lighting", "scissorTest"
    };

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private static volatile boolean initialized;
    private static volatile boolean enabled;
    private static volatile Path reportPath;
    private static boolean shutdownHookInstalled;
    private static boolean installed;

    private static long queries;
    private static long hits;
    private static long misses;
    private static long nativeSeeds;
    private static long unsupportedQueries;
    private static long enableUpdates;
    private static long disableUpdates;
    private static long contextChanges;
    private static long pushAttribs;
    private static long popAttribs;
    private static long attribUnderflows;
    private static long listCompiles;
    private static long listCalls;
    private static long invalidations;
    private static final long[] capQueries = new long[CAP_NAMES.length];
    private static final long[] capHits = new long[CAP_NAMES.length];
    private static final long[] capNativeSeeds = new long[CAP_NAMES.length];

    private GlIsEnabledStateCacheRuntime() {
    }

    static boolean enabled() {
        initializeFromProperties();
        return enabled;
    }

    static void installed() {
        installed = true;
    }

    /** Returns -1 when the original LWJGL getter must run, otherwise 0/1. */
    public static int cached(int cap, Object contextToken) {
        if (!enabled()) return -1;
        State state = state(contextToken);
        queries++;
        int index = capIndex(cap);
        if (index < 0) {
            unsupportedQueries++;
            misses++;
            return -1;
        }
        capQueries[index]++;
        if (state.recordingDisplayList) {
            misses++;
            return -1;
        }
        byte value = state.values[index];
        if (value == UNKNOWN) {
            misses++;
            return -1;
        }
        hits++;
        capHits[index]++;
        return value;
    }

    /** Seeds an unknown/suspect value after the original glIsEnabled getter completes. */
    public static void observedQuery(int cap, boolean value, Object contextToken) {
        if (!enabled()) return;
        State state = state(contextToken);
        int index = capIndex(cap);
        if (index < 0 || state.recordingDisplayList) return;
        state.values[index] = (byte) (value ? 1 : 0);
        nativeSeeds++;
        capNativeSeeds[index]++;
    }

    public static void enable(int cap, Object contextToken) {
        updateEnable(cap, true, contextToken);
    }

    public static void disable(int cap, Object contextToken) {
        updateEnable(cap, false, contextToken);
    }

    private static void updateEnable(int cap, boolean value, Object contextToken) {
        if (!enabled()) return;
        State state = state(contextToken);
        int index = capIndex(cap);
        if (index < 0) return;
        if (value) enableUpdates++;
        else disableUpdates++;
        if (state.recordingDisplayList) {
            state.values[index] = UNKNOWN;
            invalidations++;
            return;
        }
        state.values[index] = (byte) (value ? 1 : 0);
    }

    public static void pushAttrib(int mask, Object contextToken) {
        if (!enabled()) return;
        State state = state(contextToken);
        pushAttribs++;
        if (state.recordingDisplayList) {
            invalidate(state);
            return;
        }
        state.attribStack.push(new Snapshot(mask, state.values.clone()));
    }

    public static void popAttrib(Object contextToken) {
        if (!enabled()) return;
        State state = state(contextToken);
        popAttribs++;
        if (state.recordingDisplayList) {
            invalidate(state);
            return;
        }
        Snapshot snapshot = state.attribStack.pollFirst();
        if (snapshot == null) {
            attribUnderflows++;
            invalidate(state);
            return;
        }
        restore(state.values, snapshot.values, snapshot.mask);
    }

    public static void beginList(Object contextToken) {
        if (!enabled()) return;
        State state = state(contextToken);
        listCompiles++;
        state.recordingDisplayList = true;
        state.attribStack.clear();
        invalidate(state);
    }

    public static void endList(Object contextToken) {
        if (!enabled()) return;
        State state = state(contextToken);
        state.recordingDisplayList = false;
        state.attribStack.clear();
        invalidate(state);
    }

    public static void callList(Object contextToken) {
        if (!enabled()) return;
        State state = state(contextToken);
        listCalls++;
        state.attribStack.clear();
        invalidate(state);
    }

    static synchronized Map<String, Object> telemetry() {
        initializeFromProperties();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", enabled);
        values.put("installed", installed);
        values.put("queries", queries);
        values.put("hits", hits);
        values.put("misses", misses);
        values.put("hitPercent", queries == 0L ? null : Math.round(10_000.0 * hits / queries) / 100.0);
        values.put("nativeSeeds", nativeSeeds);
        values.put("unsupportedQueries", unsupportedQueries);
        values.put("enableUpdates", enableUpdates);
        values.put("disableUpdates", disableUpdates);
        values.put("contextChanges", contextChanges);
        values.put("pushAttribs", pushAttribs);
        values.put("popAttribs", popAttribs);
        values.put("attribUnderflows", attribUnderflows);
        values.put("listCompiles", listCompiles);
        values.put("listCalls", listCalls);
        values.put("invalidations", invalidations);
        Map<String, Object> caps = new LinkedHashMap<>();
        for (int i = 0; i < CAP_NAMES.length; i++) {
            Map<String, Object> cap = new LinkedHashMap<>();
            cap.put("queries", capQueries[i]);
            cap.put("hits", capHits[i]);
            cap.put("nativeSeeds", capNativeSeeds[i]);
            cap.put("hitPercent", capQueries[i] == 0L
                    ? null
                    : Math.round(10_000.0 * capHits[i] / capQueries[i]) / 100.0);
            caps.put(CAP_NAMES[i], cap);
        }
        values.put("caps", caps);
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
        STATE.remove();
    }

    private static State state(Object contextToken) {
        State state = STATE.get();
        if (state.contextToken != contextToken) {
            if (state.contextToken != null) contextChanges++;
            state.contextToken = contextToken;
            state.recordingDisplayList = false;
            state.attribStack.clear();
            Arrays.fill(state.values, UNKNOWN);
        }
        return state;
    }

    private static void invalidate(State state) {
        Arrays.fill(state.values, UNKNOWN);
        invalidations++;
    }

    private static void restore(byte[] target, byte[] source, int mask) {
        if ((mask & GL_ENABLE_BIT) != 0) {
            System.arraycopy(source, 0, target, 0, target.length);
            return;
        }
        if ((mask & GL_STENCIL_BUFFER_BIT) != 0) target[0] = source[0];
        if ((mask & GL_COLOR_BUFFER_BIT) != 0) {
            target[1] = source[1];
            target[2] = source[2];
        }
        if ((mask & GL_LIGHTING_BIT) != 0) target[3] = source[3];
        if ((mask & GL_SCISSOR_BIT) != 0) target[4] = source[4];
    }

    private static int capIndex(int cap) {
        return switch (cap) {
            case GL_STENCIL_TEST -> 0;
            case GL_ALPHA_TEST -> 1;
            case GL_BLEND -> 2;
            case GL_LIGHTING -> 3;
            case GL_SCISSOR_TEST -> 4;
            default -> -1;
        };
    }

    private static void initializeFromProperties() {
        if (initialized) return;
        synchronized (GlIsEnabledStateCacheRuntime.class) {
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
        STATE.remove();
        initialized = true;
        if (hook && report != null && !shutdownHookInstalled) {
            shutdownHookInstalled = true;
            Runtime.getRuntime().addShutdownHook(new Thread(
                    GlIsEnabledStateCacheRuntime::writeReport,
                    "Preflight-GL-IsEnabled-Cache-Report"));
        }
    }

    private static void clearCounters() {
        queries = 0L;
        hits = 0L;
        misses = 0L;
        nativeSeeds = 0L;
        unsupportedQueries = 0L;
        enableUpdates = 0L;
        disableUpdates = 0L;
        contextChanges = 0L;
        pushAttribs = 0L;
        popAttribs = 0L;
        attribUnderflows = 0L;
        listCompiles = 0L;
        listCalls = 0L;
        invalidations = 0L;
        Arrays.fill(capQueries, 0L);
        Arrays.fill(capHits, 0L);
        Arrays.fill(capNativeSeeds, 0L);
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

    private static final class State {
        private Object contextToken;
        private final byte[] values = new byte[CAP_NAMES.length];
        private final ArrayDeque<Snapshot> attribStack = new ArrayDeque<>();
        private boolean recordingDisplayList;

        private State() {
            Arrays.fill(values, UNKNOWN);
        }
    }

    private record Snapshot(int mask, byte[] values) {
    }
}
