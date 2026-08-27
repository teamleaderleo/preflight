package dev.starsector.preflight.agent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Opt-in, bounded, nonblocking EXT timer-query discovery instrumentation. */
final class GpuFrameTimeRuntime {
    static final String ENABLE_PROPERTY = "preflight.framePacing.gpuTimer";
    static final String ENABLE_ENVIRONMENT = "PREFLIGHT_FRAME_GPU_TIMER";

    private static final int GL_CURRENT_QUERY = 34917;
    private static final int GL_QUERY_RESULT = 34918;
    private static final int GL_QUERY_RESULT_AVAILABLE = 34919;
    private static final int GL_TIME_ELAPSED_EXT = 35007;
    private static final int RING_SIZE = 16;
    private static final int POLLS_PER_FRAME = 2;
    private static final int FREE = 0;
    private static final int ACTIVE = 1;
    private static final int PENDING = 2;
    private static final int PAUSE_PAUSED = 1;
    private static final int PAUSE_UNPAUSED = 2;

    private static final int[] queryIds = new int[RING_SIZE];
    private static final byte[] states = new byte[RING_SIZE];
    private static final long[] sequences = new long[RING_SIZE];
    private static final boolean[] resultReady = new boolean[RING_SIZE];
    private static final long[] resultNanos = new long[RING_SIZE];
    private static final boolean[] metadataReady = new boolean[RING_SIZE];
    private static final boolean[] comparable = new boolean[RING_SIZE];
    private static final boolean[] campaignAfterWarmup = new boolean[RING_SIZE];
    private static final int[] pauseStates = new int[RING_SIZE];
    private static final boolean[] combat = new boolean[RING_SIZE];
    private static final long[] totalNanos = new long[RING_SIZE];
    private static final long[] swapOffCpuNanos = new long[RING_SIZE];
    private static final int[] swapIntervals = new int[RING_SIZE];

    private static boolean requested;
    private static boolean attempted;
    private static boolean initialized;
    private static boolean disabled;
    private static String problem;
    private static long initializationNanos;
    private static int activeSlot = -1;
    private static int allocationCursor;
    private static int pollCursor;
    private static MethodHandle generateQuery;
    private static MethodHandle deleteQuery;
    private static MethodHandle beginQuery;
    private static MethodHandle endQuery;
    private static MethodHandle currentQuery;
    private static MethodHandle resultAvailable;
    private static MethodHandle unsignedResult;
    private static long queriesGenerated;
    private static long queriesBegun;
    private static long queriesEnded;
    private static long beginOwnershipChecks;
    private static boolean beginOwnershipVerified;
    private static long availabilityPolls;
    private static long unavailablePolls;
    private static long resultsRead;
    private static long framesMatched;
    private static long resultsDiscarded;
    private static long skippedNoFreeSlot;
    private static long skippedExistingOwner;
    private static long containedFailures;
    private static long cleanupEnds;
    private static long queriesDeleted;
    private static boolean released;
    private static long hookSamples;
    private static long hookNanos;
    private static long hookMaximumNanos;
    private static final PairedStats allComparable = new PairedStats();
    private static final PairedStats campaignSettled = new PairedStats();
    private static final PairedStats campaignPausedSettled = new PairedStats();
    private static final PairedStats campaignUnpausedSettled = new PairedStats();
    private static final PairedStats combatComparable = new PairedStats();

    private GpuFrameTimeRuntime() {
    }

    static boolean requested() {
        return requested;
    }

