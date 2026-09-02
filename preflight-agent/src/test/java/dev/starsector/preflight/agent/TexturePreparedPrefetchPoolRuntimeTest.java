package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TexturePreparedPrefetchPoolRuntimeTest {
    private static volatile CountDownLatch entered;
    private static volatile CountDownLatch release;
    private static volatile CountDownLatch byteEntered;
    private static volatile CountDownLatch byteRelease;

    @AfterEach
    void reset() {
        TexturePreparedPrefetchPoolRuntime.beginSession();
    }

    @Test
    void drainsTheExactQueuesWithThreeConcurrentConsumers() throws Exception {
        List<String> images = Collections.synchronizedList(new ArrayList<>(List.of(
                "one", "two", "three", "four", "five", "six")));
        List<String> bytes = Collections.synchronizedList(new ArrayList<>());
        Map<String, Object> imageResults = new ConcurrentHashMap<>();
        Map<String, Object> byteResults = new ConcurrentHashMap<>();
        Object imageMarker = new BufferedImage(1, 1, BufferedImage.TYPE_BYTE_GRAY);
        Object byteMarker = new byte[0];
        entered = new CountDownLatch(3);
        release = new CountDownLatch(1);

        TexturePreparedPrefetchPoolRuntime.start(
                TexturePreparedPrefetchPoolRuntimeTest.class,
                images,
                imageResults,
                imageMarker,
                bytes,
                byteResults,
                byteMarker,
                "decodeImage",
                "decodeBytes",
                3);

        assertTrue(entered.await(2, TimeUnit.SECONDS));
        release.countDown();
        waitFor(() -> ((Number) TexturePreparedPrefetchPoolRuntime.report()
                .get("imageCompletions")).longValue() == 6L);

        assertEquals(6, imageResults.size());
        assertEquals(6L, TexturePreparedPrefetchPoolRuntime.report().get("imageClaims"));
        assertEquals(6L, TexturePreparedPrefetchPoolRuntime.report().get("imageCompletions"));
        assertEquals(3, TexturePreparedPrefetchPoolRuntime.report().get("peakWorkers"));
        assertEquals(0L, TexturePreparedPrefetchPoolRuntime.report().get("failures"));
    }

    @Test
    void splitQueuesDoNotHoldImagesBehindAStalledByteDecoder() throws Exception {
        List<String> images = Collections.synchronizedList(new ArrayList<>(List.of("cursor")));
        List<String> bytes = Collections.synchronizedList(new ArrayList<>(List.of("sound")));
        Map<String, Object> imageResults = new ConcurrentHashMap<>();
        Map<String, Object> byteResults = new ConcurrentHashMap<>();
        byteEntered = new CountDownLatch(1);
        byteRelease = new CountDownLatch(1);

        TexturePreparedPrefetchPoolRuntime.startSplitQueues(
                TexturePreparedPrefetchPoolRuntimeTest.class,
                images,
                imageResults,
                new BufferedImage(1, 1, BufferedImage.TYPE_BYTE_GRAY),
                bytes,
                byteResults,
                new byte[0],
                "decodeImmediateImage",
                "decodeBlockedBytes",
                2);

        assertTrue(byteEntered.await(2, TimeUnit.SECONDS));
        waitFor(() -> ((Number) TexturePreparedPrefetchPoolRuntime.report()
                .get("imageCompletions")).longValue() == 1L);
        assertEquals(0L, TexturePreparedPrefetchPoolRuntime.report().get("byteCompletions"));
        assertEquals("split-queues", TexturePreparedPrefetchPoolRuntime.report().get("queueMode"));

        byteRelease.countDown();
        waitFor(() -> ((Number) TexturePreparedPrefetchPoolRuntime.report()
                .get("byteCompletions")).longValue() == 1L);
        assertEquals(1, imageResults.size());
        assertEquals(1, byteResults.size());
    }

    private static BufferedImage decodeImage(String ignored) throws InterruptedException {
        entered.countDown();
        release.await();
        return new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
    }

    private static byte[] decodeBytes(String path) {
        return path.getBytes(StandardCharsets.UTF_8);
    }

    private static BufferedImage decodeImmediateImage(String ignored) {
        return new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
    }

    private static byte[] decodeBlockedBytes(String path) throws InterruptedException {
        byteEntered.countDown();
        byteRelease.await();
        return path.getBytes(StandardCharsets.UTF_8);
    }

    private static void waitFor(Check condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!condition.done() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.done());
    }

    private interface Check {
        boolean done();
    }
}
