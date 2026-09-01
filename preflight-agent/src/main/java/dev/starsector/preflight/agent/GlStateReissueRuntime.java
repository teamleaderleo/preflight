package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Explicit, discovery-only census of selected OpenGL state calls and proven same-state reissues. */
public final class GlStateReissueRuntime {
    static final String PLAN_ID = "lwjgl-opengl-state-reissue-v1";
    static final String ENABLE_PROPERTY = "preflight.framePacing.glStateReissues";
    static final String ENABLE_ENVIRONMENT = "PREFLIGHT_FRAME_GL_STATE_REISSUES";

    static final int BIND_TEXTURE = 0;
    static final int CAPABILITY = 1;
    static final int BLEND_FUNC = 2;
    static final int ALPHA_FUNC = 3;
    static final int DEPTH_FUNC = 4;
    static final int DEPTH_MASK = 5;
    static final int CULL_FACE = 6;
    static final int SCISSOR = 7;
    static final int VIEWPORT = 8;
    static final int MATRIX_MODE = 9;
    static final int ACTIVE_TEXTURE = 10;
    static final int CLIENT_ACTIVE_TEXTURE = 11;

    static final int INVALIDATE_CALL_LIST = 0;
    static final int INVALIDATE_SERVER_ATTRIB_POP = 1;
    static final int INVALIDATE_CLIENT_ATTRIB_POP = 2;

    private static final String[] METHOD_NAMES = {
        "glBindTexture",
        "glEnableOrDisable",
        "glBlendFunc",
        "glAlphaFunc",
        "glDepthFunc",
        "glDepthMask",
        "glCullFace",
        "glScissor",
        "glViewport",
        "glMatrixMode",
        "glActiveTexture",
        "glClientActiveTexture"
    };
    private static final String[] INVALIDATION_NAMES = {
        "displayListCall", "serverAttribPop", "clientAttribPop"
    };
    private static final int TABLE_SIZE = 256;
    private static final int TABLE_MASK = TABLE_SIZE - 1;
    private static final int UNKNOWN_TEXTURE_UNIT = Integer.MIN_VALUE;
    private static final long SLOW_FRAME_NANOS = 33_333_333L;

    private static final long[] frameCalls = new long[METHOD_NAMES.length];
    private static final long[] frameKnown = new long[METHOD_NAMES.length];
    private static final long[] frameRedundant = new long[METHOD_NAMES.length];
    private static final long[] calls = new long[METHOD_NAMES.length];
    private static final long[] known = new long[METHOD_NAMES.length];
    private static final long[] redundant = new long[METHOD_NAMES.length];
    private static final long[] slowCalls = new long[METHOD_NAMES.length];
    private static final long[] slowKnown = new long[METHOD_NAMES.length];
    private static final long[] slowRedundant = new long[METHOD_NAMES.length];
    private static final boolean[] scalarKnown = new boolean[METHOD_NAMES.length];
    private static final int[] scalarA = new int[METHOD_NAMES.length];
    private static final int[] scalarB = new int[METHOD_NAMES.length];
    private static final int[] scalarC = new int[METHOD_NAMES.length];
    private static final int[] scalarD = new int[METHOD_NAMES.length];
    private static final boolean[] capabilityOccupied = new boolean[TABLE_SIZE];
    private static final int[] capabilityKeys = new int[TABLE_SIZE];
    private static final boolean[] capabilityValues = new boolean[TABLE_SIZE];
    private static final boolean[] textureOccupied = new boolean[TABLE_SIZE];
    private static final long[] textureKeys = new long[TABLE_SIZE];
    private static final int[] textureValues = new int[TABLE_SIZE];
    private static final long[] invalidations = new long[INVALIDATION_NAMES.length];
    private static final Map<String, Integer> installedTargets = new TreeMap<>();

    private static volatile boolean requested;
    private static volatile boolean enabled;
    private static String problem;
    private static volatile boolean windowActive;
    private static volatile long ownerThreadId;
    private static boolean initialBoundaryPending;
    private static long frames;
    private static long slowFrames;
    private static long discardedFrames;
    private static long initialPartialFramesDropped;
    private static long rawCalls;
    private static long unexpectedThreadCalls;
    private static long unknownMethodCalls;
    private static long frameInvalidations;
    private static long retainedInvalidations;
    private static long slowInvalidations;
    private static long capabilityTableOverflows;
    private static long textureTableOverflows;
    private static int activeTextureUnit = UNKNOWN_TEXTURE_UNIT;

