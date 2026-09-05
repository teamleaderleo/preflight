package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.PreparedTexture;
import dev.starsector.preflight.core.PreparedTextureIO;
import dev.starsector.preflight.core.PreparedTexturePrefetchOrderIO;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceIndexIO;
import dev.starsector.preflight.core.TextureManifest;
import dev.starsector.preflight.core.TextureManifestIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.opengl.GLContext;

class TexturePreparedResourceRuntimeTest {
    @TempDir Path temporaryDirectory;
    private static final String PATH = "graphics/test.png";
    private final List<String> queue = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, BufferedImage> results = new ConcurrentHashMap<>();
    private final BufferedImage sentinel = image();

    @BeforeEach
    @AfterEach
    void reset() {
        TexturePreparedStagingRuntime.beginSession();
        System.clearProperty(TexturePreparedStagingRuntime.ENABLED_PROPERTY);
        TexturePreparedResourceRuntime.beginSession();
        TexturePreparedPixelRuntime.beginSession();
        TextureCompatibilityRuntime.beginSession();
        System.clearProperty(TextureCompatibilityRuntime.TRUST_VALIDATED_INDEX_PROPERTY);
        System.clearProperty(TexturePreparedResourceRuntime.PROPERTY);
        System.clearProperty(TexturePreparedResourceRuntime.CLAIM_PROPERTY);
        System.clearProperty(TexturePreparedResourceRuntime.BARRIER_PROPERTY);
        System.clearProperty(TexturePreparedResourceRuntime.PRESTART_PROPERTY);
        System.clearProperty(TexturePreparedPrefetchPlan.WINDOWS_WORKERS_PROPERTY);
        System.clearProperty(TexturePreparedPrefetchPlan.WINDOWS_SPLIT_QUEUES_PROPERTY);
    }

    @Test
    void prestartConsumesStagedCarrierWithoutReadingItsBlobAgain() throws Exception {
        carrier(2);
        Path cache = temporaryDirectory.resolve("cache");
        String profile = "ab".repeat(32);
        PreparedTexturePrefetchOrderIO.write(PreparedTexturePrefetchOrderIO.path(cache, profile),
                profile, List.of(PATH));
        assertTrue(TextureAccessLearningRuntime.configure(cache, profile));
        System.setProperty(TexturePreparedStagingRuntime.ENABLED_PROPERTY, "true");
        TexturePreparedStagingRuntime.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while ((int) TexturePreparedStagingRuntime.telemetry().get("queuedEntries") == 0
                && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(1, TexturePreparedStagingRuntime.telemetry().get("queuedEntries"));
        // An extra serving read now fails: the admitted immutable carrier must own this completion.
        Files.delete(cache.resolve("blobs/ab/" + profile + "-identity.spft"));
        System.setProperty(TexturePreparedResourceRuntime.PROPERTY, "true");
        System.setProperty(TexturePreparedResourceRuntime.PRESTART_PROPERTY, "true");
        activate(Thread.currentThread());
        field("workerThread").set(null, null);
        queue.add(PATH);
        TexturePreparedResourceRuntime.worker(new Thread(() -> { }));
        TexturePreparedResourceRuntime.enter(PATH, PATH);
        var completion = TexturePreparedResourceRuntime.take(PATH, null, null);
        assertNotNull(completion);
        assertEquals(TexturePreparedResourceRuntime.Kind.PREPARED, completion.kind());
        assertEquals(1L, TexturePreparedStagingRuntime.telemetry().get("stagedHits"));
        assertEquals(0, TexturePreparedStagingRuntime.telemetry().get("queuedEntries"));
        assertEquals(0, TexturePreparedPixelRuntime.telemetry().get("activeBuffers"));
        TexturePreparedResourceRuntime.exit(true);
        assertEquals(1L, telemetry().get("committed"));
    }

    @Test
    void prestartAdmissionConsumesPreparedImagesBeforeBytePhaseWithoutStartingAnotherWorker() throws Exception {
        carrier(2);
        System.setProperty(TexturePreparedResourceRuntime.PROPERTY, "true");
        System.setProperty(TexturePreparedResourceRuntime.PRESTART_PROPERTY, "true");
        activate(Thread.currentThread());
        field("workerThread").set(null, null);
        field("workerImagePhase").setBoolean(null, false);
        queue.addAll(List.of(PATH, "unknown", PATH, "graphics/kaleidoscope/late.png"));
        Thread worker = new Thread(() -> { });
        TexturePreparedResourceRuntime.worker(worker);
        assertEquals(Thread.State.NEW, worker.getState());
        assertEquals(List.of("unknown", "graphics/kaleidoscope/late.png"), queue);
        assertEquals(2L, telemetry().get("prestartRemoved"));
        assertEquals(1, telemetry().get("prestartPending"));
        TexturePreparedResourceRuntime.enter(PATH, PATH);
        assertNotNull(TexturePreparedResourceRuntime.take(PATH, null, null));
        TexturePreparedResourceRuntime.exit(true);
        assertEquals(1L, telemetry().get("prestartTaken"));
        assertEquals(0L, telemetry().get("waitPolls"));
        assertTrue(results.isEmpty());
        TexturePreparedResourceRuntime.end();
        TexturePreparedResourceRuntime.end();
        assertEquals(0L, telemetry().get("prestartUnused"));
        assertEquals(0, telemetry().get("prestartPending"));
    }

    @Test
    void prestartAdmissionKeepsConflictingResultsAndRetiresUnusedLastJobOnce() throws Exception {
        carrier(2);
        System.setProperty(TexturePreparedResourceRuntime.PROPERTY, "true");
        System.setProperty(TexturePreparedResourceRuntime.PRESTART_PROPERTY, "true");
        activate(Thread.currentThread());
        field("workerThread").set(null, null);
        queue.add(PATH);
        results.put(PATH, sentinel);
        TexturePreparedResourceRuntime.worker(new Thread(() -> { }));
        assertEquals(List.of(PATH), queue);
        assertEquals(0L, telemetry().get("prestartRemoved"));
        results.clear();
        field("workerThread").set(null, null);
        TexturePreparedResourceRuntime.worker(new Thread(() -> { }));
        assertTrue(queue.isEmpty());
        TexturePreparedResourceRuntime.end();
        TexturePreparedResourceRuntime.end();
        assertEquals(1L, telemetry().get("prestartUnused"));
    }

    @Test
    void prestartAdmissionDeclinesWithoutOptInOrBeforeAnUnstartedExactWorker() throws Exception {
        carrier(2);
        System.setProperty(TexturePreparedResourceRuntime.PROPERTY, "true");
        activate(Thread.currentThread());
        field("workerThread").set(null, null);
        queue.add(PATH);
        TexturePreparedResourceRuntime.worker(new Thread(() -> { }));
        assertEquals(List.of(PATH), queue);
        System.setProperty(TexturePreparedResourceRuntime.PRESTART_PROPERTY, "true");
        field("workerThread").set(null, null);
        TexturePreparedResourceRuntime.worker(Thread.currentThread());
        assertEquals(List.of(PATH), queue);
        field("workerThread").set(null, null);
        TexturePreparedResourceRuntime.worker(new Thread() { });
        assertEquals(List.of(PATH), queue);
    }

    @Test
    void requestRequiresExplicitOptInAndOneUnsplitWorker() {
        assertFalse(TexturePreparedResourceRuntime.requested());
        System.setProperty(TexturePreparedResourceRuntime.PROPERTY, "true");
        assertTrue(TexturePreparedResourceRuntime.requested());
        System.setProperty(TexturePreparedPrefetchPlan.WINDOWS_WORKERS_PROPERTY, "2");
        assertFalse(TexturePreparedResourceRuntime.requested());
        System.setProperty(TexturePreparedPrefetchPlan.WINDOWS_WORKERS_PROPERTY, "1");
        System.setProperty(TexturePreparedPrefetchPlan.WINDOWS_SPLIT_QUEUES_PROPERTY, "true");
        assertFalse(TexturePreparedResourceRuntime.requested());
    }

    @Test
    void mainWaitsForExactByteBoundaryAndThenLoadsWithoutWorkerImagePublication() throws Exception {
        carrier(2);
        System.setProperty(TexturePreparedResourceRuntime.PROPERTY, "true");
        System.setProperty(TexturePreparedResourceRuntime.BARRIER_PROPERTY, "true");
        CountDownLatch entered = new CountDownLatch(1), finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread main = new Thread(() -> {
            try {
                TexturePreparedResourceRuntime.enter(PATH, PATH);
                entered.countDown();
                assertNotNull(TexturePreparedResourceRuntime.take(PATH, null, null));
                TexturePreparedResourceRuntime.exit(true);
            } catch (Throwable error) { failure.set(error); }
            finally { finished.countDown(); }
        });
        activate(main);
        queue.add(PATH);
        main.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertFalse(finished.await(50, TimeUnit.MILLISECONDS));
            assertEquals(0L, telemetry().get("published"));
            TexturePreparedResourceRuntime.bytePhaseComplete();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(1L, telemetry().get("barrierTaken"));
            assertTrue(results.isEmpty());
        } finally { main.interrupt(); main.join(2_000); }
    }

