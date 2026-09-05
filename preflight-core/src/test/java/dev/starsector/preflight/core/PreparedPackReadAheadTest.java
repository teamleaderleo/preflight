package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedPackReadAheadTest {
    @TempDir Path directory;

    @Test
    void positionedReadsRemainByteExactAcrossHitsRefillsAndLargeBypass() throws Exception {
        byte[] bytes = new byte[PreparedPackReadAhead.WINDOW_BYTES + 513];
        new Random(42).nextBytes(bytes);
        Path file = directory.resolve("pack");
        Files.write(file, bytes);
        try (FileChannel source = FileChannel.open(file);
                PreparedPackReadAhead read = new PreparedPackReadAhead(source, bytes.length)) {
            for (int[] request : new int[][] {{0, 7}, {7, 4096}, {bytes.length - 800, 800},
                    {0, bytes.length}, {3, 123}, {100, 67}}) {
                ByteBuffer dst = ByteBuffer.allocate(request[1] + 11);
                dst.position(5).limit(5 + request[1]);
                long offset = request[0];
                while (dst.hasRemaining()) {
                    int count = read.read(dst, offset);
                    assertTrue(count > 0);
                    offset += count;
                }
                assertArrayEquals(Arrays.copyOfRange(bytes, request[0], request[0] + request[1]),
                        Arrays.copyOfRange(dst.array(), 5, 5 + request[1]));
            }
            assertEquals(0, source.position());
            assertEquals(-1, read.read(ByteBuffer.allocate(1), bytes.length));
            assertEquals(0, read.read(ByteBuffer.allocate(0), 0));
            assertTrue((long) read.telemetry().get("hits") > 0);
            assertTrue((long) read.telemetry().get("largeReadBypasses") > 0);
            read.close();
            assertTrue(source.isOpen(), "borrowed window does not own channel close");
            assertEquals(0, read.telemetry().get("windowBytes"));
            assertThrows(ClosedChannelException.class, () -> read.read(ByteBuffer.allocate(1), 0));
        }
    }

    @Test
    void exactEntryReadsDoNotFetchNeighborsOrReuseAnEarlierEntrySnapshot() throws Exception {
        Path file = directory.resolve("ranges");
        Files.write(file, new byte[1024]);
        try (FileChannel source = FileChannel.open(file, java.nio.file.StandardOpenOption.READ,
                    java.nio.file.StandardOpenOption.WRITE);
                PreparedPackReadAhead read = new PreparedPackReadAhead(source, 1024)) {
            read.beginEntry(100, 20);
            read.read(ByteBuffer.allocate(4), 100);
            read.read(ByteBuffer.allocate(16), 104);
            assertEquals(20L, read.telemetry().get("bytesRead"));
            assertEquals(1L, read.telemetry().get("fileReads"));
            source.write(ByteBuffer.wrap(new byte[] {42}), 100);
            read.beginEntry(100, 20);
            ByteBuffer changed = ByteBuffer.allocate(1);
            read.read(changed, 100);
            assertEquals(42, changed.get(0));
            assertEquals(40L, read.telemetry().get("bytesRead"));
        }
    }

    @Test
    void packCloseClosesSourceBeforeWaitingForScratchOwner() throws Exception {
        String previous = System.getProperty(PreparedTexturePack.READ_AHEAD_PROPERTY);
        System.setProperty(PreparedTexturePack.READ_AHEAD_PROPERTY, "true");
        Path file = directory.resolve("closing");
        Files.write(file, new byte[64]);
        try (FileChannel source = FileChannel.open(file);
                PreparedTexturePack pack = new PreparedTexturePack(file, "ab".repeat(32), source,
                        64, 0, java.util.Map.of("blob", new PreparedTexturePack.Range(0, 64, 0)))) {
            var field = PreparedTexturePack.class.getDeclaredField("readAhead");
            field.setAccessible(true);
            Object scratch = field.get(pack);
            var started = new java.util.concurrent.CountDownLatch(1);
            var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread closer = new Thread(() -> {
                started.countDown();
                try { pack.close(); } catch (Throwable failure) { error.set(failure); }
            });
            boolean sourceClosed;
            synchronized (scratch) {
                closer.start();
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (source.isOpen() && System.nanoTime() < deadline) Thread.sleep(1);
                sourceClosed = !source.isOpen();
            }
            closer.join(5_000);
            assertFalse(closer.isAlive());
            assertNull(error.get());
            assertTrue(sourceClosed, "a reader holding scratch must not prevent source cancellation");
        } finally {
            if (previous == null) System.clearProperty(PreparedTexturePack.READ_AHEAD_PROPERTY);
            else System.setProperty(PreparedTexturePack.READ_AHEAD_PROPERTY, previous);
        }
    }

    @Test
    void cachedHitDoesNotHideClosedChannelOrInterruption() throws Exception {
        Path file = directory.resolve("pack");
        Files.write(file, new byte[128]);
        try (FileChannel source = FileChannel.open(file);
                PreparedPackReadAhead read = new PreparedPackReadAhead(source, 128)) {
            read.read(ByteBuffer.allocate(1), 0);
            Thread.currentThread().interrupt();
            try {
                assertThrows(ClosedByInterruptException.class, () -> read.read(ByteBuffer.allocate(1), 1));
                assertFalse(source.isOpen());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
            assertThrows(ClosedChannelException.class, () -> read.read(ByteBuffer.allocate(1), 1));
        }
    }

    @Test
    void readAheadPreservesPackIntegrityChecksAndRawLz4Pixels() throws Exception {
        String previous = System.getProperty(PreparedTexturePack.READ_AHEAD_PROPERTY);
        System.setProperty(PreparedTexturePack.READ_AHEAD_PROPERTY, "true");
        try {
            PreparedTexturePackIntegrityTest integrity = new PreparedTexturePackIntegrityTest();
            integrity.temporaryDirectory = directory.resolve("integrity");
            Files.createDirectories(integrity.temporaryDirectory);
            integrity.sameLengthPackedRawPixelMutationRejectsWhileLooseBlobRemainsValid();
            integrity.mutationAfterPackOpenIsRejectedAtServingBoundary();
            integrity.corruptedEmbeddedLz4ChecksumRejects();
            PreparedTexturePackIOTest roundtrip = new PreparedTexturePackIOTest();
            roundtrip.temporaryDirectory = directory.resolve("roundtrip");
            Files.createDirectories(roundtrip.temporaryDirectory);
            roundtrip.roundTripsRawAndCompressedBlobsThroughOneSharedChannel();
        } finally {
            if (previous == null) System.clearProperty(PreparedTexturePack.READ_AHEAD_PROPERTY);
            else System.setProperty(PreparedTexturePack.READ_AHEAD_PROPERTY, previous);
        }
    }
}
