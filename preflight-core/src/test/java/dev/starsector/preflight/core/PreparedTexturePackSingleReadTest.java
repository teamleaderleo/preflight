package dev.starsector.preflight.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedTexturePackSingleReadTest {
    @TempDir Path directory;

    @Test
    void wholeLz4EntryUsesOneReadAndDoesNotReadNeighbors() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(PreparedTextureIO.singleReadLz4Enabled());
        Fixture fixture = fixture();
        try (CountingChannel channel = fixture.open(Integer.MAX_VALUE)) {
            PreparedTexture result = read(channel, fixture);
            assertArrayEquals(fixture.pixels(), result.pixels());
            assertEquals(1, channel.calls);
            assertEquals(fixture.bytes().length, channel.bytesRead);
        }
    }

    @Test
    void shortReadsStillCoverEveryByteExactlyOnce() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(PreparedTextureIO.singleReadLz4Enabled());
        Fixture fixture = fixture();
        try (CountingChannel channel = fixture.open(7)) {
            assertArrayEquals(fixture.pixels(), read(channel, fixture).pixels());
            assertEquals(fixture.bytes().length, channel.bytesRead);
            assertEquals((fixture.bytes().length + 6) / 7, channel.calls);
        }
    }

    @Test
    void changedTrailerAndTruncatedEntryAreRejected() throws Exception {
        Fixture fixture = fixture();
        byte[] changed = fixture.bytes().clone();
        changed[changed.length - 1] ^= 1;
        try (FileChannel writer = FileChannel.open(fixture.path(), StandardOpenOption.WRITE)) {
            writer.write(ByteBuffer.wrap(changed), 13);
        }
        try (CountingChannel channel = fixture.open(Integer.MAX_VALUE)) {
            IOException failure = assertThrows(IOException.class, () -> read(channel, fixture));
            org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("CRC32C mismatch"));
        }
        try (FileChannel writer = FileChannel.open(fixture.path(), StandardOpenOption.WRITE)) {
            writer.truncate(13 + changed.length - 1);
        }
        try (CountingChannel channel = fixture.open(Integer.MAX_VALUE)) {
            assertThrows(IOException.class, () -> read(channel, fixture));
        }
    }

    private PreparedTexture read(CountingChannel channel, Fixture fixture) throws IOException {
        return PreparedTexturePackIntegrity.readTrusted(
                channel, 13, fixture.bytes().length, fixture.crc(), "entry-lz4.spft");
    }

    private Fixture fixture() throws IOException {
        byte[] pixels = new byte[32 * 32 * 4];
        java.util.Arrays.fill(pixels, (byte) 123);
        PreparedTexture texture = new PreparedTexture("01".repeat(32),
                PreparedTexture.Transformation.IDENTITY, 32, 32, 32, 32, 4, 1, 2, 3, pixels);
        Path blob = directory.resolve("entry-lz4.spft");
        PreparedTextureIO.write(blob, texture, PreparedTextureIO.StorageCodec.LZ4);
        byte[] bytes = Files.readAllBytes(blob);
        CRC32C crc = new CRC32C();
        crc.update(bytes);
        Path pack = directory.resolve("pack");
        ByteBuffer packed = ByteBuffer.allocate(13 + bytes.length + 19);
        packed.position(13);
        packed.put(bytes);
        Files.write(pack, packed.array());
        return new Fixture(pack, bytes, pixels, (int) crc.getValue());
    }

    private record Fixture(Path path, byte[] bytes, byte[] pixels, int crc) {
        CountingChannel open(int maxRead) throws IOException {
            return new CountingChannel(FileChannel.open(path), maxRead);
        }
    }

    private static final class CountingChannel extends FileChannel {
        private final FileChannel source;
        private final int maxRead;
        int calls;
        long bytesRead;

        CountingChannel(FileChannel source, int maxRead) {
            this.source = source;
            this.maxRead = maxRead;
        }

        @Override public int read(ByteBuffer destination, long position) throws IOException {
            calls++;
            int limit = destination.limit();
            destination.limit(destination.position() + Math.min(destination.remaining(), maxRead));
            try {
                int count = source.read(destination, position);
                bytesRead += Math.max(0, count);
                return count;
            } finally {
                destination.limit(limit);
            }
        }
        @Override protected void implCloseChannel() throws IOException { source.close(); }
        @Override public long size() throws IOException { return source.size(); }
        @Override public int read(ByteBuffer dst) { throw new UnsupportedOperationException(); }
        @Override public long read(ByteBuffer[] dsts, int offset, int length) { throw new UnsupportedOperationException(); }
        @Override public int write(ByteBuffer src) { throw new UnsupportedOperationException(); }
        @Override public long write(ByteBuffer[] srcs, int offset, int length) { throw new UnsupportedOperationException(); }
        @Override public int write(ByteBuffer src, long position) { throw new UnsupportedOperationException(); }
        @Override public long position() { throw new UnsupportedOperationException(); }
        @Override public FileChannel position(long position) { throw new UnsupportedOperationException(); }
        @Override public FileChannel truncate(long size) { throw new UnsupportedOperationException(); }
        @Override public void force(boolean metadata) { throw new UnsupportedOperationException(); }
        @Override public long transferTo(long position, long count, WritableByteChannel target) { throw new UnsupportedOperationException(); }
        @Override public long transferFrom(ReadableByteChannel src, long position, long count) { throw new UnsupportedOperationException(); }
        @Override public MappedByteBuffer map(MapMode mode, long position, long size) { throw new UnsupportedOperationException(); }
        @Override public FileLock lock(long position, long size, boolean shared) { throw new UnsupportedOperationException(); }
        @Override public FileLock tryLock(long position, long size, boolean shared) { throw new UnsupportedOperationException(); }
    }
}