    private GlStateReissueRuntime() {
    }

    static boolean explicitlyRequested() {
        try {
            if (Boolean.getBoolean(ENABLE_PROPERTY)) return true;
            String environment = System.getenv(ENABLE_ENVIRONMENT);
            return "1".equals(environment) || "true".equalsIgnoreCase(environment);
        } catch (RuntimeException problem) {
            return false;
        }
    }

    static synchronized void beginSession(boolean frameTelemetryRequested) {
        requested = frameTelemetryRequested && explicitlyRequested();
        enabled = requested
                && !GpuFrameTimeRuntime.requested()
                && AdapterPlanControl.allows(PLAN_ID);
        problem = requested && !enabled
                ? GpuFrameTimeRuntime.requested()
                        ? "gpu-frame-timer-also-requested" : "plan-disabled-or-out-of-scope"
                : null;
        windowActive = false;
        ownerThreadId = -1L;
        initialBoundaryPending = false;
        frames = 0L;
        slowFrames = 0L;
        discardedFrames = 0L;
        initialPartialFramesDropped = 0L;
        rawCalls = 0L;
        unexpectedThreadCalls = 0L;
        unknownMethodCalls = 0L;
        frameInvalidations = 0L;
        retainedInvalidations = 0L;
        slowInvalidations = 0L;
        capabilityTableOverflows = 0L;
        textureTableOverflows = 0L;
        Arrays.fill(frameCalls, 0L);
        Arrays.fill(frameKnown, 0L);
        Arrays.fill(frameRedundant, 0L);
        Arrays.fill(calls, 0L);
        Arrays.fill(known, 0L);
        Arrays.fill(redundant, 0L);
        Arrays.fill(slowCalls, 0L);
        Arrays.fill(slowKnown, 0L);
        Arrays.fill(slowRedundant, 0L);
        Arrays.fill(invalidations, 0L);
        installedTargets.clear();
        clearModel();
    }

    static boolean planEnabled() {
        return enabled;
    }

    static synchronized void installed(String internalName, int methods) {
        if (enabled && internalName != null && methods > 0) {
            installedTargets.put(internalName, methods);
        }
    }

    static synchronized void beginMeasurementWindow() {
        if (!enabled) return;
        windowActive = false;
        ownerThreadId = Thread.currentThread().getId();
        initialBoundaryPending = true;
        frames = 0L;
        slowFrames = 0L;
        discardedFrames = 0L;
        initialPartialFramesDropped = 0L;
        rawCalls = 0L;
        unexpectedThreadCalls = 0L;
        unknownMethodCalls = 0L;
        frameInvalidations = 0L;
        retainedInvalidations = 0L;
        slowInvalidations = 0L;
        capabilityTableOverflows = 0L;
        textureTableOverflows = 0L;
        Arrays.fill(frameCalls, 0L);
        Arrays.fill(frameKnown, 0L);
        Arrays.fill(frameRedundant, 0L);
        Arrays.fill(calls, 0L);
        Arrays.fill(known, 0L);
        Arrays.fill(redundant, 0L);
        Arrays.fill(slowCalls, 0L);
        Arrays.fill(slowKnown, 0L);
        Arrays.fill(slowRedundant, 0L);
        Arrays.fill(invalidations, 0L);
        clearModel();
        windowActive = true;
    }

    public static void recordBindTexture(int target, int texture) {
        if (!acceptCall(BIND_TEXTURE)) return;
        long key = ((long) activeTextureUnit << 32) ^ (target & 0xffffffffL);
        int slot = tableSlot(textureOccupied, textureKeys, key);
        if (slot < 0) {
            textureTableOverflows++;
            observe(BIND_TEXTURE, false, false);
            return;
        }
        boolean wasKnown = textureOccupied[slot];
        boolean wasRedundant = wasKnown && textureValues[slot] == texture;
        textureOccupied[slot] = true;
        textureKeys[slot] = key;
        textureValues[slot] = texture;
        observe(BIND_TEXTURE, wasKnown, wasRedundant);
    }

    public static void recordCapability(int capability, boolean value) {
        if (!acceptCall(CAPABILITY)) return;
        int slot = tableSlot(capabilityOccupied, capabilityKeys, capability);
        if (slot < 0) {
            capabilityTableOverflows++;
            observe(CAPABILITY, false, false);
            return;
        }
        boolean wasKnown = capabilityOccupied[slot];
        boolean wasRedundant = wasKnown && capabilityValues[slot] == value;
        capabilityOccupied[slot] = true;
        capabilityKeys[slot] = capability;
        capabilityValues[slot] = value;
        observe(CAPABILITY, wasKnown, wasRedundant);
    }

