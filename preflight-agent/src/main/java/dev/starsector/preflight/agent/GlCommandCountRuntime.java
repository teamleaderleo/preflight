package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Explicit, discovery-only whole-frame counts for selected LWJGL OpenGL command families. */
public final class GlCommandCountRuntime {
    static final String PLAN_ID = "lwjgl-opengl-command-count-v1";
    static final String ENABLE_PROPERTY = "preflight.framePacing.glCounts";
    static final String ENABLE_ENVIRONMENT = "PREFLIGHT_FRAME_GL_COUNTS";

    static final int IMMEDIATE_DRAW = 0;
    static final int ARRAY_DRAW = 1;
    static final int TEXTURE_BIND = 2;
    static final int TEXTURE_UPLOAD = 3;
    static final int FIXED_FUNCTION_STATE = 4;
    static final int MATRIX_STATE = 5;
    static final int SYNCHRONOUS_READBACK = 6;
    static final int TEXTURE_UNIT_STATE = 7;
    static final int BUFFER_BIND = 8;
    static final int BUFFER_UPLOAD = 9;
    static final int SHADER_PROGRAM_STATE = 10;
    static final int UNIFORM_UPDATE = 11;
    static final int FRAMEBUFFER_STATE = 12;

    private static final String[] CATEGORY_NAMES = {
        "immediateDraw",
        "arrayOrPixelDraw",
        "textureBind",
        "textureUpload",
        "fixedFunctionState",
        "matrixState",
        "readbackOrExplicitFlush",
        "textureUnitState",
        "bufferBind",
        "bufferUpload",
        "shaderProgramState",
        "uniformUpdate",
        "framebufferState"
    };
    private static final int WORST_FRAME_LIMIT = 64;
    private static final long SLOW_FRAME_NANOS = 33_333_333L;

    private static final long[] frameCounts = new long[CATEGORY_NAMES.length];
    private static final long[] totals = new long[CATEGORY_NAMES.length];
    private static final long[] slowFrameTotals = new long[CATEGORY_NAMES.length];
    private static final long[] maximumPerFrame = new long[CATEGORY_NAMES.length];
    private static final long[] worstSequences = new long[WORST_FRAME_LIMIT];
    private static final long[] worstDurations = new long[WORST_FRAME_LIMIT];
    private static final long[][] worstCounts =
            new long[WORST_FRAME_LIMIT][CATEGORY_NAMES.length];
    private static final Map<String, Integer> installedTargets = new TreeMap<>();

    private static volatile boolean requested;
    private static volatile boolean requestedByStateReissue;
    private static volatile boolean requestedByMatrixOperations;
    private static volatile boolean enabled;
    private static String problem;
    private static volatile boolean windowActive;
    private static volatile long ownerThreadId;
    private static String windowState;
    private static String windowCampaignPause;
    private static boolean initialBoundaryPending;
    private static long initialPartialFramesDropped;
    private static long frames;
    private static long discardedFrames;
    private static long slowFrames;
    private static long noCommandFrames;
    private static long commands;
    private static long maximumCommandsPerFrame;
    private static volatile long acceptedCalls;
    private static volatile long unexpectedThreadCalls;
    private static volatile long unknownCategoryCalls;
    private static long boundaryHookSamples;
    private static long boundaryHookNanos;
    private static long boundaryHookMaximumNanos;
    private static int worstCount;
    private static int shortestWorst;

    private GlCommandCountRuntime() {
    }

    static synchronized void beginSession(boolean frameTelemetryRequested) {
        requestedByStateReissue = frameTelemetryRequested && GlStateReissueRuntime.planEnabled();
        requestedByMatrixOperations = frameTelemetryRequested
                && GlMatrixOperationRuntime.planEnabled();
        requested = frameTelemetryRequested && (explicitlyRequested()
                || requestedByStateReissue || requestedByMatrixOperations);
        enabled = requested && !GpuFrameTimeRuntime.requested();
        problem = requested && !enabled ? "gpu-frame-timer-also-requested" : null;
        windowActive = false;
        ownerThreadId = -1L;
        windowState = null;
        windowCampaignPause = null;
        initialBoundaryPending = false;
        initialPartialFramesDropped = 0L;
        frames = 0L;
        discardedFrames = 0L;
        slowFrames = 0L;
        noCommandFrames = 0L;
        commands = 0L;
        maximumCommandsPerFrame = 0L;
        acceptedCalls = 0L;
        unexpectedThreadCalls = 0L;
        unknownCategoryCalls = 0L;
        boundaryHookSamples = 0L;
        boundaryHookNanos = 0L;
        boundaryHookMaximumNanos = 0L;
        worstCount = 0;
        shortestWorst = 0;
        Arrays.fill(frameCounts, 0L);
        Arrays.fill(totals, 0L);
        Arrays.fill(slowFrameTotals, 0L);
        Arrays.fill(maximumPerFrame, 0L);
        Arrays.fill(worstSequences, 0L);
        Arrays.fill(worstDurations, 0L);
        for (long[] counts : worstCounts) Arrays.fill(counts, 0L);
        installedTargets.clear();
    }

