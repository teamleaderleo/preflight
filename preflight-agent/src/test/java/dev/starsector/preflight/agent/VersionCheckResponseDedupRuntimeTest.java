package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VersionCheckResponseDedupRuntimeTest {
    @BeforeEach
    void reset() {
        VersionCheckResponseDedupRuntime.reset();
    }

    @Test
    void returnsIndependentStreamsFromOneSuccessfulHttpRead() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        byte[] expected = "{version: 1}".getBytes(StandardCharsets.UTF_8);
        URL url = url("https://version.test/mod.version", () -> {
            opens.incrementAndGet();
            return new ByteArrayInputStream(expected);
        });

        InputStream first = VersionCheckResponseDedupRuntime.openStream(url);
        assertEquals('{', first.read());
        InputStream second = VersionCheckResponseDedupRuntime.openStream(url);

        assertArrayEquals(expected, second.readAllBytes());
        assertEquals(1, opens.get());
        assertEquals(1L, VersionCheckResponseDedupRuntime.telemetry().get("networkFetches"));
        assertEquals(1L, VersionCheckResponseDedupRuntime.telemetry().get("reusedResponses"));
        assertEquals(1L, VersionCheckResponseDedupRuntime.telemetry().get("cachedUrls"));
    }

    @Test
    void failedReadIsNotMadeAuthoritativeForTheNextChecker() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        byte[] expected = "recovered".getBytes(StandardCharsets.UTF_8);
        URL url = url("http://version.test/retry.version", () -> {
            if (opens.getAndIncrement() == 0) throw new IOException("transient");
            return new ByteArrayInputStream(expected);
        });

        assertThrows(IOException.class, () -> VersionCheckResponseDedupRuntime.openStream(url));
        assertArrayEquals(expected,
                VersionCheckResponseDedupRuntime.openStream(url).readAllBytes());
        assertArrayEquals(expected,
                VersionCheckResponseDedupRuntime.openStream(url).readAllBytes());

        assertEquals(2, opens.get());
        assertEquals(1L, VersionCheckResponseDedupRuntime.telemetry().get("failedFetches"));
        assertEquals(1L, VersionCheckResponseDedupRuntime.telemetry().get("reusedResponses"));
    }

    @Test
    void nonHttpDevelopmentUrlsAlwaysUseTheirOriginalStream() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        URL url = url("memory://version.test/local.version", () -> {
            opens.incrementAndGet();
            return new ByteArrayInputStream(new byte[] {1});
        });

        VersionCheckResponseDedupRuntime.openStream(url).close();
        VersionCheckResponseDedupRuntime.openStream(url).close();

        assertEquals(2, opens.get());
        assertEquals(2L, VersionCheckResponseDedupRuntime.telemetry().get("bypassedProtocols"));
        assertEquals(0L, VersionCheckResponseDedupRuntime.telemetry().get("cachedUrls"));
    }

    @Test
    void rejectsOversizedResponsesWithoutCachingThem() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        byte[] oversized = new byte[VersionCheckResponseDedupRuntime.MAX_RESPONSE_BYTES + 1];
        URL url = url("https://version.test/oversized.version", () -> {
            opens.incrementAndGet();
            return new ByteArrayInputStream(oversized);
        });

        assertThrows(IOException.class, () -> VersionCheckResponseDedupRuntime.openStream(url));
        assertThrows(IOException.class, () -> VersionCheckResponseDedupRuntime.openStream(url));

        assertEquals(2, opens.get());
        assertEquals(0L, VersionCheckResponseDedupRuntime.telemetry().get("cachedUrls"));
        assertEquals(0L, VersionCheckResponseDedupRuntime.telemetry().get("cachedBytes"));
    }

    @Test
    void bypassesCachingAfterTheUrlLimit() throws Exception {
        for (int index = 0; index < VersionCheckResponseDedupRuntime.MAX_CACHED_URLS; index++) {
            URL cached = url("https://version.test/" + index, InputStream::nullInputStream);
            VersionCheckResponseDedupRuntime.openStream(cached).close();
        }
        AtomicInteger opens = new AtomicInteger();
        URL uncached = url("https://version.test/uncached", () -> {
            opens.incrementAndGet();
            return InputStream.nullInputStream();
        });

        VersionCheckResponseDedupRuntime.openStream(uncached).close();
        VersionCheckResponseDedupRuntime.openStream(uncached).close();

        assertEquals(2, opens.get());
        assertEquals((long) VersionCheckResponseDedupRuntime.MAX_CACHED_URLS,
                VersionCheckResponseDedupRuntime.telemetry().get("cachedUrls"));
    }

    @Test
    void stopsRetainingResponsesAtTheByteLimit() throws Exception {
        byte[] maximumResponse = new byte[VersionCheckResponseDedupRuntime.MAX_RESPONSE_BYTES];
        int cachedResponses = Math.toIntExact(VersionCheckResponseDedupRuntime.MAX_CACHED_BYTES
                / VersionCheckResponseDedupRuntime.MAX_RESPONSE_BYTES);
        for (int index = 0; index < cachedResponses; index++) {
            URL cached = url("https://version.test/large/" + index,
                    () -> new ByteArrayInputStream(maximumResponse));
            VersionCheckResponseDedupRuntime.openStream(cached).close();
        }
        AtomicInteger opens = new AtomicInteger();
        URL uncached = url("https://version.test/over-byte-budget", () -> {
            opens.incrementAndGet();
            return new ByteArrayInputStream(new byte[] {1});
        });

        VersionCheckResponseDedupRuntime.openStream(uncached).close();
        VersionCheckResponseDedupRuntime.openStream(uncached).close();

        assertEquals(2, opens.get());
        assertEquals(VersionCheckResponseDedupRuntime.MAX_CACHED_BYTES,
                VersionCheckResponseDedupRuntime.telemetry().get("cachedBytes"));
        assertEquals((long) cachedResponses,
                VersionCheckResponseDedupRuntime.telemetry().get("cachedUrls"));
    }

    private static URL url(String spec, Opener opener) throws Exception {
        return new URL(null, spec, new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL url) {
                return new URLConnection(url) {
                    @Override
                    public void connect() {
                    }

                    @Override
                    public InputStream getInputStream() throws IOException {
                        return opener.open();
                    }
                };
            }
        });
    }

    @FunctionalInterface
    private interface Opener {
        InputStream open() throws IOException;
    }
}
