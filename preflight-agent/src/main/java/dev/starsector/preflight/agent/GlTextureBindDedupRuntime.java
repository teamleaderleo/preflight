package dev.starsector.preflight.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Opt-in, fail-open suppression of repeated GL_TEXTURE_2D binds within one display frame. */
public final class GlTextureBindDedupRuntime {
    static final String PLAN_ID = "lwjgl-texture-bind-dedup-v1";
    static final String ENABLE_PROPERTY = "preflight.framePacing.textureBindDedup";
    static final String ENABLE_ENVIRONMENT = "PREFLIGHT_GL_TEXTURE_BIND_DEDUP";

    private static final int GL_TEXTURE_2D = 3553;
    private static final Map<String, Integer> installedTargets = new TreeMap<>();

    private static volatile boolean requested;
    private static volatile boolean enabled;
    private static volatile boolean active;
    private static volatile long ownerThreadId;
    private static volatile boolean runtimeDisabled;
    private static String problem;
    private static String runtimeDisableReason;
    private static boolean known;
    private static int boundTexture;
    private static int listCompilationDepth;
    private static boolean pendingOriginalTrack;
    private static long frames;
    private static long bindCalls;
    private static long originalCalls;
    private static long suppressedCalls;
    private static long unsupportedCalls;
    private static long invalidations;
    private static long displayListCompilations;
    private static long unexpectedThreadCalls;

    private GlTextureBindDedupRuntime() {
    }

    static synchronized void beginSession(boolean frameTelemetryRequested) {
        requested = frameTelemetryRequested && explicitlyRequested();
        enabled = requested
                && AdapterPlanControl.allows(PLAN_ID)
                && !GpuFrameTimeRuntime.explicitlyRequested()
                && !GlStateReissueRuntime.explicitlyRequested()
                && !GlCommandCountRuntime.explicitlyRequested();
        problem = requested && !enabled
                ? !AdapterPlanControl.allows(PLAN_ID)
                        ? "plan-disabled-or-out-of-scope"
                        : "conflicting-opengl-diagnostic-requested"
                : null;
        active = false;
        ownerThreadId = -1L;
        runtimeDisabled = false;
        runtimeDisableReason = null;
        known = false;
        boundTexture = 0;
        listCompilationDepth = 0;
        pendingOriginalTrack = false;
        frames = 0L;
        bindCalls = 0L;
        originalCalls = 0L;
        suppressedCalls = 0L;
        unsupportedCalls = 0L;
        invalidations = 0L;
        displayListCompilations = 0L;
        unexpectedThreadCalls = 0L;
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

    /** Called after the preceding frame has been measured; state never crosses this boundary. */
    static void beginFrame() {
        if (!enabled || runtimeDisabled) return;
        long thread = Thread.currentThread().getId();
        if (ownerThreadId != -1L && ownerThreadId != thread) {
            unexpectedThreadCalls++;
            disable("unexpected-opengl-thread");
            return;
        }
        ownerThreadId = thread;
        active = true;
        known = false;
        pendingOriginalTrack = false;
        frames++;
    }

    /** Returns true only when the original public LWJGL wrapper may return immediately. */
    public static boolean shouldSkip(int target, int texture) {
        pendingOriginalTrack = false;
        if (!eligibleThread()) return false;
        bindCalls++;
        if (listCompilationDepth != 0 || target != GL_TEXTURE_2D || texture < 0) {
            unsupportedCalls++;
            known = false;
            originalCalls++;
            return false;
        }
        if (known && boundTexture == texture) {
            suppressedCalls++;
            return true;
        }
        originalCalls++;
        pendingOriginalTrack = true;
        return false;
    }

    /** Records only a wrapper that returned normally after making its original native call. */
    public static void originalBindCompleted(int target, int texture) {
        if (!pendingOriginalTrack) return;
        pendingOriginalTrack = false;
        if (enabled && active && !runtimeDisabled && listCompilationDepth == 0
                && target == GL_TEXTURE_2D && texture >= 0) {
            boundTexture = texture;
            known = true;
        } else {
            known = false;
        }
    }

    public static void invalidate() {
        if (!eligibleThread()) return;
        known = false;
        invalidations++;
    }

    public static void beginDisplayList() {
        if (!eligibleLifecycleThread()) return;
        known = false;
        invalidations++;
        displayListCompilations++;
        listCompilationDepth++;
        if (listCompilationDepth != 1) disable("nested-display-list-compilation");
    }

    public static void endDisplayList() {
        if (!eligibleLifecycleThread()) return;
        known = false;
        invalidations++;
        if (listCompilationDepth != 1) {
            disable("unbalanced-display-list-compilation");
            return;
        }
        listCompilationDepth = 0;
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
        result.put("enableProperty", ENABLE_PROPERTY);
        result.put("enableEnvironment", ENABLE_ENVIRONMENT);
        result.put("installedTargets", Map.copyOf(installedTargets));
        result.put("installedTargetCount", installedTargets.size());
        result.put("installedMethodCount", installedTargets.values().stream()
                .mapToInt(Integer::intValue).sum());
        result.put("frames", frames);
        result.put("bindCalls", bindCalls);
        result.put("originalCalls", originalCalls);
        result.put("suppressedCalls", suppressedCalls);
        result.put("suppressedPercent", percentage(suppressedCalls, bindCalls));
        result.put("unsupportedCalls", unsupportedCalls);
        result.put("invalidations", invalidations);
        result.put("displayListCompilations", displayListCompilations);
        result.put("unexpectedThreadCalls", unexpectedThreadCalls);
        result.put("scope", "GL_TEXTURE_2D binds within one exact Display.update frame");
        result.put("fallback", "unknown state, unsupported input, invalidation, wrong thread, or runtime fault calls original LWJGL wrapper");
        result.put("classification", "experimental optimization; FPS claim requires thin controlled cohorts");
        return Collections.unmodifiableMap(result);
    }

    static synchronized void reset() {
        beginSession(false);
    }

    private static boolean eligibleThread() {
        if (!enabled || !active || runtimeDisabled) return false;
        if (Thread.currentThread().getId() == ownerThreadId) return true;
        unexpectedThreadCalls++;
        disable("unexpected-opengl-thread");
        return false;
    }

    private static boolean eligibleLifecycleThread() {
        if (!enabled || runtimeDisabled) return false;
        long thread = Thread.currentThread().getId();
        if (ownerThreadId == -1L) {
            ownerThreadId = thread;
            return true;
        }
        if (thread == ownerThreadId) return true;
        unexpectedThreadCalls++;
        disable("unexpected-opengl-thread");
        return false;
    }

    private static void disable(String reason) {
        runtimeDisabled = true;
        runtimeDisableReason = reason;
        active = false;
        known = false;
        pendingOriginalTrack = false;
    }

    private static boolean explicitlyRequested() {
        try {
            if (Boolean.getBoolean(ENABLE_PROPERTY)) return true;
            String environment = System.getenv(ENABLE_ENVIRONMENT);
            return "1".equals(environment) || "true".equalsIgnoreCase(environment);
        } catch (RuntimeException problem) {
            return false;
        }
    }

    private static Double percentage(long numerator, long denominator) {
        return denominator == 0L ? null : numerator * 100.0 / denominator;
    }
}