    @Test
    void byteBoundaryRemovesOnlyAdmittedJobsIncludingLastAndRetiresOnce() throws Exception {
        carrier(2);
        System.setProperty(TexturePreparedResourceRuntime.PROPERTY, "true");
        System.setProperty(TexturePreparedResourceRuntime.BARRIER_PROPERTY, "true");
        activate(Thread.currentThread());
        queue.addAll(List.of("unknown", PATH, "graphics/kaleidoscope/late.png", PATH));
        Thread outsider = new Thread(TexturePreparedResourceRuntime::bytePhaseComplete);
        outsider.start();
        outsider.join(2_000);
        assertEquals(4, queue.size());
        TexturePreparedResourceRuntime.bytePhaseComplete();
        TexturePreparedResourceRuntime.bytePhaseComplete();
        assertEquals(List.of("unknown", "graphics/kaleidoscope/late.png"), queue);
        assertEquals(2L, telemetry().get("barrierRemoved"));
        assertTrue(results.isEmpty());
        TexturePreparedResourceRuntime.enter(PATH, PATH);
        assertNull(TexturePreparedResourceRuntime.take(PATH, new Object(), null));
        assertNull(TexturePreparedResourceRuntime.take(PATH, null, new Object()));
        var completion = TexturePreparedResourceRuntime.take(PATH, null, null);
        assertNotNull(completion);
        assertEquals(TexturePreparedResourceRuntime.Kind.PREPARED, completion.kind());
        TexturePreparedResourceRuntime.exit(true);
        TexturePreparedResourceRuntime.end();
        TexturePreparedResourceRuntime.end();
        assertEquals(1L, telemetry().get("barrierTaken"));
        assertEquals(0L, telemetry().get("barrierUnused"));
        assertEquals(0, telemetry().get("barrierPending"));
        assertEquals(1L, telemetry().get("committed"));
    }

    @Test
    void byteBoundaryKeepsConflictingResultsAndRetiresUnusedAdmissions() throws Exception {
        carrier(2);
        System.setProperty(TexturePreparedResourceRuntime.PROPERTY, "true");
        System.setProperty(TexturePreparedResourceRuntime.BARRIER_PROPERTY, "true");
        activate(Thread.currentThread());
        queue.add(PATH);
        results.put(PATH, sentinel);
        TexturePreparedResourceRuntime.bytePhaseComplete();
        assertEquals(List.of(PATH), queue);
        assertEquals(0L, telemetry().get("barrierRemoved"));
        TexturePreparedResourceRuntime.end();
        activate(Thread.currentThread());
        field("bytePhaseComplete").setBoolean(null, false);
        results.clear();
        TexturePreparedResourceRuntime.bytePhaseComplete();
        assertTrue(queue.isEmpty(), "last entry is safe before the image loop begins");
        TexturePreparedResourceRuntime.end();
        assertEquals(1L, telemetry().get("barrierUnused"));
    }

    @Test
    void mainClaimsQueuedPreparedJobAndLeavesWorkerTailUntouched() throws Exception {
        carrier(2);
        enableClaims();
        activate(Thread.currentThread());
        queue.addAll(List.of(PATH, "graphics/kaleidoscope/late.png"));
        TexturePreparedResourceRuntime.enter(PATH, PATH);
        var completion = TexturePreparedResourceRuntime.take(PATH, null, null);
        assertNotNull(completion);
        assertEquals(TexturePreparedResourceRuntime.Kind.PREPARED, completion.kind());
        assertEquals(List.of("graphics/kaleidoscope/late.png"), queue);
        assertTrue(results.isEmpty(), "main must not manufacture a stock result or sentinel");
        assertEquals(1L, telemetry().get("queuedClaims"));
        assertEquals(1L, telemetry().get("published"));
        var pixel = completion.prepare();
        assertNotNull(pixel);
        TexturePreparedPixelRuntime.release(pixel.buffer());
        TexturePreparedResourceRuntime.exit(true);
        TexturePreparedResourceRuntime.exit(true);
        assertEquals(1L, telemetry().get("committed"));
        assertEquals(0L, telemetry().get("inFlight"));
        assertEquals(0L, telemetry().get("waitPolls"));
    }

    @Test
    void readyCompletionWinsWithoutAnotherPreparedReadOrQueueRemoval() throws Exception {
        BufferedImage image = carrier(2);
        enableClaims();
        activate(Thread.currentThread());
        queue.addAll(List.of("other", "tail"));
        TexturePreparedResourceRuntime.publish(PATH, image);
        results.put(PATH, image);
        Object loads = TexturePreparedPixelRuntime.telemetry().get("loadCalls");
        TexturePreparedResourceRuntime.enter(PATH, PATH);
        assertSame(image, TexturePreparedResourceRuntime.take(PATH, null, null).image());
        assertEquals(loads, TexturePreparedPixelRuntime.telemetry().get("loadCalls"));
        assertEquals(List.of("other", "tail"), queue);
        assertEquals(0L, telemetry().get("queuedClaims"));
        TexturePreparedResourceRuntime.exit(true);
    }