    static boolean planEnabled() {
        return enabled;
    }

    static synchronized void installed(String internalClassName, int methods) {
        if (!enabled || internalClassName == null || methods <= 0) return;
        installedTargets.put(internalClassName, methods);
    }

    static synchronized void beginMeasurementWindow(String state, String campaignPause) {
        if (!enabled) return;
        if (!("campaign".equals(state) || "combat".equals(state))) {
            throw new IllegalArgumentException("unknown-frame-window-state");
        }
        if (("campaign".equals(state)
                        && !("paused".equals(campaignPause) || "unpaused".equals(campaignPause)))
                || ("combat".equals(state) && campaignPause != null)) {
            throw new IllegalArgumentException("invalid-frame-window-pause-state");
        }
        windowActive = false;
        ownerThreadId = Thread.currentThread().getId();
        windowState = state;
        windowCampaignPause = campaignPause;
        initialBoundaryPending = true;
        initialPartialFramesDropped = 0L;
        frames = 0L;
        discardedFrames = 0L;
        slowFrames = 0L;
        noCommandFrames = 0L;
        commands = 0L;
        maximumCommandsPerFrame = 0L;
        acceptedCalls = 0L;
        unexpectedThreadCalls = 0L;
        unknownCategoryCalls = 0L;
        boundaryHookSamples = 0L;
        boundaryHookNanos = 0L;
        boundaryHookMaximumNanos = 0L;
        worstCount = 0;
        shortestWorst = 0;
        Arrays.fill(frameCounts, 0L);
        Arrays.fill(totals, 0L);
        Arrays.fill(slowFrameTotals, 0L);
        Arrays.fill(maximumPerFrame, 0L);
        Arrays.fill(worstSequences, 0L);
        Arrays.fill(worstDurations, 0L);
        for (long[] counts : worstCounts) Arrays.fill(counts, 0L);
        windowActive = true;
    }

    /** Hot-path entry injected into exact LWJGL wrappers; no allocation or clock read occurs. */
    public static void record(int category) {
        if (!windowActive) return;
        if (Thread.currentThread().getId() != ownerThreadId) {
            unexpectedThreadCalls++;
            return;
        }
        if (category < 0 || category >= frameCounts.length) {
            unknownCategoryCalls++;
            return;
        }
        frameCounts[category]++;
        acceptedCalls++;
    }

    static synchronized void observeFrame(long sequence, long durationNanos, boolean comparable) {
        if (!windowActive) return;
        long started = System.nanoTime();
        try {
            if (initialBoundaryPending) {
                initialBoundaryPending = false;
                initialPartialFramesDropped++;
                discardedFrames++;
                Arrays.fill(frameCounts, 0L);
                return;
            }
            if (Thread.currentThread().getId() != ownerThreadId || !comparable
                    || durationNanos <= 0L) {
                discardedFrames++;
                Arrays.fill(frameCounts, 0L);
                return;
            }
            long frameCommands = 0L;
            boolean slow = durationNanos > SLOW_FRAME_NANOS;
            for (int category = 0; category < frameCounts.length; category++) {
                long count = frameCounts[category];
                frameCommands += count;
                totals[category] += count;
                maximumPerFrame[category] = Math.max(maximumPerFrame[category], count);
                if (slow) slowFrameTotals[category] += count;
            }
            frames++;
            commands += frameCommands;
            if (slow) slowFrames++;
            if (frameCommands == 0L) noCommandFrames++;
            maximumCommandsPerFrame = Math.max(maximumCommandsPerFrame, frameCommands);
            retainWorst(sequence, durationNanos, frameCounts);
            Arrays.fill(frameCounts, 0L);
        } finally {
            long elapsed = System.nanoTime() - started;
            boundaryHookSamples++;
            boundaryHookNanos += elapsed;
            boundaryHookMaximumNanos = Math.max(boundaryHookMaximumNanos, elapsed);
        }
    }

