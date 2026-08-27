package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Explicit, discovery-only census of legacy OpenGL matrix operations and exact identity calls. */
public final class GlMatrixOperationRuntime {
    static final String PLAN_ID = "lwjgl-opengl-matrix-operation-census-v1";
    static final String ENABLE_PROPERTY = "preflight.framePacing.glMatrixOperations";
    static final String ENABLE_ENVIRONMENT = "PREFLIGHT_FRAME_GL_MATRIX_OPERATIONS";

    static final int MATRIX_MODE = 0;
    static final int LOAD_IDENTITY = 1;
    static final int PUSH_MATRIX = 2;
    static final int POP_MATRIX = 3;
    static final int LOAD_MATRIX_FLOAT = 4;
    static final int LOAD_MATRIX_DOUBLE = 5;
    static final int MULT_MATRIX_FLOAT = 6;
    static final int MULT_MATRIX_DOUBLE = 7;
    static final int TRANSLATE_FLOAT = 8;
    static final int TRANSLATE_DOUBLE = 9;
    static final int ROTATE_FLOAT = 10;
    static final int ROTATE_DOUBLE = 11;
    static final int SCALE_FLOAT = 12;
    static final int SCALE_DOUBLE = 13;
    static final int ORTHO = 14;
    static final int FRUSTUM = 15;

    private static final String[] METHOD_NAMES = {
        "glMatrixMode(int)",
        "glLoadIdentity()",
        "glPushMatrix()",
        "glPopMatrix()",
        "glLoadMatrix(FloatBuffer)",
        "glLoadMatrix(DoubleBuffer)",
        "glMultMatrix(FloatBuffer)",
        "glMultMatrix(DoubleBuffer)",
        "glTranslatef(float,float,float)",
        "glTranslated(double,double,double)",
        "glRotatef(float,float,float,float)",
        "glRotated(double,double,double,double)",
        "glScalef(float,float,float)",
        "glScaled(double,double,double)",
        "glOrtho(double,double,double,double,double,double)",
        "glFrustum(double,double,double,double,double,double)"
    };
    private static final long SLOW_FRAME_NANOS = 33_333_333L;
    private static final long[] frameCalls = new long[METHOD_NAMES.length];
    private static final long[] frameIdentity = new long[METHOD_NAMES.length];
    private static final long[] calls = new long[METHOD_NAMES.length];
    private static final long[] identity = new long[METHOD_NAMES.length];
    private static final long[] slowCalls = new long[METHOD_NAMES.length];
    private static final long[] slowIdentity = new long[METHOD_NAMES.length];
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

    private GlMatrixOperationRuntime() {
    }

    static boolean explicitlyRequested() {
        try {
            if (Boolean.getBoolean(ENABLE_PROPERTY)) return true;
            String environment = System.getenv(ENABLE_ENVIRONMENT);
            return "1".equals(environment) || "true".equalsIgnoreCase(environment);
        } catch (RuntimeException ignored) {
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
        clearAll();
        installedTargets.clear();
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
        clearAll();
        windowActive = true;
    }

    public static void record(int method) {
        record(method, false);
    }

    public static void recordTranslateF(float x, float y, float z) {
        record(TRANSLATE_FLOAT, x == 0.0f && y == 0.0f && z == 0.0f);
    }

    public static void recordTranslateD(double x, double y, double z) {
        record(TRANSLATE_DOUBLE, x == 0.0d && y == 0.0d && z == 0.0d);
    }

    public static void recordRotateF(float angle) {
        record(ROTATE_FLOAT, angle == 0.0f);
    }

    public static void recordRotateD(double angle) {
        record(ROTATE_DOUBLE, angle == 0.0d);
    }

    public static void recordScaleF(float x, float y, float z) {
        record(SCALE_FLOAT, x == 1.0f && y == 1.0f && z == 1.0f);
    }

    public static void recordScaleD(double x, double y, double z) {
        record(SCALE_DOUBLE, x == 1.0d && y == 1.0d && z == 1.0d);
    }

    private static void record(int method, boolean identityOperation) {
        if (!windowActive) return;
        if (Thread.currentThread().getId() != ownerThreadId) {
            unexpectedThreadCalls++;
            return;
        }
        rawCalls++;
        if (method < 0 || method >= METHOD_NAMES.length) {
            unknownMethodCalls++;
            return;
        }
        frameCalls[method]++;
        if (identityOperation) frameIdentity[method]++;
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
            identity[method] += frameIdentity[method];
            if (slow) {
                slowCalls[method] += frameCalls[method];
                slowIdentity[method] += frameIdentity[method];
            }
        }
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
        long allCalls = 0L;
        long allIdentity = 0L;
        List<Map<String, Object>> methods = new ArrayList<>();
        for (int method = 0; method < METHOD_NAMES.length; method++) {
            allCalls += calls[method];
            allIdentity += identity[method];
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", method);
            row.put("name", METHOD_NAMES[method]);
            row.put("calls", calls[method]);
            row.put("meanPerFrame", frames == 0L ? null : calls[method] * 1.0 / frames);
            row.put("identityOrNoOpCalls", identity[method]);
            row.put("identityOrNoOpPercent", percentage(identity[method], calls[method]));
            row.put("slowFrameCalls", slowCalls[method]);
            row.put("slowFrameMean", slowFrames == 0L ? null
                    : slowCalls[method] * 1.0 / slowFrames);
            row.put("slowFrameIdentityOrNoOpCalls", slowIdentity[method]);
            methods.add(row);
        }
        result.put("calls", allCalls);
        result.put("meanCallsPerFrame", frames == 0L ? null : allCalls * 1.0 / frames);
        result.put("identityOrNoOpCalls", allIdentity);
        result.put("identityOrNoOpPercent", percentage(allIdentity, allCalls));
        result.put("methods", List.copyOf(methods));
        result.put("classification", "intrusive discovery instrumentation; never an FPS claim");
        result.put("hotPath", "one thread-id read, bounds check, primitive counters, and exact primitive identity comparisons; no clock read or allocation");
        return result;
    }

    static synchronized void reset() {
        beginSession(false);
    }

    private static void clearAll() {
        Arrays.fill(frameCalls, 0L);
        Arrays.fill(frameIdentity, 0L);
        Arrays.fill(calls, 0L);
        Arrays.fill(identity, 0L);
        Arrays.fill(slowCalls, 0L);
        Arrays.fill(slowIdentity, 0L);
    }

    private static void clearFrame() {
        Arrays.fill(frameCalls, 0L);
        Arrays.fill(frameIdentity, 0L);
    }

    private static Double percentage(long numerator, long denominator) {
        return denominator == 0L ? null : numerator * 100.0 / denominator;
    }
}
