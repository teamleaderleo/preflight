package dev.starsector.preflight.agent;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Preserves sound-store relative lookup and adds the classpath-root lookup its callers expect. */
public final class AudioResourceFallbackRuntime {
    static final String PLAN_ID = "audio-classpath-root-resource-fallback-v1";

    private static final AtomicLong lookups = new AtomicLong();
    private static final AtomicLong originalHits = new AtomicLong();
    private static final AtomicLong fallbackAttempts = new AtomicLong();
    private static final AtomicLong fallbackHits = new AtomicLong();
    private static final AtomicLong fallbackMisses = new AtomicLong();
    private static final AtomicLong fallbackFailures = new AtomicLong();
    private static volatile boolean installed;

    private AudioResourceFallbackRuntime() {
    }

    static void beginSession() {
        installed = false;
        lookups.set(0L);
        originalHits.set(0L);
        fallbackAttempts.set(0L);
        fallbackHits.set(0L);
        fallbackMisses.set(0L);
        fallbackFailures.set(0L);
    }

    static void installed() {
        installed = true;
    }

    /** Called in place of the reviewed {@link Class#getResourceAsStream(String)} instructions. */
    public static InputStream open(Class<?> owner, String path) {
        lookups.incrementAndGet();
        InputStream original = owner.getResourceAsStream(path);
        if (original != null) {
            originalHits.incrementAndGet();
            return original;
        }
        if (path == null || path.startsWith("/")) return null;
        fallbackAttempts.incrementAndGet();
        try {
            InputStream fallback = owner.getResourceAsStream("/" + path);
            if (fallback == null) {
                fallbackMisses.incrementAndGet();
            } else {
                fallbackHits.incrementAndGet();
            }
            return fallback;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (RuntimeException failed) {
            // The shipped relative lookup already returned null. Preserve that result on failure.
            fallbackFailures.incrementAndGet();
            return null;
        }
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("installed", installed);
        result.put("lookups", lookups.get());
        result.put("originalHits", originalHits.get());
        result.put("fallbackAttempts", fallbackAttempts.get());
        result.put("fallbackHits", fallbackHits.get());
        result.put("fallbackMisses", fallbackMisses.get());
        result.put("fallbackFailures", fallbackFailures.get());
        return result;
    }

    static void reset() {
        beginSession();
    }
}
