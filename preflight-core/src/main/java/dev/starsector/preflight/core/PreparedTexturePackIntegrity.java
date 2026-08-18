package dev.starsector.preflight.core;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.zip.CRC32C;
import java.util.zip.Checksum;

/** Integrity helpers for SPFT blobs embedded in a prepared texture pack. */
final class PreparedTexturePackIntegrity {
    static final int COPY_BUFFER_BYTES = 64 * 1024;
    private static final byte[] SPFT_MAGIC = {'S', 'P', 'F', 'T'};
    private static final int SPFT_PREFIX_BYTES = SPFT_MAGIC.length + Integer.BYTES * 2;
    private static final int SPFT_CHECKSUM_BYTES = 32;

    private PreparedTexturePackIntegrity() {
    }

    static PreparedTexture readTrusted(
            FileChannel channel,
            long offset,
            int length,
            int expectedCrc32c,
            String sourceLabel) throws IOException {
        if (length < SPFT_PREFIX_BYTES + SPFT_CHECKSUM_BYTES) {
            throw new IOException("Prepared texture pack entry is too small: " + sourceLabel);
        }
        CRC32C crc32c = new CRC32C();
        long end = Math.addExact(offset, length);
        ChecksumChannel verifying = new ChecksumChannel(channel, offset, end, crc32c);
        PreparedTexture texture = PreparedTextureIO.readTrusted(
                verifying, offset, length, sourceLabel);

        ByteBuffer checksum = ByteBuffer.allocate(SPFT_CHECKSUM_BYTES);
        readFully(
                verifying,
                end - SPFT_CHECKSUM_BYTES,
                checksum,
                "Prepared texture pack ended inside its embedded checksum");
        long actual = verifying.finish();
        long expected = Integer.toUnsignedLong(expectedCrc32c);
        if (actual != expected) {
            throw new IOException("Prepared texture pack entry CRC32C mismatch: " + sourceLabel);
        }
        return texture;
    }

    /**
     * Copies one complete SPFT into the pack while verifying its embedded SHA-256 and returning a
     * CRC32C over the exact complete bytes copied. The CRC includes the SPFT prefix, payload, and
     * trailing SHA-256 so every byte later served by the pack is covered by the authenticated
     * per-entry index value.
     */
    static int copyVerifiedSpft(
            FileChannel input,
            int expectedLength,
            FileChannel output,
            ByteBuffer copyBuffer,
            String sourceLabel) throws IOException {
        if (expectedLength < SPFT_PREFIX_BYTES + SPFT_CHECKSUM_BYTES
                || input.size() != expectedLength) {
            throw new IOException("Prepared texture blob length changed while packing: " + sourceLabel);
        }

        CRC32C crc32c = new CRC32C();
        MessageDigest payloadSha256 = newSha256();

        ByteBuffer prefix = ByteBuffer.allocate(SPFT_PREFIX_BYTES).order(ByteOrder.BIG_ENDIAN);
        readFully(input, 0, prefix, "Prepared texture blob ended inside its prefix");
        prefix.flip();
        update(crc32c, prefix);
        writeFully(output, prefix.asReadOnlyBuffer());

        byte[] magic = new byte[SPFT_MAGIC.length];
        prefix.get(magic);
        if (!Arrays.equals(SPFT_MAGIC, magic)) {
            throw new IOException("Prepared texture blob magic header is invalid: " + sourceLabel);
        }
        int version = prefix.getInt();
        if (version != PreparedTexture.FORMAT_VERSION) {
            throw new IOException("Unsupported prepared texture version " + version + ": " + sourceLabel);
        }
        int payloadLength = prefix.getInt();
        long total = SPFT_PREFIX_BYTES + (long) payloadLength + SPFT_CHECKSUM_BYTES;
        if (payloadLength < 0 || total != expectedLength) {
            throw new IOException("Prepared texture blob payload length is invalid: " + sourceLabel);
        }

        long position = SPFT_PREFIX_BYTES;
        long remaining = payloadLength;
        while (remaining > 0) {
            copyBuffer.clear();
            copyBuffer.limit((int) Math.min(copyBuffer.capacity(), remaining));
            int read = input.read(copyBuffer, position);
            if (read < 0) {
                throw new EOFException("Prepared texture blob ended inside its payload: " + sourceLabel);
            }
            if (read == 0) {
                throw new IOException("Prepared texture blob read made no progress: " + sourceLabel);
            }
            position += read;
            remaining -= read;
            copyBuffer.flip();
            ByteBuffer bytes = copyBuffer.asReadOnlyBuffer();
            payloadSha256.update(bytes.duplicate());
            update(crc32c, bytes);
            writeFully(output, bytes);
        }

        ByteBuffer checksum = ByteBuffer.allocate(SPFT_CHECKSUM_BYTES);
        readFully(input, position, checksum, "Prepared texture blob ended inside its checksum");
        checksum.flip();
        byte[] expectedPayloadSha256 = new byte[SPFT_CHECKSUM_BYTES];
        checksum.duplicate().get(expectedPayloadSha256);
        update(crc32c, checksum);
        writeFully(output, checksum.asReadOnlyBuffer());
        if (!MessageDigest.isEqual(expectedPayloadSha256, payloadSha256.digest())) {
            throw new IOException("Prepared texture blob checksum mismatch while packing: " + sourceLabel);
        }
        if (input.size() != expectedLength) {
            throw new IOException("Prepared texture blob length changed while packing: " + sourceLabel);
        }
        return (int) crc32c.getValue();
    }