    static synchronized void beginSession(boolean telemetryRequested) {
        requested = telemetryRequested && explicitlyRequested();
        attempted = false;
        initialized = false;
        disabled = false;
        problem = null;
        initializationNanos = 0L;
        activeSlot = -1;
        allocationCursor = 0;
        pollCursor = 0;
        generateQuery = null;
        deleteQuery = null;
        beginQuery = null;
        endQuery = null;
        currentQuery = null;
        resultAvailable = null;
        unsignedResult = null;
        queriesGenerated = 0L;
        queriesBegun = 0L;
        queriesEnded = 0L;
        beginOwnershipChecks = 0L;
        beginOwnershipVerified = false;
        availabilityPolls = 0L;
        unavailablePolls = 0L;
        resultsRead = 0L;
        framesMatched = 0L;
        resultsDiscarded = 0L;
        skippedNoFreeSlot = 0L;
        skippedExistingOwner = 0L;
        containedFailures = 0L;
        cleanupEnds = 0L;
        queriesDeleted = 0L;
        released = false;
        hookSamples = 0L;
        hookNanos = 0L;
        hookMaximumNanos = 0L;
        Arrays.fill(queryIds, 0);
        Arrays.fill(states, (byte) FREE);
        Arrays.fill(sequences, 0L);
        Arrays.fill(resultReady, false);
        Arrays.fill(resultNanos, 0L);
        Arrays.fill(metadataReady, false);
        Arrays.fill(comparable, false);
        Arrays.fill(campaignAfterWarmup, false);
        Arrays.fill(pauseStates, 0);
        Arrays.fill(combat, false);
        Arrays.fill(totalNanos, 0L);
        Arrays.fill(swapOffCpuNanos, -1L);
        Arrays.fill(swapIntervals, Integer.MIN_VALUE);
        allComparable.reset();
        campaignSettled.reset();
        campaignPausedSettled.reset();
        campaignUnpausedSettled.reset();
        combatComparable.reset();
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

    static synchronized void beforeSwap(long nextSequence, boolean extTimerCapable) {
        if (!requested || disabled) return;
        long started = System.nanoTime();
        try {
            if (!attempted) initialize(extTimerCapable);
            if (!initialized) return;
            if (activeSlot >= 0) {
                int slot = activeSlot;
                endQuery.invokeExact(GL_TIME_ELAPSED_EXT);
                activeSlot = -1;
                states[slot] = PENDING;
                sequences[slot] = nextSequence;
                queriesEnded++;
            }
            pollPending();
        } catch (Throwable failure) {
            contain(failure);
        } finally {
            recordHook(System.nanoTime() - started);
        }
    }

    static synchronized void afterSwap() {
        if (!initialized || disabled || activeSlot >= 0) return;
        long started = System.nanoTime();
        try {
            int owner = (int) currentQuery.invokeExact(GL_TIME_ELAPSED_EXT, GL_CURRENT_QUERY);
            if (owner != 0) {
                skippedExistingOwner++;
                return;
            }
            int slot = nextFreeSlot();
            if (slot < 0) {
                skippedNoFreeSlot++;
                return;
            }
            beginQuery.invokeExact(GL_TIME_ELAPSED_EXT, queryIds[slot]);
            states[slot] = ACTIVE;
            activeSlot = slot;
            queriesBegun++;
            if (!beginOwnershipVerified) {
                beginOwnershipChecks++;
                int installedOwner = (int) currentQuery.invokeExact(
                        GL_TIME_ELAPSED_EXT, GL_CURRENT_QUERY);
                if (installedOwner != queryIds[slot]) {
                    throw new IllegalStateException(
                            "OpenGL did not install the owned elapsed-time query");
                }
                beginOwnershipVerified = true;
            }
        } catch (Throwable failure) {
            contain(failure);
        } finally {
            recordHook(System.nanoTime() - started);
        }
    }

    static synchronized void observeFrame(
            long sequence,
            boolean eligible,
            boolean settledCampaign,
            int campaignPause,
            boolean eligibleCombat,
            long total,
            long swapOffCpu,
            int swapInterval) {
        if (!initialized && !disabled) return;
        for (int slot = 0; slot < RING_SIZE; slot++) {
            if (states[slot] != PENDING || sequences[slot] != sequence) continue;
            metadataReady[slot] = true;
            comparable[slot] = eligible;
            campaignAfterWarmup[slot] = settledCampaign;
            pauseStates[slot] = campaignPause;
            combat[slot] = eligibleCombat;
            totalNanos[slot] = total;
            swapOffCpuNanos[slot] = swapOffCpu;
            swapIntervals[slot] = swapInterval;
            framesMatched++;
            finishIfReady(slot);
            return;
        }
    }

    /** Releases only this experiment's fixed query ring while the Display context is still current. */
    static synchronized void release() {
        if (!attempted || released) return;
        released = true;
        if (!initialized) return;
        if (activeSlot >= 0) {
            try {
                int owner = (int) currentQuery.invokeExact(GL_TIME_ELAPSED_EXT, GL_CURRENT_QUERY);
                if (owner == queryIds[activeSlot]) {
                    endQuery.invokeExact(GL_TIME_ELAPSED_EXT);
                    cleanupEnds++;
                }
            } catch (Throwable failure) {
                containedFailures++;
                problem = boundedProblem(failure);
            }
            activeSlot = -1;
        }
        for (int index = 0; index < RING_SIZE; index++) {
            if (queryIds[index] <= 0) continue;
            try {
                deleteQuery.invokeExact(queryIds[index]);
                queriesDeleted++;
            } catch (Throwable failure) {
                containedFailures++;
                problem = boundedProblem(failure);
            }
            queryIds[index] = 0;
            clearSlot(index);
        }
        initialized = false;
    }

    private static void initialize(boolean extTimerCapable) throws Throwable {
        attempted = true;
        long started = System.nanoTime();
        try {
            if (!extTimerCapable) {
                problem = "live context lacks OpenGL 1.5 query objects or GL_EXT_timer_query";
                return;
            }
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Class<?> gl15 = Class.forName("org.lwjgl.opengl.GL15");
            Class<?> ext = Class.forName("org.lwjgl.opengl.EXTTimerQuery");
            generateQuery = lookup.findStatic(gl15, "glGenQueries", MethodType.methodType(int.class));
            deleteQuery = lookup.findStatic(
                    gl15, "glDeleteQueries", MethodType.methodType(void.class, int.class));
            beginQuery = lookup.findStatic(gl15, "glBeginQuery",
                    MethodType.methodType(void.class, int.class, int.class));
            endQuery = lookup.findStatic(
                    gl15, "glEndQuery", MethodType.methodType(void.class, int.class));
            currentQuery = lookup.findStatic(gl15, "glGetQueryi",
                    MethodType.methodType(int.class, int.class, int.class));
            resultAvailable = lookup.findStatic(gl15, "glGetQueryObjecti",
                    MethodType.methodType(int.class, int.class, int.class));
            unsignedResult = lookup.findStatic(ext, "glGetQueryObjectuEXT",
                    MethodType.methodType(long.class, int.class, int.class));
            for (int index = 0; index < RING_SIZE; index++) {
                queryIds[index] = (int) generateQuery.invokeExact();
                if (queryIds[index] <= 0) {
                    throw new IllegalStateException("OpenGL returned an invalid timer query id");
                }
                queriesGenerated++;
            }
            initialized = true;
        } finally {
            initializationNanos = System.nanoTime() - started;
            if (!initialized && queriesGenerated > 0L) deleteGeneratedQueries();
        }
    }

    private static void pollPending() throws Throwable {
        int polls = 0;
        int inspected = 0;
        while (polls < POLLS_PER_FRAME && inspected < RING_SIZE) {
            int slot = pollCursor;
            pollCursor = (pollCursor + 1) % RING_SIZE;
            inspected++;
            if (states[slot] != PENDING || resultReady[slot]) continue;
            availabilityPolls++;
            polls++;
            int available = (int) resultAvailable.invokeExact(
                    queryIds[slot], GL_QUERY_RESULT_AVAILABLE);
            if (available == 0) {
                unavailablePolls++;
                continue;
            }
            long value = (long) unsignedResult.invokeExact(queryIds[slot], GL_QUERY_RESULT);
            if (value < 0L) throw new IllegalStateException("GPU timer result exceeded signed range");
            resultNanos[slot] = value;
            resultReady[slot] = true;
            resultsRead++;
            finishIfReady(slot);
        }
    }

    private static void finishIfReady(int slot) {
        if (!metadataReady[slot] || !resultReady[slot]) return;
        if (comparable[slot] && totalNanos[slot] > 0L) {
            allComparable.record(resultNanos[slot], totalNanos[slot],
                    swapOffCpuNanos[slot], swapIntervals[slot], sequences[slot]);
            if (campaignAfterWarmup[slot]) {
                campaignSettled.record(resultNanos[slot], totalNanos[slot],
                        swapOffCpuNanos[slot], swapIntervals[slot], sequences[slot]);
                if (pauseStates[slot] == PAUSE_PAUSED) {
                    campaignPausedSettled.record(resultNanos[slot], totalNanos[slot],
                            swapOffCpuNanos[slot], swapIntervals[slot], sequences[slot]);
                } else if (pauseStates[slot] == PAUSE_UNPAUSED) {
                    campaignUnpausedSettled.record(resultNanos[slot], totalNanos[slot],
                            swapOffCpuNanos[slot], swapIntervals[slot], sequences[slot]);
                }
            }
            if (combat[slot]) {
                combatComparable.record(resultNanos[slot], totalNanos[slot],
                        swapOffCpuNanos[slot], swapIntervals[slot], sequences[slot]);
            }
        } else {
            resultsDiscarded++;
        }
        clearSlot(slot);
    }

    private static int nextFreeSlot() {
        for (int offset = 0; offset < RING_SIZE; offset++) {
            int slot = (allocationCursor + offset) % RING_SIZE;
            if (states[slot] == FREE) {
                allocationCursor = (slot + 1) % RING_SIZE;
                return slot;
            }
        }
        return -1;
    }

    private static void contain(Throwable failure) {
        containedFailures++;
        problem = boundedProblem(failure);
        disabled = true;
        if (activeSlot >= 0) {
            try {
                int owner = (int) currentQuery.invokeExact(GL_TIME_ELAPSED_EXT, GL_CURRENT_QUERY);
                if (owner == queryIds[activeSlot]) {
                    endQuery.invokeExact(GL_TIME_ELAPSED_EXT);
                    cleanupEnds++;
                }
            } catch (Throwable ignored) {
                // The experiment is already disabled. Context teardown owns final cleanup.
            }
            activeSlot = -1;
        }
    }

    private static void deleteGeneratedQueries() {
        if (deleteQuery == null) return;
        for (int index = 0; index < RING_SIZE; index++) {
            if (queryIds[index] <= 0) continue;
            try {
                deleteQuery.invokeExact(queryIds[index]);
            } catch (Throwable ignored) {
                // Initialization already failed; leave final cleanup to context teardown.
            }
            queryIds[index] = 0;
        }
    }

    private static void clearSlot(int slot) {
        states[slot] = FREE;
        sequences[slot] = 0L;
        resultReady[slot] = false;
        resultNanos[slot] = 0L;
        metadataReady[slot] = false;
        comparable[slot] = false;
        campaignAfterWarmup[slot] = false;
        pauseStates[slot] = 0;
        combat[slot] = false;
        totalNanos[slot] = 0L;
        swapOffCpuNanos[slot] = -1L;
        swapIntervals[slot] = Integer.MIN_VALUE;
    }

    private static void recordHook(long elapsed) {
        hookSamples++;
        hookNanos += Math.max(0L, elapsed);
        hookMaximumNanos = Math.max(hookMaximumNanos, elapsed);
    }

    static synchronized Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requested", requested);
        result.put("enableProperty", ENABLE_PROPERTY);
        result.put("enableEnvironment", ENABLE_ENVIRONMENT);
        result.put("attempted", attempted);
        result.put("initialized", initialized);
        result.put("active", initialized && !disabled);
        result.put("disabledAfterFailure", disabled);
        result.put("problem", problem);
        result.put("path", "GL_EXT_timer_query time-elapsed query");
        result.put("ringSize", RING_SIZE);
        result.put("maximumAvailabilityPollsPerFrame", POLLS_PER_FRAME);
        result.put("initializationMicros", attempted ? initializationNanos / 1_000.0 : null);
        result.put("queriesGenerated", queriesGenerated);
        result.put("queriesBegun", queriesBegun);
        result.put("queriesEnded", queriesEnded);
        result.put("beginOwnershipChecks", beginOwnershipChecks);
        result.put("beginOwnershipVerified", beginOwnershipVerified);
        result.put("availabilityPolls", availabilityPolls);
        result.put("unavailablePolls", unavailablePolls);
        result.put("resultsRead", resultsRead);
        result.put("framesMatched", framesMatched);
        result.put("resultsDiscarded", resultsDiscarded);
        result.put("skippedNoFreeSlot", skippedNoFreeSlot);
        result.put("skippedExistingQueryOwner", skippedExistingOwner);
        result.put("containedFailures", containedFailures);
        result.put("cleanupEnds", cleanupEnds);
        result.put("releasedBeforeContextDestroy", released);
        result.put("queriesDeleted", queriesDeleted);
        result.put("pendingSlots", pendingSlots());
        result.put("activeSlot", activeSlot >= 0);
        result.put("hookSamples", hookSamples);
        result.put("hookAverageMicros", hookSamples == 0L ? null
                : hookNanos / 1_000.0 / hookSamples);
        result.put("hookMaximumMicros", hookSamples == 0L ? null
                : hookMaximumNanos / 1_000.0);
        result.put("classification", "intrusive discovery instrumentation; never an FPS claim");
        result.put("semanticEffect",
                "fixed process-lifetime query objects; no waits; original rendering and swap unchanged");
        result.put("allComparable", allComparable.toMap());
        result.put("campaignAfter30Seconds", campaignSettled.toMap());
        result.put("campaignPausedAfter30Seconds", campaignPausedSettled.toMap());
        result.put("campaignUnpausedAfter30Seconds", campaignUnpausedSettled.toMap());
        result.put("combatComparable", combatComparable.toMap());
        return result;
    }