    static synchronized Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("requested", requested);
        result.put("requestedByStateReissue", requestedByStateReissue);
        result.put("requestedByMatrixOperations", requestedByMatrixOperations);
        result.put("enabled", enabled);
        result.put("problem", problem);
        result.put("active", windowActive);
        result.put("enableProperty", ENABLE_PROPERTY);
        result.put("enableEnvironment", ENABLE_ENVIRONMENT);
        result.put("state", windowState);
        result.put("campaignPause", windowCampaignPause);
        result.put("ownerThreadId", ownerThreadId < 0L ? null : ownerThreadId);
        result.put("installedTargets", Map.copyOf(installedTargets));
        result.put("installedTargetCount", installedTargets.size());
        result.put("installedMethodCount", installedTargets.values().stream()
                .mapToInt(Integer::intValue).sum());
        result.put("frames", frames);
        result.put("discardedFrames", discardedFrames);
        result.put("initialPartialFramesDropped", initialPartialFramesDropped);
        result.put("slowFrames", slowFrames);
        result.put("noCommandFrames", noCommandFrames);
        result.put("commands", commands);
        result.put("wrapperCallsObserved", acceptedCalls);
        result.put("unexpectedThreadCalls", unexpectedThreadCalls);
        result.put("unknownCategoryCalls", unknownCategoryCalls);
        result.put("meanCommandsPerFrame", frames == 0L ? null : commands * 1.0 / frames);
        result.put("maximumCommandsPerFrame", frames == 0L ? null : maximumCommandsPerFrame);
        List<Map<String, Object>> categories = new ArrayList<>();
        for (int category = 0; category < CATEGORY_NAMES.length; category++) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", category);
            value.put("name", CATEGORY_NAMES[category]);
            value.put("calls", totals[category]);
            value.put("meanCallsPerFrame", frames == 0L
                    ? null : totals[category] * 1.0 / frames);
            value.put("slowFrameCalls", slowFrameTotals[category]);
            value.put("meanCallsPerSlowFrame", slowFrames == 0L
                    ? null : slowFrameTotals[category] * 1.0 / slowFrames);
            value.put("maximumCallsPerFrame", maximumPerFrame[category]);
            categories.add(value);
        }
        result.put("categories", List.copyOf(categories));
        result.put("worstFrames", worstFrames());
        Map<String, Object> overhead = new LinkedHashMap<>();
        overhead.put("boundaryHookSamples", boundaryHookSamples);
        overhead.put("boundaryHookAverageMicros", boundaryHookSamples == 0L ? null
                : boundaryHookNanos / 1_000.0 / boundaryHookSamples);
        overhead.put("boundaryHookMaximumMicros", boundaryHookSamples == 0L ? null
                : boundaryHookMaximumNanos / 1_000.0);
        overhead.put("perCommandClockReads", 0);
        overhead.put("perCommandWork", "one thread-id read, bounds check, and primitive increment");
        overhead.put("unmeasuredCost", "injected Java call and per-command bookkeeping");
        result.put("measurementOverhead", overhead);
        result.put("retention", Map.of(
                "worstFrameLimit", WORST_FRAME_LIMIT,
                "categoryCount", CATEGORY_NAMES.length));
        result.put("coverage",
                "selected wrapper families only; immediate mode counts glBegin batches, not vertices");
        result.put("classification", "intrusive discovery instrumentation; never an FPS claim");
        result.put("semanticEffect", "counting only; original LWJGL command arguments and calls unchanged");
        return result;
    }

    static synchronized void reset() {
        beginSession(false);
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

    private static void retainWorst(long sequence, long durationNanos, long[] counts) {
        int target;
        if (worstCount < WORST_FRAME_LIMIT) {
            target = worstCount++;
        } else {
            if (durationNanos <= worstDurations[shortestWorst]) return;
            target = shortestWorst;
        }
        worstSequences[target] = sequence;
        worstDurations[target] = durationNanos;
        System.arraycopy(counts, 0, worstCounts[target], 0, CATEGORY_NAMES.length);
        recomputeShortestWorst();
    }

    private static void recomputeShortestWorst() {
        if (worstCount == 0) return;
        int shortest = 0;
        for (int index = 1; index < worstCount; index++) {
            if (worstDurations[index] < worstDurations[shortest]) shortest = index;
        }
        shortestWorst = shortest;
    }

    private static List<Map<String, Object>> worstFrames() {
        List<Integer> indexes = new ArrayList<>(worstCount);
        for (int index = 0; index < worstCount; index++) indexes.add(index);
        indexes.sort(Comparator.comparingLong((Integer index) -> worstDurations[index]).reversed());
        List<Map<String, Object>> values = new ArrayList<>(indexes.size());
        for (int index : indexes) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("sequence", worstSequences[index]);
            value.put("durationMicros", worstDurations[index] / 1_000L);
            long total = 0L;
            Map<String, Object> counts = new LinkedHashMap<>();
            for (int category = 0; category < CATEGORY_NAMES.length; category++) {
                long count = worstCounts[index][category];
                total += count;
                if (count > 0L) counts.put(CATEGORY_NAMES[category], count);
            }
            value.put("commands", total);
            value.put("categories", Map.copyOf(counts));
            values.add(Map.copyOf(value));
        }
        return List.copyOf(values);
    }
}