    private static void update(Checksum checksum, ByteBuffer source) {
        checksum.update(source.duplicate());
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void readFully(
            FileChannel channel, long offset, ByteBuffer target, String eofMessage) throws IOException {
        long position = offset;
        while (target.hasRemaining()) {
            int read = channel.read(target, position);
            if (read < 0) {
                throw new EOFException(eofMessage);
            }
            if (read == 0) {
                throw new IOException("Prepared texture pack integrity read made no progress");
            }
            position += read;
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer source) throws IOException {
        while (source.hasRemaining()) {
            if (channel.write(source) <= 0) {
                throw new IOException("Prepared texture pack integrity write made no progress");
            }
        }
    }

    /** Positioned read-only view that updates one checksum over a required sequential range. */
    private static final class ChecksumChannel extends FileChannel {
        private final FileChannel delegate;
        private final long start;
        private final long end;
        private final Checksum checksum;
        private long nextPosition;

        private ChecksumChannel(FileChannel delegate, long start, long end, Checksum checksum) {
            this.delegate = delegate;
            this.start = start;
            this.end = end;
            this.checksum = checksum;
            this.nextPosition = start;
        }

        @Override
        public int read(ByteBuffer destination, long position) throws IOException {
            if (position != nextPosition) {
                throw new IOException("Prepared texture pack integrity read was not sequential");
            }
            if (position < start || position >= end) {
                return position == end ? -1 : throwOutsideRange();
            }
            int before = destination.position();
            int oldLimit = destination.limit();
            int allowed = Math.toIntExact(Math.min((long) destination.remaining(), end - position));
            destination.limit(before + allowed);
            final int read;
            try {
                read = delegate.read(destination, position);
            } finally {
                destination.limit(oldLimit);
            }
            if (read <= 0) {
                return read;
            }
            ByteBuffer bytes = destination.duplicate();
            bytes.position(before);
            bytes.limit(before + read);
            checksum.update(bytes);
            nextPosition = Math.addExact(nextPosition, read);
            return read;
        }

        private int throwOutsideRange() throws IOException {
            throw new IOException("Prepared texture pack integrity read escaped its entry range");
        }

        private long finish() throws IOException {
            if (nextPosition != end) {
                throw new IOException("Prepared texture pack entry was not completely read for integrity verification");
            }
            return checksum.getValue();
        }

        @Override
        public int read(ByteBuffer destination) throws IOException {
            throw new IOException("Prepared texture pack integrity requires positioned reads");
        }

        @Override
        public long read(ByteBuffer[] destinations, int offset, int length) throws IOException {
            throw new IOException("Prepared texture pack integrity requires positioned reads");
        }

        @Override
        public int write(ByteBuffer source) {
            throw new NonWritableChannelException();
        }

        @Override
        public long write(ByteBuffer[] sources, int offset, int length) {
            throw new NonWritableChannelException();
        }

        @Override
        public long position() throws IOException {
            throw new IOException("Prepared texture pack integrity requires positioned reads");
        }

        @Override
        public FileChannel position(long newPosition) throws IOException {
            throw new IOException("Prepared texture pack integrity requires positioned reads");
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public FileChannel truncate(long size) {
            throw new NonWritableChannelException();
        }

        @Override
        public void force(boolean metaData) throws IOException {
            throw new IOException("Prepared texture pack integrity view is read-only");
        }

        @Override
        public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
            throw new IOException("Prepared texture pack integrity view does not support transferTo");
        }

        @Override
        public long transferFrom(ReadableByteChannel source, long position, long count) {
            throw new NonWritableChannelException();
        }

        @Override
        public int write(ByteBuffer source, long position) {
            throw new NonWritableChannelException();
        }

        @Override
        public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
            throw new IOException("Prepared texture pack integrity view does not support mapped reads");
        }

        @Override
        public FileLock lock(long position, long size, boolean shared) throws IOException {
            throw new IOException("Prepared texture pack integrity view does not support locking");
        }

        @Override
        public FileLock tryLock(long position, long size, boolean shared) throws IOException {
            throw new IOException("Prepared texture pack integrity view does not support locking");
        }

        @Override
        protected void implCloseChannel() {
            // Borrowed view: PreparedTexturePack owns the shared channel.
        }
    }
}