    public static void recordPair(int method, int first, int second) {
        if (!acceptCall(method)) return;
        boolean wasKnown = scalarKnown[method];
        boolean wasRedundant = wasKnown && scalarA[method] == first && scalarB[method] == second;
        scalarKnown[method] = true;
        scalarA[method] = first;
        scalarB[method] = second;
        observe(method, wasKnown, wasRedundant);
    }

    public static void recordAlpha(int function, float reference) {
        recordPair(ALPHA_FUNC, function, Float.floatToRawIntBits(reference));
    }

    public static void recordSingle(int method, int value) {
        if (!acceptCall(method)) return;
        boolean wasKnown = scalarKnown[method];
        boolean wasRedundant = wasKnown && scalarA[method] == value;
        scalarKnown[method] = true;
        scalarA[method] = value;
        observe(method, wasKnown, wasRedundant);
    }

    public static void recordBoolean(int method, boolean value) {
        recordSingle(method, value ? 1 : 0);
    }

    public static void recordQuad(int method, int a, int b, int c, int d) {
        if (!acceptCall(method)) return;
        boolean wasKnown = scalarKnown[method];
        boolean wasRedundant = wasKnown
                && scalarA[method] == a && scalarB[method] == b
                && scalarC[method] == c && scalarD[method] == d;
        scalarKnown[method] = true;
        scalarA[method] = a;
        scalarB[method] = b;
        scalarC[method] = c;
        scalarD[method] = d;
        observe(method, wasKnown, wasRedundant);
    }

    public static void recordActiveTexture(int textureUnit) {
        if (!acceptCall(ACTIVE_TEXTURE)) return;
        boolean wasKnown = scalarKnown[ACTIVE_TEXTURE];
        boolean wasRedundant = wasKnown && scalarA[ACTIVE_TEXTURE] == textureUnit;
        scalarKnown[ACTIVE_TEXTURE] = true;
        scalarA[ACTIVE_TEXTURE] = textureUnit;
        activeTextureUnit = textureUnit;
        observe(ACTIVE_TEXTURE, wasKnown, wasRedundant);
    }

    public static void recordInvalidation(int reason) {
        if (!windowActive) return;
        if (Thread.currentThread().getId() != ownerThreadId) {
            unexpectedThreadCalls++;
            return;
        }
        if (reason < 0 || reason >= invalidations.length) {
            unknownMethodCalls++;
            clearModel();
            return;
        }
        rawCalls++;
        frameInvalidations++;
        invalidations[reason]++;
        clearModel();
    }

    static synchronized void observeFrame(long durationNanos, boolean comparable) {
        if (!windowActive) return;
        if (initialBoundaryPending) {
            initialBoundaryPending = false;
            initialPartialFramesDropped++;
            discardedFrames++;
            clearFrame();
            return;
        }
        if (Thread.currentThread().getId() != ownerThreadId || !comparable || durationNanos <= 0L) {
            discardedFrames++;
            clearFrame();
            return;
        }
        boolean slow = durationNanos > SLOW_FRAME_NANOS;
        for (int method = 0; method < METHOD_NAMES.length; method++) {
            calls[method] += frameCalls[method];
            known[method] += frameKnown[method];
            redundant[method] += frameRedundant[method];
            if (slow) {
                slowCalls[method] += frameCalls[method];
                slowKnown[method] += frameKnown[method];
                slowRedundant[method] += frameRedundant[method];
            }
        }
        retainedInvalidations += frameInvalidations;
        if (slow) slowInvalidations += frameInvalidations;
        frames++;
        if (slow) slowFrames++;
        clearFrame();
    }

