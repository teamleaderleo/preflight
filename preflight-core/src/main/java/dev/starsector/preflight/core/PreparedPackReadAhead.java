package dev.starsector.preflight.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Map;

/** Bounded borrowed read window for one exact entry of an open prepared pack. Entry CRCs are
 * still verified by the caller. It never speculates into neighboring entries.
 */
final class PreparedPackReadAhead extends FileChannel {
    static final int WINDOW_BYTES = 4 * 1024 * 1024;
    private final FileChannel source;
    private final long fileBytes;
    private byte[] window;
    private long start = -1;
    private int length;
    private long entryEnd;
    private long entryStart;
    private boolean largeEntry;
    private long fills, fileReads, fileReadNanos, bytesRead, hits, bypasses, checksumNanos;

    PreparedPackReadAhead(FileChannel source, long fileBytes) {
        this.source = source;
        this.fileBytes = fileBytes;
        this.entryEnd = fileBytes;
    }

    /** Caller holds this monitor across the complete entry parse/CRC, so concurrent consumers
     * cannot change each other's range. No pixels returned by parsing alias this scratch.
     */
    synchronized void beginEntry(long offset, int bytes) throws IOException {
        if (offset < 0 || bytes <= 0 || offset > fileBytes - bytes) {
            throw new IOException("Prepared pack entry range is invalid");
        }
        start = -1;
        length = 0;
        entryEnd = offset + bytes;
        entryStart = offset;
        largeEntry = bytes > WINDOW_BYTES;
    }

    @Override
    public synchronized int read(ByteBuffer destination, long position) throws IOException {
        if (!isOpen() || !source.isOpen()) throw new ClosedChannelException();
        // Cached reads must not evade the shared FileChannel's interrupt/close contract.
        if (Thread.currentThread().isInterrupted()) {
            source.close();
            throw new ClosedByInterruptException();
        }
        if (position < 0) throw new IllegalArgumentException("Negative pack position");
        if (!destination.hasRemaining()) return 0;
        if (position < entryStart) throw new IOException("Prepared pack read escaped its entry");
        if (position >= entryEnd) return -1;
        if (largeEntry || destination.remaining() > WINDOW_BYTES) {
            bypasses++;
            int limit = destination.limit();
            destination.limit(destination.position() + (int) Math.min(destination.remaining(), entryEnd - position));
            try { return readSource(destination, position); }
            finally { destination.limit(limit); }
        }
        if (position < start || position >= start + length) {
            length = 0;
            start = -1;
            if (window == null) window = new byte[WINDOW_BYTES];
            ByteBuffer target = ByteBuffer.wrap(window, 0,
                    (int) Math.min(WINDOW_BYTES, entryEnd - position));
            while (target.hasRemaining()) {
                int count = readSource(target, position + target.position());
                if (count < 0) break;
                if (count == 0) throw new IOException("Prepared pack read-ahead made no progress");
            }
            start = position;
            length = target.position();
            fills++;
        } else {
            hits++;
        }
        if (length == 0) return -1;
        int offset = Math.toIntExact(position - start);
        int count = Math.min(destination.remaining(), length - offset);
        destination.put(window, offset, count);
        return count;
    }

    private int readSource(ByteBuffer destination, long position) throws IOException {
        long started = System.nanoTime();
        try {
            int count = source.read(destination, position);
            if (count > 0) bytesRead += count;
            return count;
        } finally { fileReads++; fileReadNanos += Math.max(0L, System.nanoTime() - started); }
    }

    synchronized Map<String, Object> telemetry() {
        return Map.of("enabled", true, "windowBytes", window == null ? 0 : window.length,
                "fills", fills, "fileReads", fileReads, "fileReadMillis", fileReadNanos / 1_000_000L,
                "bytesRead", bytesRead, "hits", hits, "largeReadBypasses", bypasses,
                "checksumMillis", checksumNanos / 1_000_000L);
    }

    synchronized void checksumTime(long nanos) { checksumNanos += nanos; }

    @Override protected synchronized void implCloseChannel() { window = null; start = -1; length = 0; }
    private static IOException positionedOnly() { return new IOException("Prepared pack requires positioned reads"); }
    @Override public int read(ByteBuffer dst) throws IOException { throw positionedOnly(); }
    @Override public long read(ByteBuffer[] dst, int offset, int count) throws IOException { throw positionedOnly(); }
    @Override public int write(ByteBuffer src) { throw new NonWritableChannelException(); }
    @Override public long write(ByteBuffer[] src, int offset, int count) { throw new NonWritableChannelException(); }
    @Override public int write(ByteBuffer src, long position) { throw new NonWritableChannelException(); }
    @Override public long position() throws IOException { throw positionedOnly(); }
    @Override public FileChannel position(long position) throws IOException { throw positionedOnly(); }
    @Override public long size() throws IOException { return source.size(); }
    @Override public FileChannel truncate(long size) { throw new NonWritableChannelException(); }
    @Override public void force(boolean metadata) throws IOException { throw positionedOnly(); }
    @Override public long transferTo(long position, long count, WritableByteChannel target) throws IOException { throw positionedOnly(); }
    @Override public long transferFrom(ReadableByteChannel src, long position, long count) { throw new NonWritableChannelException(); }
    @Override public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException { throw positionedOnly(); }
    @Override public FileLock lock(long position, long size, boolean shared) throws IOException { throw positionedOnly(); }
    @Override public FileLock tryLock(long position, long size, boolean shared) throws IOException { throw positionedOnly(); }
}
