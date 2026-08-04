package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Telemetry for the exact OpenAL streaming-source error-order repair. */
public final class AudioStreamSourceErrorRuntime {
    static final String PLAN_ID = "audio-stream-source-error-order-v1";

    private static final ThreadLocal<Integer> priorError = new ThreadLocal<>();
    private static final AtomicLong attempts = new AtomicLong();
    private static final AtomicLong staleErrors = new AtomicLong();
    private static final AtomicLong generationErrors = new AtomicLong();
    private static final AtomicLong recoveredStaleErrors = new AtomicLong();
    private static volatile boolean installed;

    private AudioStreamSourceErrorRuntime() {
    }

    static void installed() {
        installed = true;
    }

    /** Called after clearing OpenAL's old error and before generating the source. */
    public static void beforeGeneration(int error) {
        attempts.incrementAndGet();
        priorError.set(error);
        if (error != 0) staleErrors.incrementAndGet();
    }

    /** Called with the error that actually belongs to {@code alGenSources}. */
    public static void afterGeneration(int error) {
        Integer stale = priorError.get();
        priorError.remove();
        if (error != 0) generationErrors.incrementAndGet();
        if (stale != null && stale != 0 && error == 0) recoveredStaleErrors.incrementAndGet();
    }

    static void beginSession() {
        installed = false;
        attempts.set(0L);
        staleErrors.set(0L);
        generationErrors.set(0L);
        recoveredStaleErrors.set(0L);
        priorError.remove();
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", PLAN_ID);
        values.put("installed", installed);
        values.put("attempts", attempts.get());
        values.put("staleErrors", staleErrors.get());
        values.put("generationErrors", generationErrors.get());
        values.put("recoveredStaleErrors", recoveredStaleErrors.get());
        return values;
    }
}
