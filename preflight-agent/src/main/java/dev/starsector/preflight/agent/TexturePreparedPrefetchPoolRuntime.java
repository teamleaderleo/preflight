package dev.starsector.preflight.agent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Runs the reviewed Windows prefetch queues with bounded, race-free parallel consumers. */
public final class TexturePreparedPrefetchPoolRuntime {
    private static final int MAX_WORKERS = 8;
    private static final int MAX_FAILURES = 8;
    private static final Object LOCK = new Object();

    private static final AtomicLong STARTS = new AtomicLong();
    private static final AtomicLong STOPS = new AtomicLong();
    private static final AtomicLong IMAGE_CLAIMS = new AtomicLong();
    private static final AtomicLong BYTE_CLAIMS = new AtomicLong();
    private static final AtomicLong IMAGE_COMPLETIONS = new AtomicLong();
    private static final AtomicLong BYTE_COMPLETIONS = new AtomicLong();
    private static final AtomicLong FAILURES = new AtomicLong();
    private static final AtomicInteger ACTIVE_WORKERS = new AtomicInteger();
    private static final AtomicInteger PEAK_WORKERS = new AtomicInteger();
    private static final List<String> FAILURE_SAMPLES = new ArrayList<>();

    private static volatile Session session;
    private static volatile int configuredWorkers;
    private static volatile String queueMode = "stock-order";

    private TexturePreparedPrefetchPoolRuntime() {
    }

    /** Starts consumers for the exact fields and decoders supplied by the transformed owner. */
    public static void start(
            Class<?> owner,
            List<String> imageQueue,
            Map<String, Object> imageResults,
            Object imageLoadingMarker,
            List<String> byteQueue,
            Map<String, Object> byteResults,
            Object byteLoadingMarker,
            String imageDecoderName,
            String byteDecoderName,
            int workers) {
        startSession(
                owner,
                imageQueue,
                imageResults,
                imageLoadingMarker,
                byteQueue,
                byteResults,
                byteLoadingMarker,
                imageDecoderName,
                byteDecoderName,
                workers,
                false);
    }

    /** Starts one exact decoder per independent stock queue so images do not wait behind bytes. */
    public static void startSplitQueues(
            Class<?> owner,
            List<String> imageQueue,
            Map<String, Object> imageResults,
            Object imageLoadingMarker,
            List<String> byteQueue,
            Map<String, Object> byteResults,
            Object byteLoadingMarker,
            String imageDecoderName,
            String byteDecoderName,
            int workers) {
        if (workers != 2) {
            throw new IllegalArgumentException("Split prepared prefetch requires exactly two workers");
        }
        startSession(
                owner,
                imageQueue,
                imageResults,
                imageLoadingMarker,
                byteQueue,
                byteResults,
                byteLoadingMarker,
                imageDecoderName,
                byteDecoderName,
                workers,
                true);
    }

    private static void startSession(
            Class<?> owner,
            List<String> imageQueue,
            Map<String, Object> imageResults,
            Object imageLoadingMarker,
            List<String> byteQueue,
            Map<String, Object> byteResults,
            Object byteLoadingMarker,
            String imageDecoderName,
            String byteDecoderName,
            int workers,
            boolean splitQueues) {
        if (workers < 2 || workers > MAX_WORKERS) {
            throw new IllegalArgumentException("Prepared prefetch workers must be between 2 and " + MAX_WORKERS);
        }
        Method imageDecoder = decoder(owner, imageDecoderName);
        Method byteDecoder = decoder(owner, byteDecoderName);
        Session replacement = new Session(
                imageQueue,
                imageResults,
                imageLoadingMarker,
                byteQueue,
                byteResults,
                byteLoadingMarker,
                imageDecoder,
                byteDecoder);
        synchronized (LOCK) {
            stopLocked();
            session = replacement;
            configuredWorkers = workers;
            queueMode = splitQueues ? "split-queues" : "stock-order";
            STARTS.incrementAndGet();
            if (splitQueues) {
                startWorker(replacement, () -> runQueueWorker(replacement, true), "Image");
                startWorker(replacement, () -> runQueueWorker(replacement, false), "Bytes");
            } else {
                for (int index = 0; index < workers; index++) {
                    startWorker(replacement, () -> runWorker(replacement), String.valueOf(index + 1));
                }
            }
        }
    }

    private static void startWorker(Session replacement, Runnable task, String suffix) {
        Thread thread = new Thread(task, "Preflight-Windows-Prefetch-" + suffix);
        replacement.threads.add(thread);
        thread.start();
    }

    /** Interrupts every worker started for the exact prefetch session. */
    public static void stop() {
        synchronized (LOCK) {
            stopLocked();
        }
    }

    static void beginSession() {
        stop();
        STARTS.set(0);
        STOPS.set(0);
        IMAGE_CLAIMS.set(0);
        BYTE_CLAIMS.set(0);
        IMAGE_COMPLETIONS.set(0);
        BYTE_COMPLETIONS.set(0);
        FAILURES.set(0);
        ACTIVE_WORKERS.set(0);
        PEAK_WORKERS.set(0);
        configuredWorkers = 0;
        queueMode = "stock-order";
        synchronized (FAILURE_SAMPLES) {
            FAILURE_SAMPLES.clear();
        }
    }