    static synchronized Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("requested", requested);
        result.put("enabled", enabled);
        result.put("problem", problem);
        result.put("active", windowActive);
        result.put("enableProperty", ENABLE_PROPERTY);
        result.put("enableEnvironment", ENABLE_ENVIRONMENT);
        result.put("installedTargets", Map.copyOf(installedTargets));
        result.put("installedTargetCount", installedTargets.size());
        result.put("installedMethodCount", installedTargets.values().stream()
                .mapToInt(Integer::intValue).sum());
        result.put("frames", frames);
        result.put("slowFrames", slowFrames);
        result.put("discardedFrames", discardedFrames);
        result.put("initialPartialFramesDropped", initialPartialFramesDropped);
        result.put("rawCallsObserved", rawCalls);
        result.put("unexpectedThreadCalls", unexpectedThreadCalls);
        result.put("unknownMethodCalls", unknownMethodCalls);
        result.put("capabilityTableOverflows", capabilityTableOverflows);
        result.put("textureTableOverflows", textureTableOverflows);
        List<Map<String, Object>> methods = new ArrayList<>();
        long allCalls = 0L;
        long allKnown = 0L;
        long allRedundant = 0L;
        for (int method = 0; method < METHOD_NAMES.length; method++) {
            allCalls += calls[method];
            allKnown += known[method];
            allRedundant += redundant[method];
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", method);
            row.put("name", METHOD_NAMES[method]);
            row.put("calls", calls[method]);
            row.put("knownComparisons", known[method]);
            row.put("sameStateReissues", redundant[method]);
            row.put("sameStatePercentOfKnown", percentage(redundant[method], known[method]));
            row.put("sameStatePercentOfCalls", percentage(redundant[method], calls[method]));
            row.put("slowFrameCalls", slowCalls[method]);
            row.put("slowKnownComparisons", slowKnown[method]);
            row.put("slowSameStateReissues", slowRedundant[method]);
            methods.add(row);
        }
        result.put("calls", allCalls);
        result.put("knownComparisons", allKnown);
        result.put("sameStateReissues", allRedundant);
        result.put("sameStatePercentOfKnown", percentage(allRedundant, allKnown));
        result.put("sameStatePercentOfCalls", percentage(allRedundant, allCalls));
        result.put("methods", List.copyOf(methods));
        Map<String, Object> invalidation = new LinkedHashMap<>();
        invalidation.put("retainedCalls", retainedInvalidations);
        invalidation.put("slowFrameCalls", slowInvalidations);
        for (int reason = 0; reason < invalidations.length; reason++) {
            invalidation.put(INVALIDATION_NAMES[reason], invalidations[reason]);
        }
        result.put("modelInvalidations", Map.copyOf(invalidation));
        result.put("tableSlotsPerStateFamily", TABLE_SIZE);
        result.put("classification", "intrusive discovery instrumentation; never an FPS claim");
        result.put("meaning", "same-state reissue is an observed redundancy lead, not suppression proof");
        result.put("uncertainty", "display lists and attribute pops invalidate tracked state; unobserved extension state changes remain outside coverage");
        return result;
    }

    static synchronized void reset() {
        beginSession(false);
    }

    private static boolean acceptCall(int method) {
        if (!windowActive) return false;
        if (Thread.currentThread().getId() != ownerThreadId) {
            unexpectedThreadCalls++;
            return false;
        }
        if (method < 0 || method >= METHOD_NAMES.length) {
            unknownMethodCalls++;
            return false;
        }
        rawCalls++;
        return true;
    }

    private static void observe(int method, boolean wasKnown, boolean wasRedundant) {
        frameCalls[method]++;
        if (wasKnown) frameKnown[method]++;
        if (wasRedundant) frameRedundant[method]++;
    }

    private static int tableSlot(boolean[] occupied, int[] keys, int key) {
        int start = mix(key) & TABLE_MASK;
        for (int step = 0; step < TABLE_SIZE; step++) {
            int slot = (start + step) & TABLE_MASK;
            if (!occupied[slot] || keys[slot] == key) return slot;
        }
        return -1;
    }

    private static int tableSlot(boolean[] occupied, long[] keys, long key) {
        int start = mix((int) (key ^ (key >>> 32))) & TABLE_MASK;
        for (int step = 0; step < TABLE_SIZE; step++) {
            int slot = (start + step) & TABLE_MASK;
            if (!occupied[slot] || keys[slot] == key) return slot;
        }
        return -1;
    }

    private static int mix(int value) {
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        value *= 0x846ca68b;
        return value ^ (value >>> 16);
    }

    private static Double percentage(long numerator, long denominator) {
        return denominator == 0L ? null : numerator * 100.0 / denominator;
    }

    private static void clearFrame() {
        Arrays.fill(frameCalls, 0L);
        Arrays.fill(frameKnown, 0L);
        Arrays.fill(frameRedundant, 0L);
        frameInvalidations = 0L;
    }

    private static void clearModel() {
        Arrays.fill(scalarKnown, false);
        Arrays.fill(capabilityOccupied, false);
        Arrays.fill(textureOccupied, false);
        activeTextureUnit = UNKNOWN_TEXTURE_UNIT;
    }
}
