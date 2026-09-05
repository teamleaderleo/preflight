package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TexturePreparedStagingLifecycleTest {
    @TempDir Path directory;

    @AfterEach void reset() { TexturePreparedStagingRuntime.beginSession(); }

    @Test
    void stoppingProducerDoesNotInterruptItsBorrowedChannel() throws Exception {
        Path file = directory.resolve("pack");
        Files.write(file, new byte[] {42});
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (FileChannel channel = FileChannel.open(file)) {
            Thread worker = new Thread(() -> {
                entered.countDown();
                try {
                    try { proceed.await(); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    assertEquals(1, channel.read(ByteBuffer.allocate(1), 0));
                } catch (Throwable error) { failure.set(error); }
            });
            producer().set(null, worker);
            worker.start();
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                TexturePreparedStagingRuntime.stop();
            } finally {
                proceed.countDown();
                worker.join(2000);
            }
            assertFalse(worker.isAlive());
            assertNull(failure.get());
            assertTrue(channel.isOpen());
        }
    }

    @Test
    void previousSessionProducerCannotPublishIntoReplacementSession() throws Exception {
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread old = new Thread(() -> {
            try {
                proceed.await();
                var publish = TexturePreparedStagingRuntime.class.getDeclaredMethod(
                        "publish", String.class, BufferedImage.class, long.class);
                publish.setAccessible(true);
                publish.invoke(null, "old-identity", new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), 3L);
            } catch (Throwable error) { failure.set(error); }
        });
        producer().set(null, old);
        old.start();
        try {
            TexturePreparedStagingRuntime.beginSession();
            producer().set(null, new Thread(() -> { }));
        } finally {
            proceed.countDown();
            old.join(2000);
        }
        assertFalse(old.isAlive());
        assertNull(failure.get());
        assertEquals(0, TexturePreparedStagingRuntime.telemetry().get("queuedEntries"));
        assertEquals(0L, TexturePreparedStagingRuntime.telemetry().get("queuedBytes"));
        assertEquals(0L, TexturePreparedStagingRuntime.telemetry().get("stagedEntries"));
    }

    private static Field producer() throws Exception {
        Field field = TexturePreparedStagingRuntime.class.getDeclaredField("producer");
        field.setAccessible(true);
        return field;
    }
}
