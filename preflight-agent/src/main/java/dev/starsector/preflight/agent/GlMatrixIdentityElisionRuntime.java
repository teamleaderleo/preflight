package dev.starsector.preflight.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Opt-in, fail-open suppression of exact identity matrix transforms in LWJGL 2. */
public final class GlMatrixIdentityElisionRuntime {
    static final String PLAN_ID = "lwjgl-matrix-identity-elision-v1";
    static final String ENABLE_PROPERTY = "preflight.framePacing.matrixIdentityElision";
    static final String ENABLE_ENVIRONMENT = "PREFLIGHT_GL_MATRIX_IDENTITY_ELISION";

    private static final Map<String, Integer> installedTargets = new TreeMap<>();

    private static volatile boolean requested;
    private static volatile boolean enabled;
    private static volatile boolean active;
    private static volatile boolean windowActive;
    private static volatile boolean windowPending;
    private static volatile boolean runtimeDisabled;
    private static volatile long ownerThreadId;
    private static volatile boolean insidePrimitive;
    private static volatile int installedMethodCount;
    private static String problem;
    private static String runtimeDisableReason;
    private static long frames;
    private static long transformCalls;
    private static long originalCalls;
    private static long suppressedCalls;
    private static long translateCalls;
    private static long translateSuppressed;
    private static long rotateCalls;
    private static long rotateSuppressed;
    private static long scaleCalls;
    private static long scaleSuppressed;
    private static long primitiveDeclines;
    private static long unexpectedThreadCalls;
    private static long beginCalls;
    private static long endCalls;
    private static long frameBoundaryPrimitiveLeaks;

    private GlMatrixIdentityElisionRuntime() {
    }

    static synchronized void beginSession(boolean frameTelemetryRequested) {
        requested = frameTelemetryRequested && explicitlyRequested();
        enabled = requested
                && AdapterPlanControl.allows(PLAN_ID)
                && !GpuFrameTimeRuntime.explicitlyRequested()
                && !GlCommandCountRuntime.explicitlyRequested()
                && !GlStateReissueRuntime.explicitlyRequested()
                && !GlMatrixOperationRuntime.explicitlyRequested();
        problem = requested && !enabled
                ? !AdapterPlanControl.allows(PLAN_ID)
                        ? "plan-disabled-or-out-of-scope"
                        : "conflicting-opengl-diagnostic-requested"
                : null;
        active = false;
        windowActive = false;
        windowPending = false;
        runtimeDisabled = false;
        runtimeDisableReason = null;
        ownerThreadId = -1L;
        insidePrimitive = false;
        installedMethodCount = 0;
        resetWindowCounters();
        installedTargets.clear();
    }

    static boolean planEnabled() {
        return enabled;
    }

    static synchronized void installed(String internalName, int methods) {
        if (enabled && internalName != null && methods > 0) {
            installedTargets.put(internalName, methods);
            installedMethodCount = installedTargets.values().stream()
                    .mapToInt(Integer::intValue).sum();
        }
    }

    /** Called after the preceding frame has been measured. */
    static void beginFrame() {
        if (!enabled || runtimeDisabled
                || installedMethodCount != GlMatrixIdentityElisionPlan.EXPECTED_METHODS) return;
        long thread = Thread.currentThread().getId();
        if (active && ownerThreadId == thread && insidePrimitive) {
            frameBoundaryPrimitiveLeaks++;
            runtimeDisabled = true;
            runtimeDisableReason = "glBegin-scope-crossed-frame-boundary";
            active = false;
            windowActive = false;
            windowPending = false;
            return;
        }
        ownerThreadId = thread;
        active = true;
        if (windowPending) {
            windowPending = false;
            windowActive = true;
        } else if (windowActive) {
            frames++;
        }
    }

    static synchronized void beginMeasurementWindow() {
        if (!enabled) return;
        windowActive = false;
        windowPending = true;
        resetWindowCounters();
    }

    public static boolean shouldSkipTranslateF(float x, float y, float z) {
        return decide(0, x == 0.0f && y == 0.0f && z == 0.0f);
    }

    public static boolean shouldSkipTranslateD(double x, double y, double z) {
        return decide(0, x == 0.0d && y == 0.0d && z == 0.0d);
    }