    private static int pendingSlots() {
        int count = 0;
        for (byte state : states) if (state == PENDING) count++;
        return count;
    }

    private static String boundedProblem(Throwable failure) {
        String message = failure.getClass().getName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
        return message.length() <= 512 ? message : message.substring(0, 512);
    }

    private static final class PairedStats {
        private static final int WORST_LIMIT = 64;
        private final Distribution gpu = new Distribution();
        private final Distribution frame = new Distribution();
        private final Distribution swapOffCpu = new Distribution();
        private final long[] worstSequences = new long[WORST_LIMIT];
        private final long[] worstFrames = new long[WORST_LIMIT];
        private final long[] worstGpu = new long[WORST_LIMIT];
        private final long[] worstSwapOffCpu = new long[WORST_LIMIT];
        private final int[] worstIntervals = new int[WORST_LIMIT];
        private int worstCount;
        private int shortestWorst;
        private long swapOffCpuComplete;
        private long intervalZero;
        private long intervalOne;
        private long intervalOther;

        void reset() {
            gpu.reset();
            frame.reset();
            swapOffCpu.reset();
            Arrays.fill(worstSequences, 0L);
            Arrays.fill(worstFrames, 0L);
            Arrays.fill(worstGpu, 0L);
            Arrays.fill(worstSwapOffCpu, -1L);
            Arrays.fill(worstIntervals, Integer.MIN_VALUE);
            worstCount = 0;
            shortestWorst = 0;
            swapOffCpuComplete = 0L;
            intervalZero = 0L;
            intervalOne = 0L;
            intervalOther = 0L;
        }

