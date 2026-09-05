package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class TexturePrefetchShutdownRuntimeTest {
    @TempDir Path root;

    @BeforeEach void reset() { TexturePrefetchShutdownRuntime.reset(); }

    @AfterEach void cleanup() {
        Thread.interrupted();
        System.clearProperty(TexturePrefetchShutdownRuntime.PROPERTY);
        System.clearProperty(TexturePreparedPrefetchPlan.WINDOWS_KALEIDOSCOPE_PROPERTY);
        System.clearProperty(TexturePreparedPrefetchPlan.WINDOWS_WORKERS_PROPERTY);
        System.clearProperty(TexturePreparedResourceRuntime.PROPERTY);
    }

    @Test void finiteWorkerPublishesBeforeStockInterruptAndLeavesSharedChannelOpen() throws Exception {
        Path file = root.resolve("pack");
        Files.write(file, new byte[] {42});
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Integer> published = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            Thread worker = new Thread(() -> {
                entered.countDown();
                try {
                    Thread.sleep(30);
                    ByteBuffer bytes = ByteBuffer.allocate(1);
                    channel.read(bytes, 0);
                    published.set((int) bytes.get(0));
                } catch (Throwable error) { failure.set(error); }
            });
            worker.start();
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            TexturePrefetchShutdownRuntime.await(worker, 2_000);
            worker.interrupt(); // Original shutdown still runs after the injected call.
            worker.join(2_000);
            assertNull(failure.get());
            assertEquals(42, published.get());
            assertTrue(channel.isOpen());
            assertEquals(1, channel.read(ByteBuffer.allocate(1), 0));
            assertEquals(1L, TexturePrefetchShutdownRuntime.report().get("completed"));
        }
    }

    @Test void timeoutLeavesWorkerForStockCancellation() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try { release.await(); } catch (InterruptedException ignored) { }
        });
        worker.start();
        try {
            TexturePrefetchShutdownRuntime.await(worker, 20);
            assertTrue(worker.isAlive());
            assertFalse(worker.isInterrupted());
            assertEquals(1L, TexturePrefetchShutdownRuntime.report().get("timeouts"));
        } finally { release.countDown(); worker.join(2_000); }
    }

    @Test void callerCancellationIsPreservedAndSelfJoinDeclines() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try { release.await(); } catch (InterruptedException ignored) { }
        });
        worker.start();
        try {
            Thread.currentThread().interrupt();
            TexturePrefetchShutdownRuntime.await(worker, 2_000);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1L, TexturePrefetchShutdownRuntime.report().get("interrupted"));
        } finally { Thread.interrupted(); release.countDown(); worker.join(2_000); }
        TexturePrefetchShutdownRuntime.await(Thread.currentThread(), 2_000);
        assertEquals(1L, TexturePrefetchShutdownRuntime.report().get("declined"));
    }

    @Test void defaultAppliesOnlyToSingleWorkerLateResourcePathAndCanBeDisabled() {
        assertFalse(TexturePrefetchShutdownRuntime.enabled());
        System.setProperty(TexturePreparedPrefetchPlan.WINDOWS_KALEIDOSCOPE_PROPERTY, "true");
        assertTrue(TexturePrefetchShutdownRuntime.enabled());
        System.setProperty(TexturePreparedPrefetchPlan.WINDOWS_WORKERS_PROPERTY, "2");
        assertFalse(TexturePrefetchShutdownRuntime.enabled());
        System.clearProperty(TexturePreparedPrefetchPlan.WINDOWS_WORKERS_PROPERTY);
        System.setProperty(TexturePreparedResourceRuntime.PROPERTY, "true");
        assertFalse(TexturePrefetchShutdownRuntime.enabled());
        System.clearProperty(TexturePreparedResourceRuntime.PROPERTY);
        System.setProperty(TexturePrefetchShutdownRuntime.PROPERTY, "false");
        TexturePrefetchShutdownRuntime.finish(new Thread());
        assertEquals(0L, TexturePrefetchShutdownRuntime.report().get("calls"));
    }
}
