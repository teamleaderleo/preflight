package dev.starsector.preflight.agent;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/** Shares identical successful version-check responses within one game process. */
public final class VersionCheckResponseDedupRuntime {
    static final String PLAN_ID = "version-check-response-dedup-v1";
    static final int MAX_RESPONSE_BYTES = 256 * 1024;
    static final int MAX_CACHED_URLS = 256;
    static final long MAX_CACHED_BYTES = 8L * 1024 * 1024;

    private static final ConcurrentHashMap<String, CompletableFuture<byte[]>> RESPONSES =
            new ConcurrentHashMap<>();
    private static final AtomicLong INSTALLED_TARGETS = new AtomicLong();
    private static final AtomicLong NETWORK_FETCHES = new AtomicLong();
    private static final AtomicLong REUSED_RESPONSES = new AtomicLong();
    private static final AtomicLong BYPASSED_PROTOCOLS = new AtomicLong();
    private static final AtomicLong FAILED_FETCHES = new AtomicLong();
    private static final AtomicLong RETRY_FETCHES = new AtomicLong();
    private static final AtomicLong CACHED_BYTES = new AtomicLong();

    private VersionCheckResponseDedupRuntime() {
    }

    static void installed() {
        INSTALLED_TARGETS.incrementAndGet();
    }

    /**
     * Opens a fresh stream while sharing a bounded successful HTTP response with identical callers.
     *
     * <p>The map lives only for this JVM. Failed requests are removed before their exception is
     * exposed, and a caller that happened to wait on a failed request retries the URL itself. This
     * preserves the two version checkers' independent failure behavior while removing their
     * duplicate successful downloads. Responses larger than {@link #MAX_RESPONSE_BYTES} are
     * rejected, and responses beyond the cache's URL or byte budget are not retained. Development
     * {@code file:} URLs and every other protocol always retain {@link URL#openStream()} unchanged.
     */
    public static InputStream openStream(URL url) throws IOException {
        Objects.requireNonNull(url, "url");
        String protocol = url.getProtocol().toLowerCase(Locale.ROOT);
        if (!"http".equals(protocol) && !"https".equals(protocol)) {
            BYPASSED_PROTOCOLS.incrementAndGet();
            return url.openStream();
        }

        String key = url.toExternalForm();
        CompletableFuture<byte[]> created = new CompletableFuture<>();
        CompletableFuture<byte[]> existing;
        synchronized (RESPONSES) {
            existing = RESPONSES.get(key);
            if (existing == null) {
                if (RESPONSES.size() >= MAX_CACHED_URLS) return url.openStream();
                RESPONSES.put(key, created);
            }
        }
        if (existing == null) {
            NETWORK_FETCHES.incrementAndGet();
            try (InputStream input = url.openStream()) {
                byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new IOException("Version-check response exceeds "
                            + MAX_RESPONSE_BYTES + " bytes");
                }
                if (!reserveCacheBytes(bytes.length)) RESPONSES.remove(key, created);
                created.complete(bytes);
                return new ByteArrayInputStream(bytes);
            } catch (ThreadDeath | VirtualMachineError fatal) {
                RESPONSES.remove(key, created);
                created.completeExceptionally(fatal);
                throw fatal;
            } catch (Throwable failure) {
                RESPONSES.remove(key, created);
                created.completeExceptionally(failure);
                FAILED_FETCHES.incrementAndGet();
                throwAsOriginal(failure);
                throw new AssertionError("unreachable");
            }
        }

        try {
            byte[] bytes = existing.get();
            REUSED_RESPONSES.incrementAndGet();
            return new ByteArrayInputStream(bytes);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            RETRY_FETCHES.incrementAndGet();
            return url.openStream();
        } catch (ExecutionException failedOwner) {
            // Do not make one checker's transient failure authoritative for the other checker.
            RETRY_FETCHES.incrementAndGet();
            return url.openStream();
        }
    }

    private static boolean reserveCacheBytes(int bytes) {
        long current = CACHED_BYTES.get();
        while (current <= MAX_CACHED_BYTES - bytes) {
            if (CACHED_BYTES.compareAndSet(current, current + bytes)) return true;
            current = CACHED_BYTES.get();
        }
        return false;
    }

    private static void throwAsOriginal(Throwable failure) throws IOException {
        if (failure instanceof IOException io) throw io;
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        throw new IOException("Version-check response read failed", failure);
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", PLAN_ID);
        values.put("installedTargets", INSTALLED_TARGETS.get());
        values.put("networkFetches", NETWORK_FETCHES.get());
        values.put("reusedResponses", REUSED_RESPONSES.get());
        values.put("bypassedProtocols", BYPASSED_PROTOCOLS.get());
        values.put("failedFetches", FAILED_FETCHES.get());
        values.put("retryFetches", RETRY_FETCHES.get());
        values.put("cachedUrls", RESPONSES.mappingCount());
        values.put("cachedBytes", CACHED_BYTES.get());
        return values;
    }

    static void reset() {
        RESPONSES.clear();
        INSTALLED_TARGETS.set(0L);
        NETWORK_FETCHES.set(0L);
        REUSED_RESPONSES.set(0L);
        BYPASSED_PROTOCOLS.set(0L);
        FAILED_FETCHES.set(0L);
        RETRY_FETCHES.set(0L);
        CACHED_BYTES.set(0L);
    }
}