    static Map<String, Object> report() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("configuredWorkers", configuredWorkers);
        values.put("queueMode", queueMode);
        values.put("starts", STARTS.get());
        values.put("stops", STOPS.get());
        values.put("activeWorkers", ACTIVE_WORKERS.get());
        values.put("peakWorkers", PEAK_WORKERS.get());
        values.put("imageClaims", IMAGE_CLAIMS.get());
        values.put("byteClaims", BYTE_CLAIMS.get());
        values.put("imageCompletions", IMAGE_COMPLETIONS.get());
        values.put("byteCompletions", BYTE_COMPLETIONS.get());
        values.put("failures", FAILURES.get());
        synchronized (FAILURE_SAMPLES) {
            values.put("failureSamples", List.copyOf(FAILURE_SAMPLES));
        }
        return values;
    }

    private static Method decoder(Class<?> owner, String name) {
        try {
            Method method = owner.getDeclaredMethod(name, String.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Exact prefetch decoder is unavailable: " + name, error);
        }
    }

    private static void runWorker(Session current) {
        int active = ACTIVE_WORKERS.incrementAndGet();
        PEAK_WORKERS.accumulateAndGet(active, Math::max);
        try {
            while (!current.cancelled && !Thread.currentThread().isInterrupted()) {
                Claim raw = claim(current.byteQueue, current.byteResults, current.byteLoadingMarker);
                if (raw != null) {
                    BYTE_CLAIMS.incrementAndGet();
                    complete(current, raw, current.byteDecoder, current.byteResults, BYTE_COMPLETIONS);
                    continue;
                }
                Claim image = claim(current.imageQueue, current.imageResults, current.imageLoadingMarker);
                if (image != null) {
                    IMAGE_CLAIMS.incrementAndGet();
                    complete(current, image, current.imageDecoder, current.imageResults, IMAGE_COMPLETIONS);
                    continue;
                }
                return;
            }
        } finally {
            ACTIVE_WORKERS.decrementAndGet();
        }
    }

    private static void runQueueWorker(Session current, boolean images) {
        int active = ACTIVE_WORKERS.incrementAndGet();
        PEAK_WORKERS.accumulateAndGet(active, Math::max);
        try {
            while (!current.cancelled && !Thread.currentThread().isInterrupted()) {
                Claim claim = images
                        ? claim(current.imageQueue, current.imageResults, current.imageLoadingMarker)
                        : claim(current.byteQueue, current.byteResults, current.byteLoadingMarker);
                if (claim == null) {
                    return;
                }
                if (images) {
                    IMAGE_CLAIMS.incrementAndGet();
                    complete(current, claim, current.imageDecoder, current.imageResults, IMAGE_COMPLETIONS);
                } else {
                    BYTE_CLAIMS.incrementAndGet();
                    complete(current, claim, current.byteDecoder, current.byteResults, BYTE_COMPLETIONS);
                }
            }
        } finally {
            ACTIVE_WORKERS.decrementAndGet();
        }
    }

    private static Claim claim(List<String> queue, Map<String, Object> results, Object marker) {
        synchronized (queue) {
            if (queue.isEmpty()) {
                return null;
            }
            String path = queue.remove(0);
            results.put(path, marker);
            return new Claim(path);
        }
    }

    private static void complete(
            Session current,
            Claim claim,
            Method decoder,
            Map<String, Object> results,
            AtomicLong completions) {
        try {
            Object value = decoder.invoke(null, claim.path);
            if (value == null) {
                throw new IllegalStateException("decoder returned null");
            }
            results.put(claim.path, value);
            completions.incrementAndGet();
        } catch (Throwable error) {
            results.remove(claim.path);
            Throwable cause = error instanceof InvocationTargetException invocation && invocation.getCause() != null
                    ? invocation.getCause()
                    : error;
            if (cause instanceof InterruptedException || current.cancelled) {
                Thread.currentThread().interrupt();
                return;
            }
            FAILURES.incrementAndGet();
            synchronized (FAILURE_SAMPLES) {
                if (FAILURE_SAMPLES.size() < MAX_FAILURES) {
                    FAILURE_SAMPLES.add(cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage()));
                }
            }
        }
    }

    private static void stopLocked() {
        Session current = session;
        if (current == null) {
            return;
        }
        session = null;
        current.cancelled = true;
        for (Thread thread : current.threads) {
            thread.interrupt();
        }
        STOPS.incrementAndGet();
    }

    private record Claim(String path) {
    }

    private static final class Session {
        private final List<String> imageQueue;
        private final Map<String, Object> imageResults;
        private final Object imageLoadingMarker;
        private final List<String> byteQueue;
        private final Map<String, Object> byteResults;
        private final Object byteLoadingMarker;
        private final Method imageDecoder;
        private final Method byteDecoder;
        private final List<Thread> threads = new ArrayList<>();
        private volatile boolean cancelled;

        private Session(
                List<String> imageQueue,
                Map<String, Object> imageResults,
                Object imageLoadingMarker,
                List<String> byteQueue,
                Map<String, Object> byteResults,
                Object byteLoadingMarker,
                Method imageDecoder,
                Method byteDecoder) {
            this.imageQueue = imageQueue;
            this.imageResults = imageResults;
            this.imageLoadingMarker = imageLoadingMarker;
            this.byteQueue = byteQueue;
            this.byteResults = byteResults;
            this.byteLoadingMarker = byteLoadingMarker;
            this.imageDecoder = imageDecoder;
            this.byteDecoder = byteDecoder;
        }
    }
}