        void record(long gpuNanos, long frameNanos, long offCpuNanos, int interval, long sequence) {
            gpu.record(gpuNanos);
            frame.record(frameNanos);
            if (offCpuNanos >= 0L) {
                swapOffCpu.record(offCpuNanos);
                swapOffCpuComplete++;
            }
            if (interval == 0) intervalZero++;
            else if (interval == 1) intervalOne++;
            else intervalOther++;
            retainWorst(sequence, frameNanos, gpuNanos, offCpuNanos, interval);
        }

        private void retainWorst(
                long sequence, long frameNanos, long gpuNanos, long offCpuNanos, int interval) {
            int slot;
            if (worstCount < WORST_LIMIT) slot = worstCount++;
            else if (frameNanos <= worstFrames[shortestWorst]) return;
            else slot = shortestWorst;
            worstSequences[slot] = sequence;
            worstFrames[slot] = frameNanos;
            worstGpu[slot] = gpuNanos;
            worstSwapOffCpu[slot] = offCpuNanos;
            worstIntervals[slot] = interval;
            shortestWorst = 0;
            for (int index = 1; index < worstCount; index++) {
                if (worstFrames[index] < worstFrames[shortestWorst]) shortestWorst = index;
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("pairedFrames", gpu.samples);
            values.put("gpuTime", gpu.toMap());
            values.put("frameTime", frame.toMap());
            values.put("swapOffCpuCompleteFrames", swapOffCpuComplete);
            values.put("swapInferredOffCpu", swapOffCpu.toMap());
            Map<String, Object> intervals = new LinkedHashMap<>();
            intervals.put("zero", intervalZero);
            intervals.put("one", intervalOne);
            intervals.put("otherOrUnavailable", intervalOther);
            values.put("swapIntervals", intervals);
            List<Map<String, Object>> worst = new ArrayList<>();
            Integer[] order = new Integer[worstCount];
            for (int index = 0; index < worstCount; index++) order[index] = index;
            Arrays.sort(order, Comparator.comparingLong((Integer index) -> worstFrames[index])
                    .reversed());
            for (int index : order) {
                Map<String, Object> pair = new LinkedHashMap<>();
                pair.put("sequence", worstSequences[index]);
                pair.put("frameMicros", worstFrames[index] / 1_000.0);
                pair.put("gpuMicros", worstGpu[index] / 1_000.0);
                pair.put("swapOffCpuMicros", worstSwapOffCpu[index] < 0L ? null
                        : worstSwapOffCpu[index] / 1_000.0);
                pair.put("swapInterval", worstIntervals[index] == Integer.MIN_VALUE ? null
                        : worstIntervals[index]);
                worst.add(pair);
            }
            values.put("worstFramePairs", worst);
            return values;
        }
    }

