package dev.starsector.preflight.agent;

import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded producer that stages learned prepared carriers before Starsector starts its own queue. */
public final class TexturePreparedStagingRuntime {
    static final String PLAN_ID = "texture-prepared-staging-v1";
    static final String ENABLED_PROPERTY = "preflight.texture.preparedStaging";
    static final long MAX_STAGED_BYTES = 64L * 1024 * 1024;

    private static final Object LOCK = new Object();
    private static final Map<String, Staged> STAGED = new LinkedHashMap<>();
    private static final Set<String> CONSUMED = new HashSet<>();
    private static final Set<String> IN_PROGRESS = new HashSet<>();
    private static final AtomicBoolean SHUTDOWN_HOOK_INSTALLED = new AtomicBoolean();

    private static Thread producer;
    private static boolean cancelled;
    private static long stagedBytes;
    private static long peakStagedBytes;
    private static long loadingBytes;
    private static long peakLoadingBytes;
    private static long starts;
    private static long stops;
    private static long attempts;
    private static long stagedEntries;
    private static long stagedHits;
    private static long ordinaryMisses;
    private static long duplicateDeclines;
    private static long lateDeclines;
    private static long oversizeDeclines;
    private static long failures;

    private TexturePreparedStagingRuntime() {
    }

    static void beginSession() {
        stop();
        synchronized (LOCK) {
            STAGED.clear();
            CONSUMED.clear();
            IN_PROGRESS.clear();
            producer = null;
            cancelled = false;
            stagedBytes = 0L;
            peakStagedBytes = 0L;
            loadingBytes = 0L;
            peakLoadingBytes = 0L;
            starts = 0L;
            stops = 0L;
            attempts = 0L;
            stagedEntries = 0L;
            stagedHits = 0L;
            ordinaryMisses = 0L;
            duplicateDeclines = 0L;
            lateDeclines = 0L;
            oversizeDeclines = 0L;
            failures = 0L;
        }
    }

    /** Starts one daemon producer at the exact reviewed pre-SpecStore boundary. */
    public static void start() {
        try {
            if (!Boolean.getBoolean(ENABLED_PROPERTY) || !TexturePreparedPixelRuntime.ready()) {
                return;
            }
            List<String> order = TextureAccessLearningRuntime.snapshot();
            if (order.isEmpty()) {
                return;
            }
            synchronized (LOCK) {
                if (producer != null || cancelled) {
                    return;
                }
                starts++;
                producer = new Thread(
                        () -> produce(order), "Preflight-Prepared-Staging");
                producer.setDaemon(true);
                producer.start();
            }
            ensureShutdownHook();
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            synchronized (LOCK) {
                failures++;
            }
        }
    }

    /** Stops the producer and releases every unconsumed carrier without touching game state. */
    public static void stop() {
        Thread current;
        synchronized (LOCK) {
            current = producer;
            if (current == null && STAGED.isEmpty() && IN_PROGRESS.isEmpty()) {
                return;
            }
            cancelled = true;
            stops++;
            STAGED.clear();
            IN_PROGRESS.clear();
            stagedBytes = 0L;
            loadingBytes = 0L;
            LOCK.notifyAll();
        }
        // Reads borrow the shared pack channel. Interrupting this producer inside FileChannel.read
        // would close that channel for every consumer. Cancellation wakes bounded-queue waits;
        // an active read finishes and its result is discarded cooperatively.
    }

    /** Returns a staged carrier when ready; every miss immediately retains the current path. */
    static BufferedImage take(String logicalPath) {
        if (!Boolean.getBoolean(ENABLED_PROPERTY)) {
            return null;
        }
        String key = TextureCompatibilityRuntime.preparedPrefetchKey(logicalPath);
        if (key == null) {
            return null;
        }
        synchronized (LOCK) {
            CONSUMED.add(key);
            Staged found = STAGED.remove(key);
            if (found == null) {
                ordinaryMisses++;
                LOCK.notifyAll();
                return null;
            }
            stagedBytes = Math.max(0L, stagedBytes - found.bytes());
            stagedHits++;
            LOCK.notifyAll();
            return found.image();
        }
    }