    public static boolean shouldSkipRotateF(float angle, float x, float y, float z) {
        return decide(1, angle == 0.0f
                && Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z));
    }

    public static boolean shouldSkipRotateD(double angle, double x, double y, double z) {
        return decide(1, angle == 0.0d
                && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z));
    }

    public static boolean shouldSkipScaleF(float x, float y, float z) {
        return decide(2, x == 1.0f && y == 1.0f && z == 1.0f);
    }

    public static boolean shouldSkipScaleD(double x, double y, double z) {
        return decide(2, x == 1.0d && y == 1.0d && z == 1.0d);
    }

    public static void beginPrimitive() {
        if (!eligibleThread()) return;
        insidePrimitive = true;
        if (windowActive) beginCalls++;
    }

    public static void endPrimitive() {
        if (!eligibleThread()) return;
        insidePrimitive = false;
        if (windowActive) endCalls++;
    }

    static synchronized Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("requested", requested);
        result.put("enabled", enabled);
        result.put("problem", problem);
        result.put("active", active);
        result.put("runtimeDisabled", runtimeDisabled);
        result.put("runtimeDisableReason", runtimeDisableReason);
        result.put("measurementWindowActive", windowActive);
        result.put("measurementWindowPending", windowPending);
        result.put("enableProperty", ENABLE_PROPERTY);
        result.put("enableEnvironment", ENABLE_ENVIRONMENT);
        result.put("installedTargets", Map.copyOf(installedTargets));
        result.put("installedTargetCount", installedTargets.size());
        result.put("installedMethodCount", installedMethodCount);
        result.put("frames", frames);
        result.put("transformCalls", transformCalls);
        result.put("originalCalls", originalCalls);
        result.put("suppressedCalls", suppressedCalls);
        result.put("suppressedPercent", percentage(suppressedCalls, transformCalls));
        result.put("translateCalls", translateCalls);
        result.put("translateSuppressed", translateSuppressed);
        result.put("rotateCalls", rotateCalls);
        result.put("rotateSuppressed", rotateSuppressed);
        result.put("scaleCalls", scaleCalls);
        result.put("scaleSuppressed", scaleSuppressed);
        result.put("primitiveDeclines", primitiveDeclines);
        result.put("unexpectedThreadCalls", unexpectedThreadCalls);
        result.put("beginCalls", beginCalls);
        result.put("endCalls", endCalls);
        result.put("frameBoundaryPrimitiveLeaks", frameBoundaryPrimitiveLeaks);
        result.put("scope", "exact zero translations, finite-axis zero-angle rotations, and unit scales in reviewed LWJGL 2 GL11 wrappers");
        result.put("fallback", "non-identity input, glBegin/glEnd scope, wrong thread, disabled plan, or exact-target mismatch calls the original LWJGL wrapper");
        result.put("equivalence", "each suppressed call multiplies the current matrix by the identity matrix; transform order, stack depth, and matrix mode are unchanged");
        result.put("classification", "experimental optimization; FPS claim requires thin controlled cohorts");
        return Collections.unmodifiableMap(result);
    }

    static synchronized void reset() {
        beginSession(false);
    }

    private static boolean decide(int family, boolean identity) {
        if (!eligibleThread()) return false;
        boolean count = windowActive;
        if (count) {
            transformCalls++;
            if (family == 0) translateCalls++;
            else if (family == 1) rotateCalls++;
            else scaleCalls++;
        }
        if (!identity || insidePrimitive) {
            if (count) {
                originalCalls++;
                if (identity && insidePrimitive) primitiveDeclines++;
            }
            return false;
        }
        if (count) {
            suppressedCalls++;
            if (family == 0) translateSuppressed++;
            else if (family == 1) rotateSuppressed++;
            else scaleSuppressed++;
        }
        return true;
    }

    private static boolean eligibleThread() {
        if (!enabled || !active || runtimeDisabled) return false;
        if (Thread.currentThread().getId() == ownerThreadId) return true;
        if (windowActive) unexpectedThreadCalls++;
        return false;
    }

    private static void resetWindowCounters() {
        frames = 0L;
        transformCalls = 0L;
        originalCalls = 0L;
        suppressedCalls = 0L;
        translateCalls = 0L;
        translateSuppressed = 0L;
        rotateCalls = 0L;
        rotateSuppressed = 0L;
        scaleCalls = 0L;
        scaleSuppressed = 0L;
        primitiveDeclines = 0L;
        unexpectedThreadCalls = 0L;
        beginCalls = 0L;
        endCalls = 0L;
        frameBoundaryPrimitiveLeaks = 0L;
    }

    private static boolean explicitlyRequested() {
        try {
            if (Boolean.getBoolean(ENABLE_PROPERTY)) return true;
            String environment = System.getenv(ENABLE_ENVIRONMENT);
            return "1".equals(environment) || "true".equalsIgnoreCase(environment);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static Double percentage(long numerator, long denominator) {
        return denominator == 0L ? null : numerator * 100.0 / denominator;
    }
}