    private static final class Distribution {
        private static final long BIN_NANOS = 100_000L;
        private static final int BINS = 2_001;
        private final long[] histogram = new long[BINS];
        private long samples;
        private long total;
        private long maximum;

        void reset() {
            Arrays.fill(histogram, 0L);
            samples = 0L;
            total = 0L;
            maximum = 0L;
        }

        void record(long nanos) {
            if (nanos < 0L) return;
            samples++;
            total += nanos;
            maximum = Math.max(maximum, nanos);
            int bin = (int) Math.min(BINS - 1L, nanos / BIN_NANOS);
            histogram[bin]++;
        }

        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("samples", samples);
            values.put("averageMicros", samples == 0L ? null : total / 1_000.0 / samples);
            values.put("p50Micros", percentileMicros(0.50));
            values.put("p95Micros", percentileMicros(0.95));
            values.put("p99Micros", percentileMicros(0.99));
            values.put("maximumMicros", samples == 0L ? null : maximum / 1_000.0);
            values.put("histogramBinMicros", BIN_NANOS / 1_000L);
            values.put("histogramOverflowMicros", (BINS - 1L) * BIN_NANOS / 1_000L);
            return values;
        }

        private Long percentileMicros(double percentile) {
            if (samples == 0L) return null;
            long target = (long) Math.ceil(samples * percentile);
            long cumulative = 0L;
            for (int index = 0; index < BINS; index++) {
                cumulative += histogram[index];
                if (cumulative >= target) return index * BIN_NANOS / 1_000L;
            }
            return (BINS - 1L) * BIN_NANOS / 1_000L;
        }
    }
}