    @Test
    void cacheMissRetiresClaimAndLeavesOriginalGetterFreeToDecode() throws Exception {
        carrier(2);
        try (var files = Files.walk(temporaryDirectory)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".spft")).toList()) Files.delete(file);
        }
        enableClaims();
        activate(Thread.currentThread());
        queue.addAll(List.of(PATH, "tail"));
        TexturePreparedResourceRuntime.enter(PATH, PATH);
        assertNull(TexturePreparedResourceRuntime.take(PATH, null, null));
        assertEquals(List.of("tail"), queue);
        assertTrue(results.isEmpty());
        assertEquals(1L, telemetry().get("queuedClaims"));
        assertEquals(1L, telemetry().get("claimFallbacks"));
        assertEquals(0L, telemetry().get("published"));
        assertEquals(0L, telemetry().get("inFlight"));
        assertNull(TexturePreparedResourceRuntime.take(PATH, null, null));
        assertEquals(1L, telemetry().get("queuedClaims"), "a removed job cannot be claimed twice");
    }

    @Test
    void claimsDeclineTransformsExistingHandlesAndWrongRegistrationWithoutRemovingJobs() throws Exception {
        carrier(2);
        enableClaims();
        activate(Thread.currentThread());
        queue.addAll(List.of(PATH, "tail"));
        TexturePreparedResourceRuntime.enter(PATH, "alias");
        assertNull(TexturePreparedResourceRuntime.take(PATH, null, null));
        TexturePreparedResourceRuntime.exit(true);
        TexturePreparedResourceRuntime.enter(PATH, PATH);
        assertNull(TexturePreparedResourceRuntime.take(PATH, new Object(), null));
        assertNull(TexturePreparedResourceRuntime.take(PATH, null, new Object()));
        assertEquals(List.of(PATH, "tail"), queue);
        assertEquals(0L, telemetry().get("queuedClaims"));
    }

    @Test
    void inFlightWorkerJobIsNeverStolenEvenIfAQueueEntryIsPresent() throws Exception {
        carrier(2);
        enableClaims();
        activate(Thread.currentThread());
        queue.addAll(List.of(PATH, "tail"));
        results.put(PATH, sentinel);
        TexturePreparedResourceRuntime.enter(PATH, PATH);
        Thread.currentThread().interrupt();
        try { assertNull(TexturePreparedResourceRuntime.take(PATH, null, null)); }
        finally { Thread.interrupted(); }
        assertEquals(List.of(PATH, "tail"), queue);
        assertSame(sentinel, results.get(PATH));
        assertEquals(0L, telemetry().get("queuedClaims"));
    }

    @Test
    void disablingClaimsOrChangingPreparedIdentityKeepsStockQueueOwnership() throws Exception {
        carrier(2);
        enableClaims();
        activate(Thread.currentThread());
        queue.addAll(List.of(PATH, "tail"));
        TexturePreparedResourceRuntime.enter(PATH, PATH);
        System.setProperty(TexturePreparedResourceRuntime.CLAIM_PROPERTY, "false");
        Thread.currentThread().interrupt();
        try { assertNull(TexturePreparedResourceRuntime.take(PATH, null, null)); }
        finally { Thread.interrupted(); }
        assertEquals(List.of(PATH, "tail"), queue);
        System.setProperty(TexturePreparedResourceRuntime.CLAIM_PROPERTY, "true");
        TextureCompatibilityRuntime.beginSession(); // the admitted key is no longer available
        Thread.currentThread().interrupt();
        try { assertNull(TexturePreparedResourceRuntime.take(PATH, null, null)); }
        finally { Thread.interrupted(); }
        assertEquals(List.of(PATH, "tail"), queue);
        assertEquals(0L, telemetry().get("queuedClaims"));
    }

    @Test
    void lastEntryRemainsForWorkerThatAlreadyObservedNonemptyQueue() throws Exception {
        BufferedImage image = carrier(2);
        enableClaims();
        activate(Thread.currentThread());
        queue.addAll(List.of(PATH, "tail"));
        // Stock worker has observed nonempty before taking its monitor.
        assertFalse(queue.isEmpty());
        TexturePreparedResourceRuntime.enter(PATH, PATH);
        assertNotNull(TexturePreparedResourceRuntime.take(PATH, null, null));
        TexturePreparedResourceRuntime.exit(true);
        synchronized (queue) { assertEquals("tail", queue.remove(0)); }

        queue.add(PATH);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                // Wait for main's singleton decline, without racing test assertions.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while ((long) telemetry().get("lastEntryDeclines") == 0 && System.nanoTime() < deadline)
                    Thread.sleep(10);
                synchronized (queue) {
                    assertEquals(PATH, queue.remove(0));
                    results.put(PATH, sentinel);
                }
                TexturePreparedResourceRuntime.publish(PATH, image);
                results.put(PATH, image);
            } catch (Throwable error) { failure.set(error); }
        });
        TexturePreparedResourceRuntime.worker(worker);
        worker.start();
        TexturePreparedResourceRuntime.enter(PATH, PATH);
        assertSame(image, TexturePreparedResourceRuntime.take(PATH, null, null).image());
        worker.join(2_000);
        assertFalse(worker.isAlive());
        assertNull(failure.get());
        assertEquals(1L, telemetry().get("lastEntryDeclines"));
        assertEquals(1L, telemetry().get("queuedClaims"));
        TexturePreparedResourceRuntime.exit(true);
    }

    @Test
    void workerSentinelWinsClaimRaceAndMainConsumesExactlyOnce() throws Exception {
        BufferedImage image = carrier(2);
        enableClaims();
        for (int attempt = 0; attempt < 30; attempt++) {
            TexturePreparedResourceRuntime.beginSession();
            activate(Thread.currentThread());
            queue.clear();
            results.clear();
            queue.addAll(List.of(PATH, "tail"));
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch start = new CountDownLatch(1);
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    synchronized (queue) {
                        if (!queue.remove(PATH)) return;
                        results.put(PATH, sentinel);
                    }
                    TexturePreparedResourceRuntime.publish(PATH, image);
                    results.put(PATH, image);
                } catch (Throwable error) { failure.set(error); }
            });
            TexturePreparedResourceRuntime.worker(worker);
            worker.start();
            TexturePreparedResourceRuntime.enter(PATH, PATH);
            start.countDown();
            assertNotNull(TexturePreparedResourceRuntime.take(PATH, null, null));
            TexturePreparedResourceRuntime.exit(true);
            worker.join(2_000);
            assertFalse(worker.isAlive());
            assertNull(failure.get());
            assertEquals(List.of("tail"), queue);
            assertTrue(results.isEmpty());
            assertEquals(1L, telemetry().get("published"));
            assertEquals(1L, telemetry().get("committed"));
            assertEquals(0L, telemetry().get("inFlight"));
        }
    }

    private static void enableClaims() {
        System.setProperty(TexturePreparedResourceRuntime.PROPERTY, "true");
        System.setProperty(TexturePreparedResourceRuntime.CLAIM_PROPERTY, "true");
    }

    @Test
    void exactWorkerSignalsWaitingConsumerOnlyAfterItsStockResultIsVisible() throws Exception {
        BufferedImage image = carrier(2);
        enableClaims();
        activate(Thread.currentThread());
        results.put(PATH, sentinel);
        Object lock = field("LOCK").get(null);
        Field waiting = field("waitingPath");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (System.nanoTime() < deadline) {
                    synchronized (lock) {
                        if (PATH.equals(waiting.get(null))) {
                            TexturePreparedResourceRuntime.publish(PATH, image);
                            TexturePreparedResourceRuntime.resultReady(PATH, image);
                            assertEquals(0L, telemetry().get("resultSignals"), "sentinel is not a result");
                            results.put(PATH, image);
                            TexturePreparedResourceRuntime.resultReady("other", image);
                            assertEquals(0L, telemetry().get("resultSignals"));
                            TexturePreparedResourceRuntime.resultReady(PATH, image);
                            return;
                        }
                    }
                    Thread.sleep(1);
                }
                throw new AssertionError("consumer never registered its wait");
            } catch (Throwable error) {
                failure.set(error);
                // A failed assertion must not strand the test's consumer.
                TexturePreparedResourceRuntime.publish(PATH, image);
                results.put(PATH, image);
                TexturePreparedResourceRuntime.resultReady(PATH, image);
            }
        });
        TexturePreparedResourceRuntime.worker(worker);
        worker.start();
        TexturePreparedResourceRuntime.enter(PATH, PATH);
        assertSame(image, TexturePreparedResourceRuntime.take(PATH, null, null).image());
        worker.join(3_000);
        assertFalse(worker.isAlive());
        assertNull(failure.get());
        assertTrue((long) telemetry().get("resultSignals") >= 1);
        assertEquals(0L, telemetry().get("queuedClaims"));
        TexturePreparedResourceRuntime.exit(true);
    }

    @Test
    void claimsWaitForExactWorkerImagePhaseBeforeChangingQueueOwnership() throws Exception {
        BufferedImage image = carrier(2);
        enableClaims();
        activate(Thread.currentThread());
        field("workerImagePhase").setBoolean(null, false);
        queue.addAll(List.of(PATH, "tail"));
        TexturePreparedResourceRuntime.enter(PATH, PATH);
        Thread.currentThread().interrupt();
        try { assertNull(TexturePreparedResourceRuntime.take(PATH, null, null)); }
        finally { Thread.interrupted(); }
        assertEquals(List.of(PATH, "tail"), queue);
        assertEquals(0L, telemetry().get("queuedClaims"));
        assertEquals(1L, telemetry().get("imagePhaseDeferrals"));
        TexturePreparedResourceRuntime.exit(true);
        Thread wrongWorker = new Thread(() -> TexturePreparedResourceRuntime.publish("other", image));
        wrongWorker.start();
        wrongWorker.join(2_000);
        assertEquals(false, telemetry().get("workerImagePhaseObserved"));
        // activate binds the current thread as the exact worker for this isolated fixture.
        TexturePreparedResourceRuntime.publish("other", image);
        assertEquals(true, telemetry().get("workerImagePhaseObserved"));
        TexturePreparedResourceRuntime.enter(PATH, PATH);
        assertNotNull(TexturePreparedResourceRuntime.take(PATH, null, null));
        assertEquals(1L, telemetry().get("queuedClaims"));
        TexturePreparedResourceRuntime.exit(true);
    }

    @Test
    void unrequestedBeginAndUnadmittedPublicationsAreNoOps() {
        TexturePreparedResourceRuntime.begin(List.of(), getClass());
        BufferedImage image = image();
        assertSame(image, TexturePreparedResourceRuntime.publish(PATH, image));
        assertNull(TexturePreparedResourceRuntime.publish(PATH, null));
        assertEquals(false, telemetry().get("active"));
        assertEquals(0L, telemetry().get("published"));
    }

    @Test
    void ordinaryCompletionIsTakenOnceAndCommittedOnlyOnExit() throws Exception {
        activate(Thread.currentThread());
        BufferedImage image = publish();
        TexturePreparedResourceRuntime.enter(PATH, PATH);
        var completion = TexturePreparedResourceRuntime.take(PATH, null, null);
        assertNotNull(completion);
        assertSame(image, completion.image());
        assertEquals(TexturePreparedResourceRuntime.Kind.ORIGINAL_IMAGE, completion.kind());
        assertThrows(IllegalStateException.class, completion::creditOriginalFallback);
        assertNull(completion.prepare());
        assertEquals(1L, telemetry().get("coherent"));
        assertEquals(0L, telemetry().get("direct"));
        assertThrows(IllegalStateException.class, completion::prepare);
        completion.creditOriginalFallback();
        assertEquals(1L, telemetry().get("inFlight"));
        assertNull(TexturePreparedResourceRuntime.take(PATH, null, null));
        assertFalse(results.containsKey(PATH));
        assertEquals(0, telemetry().get("pending"));
        assertEquals(0L, telemetry().get("committed"));
        TexturePreparedResourceRuntime.exit(true);
        TexturePreparedResourceRuntime.exit(true);
        assertEquals(1L, telemetry().get("committed"));
        assertEquals(0L, telemetry().get("inFlight"));
        assertThrows(IllegalStateException.class, completion::creditOriginalFallback);
        assertThrows(IllegalStateException.class, completion::prepare);
    }

    @Test
    void preparedCompletionAllocatesOnlyOnMainCommitAndReleasesItsBuffer() throws Exception {
        BufferedImage carrier = carrier(2);
        activate(Thread.currentThread());
        TexturePreparedResourceRuntime.publish(PATH, carrier);
        results.put(PATH, carrier);
        assertEquals(0, TexturePreparedPixelRuntime.telemetry().get("activeBuffers"));
        TexturePreparedResourceRuntime.enter(PATH, PATH);
        var completion = TexturePreparedResourceRuntime.take(PATH, null, null);
        assertEquals(TexturePreparedResourceRuntime.Kind.PREPARED, completion.kind());
        var pixel = completion.prepare();
        assertNotNull(pixel);
        try {
            assertTrue(pixel.buffer().isDirect());
            assertEquals(12, pixel.buffer().remaining());
            assertEquals(1L, telemetry().get("direct"));
            assertEquals(0L, telemetry().get("coherent"));
            assertThrows(IllegalStateException.class, completion::prepare);
            assertEquals(1, TexturePreparedPixelRuntime.telemetry().get("activeBuffers"));
        } finally { TexturePreparedPixelRuntime.release(pixel.buffer()); }
        TexturePreparedResourceRuntime.exit(true);
        assertEquals(0, TexturePreparedPixelRuntime.telemetry().get("activeBuffers"));
        assertEquals(0L, telemetry().get("inFlight"));
        assertEquals(1L, telemetry().get("committed"));
    }

    @Test
    void npotCompletionUsesReadableCarrierAndCreditsCoherentFallbackOnce() throws Exception {
        BufferedImage carrier = carrier(3);
        activate(Thread.currentThread());
        TexturePreparedResourceRuntime.publish(PATH, carrier);
        results.put(PATH, carrier);
        TexturePreparedResourceRuntime.enter(PATH, PATH);
        var completion = TexturePreparedResourceRuntime.take(PATH, null, null);
        assertEquals(TexturePreparedResourceRuntime.Kind.PREPARED, completion.kind());
        assertNull(completion.prepare());
        assertSame(carrier, completion.image());
        assertEquals(3, carrier.getWidth());
        assertEquals(1L, telemetry().get("coherent"));
        assertEquals(0L, TextureCompatibilityRuntime.telemetry().get("hits"));
        completion.creditOriginalFallback();
        completion.creditOriginalFallback();
        assertEquals(1L, TextureCompatibilityRuntime.telemetry().get("hits"));
        assertEquals(0, TexturePreparedPixelRuntime.telemetry().get("activeBuffers"));
        TexturePreparedResourceRuntime.exit(true);
    }

    @Test
    void typedCompletionHonorsHard1024CeilingEvenWithCoherentDirectAndNpotAvailable() throws Exception {
        String unpadded = System.getProperty(TexturePaddingRuntime.UNPADDED_PROPERTY);
        String maximum = System.getProperty(TexturePaddingRuntime.MAX_UNPADDED_DIMENSION_PROPERTY);
        String coherentDirect = System.getProperty(TexturePreparedPixelRuntime.COHERENT_DIRECT_PROPERTY);
        System.setProperty(TexturePreparedPixelRuntime.COHERENT_DIRECT_PROPERTY, "true");
        TexturePaddingRuntime.beginSession();
        TexturePaddingRuntime.reset();
        GLContext.setCapabilities(true, false);
        System.setProperty(TexturePaddingRuntime.UNPADDED_PROPERTY, "true");
        System.setProperty(TexturePaddingRuntime.MAX_UNPADDED_DIMENSION_PROPERTY, "1024");
        TexturePaddingRuntime.foldBypassInstalled();
        try {
            assertTrue(TexturePaddingRuntime.enabled());
            // Height one also needs the stock minimum-two fold bypass at the inclusive boundary.
            for (int[] dimensions : new int[][] {{1025, 1}, {1, 1025}, {1024, 1}, {1, 1024}}) {
                int width = dimensions[0], height = dimensions[1];
                BufferedImage carrier = carrier(width, height);
                Field directFlag = carrier.getClass().getDeclaredField("coherentDirect");
                directFlag.setAccessible(true);
                assertTrue(directFlag.getBoolean(carrier), "exercise the Windows coherent-direct carrier");
                activate(Thread.currentThread());
                TexturePreparedResourceRuntime.publish(PATH, carrier);
                results.put(PATH, carrier);
                TexturePreparedResourceRuntime.enter(PATH, PATH);
                var completion = TexturePreparedResourceRuntime.take(PATH, null, null);
                assertNotNull(completion);
                assertEquals(TexturePreparedResourceRuntime.Kind.PREPARED, completion.kind());
                long hitsBefore = (long) TextureCompatibilityRuntime.telemetry().get("hits");
                var pixel = completion.prepare();
                if (width > 1024 || height > 1024) {
                    assertNull(pixel, "capability must not override the safety ceiling");
                    assertSame(carrier, completion.image());
                    assertEquals(width, completion.image().getWidth());
                    assertEquals(height, completion.image().getHeight());
                    assertEquals(0xff000000, completion.image().getRGB(width - 1, height - 1));
                    System.setProperty(TexturePreparedResourceRuntime.PACKED_CONVERTER_PROPERTY, "false");
                    try {
                        assertSame(carrier, completion.converterImage());
                    } finally {
                        System.clearProperty(TexturePreparedResourceRuntime.PACKED_CONVERTER_PROPERTY);
                    }
                    BufferedImage packed = completion.converterImage();
                    assertEquals(BufferedImage.TYPE_INT_ARGB, packed.getType());
                    assertEquals(0xff000000, packed.getRGB(width - 1, height - 1));
                    assertFalse(TexturePaddingRuntime.unpadded(), "coherent conversion keeps stock folds");
                    assertEquals(0, TexturePreparedPixelRuntime.telemetry().get("activeBuffers"));
                    assertEquals(hitsBefore, TextureCompatibilityRuntime.telemetry().get("hits"));
                    completion.creditOriginalFallback();
                    completion.creditOriginalFallback();
                } else {
                    assertNotNull(pixel);
                    try {
                        assertTrue(pixel.buffer().isDirect());
                        assertEquals(width, pixel.width());
                        assertEquals(height, pixel.height());
                        assertEquals(1024 * 3, pixel.buffer().remaining());
                        assertTrue(TexturePaddingRuntime.unpadded());
                        assertTrue(TexturePaddingRuntime.unpadded());
                        assertFalse(TexturePaddingRuntime.unpadded(), "only this upload's two folds are claimed");
                    } finally { TexturePreparedPixelRuntime.release(pixel.buffer()); }
                }
                assertEquals(hitsBefore + 1, TextureCompatibilityRuntime.telemetry().get("hits"));
                assertEquals("1024", System.getProperty(TexturePaddingRuntime.MAX_UNPADDED_DIMENSION_PROPERTY));
                assertEquals(1024, TexturePaddingRuntime.report().get("maxUnpaddedDimension"));
                TexturePreparedResourceRuntime.exit(true);
                TexturePreparedResourceRuntime.end();
            }
            assertEquals(2L, telemetry().get("coherent"));
            assertEquals(2L, telemetry().get("direct"));
            assertEquals(4L, telemetry().get("committed"));
            assertEquals(1024, telemetry().get("directDimensionCeiling"));
            assertEquals(2L, telemetry().get("ceilingDeclines"));
            assertEquals(0L, telemetry().get("inFlight"));
            assertEquals(0, TexturePreparedPixelRuntime.telemetry().get("activeBuffers"));
            assertEquals(0L, TexturePaddingRuntime.report().get("dimensionCeilingDeclines"),
                    "typed hard ceiling rejects before the prepared-pixel path or padding gate");
            assertEquals(2L, TexturePaddingRuntime.report().get("texturesServedUnpadded"));
            Map<?, ?> cold = (Map<?, ?>) TexturePreparedPixelRuntime.telemetry().get("coldProbe");
            assertEquals(0L, cold.get("originalDecodeStarts"));
            assertEquals(0L, cold.get("originalDecodeReturns"));
        } finally {
            TexturePreparedResourceRuntime.end();
            TexturePreparedPixelRuntime.releaseCurrentThreadBuffer();
            TexturePaddingRuntime.beginSession();
            TexturePaddingRuntime.reset();
            GLContext.reset();
            if (unpadded == null) System.clearProperty(TexturePaddingRuntime.UNPADDED_PROPERTY);
            else System.setProperty(TexturePaddingRuntime.UNPADDED_PROPERTY, unpadded);
            if (maximum == null) System.clearProperty(TexturePaddingRuntime.MAX_UNPADDED_DIMENSION_PROPERTY);
            else System.setProperty(TexturePaddingRuntime.MAX_UNPADDED_DIMENSION_PROPERTY, maximum);
            if (coherentDirect == null) System.clearProperty(TexturePreparedPixelRuntime.COHERENT_DIRECT_PROPERTY);
            else System.setProperty(TexturePreparedPixelRuntime.COHERENT_DIRECT_PROPERTY, coherentDirect);
        }
    }

    @Test
    void aliasesTransformsAndExistingHandlersLeaveTheStockResultUntouched() throws Exception {
        activate(Thread.currentThread());
        BufferedImage image = publish();
        TexturePreparedResourceRuntime.enter(PATH, "alias");
        assertNull(TexturePreparedResourceRuntime.take(PATH, null, null));
        TexturePreparedResourceRuntime.exit(false);
        TexturePreparedResourceRuntime.enter(PATH, PATH);
        assertNull(TexturePreparedResourceRuntime.take("graphics/./test.png", null, null));
        assertNull(TexturePreparedResourceRuntime.take(PATH, new Object(), null));
        assertNull(TexturePreparedResourceRuntime.take(PATH, null, new Object()));
        assertSame(image, results.get(PATH));
        assertEquals(1, telemetry().get("pending"));
        TexturePreparedResourceRuntime.exit(false);
        assertEquals(0L, telemetry().get("failures"));
    }

    @Test
    void workerCannotTakeOrPrepareMainThreadCompletion() throws Exception {
        activate(Thread.currentThread());
        publish();
        TexturePreparedResourceRuntime.enter(PATH, PATH);
        var completion = TexturePreparedResourceRuntime.take(PATH, null, null);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                TexturePreparedResourceRuntime.enter(PATH, PATH);
                assertNull(TexturePreparedResourceRuntime.take(PATH, null, null));
                assertThrows(IllegalStateException.class, completion::prepare);
            } catch (Throwable error) { failure.set(error); }
        });
        worker.start();
        worker.join(2_000);
        assertFalse(worker.isAlive());
        assertNull(failure.get());
        TexturePreparedResourceRuntime.exit(false);
        assertEquals(1L, telemetry().get("failures"));
    }

    @Test
    void originalConsumptionUsesImageIdentityAndEndDiscardsOnlyPendingResults() throws Exception {
        activate(Thread.currentThread());
        BufferedImage first = publish();
        TexturePreparedResourceRuntime.publish(PATH, image());
        assertEquals(1L, telemetry().get("published"));
        TexturePreparedResourceRuntime.originalConsumed(PATH, image());
        assertEquals(1, telemetry().get("pending"));
        TexturePreparedResourceRuntime.originalConsumed(PATH, first);
        TexturePreparedResourceRuntime.originalConsumed(PATH, first);
        assertEquals(1L, telemetry().get("originalConsumed"));
        publish();
        TexturePreparedResourceRuntime.end();
        TexturePreparedResourceRuntime.end();
        assertEquals(1L, telemetry().get("discarded"));
        assertEquals(0, telemetry().get("pending"));
        assertTrue(results.containsKey(PATH), "stock stop retains ownership of stock results");
        TexturePreparedResourceRuntime.publish(PATH, image());
        assertEquals(0, telemetry().get("pending"));
    }

    @Test
    void nestedRegistrationCannotConsumeOrRetireTheOuterCompletion() throws Exception {
        activate(Thread.currentThread());
        publish();
        TexturePreparedResourceRuntime.enter(PATH, PATH);
        var outer = TexturePreparedResourceRuntime.take(PATH, null, null);
        assertNotNull(outer);
        TexturePreparedResourceRuntime.enter(PATH, PATH);
        assertNull(TexturePreparedResourceRuntime.take(PATH, null, null));
        assertThrows(IllegalStateException.class, outer::prepare);
        TexturePreparedResourceRuntime.exit(false);
        assertEquals(1L, telemetry().get("inFlight"));
        assertEquals(0L, telemetry().get("failures"));
        assertNull(outer.prepare());
        TexturePreparedResourceRuntime.exit(true);
        assertEquals(0L, telemetry().get("inFlight"));
        assertEquals(1L, telemetry().get("committed"));
    }

    @Test
    void retiringWorkerCannotPublishIntoTheNextBatchOrRebindItself() throws Exception {
        CountDownLatch retiredMayPublish = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread retired = new Thread(() -> {
            try {
                assertTrue(retiredMayPublish.await(2, TimeUnit.SECONDS));
                TexturePreparedResourceRuntime.worker(Thread.currentThread());
                TexturePreparedResourceRuntime.publish(PATH, image());
            } catch (Throwable error) { failure.set(error); }
        });
        activate(Thread.currentThread());
        TexturePreparedResourceRuntime.worker(retired);
        retired.start();
        try {
            TexturePreparedResourceRuntime.end();
            activate(Thread.currentThread());
            retiredMayPublish.countDown();
            retired.join(2_000);
            assertFalse(retired.isAlive());
            assertNull(failure.get());
            assertEquals(0L, telemetry().get("published"));
            publish();
            assertEquals(1L, telemetry().get("published"));
        } finally { retiredMayPublish.countDown(); retired.interrupt(); retired.join(2_000); }
    }

    @Test
    void finishWorkerWaitsForStockQueueAndInFlightResultWithoutInterruptingOrConsuming() throws Exception {
        CountDownLatch queueChecked = new CountDownLatch(1);
        CountDownLatch inFlightChecked = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<String> observedQueue = Collections.synchronizedList(new ArrayList<>() {
            @Override public boolean isEmpty() {
                queueChecked.countDown();
                return super.isEmpty();
            }
        });
        Map<String, BufferedImage> observedResults = new ConcurrentHashMap<>() {
            @Override public boolean containsValue(Object value) {
                boolean contains = super.containsValue(value);
                if (contains) inFlightChecked.countDown();
                return contains;
            }
        };
        observedQueue.add(PATH);
        BufferedImage completed = image();
        activate(Thread.currentThread());
        field("stockQueue").set(null, observedQueue);
        field("stockResults").set(null, observedResults);
        Thread worker = new Thread(() -> {
            try {
                TexturePreparedResourceRuntime.finishWorker();
                assertEquals(true, telemetry().get("active"), "only the main thread may drain/end");
                assertTrue(queueChecked.await(2, TimeUnit.SECONDS));
                assertEquals(List.of(PATH), List.copyOf(observedQueue), "drain must not consume the queue");
                // Match the stock worker: publish the in-flight sentinel before removing its job.
                observedResults.put(PATH, sentinel);
                assertEquals(PATH, observedQueue.remove(0));
                assertTrue(inFlightChecked.await(2, TimeUnit.SECONDS),
                        "an empty queue must not end while the worker is still reading");
                assertEquals(true, telemetry().get("active"));
                assertFalse(Thread.currentThread().isInterrupted());
                TexturePreparedResourceRuntime.publish(PATH, completed);
                observedResults.put(PATH, completed);
                assertTrue(releaseWorker.await(2, TimeUnit.SECONDS));
            } catch (Throwable error) { failure.set(error); }
        });
        TexturePreparedResourceRuntime.worker(worker);
        worker.start();
        try {
            TexturePreparedResourceRuntime.finishWorker();
            assertNull(failure.get());
            assertTrue(worker.isAlive(), "drain ends when work finishes, without stopping the stock worker");
            assertFalse(worker.isInterrupted());
            assertTrue(observedQueue.isEmpty());
            assertEquals(Map.of(PATH, completed), observedResults, "stock stop still owns result retention");
            assertEquals(false, telemetry().get("active"));
            assertEquals(1L, telemetry().get("published"));
            assertEquals(1L, telemetry().get("discarded"));
            assertEquals(0, telemetry().get("pending"));
            assertEquals(0L, telemetry().get("workerDrainTimeouts"));
        } finally {
            releaseWorker.countDown();
            worker.join(2_000);
            if (worker.isAlive()) { worker.interrupt(); worker.join(2_000); }
            TexturePreparedResourceRuntime.end();
        }
        assertFalse(worker.isAlive());
        assertNull(failure.get());
    }

    @Test
    void stopRevokesAnAlreadyTakenCompletion() throws Exception {
        activate(Thread.currentThread());
        publish();
        TexturePreparedResourceRuntime.enter(PATH, PATH);
        var completion = TexturePreparedResourceRuntime.take(PATH, null, null);
        assertNotNull(completion);
        TexturePreparedResourceRuntime.end();
        assertThrows(IllegalStateException.class, completion::prepare,
                "a stopped session must not authorize a later GPU commit");
        assertThrows(IllegalStateException.class, completion::creditOriginalFallback);
        assertEquals(0L, telemetry().get("inFlight"));
        assertEquals(1L, telemetry().get("discarded"));
        TexturePreparedResourceRuntime.exit(false);
        TexturePreparedResourceRuntime.end();
        assertEquals(0L, telemetry().get("inFlight"));
        assertEquals(1L, telemetry().get("discarded"));
        assertEquals(0L, telemetry().get("failures"));
        assertEquals(0L, telemetry().get("committed"));
    }

    @Test
    void stoppedScopeDoesNotPreventTheNextBatchFromTakingACompletion() throws Exception {
        activate(Thread.currentThread());
        publish();
        TexturePreparedResourceRuntime.enter(PATH, PATH);
        assertNotNull(TexturePreparedResourceRuntime.take(PATH, null, null));
        TexturePreparedResourceRuntime.end();
        activate(Thread.currentThread());
        publish();
        TexturePreparedResourceRuntime.enter(PATH, PATH);
        assertNotNull(TexturePreparedResourceRuntime.take(PATH, null, null),
                "the preceding batch scope must be retired on stop");
    }

    @Test
    void conflictingStockResultFallsBackInsteadOfPollingForever() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        Thread main = new Thread(() -> {
            try {
                TexturePreparedResourceRuntime.enter(PATH, PATH);
                assertNull(TexturePreparedResourceRuntime.take(PATH, null, null));
            } catch (Throwable error) { failure.set(error); }
            finally { TexturePreparedResourceRuntime.exit(false); finished.countDown(); }
        });
        activate(main);
        publish();
        BufferedImage replacement = image();
        results.put(PATH, replacement);
        main.start();
        boolean returned;
        try { returned = finished.await(500, TimeUnit.MILLISECONDS); }
        finally { main.interrupt(); main.join(2_000); }
        assertFalse(main.isAlive());
        assertNull(failure.get());
        assertTrue(returned, "an ordinary ready stock image must fall back, not wait for replacement");
        assertSame(replacement, results.get(PATH));
    }

    @Test
    void inFlightWaitCanBeReleasedByPublication() throws Exception {
        CountDownLatch checkedStock = new CountDownLatch(1);
        Map<String, BufferedImage> observed = new ConcurrentHashMap<>() {
            @Override public BufferedImage get(Object key) {
                checkedStock.countDown();
                return super.get(key);
            }
        };
        activate(Thread.currentThread());
        field("stockResults").set(null, observed);
        observed.put(PATH, sentinel);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        BufferedImage image = image();
        Thread worker = new Thread(() -> {
            try {
                assertTrue(checkedStock.await(2, TimeUnit.SECONDS));
                TexturePreparedResourceRuntime.publish(PATH, image);
                observed.put(PATH, image);
            } catch (Throwable error) { failure.set(error); }
        });
        TexturePreparedResourceRuntime.worker(worker);
        worker.start();
        try {
            TexturePreparedResourceRuntime.enter(PATH, PATH);
            // A watchdog ensures a broken wait never strands the test JVM.
            Thread main = Thread.currentThread();
            Thread watchdog = new Thread(() -> {
                try { Thread.sleep(2_000); main.interrupt(); }
                catch (InterruptedException done) { }
            });
            watchdog.start();
            try { assertSame(image, TexturePreparedResourceRuntime.take(PATH, null, null).image()); }
            finally { watchdog.interrupt(); watchdog.join(2_000); }
        } finally { worker.interrupt(); worker.join(2_000); Thread.interrupted(); }
        assertFalse(worker.isAlive());
        assertNull(failure.get());
    }

    @Test
    void contractGateRejectsMissingOversizedAndChangedClassBytes() {
        for (byte[] bytes : List.of(new byte[0], new byte[1_048_577], new byte[] {1, 2, 3})) {
            assertFalse(TexturePreparedResourceRuntime.contractsMatch(new ClassLoader(null) {
                @Override public InputStream getResourceAsStream(String name) {
                    return new ByteArrayInputStream(bytes);
                }
            }));
        }
        assertFalse(TexturePreparedResourceRuntime.contractsMatch(new ClassLoader(null) { }));
    }

    @Test
    void installedWindowsContractsMatchAllSevenPinnedClasses() throws Exception {
        String common = System.getProperty("preflight.starsector.common.jar", "");
        String core = System.getProperty("preflight.starsector.core.jar", "");
        Assumptions.assumeTrue(!common.isBlank() && !core.isBlank(), "supply exact Windows JARs");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {
                Path.of(common).toUri().toURL(), Path.of(core).toUri().toURL()}, null)) {
            assertTrue(TexturePreparedResourceRuntime.contractsMatch(loader));
        }
    }

    @Test
    void installedBeginAdmitsMoreThan32768DuplicateRecordsAsOnePreparedObligation() throws Exception {
        BufferedImage carrier = carrier(2);
        System.setProperty(TexturePreparedResourceRuntime.PROPERTY, "true");
        try (URLClassLoader loader = installedAdmissionLoader()) {
            assertTrue(TexturePreparedResourceRuntime.contractsMatch(loader));
            Class<?> recordClass = Class.forName(
                    "com.fs.starfarer.loading.ResourceLoaderState$Oo", false, loader);
            Class<?> typeClass = Class.forName(
                    "com.fs.starfarer.loading.ResourceLoaderState$o", true, loader);
            Object textureType = java.util.Arrays.stream(typeClass.getEnumConstants())
                    .filter(value -> ((Enum<?>) value).name().equals("TEXTURE")).findFirst().orElseThrow();
            var constructor = recordClass.getDeclaredConstructor(typeClass, String.class, int.class);
            constructor.setAccessible(true);
            int records = 32_769;
            List<Object> resources = new ArrayList<>(records);
            for (int i = 0; i < records; i++) {
                resources.add(constructor.newInstance(textureType, PATH, 1));
            }
            // No reflection seeds runtime admission: begin reads the actual installed fields,
            // checks all seven raw class hashes, and resolves the configured prepared-cache key.
            TexturePreparedResourceRuntime.begin(resources, recordClass);
            assertEquals(true, telemetry().get("active"), telemetry().toString());
            assertEquals((long) records, telemetry().get("resourceRecords"));
            assertEquals(1L, telemetry().get("admitted"));
            assertEquals(0L, telemetry().get("declines"));
            assertEquals("none", telemetry().get("admissionDecline"));
            assertEquals(records, resources.size(), "admission must not change the stock records");
            assertEquals(32_768, TexturePreparedResourceRuntime.MAX_OBLIGATIONS);

            Class<?> preloader = Class.forName("com.fs.graphics.L", false, loader);
            Field resultsField = preloader.getDeclaredField("void");
            resultsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, BufferedImage> stock = (Map<String, BufferedImage>) resultsField.get(null);
            TexturePreparedResourceRuntime.worker(Thread.currentThread());
            TexturePreparedResourceRuntime.publish(PATH, carrier);
            TexturePreparedResourceRuntime.publish(PATH, carrier);
            assertEquals(1L, telemetry().get("published"));
            stock.put(PATH, carrier);
            TexturePreparedResourceRuntime.enter(PATH, PATH);
            var completion = TexturePreparedResourceRuntime.take(PATH, null, null);
            assertNotNull(completion);
            assertSame(carrier, completion.image());
            assertEquals(TexturePreparedResourceRuntime.Kind.PREPARED, completion.kind());
            assertFalse(stock.containsKey(PATH));
            TexturePreparedResourceRuntime.exit(false);
            assertEquals(0L, telemetry().get("inFlight"));
        } finally { TexturePreparedResourceRuntime.end(); }
    }

    @Test
    void rawRecordLimitStillRejectsOversizedAdmissionBeforeInspectingRecords() throws Exception {
        carrier(2);
        System.setProperty(TexturePreparedResourceRuntime.PROPERTY, "true");
        int records = TexturePreparedResourceRuntime.MAX_RESOURCE_RECORDS + 1;
        TexturePreparedResourceRuntime.begin(Collections.nCopies(records, new Object()), getClass());
        assertEquals(false, telemetry().get("active"));
        assertEquals((long) records, telemetry().get("resourceRecords"));
        assertEquals("resource-record-limit", telemetry().get("admissionDecline"));
        assertEquals(1L, telemetry().get("declines"));
        assertEquals(0L, telemetry().get("admitted"));
        assertEquals(0, telemetry().get("pending"));
    }

    private URLClassLoader installedAdmissionLoader() throws Exception {
        List<URL> urls = new ArrayList<>();
        for (String kind : List.of("common", "core")) {
            String configured = System.getProperty("preflight.starsector." + kind + ".jar", "");
            Assumptions.assumeTrue(!configured.isBlank(), "supply exact Windows " + kind + " JAR");
            urls.add(Path.of(configured).toUri().toURL());
        }
        String shared = System.getProperty("preflight.starsector.shared.java.dir", "");
        if (!shared.isBlank()) {
            try (var files = Files.list(Path.of(shared))) {
                for (Path jar : files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".jar"))
                        .filter(path -> !path.getFileName().toString().equals("starfarer_obf.jar"))
                        .filter(path -> !path.getFileName().toString().equals("fs.common_obf.jar"))
                        .filter(path -> !path.getFileName().toString().endsWith("-sources.jar"))
                        .sorted().toList()) urls.add(jar.toUri().toURL());
            }
        }
        return new URLClassLoader(urls.toArray(URL[]::new), getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                // Admission must use installed records/preloader, with their own logging classes,
                // rather than parent test doubles with the same binary names.
                if (name.startsWith("com.fs.") || name.startsWith("org.apache.log4j.")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null) loaded = findClass(name);
                        if (resolve) resolveClass(loaded);
                        return loaded;
                    }
                }
                return super.loadClass(name, resolve);
            }
        };
    }

    private BufferedImage carrier(int size) throws Exception {
        return carrier(size, size);
    }

    private BufferedImage carrier(int width, int height) throws Exception {
        String hash = "ab".repeat(32);
        Path cache = temporaryDirectory.resolve("cache");
        String relative = "blobs/ab/" + hash + "-identity.spft";
        PreparedTexture texture = new PreparedTexture(hash, PreparedTexture.Transformation.IDENTITY,
                width, height, width, height, 3, 0, 0, 0, new byte[width * height * 3]);
        PreparedTextureIO.write(cache.resolve(relative), texture);
        Path manifest = cache.resolve("manifest.spfm");
        TextureManifestIO.write(manifest, new TextureManifest(hash, Map.of(PATH,
                new TextureManifest.Entry(hash, PreparedTexture.Transformation.IDENTITY,
                        relative, width, height, 3, width * height * 3))));
        Path index = cache.resolve("index.spfi");
        ResourceIndexIO.write(index, new ResourceIndex(hash, List.of(new ResourceIndex.Root("core", temporaryDirectory, true)), Map.of()));
        System.setProperty(TextureCompatibilityRuntime.TRUST_VALIDATED_INDEX_PROPERTY, "true");
        assertTrue(TextureCompatibilityRuntime.configure(cache, manifest, index));
        TexturePreparedPixelRuntime.select(TextureAdapterMode.PREPARED_PIXELS);
        BufferedImage carrier = TexturePreparedPixelRuntime.load(PATH);
        assertTrue(TexturePreparedPixelRuntime.isCarrier(carrier));
        return carrier;
    }

    private BufferedImage publish() {
        BufferedImage image = image();
        assertSame(image, TexturePreparedResourceRuntime.publish(PATH, image));
        results.put(PATH, image);
        return image;
    }

    // Isolate runtime ownership from installed-game initialization and GPU dependencies. The exact
    // installed class bytes are separately checked above; these are the stock synchronized shapes.
    @SuppressWarnings("unchecked")
    private void activate(Thread main) throws Exception {
        Map<String, TexturePreparedResourceRuntime.Obligation> obligations =
                (Map<String, TexturePreparedResourceRuntime.Obligation>) field("OBLIGATIONS").get(null);
        obligations.put(PATH, new TexturePreparedResourceRuntime.Obligation(
                "TEXTURE", PATH, PATH, PATH, 10));
        field("mainThread").set(null, Thread.currentThread());
        field("stockQueue").set(null, queue);
        field("stockResults").set(null, results);
        field("stockSentinel").set(null, sentinel);
        field("active").setBoolean(null, true);
        field("workerImagePhase").setBoolean(null, true);
        TexturePreparedResourceRuntime.worker(Thread.currentThread());
        field("mainThread").set(null, main);
    }

    private static Field field(String name) throws Exception {
        Field field = TexturePreparedResourceRuntime.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static BufferedImage image() { return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB); }
    private static Map<String, Object> telemetry() { return TexturePreparedResourceRuntime.telemetry(); }
}