    static Map<String, Object> telemetry() {
        synchronized (LOCK) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("planId", PLAN_ID);
            values.put("property", ENABLED_PROPERTY);
            values.put("enabled", Boolean.getBoolean(ENABLED_PROPERTY));
            values.put("maxStagedBytes", MAX_STAGED_BYTES);
            values.put("starts", starts);
            values.put("stops", stops);
            values.put("producerActive", producer != null && producer.isAlive());
            values.put("cancelled", cancelled);
            values.put("attempts", attempts);
            values.put("stagedEntries", stagedEntries);
            values.put("stagedHits", stagedHits);
            values.put("ordinaryMisses", ordinaryMisses);
            values.put("duplicateDeclines", duplicateDeclines);
            values.put("lateDeclines", lateDeclines);
            values.put("oversizeDeclines", oversizeDeclines);
            values.put("failures", failures);
            values.put("queuedEntries", STAGED.size());
            values.put("queuedBytes", stagedBytes);
            values.put("peakQueuedBytes", peakStagedBytes);
            values.put("inProgress", IN_PROGRESS.size());
            values.put("loadingBytes", loadingBytes);
            values.put("peakLoadingBytes", peakLoadingBytes);
            return Map.copyOf(values);
        }
    }

    private static void produce(List<String> order) {
        try {
            for (String path : order) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                synchronized (LOCK) {
                    if (cancelled || producer != Thread.currentThread()) break;
                }
                String key = TextureCompatibilityRuntime.preparedPrefetchKey(path);
                if (key == null || !claim(key)) {
                    continue;
                }
                BufferedImage image = null;
                long bytes = 0L;
                try {
                    image = TexturePreparedPixelRuntime.load(path);
                    bytes = TexturePreparedPixelRuntime.preparedBytes(image);
                    synchronized (LOCK) {
                        if (producer != Thread.currentThread()) return;
                        loadingBytes = bytes;
                        peakLoadingBytes = Math.max(peakLoadingBytes, bytes);
                    }
                } catch (ThreadDeath | VirtualMachineError fatal) {
                    throw fatal;
                } catch (Throwable ignored) {
                    synchronized (LOCK) {
                        if (producer == Thread.currentThread()) failures++;
                    }
                }
                publish(key, image, bytes);
            }
        } finally {
            synchronized (LOCK) {
                if (producer == Thread.currentThread()) {
                    loadingBytes = 0L;
                    producer = null;
                }
                LOCK.notifyAll();
            }
        }
    }

    private static boolean claim(String key) {
        synchronized (LOCK) {
            if (producer != Thread.currentThread()) return false;
            if (cancelled || CONSUMED.contains(key) || STAGED.containsKey(key)
                    || !IN_PROGRESS.add(key)) {
                duplicateDeclines++;
                return false;
            }
            attempts++;
            return true;
        }
    }

    private static void publish(String key, BufferedImage image, long bytes) {
        synchronized (LOCK) {
            if (producer != Thread.currentThread()) return;
            IN_PROGRESS.remove(key);
            loadingBytes = 0L;
            if (image == null || bytes <= 0L) {
                failures++;
                LOCK.notifyAll();
                return;
            }
            if (bytes > MAX_STAGED_BYTES) {
                oversizeDeclines++;
                LOCK.notifyAll();
                return;
            }
            while (producer == Thread.currentThread() && !cancelled && !CONSUMED.contains(key)
                    && stagedBytes > MAX_STAGED_BYTES - bytes) {
                try {
                    LOCK.wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    if (producer != Thread.currentThread()) return;
                    cancelled = true;
                }
            }
            if (producer != Thread.currentThread()) return;
            if (cancelled || CONSUMED.contains(key)) {
                lateDeclines++;
                LOCK.notifyAll();
                return;
            }
            STAGED.put(key, new Staged(image, bytes));
            stagedBytes += bytes;
            peakStagedBytes = Math.max(peakStagedBytes, stagedBytes);
            stagedEntries++;
            LOCK.notifyAll();
        }
    }

    private static void ensureShutdownHook() {
        if (SHUTDOWN_HOOK_INSTALLED.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(
                    new Thread(TexturePreparedStagingRuntime::stop,
                            "preflight-prepared-staging-stop"));
        }
    }

    private record Staged(BufferedImage image, long bytes) {
    }
}
