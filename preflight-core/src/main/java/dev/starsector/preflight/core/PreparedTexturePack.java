package dev.starsector.preflight.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** One profile's complete SPFT blobs in a single positionally-read file. */
public final class PreparedTexturePack implements AutoCloseable {
    private static final int SPFT_PREFIX_BYTES = 4 + Integer.BYTES * 2;
    private static final int SPFT_CHECKSUM_BYTES = 32;

    private final Path path;
    private final String profileFingerprint;
    private final FileChannel channel;
    private final long fileBytes;
    private final long payloadOffset;
    private final Map<String, Range> entries;
    private final AtomicBoolean closed = new AtomicBoolean();

    PreparedTexturePack(
            Path path,
            String profileFingerprint,
            FileChannel channel,
            long fileBytes,
            long payloadOffset,
            Map<String, Range> entries) {
        this.path = Objects.requireNonNull(path, "path");
        this.profileFingerprint = Objects.requireNonNull(profileFingerprint, "profileFingerprint");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.fileBytes = fileBytes;
        this.payloadOffset = payloadOffset;
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    public Path path() {
        return path;
    }

    public String profileFingerprint() {
        return profileFingerprint;
    }

    public int entryCount() {
        return entries.size();
    }

    public long fileBytes() {
        return fileBytes;
    }

    public boolean hasEntryOrder(Collection<String> blobRelativePaths) {
        if (blobRelativePaths == null) {
            return false;
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String path : blobRelativePaths) {
            normalized.add(ResourceIndex.normalizeRelativePath(path));
        }
        return new ArrayList<>(normalized).equals(new ArrayList<>(entries.keySet()));
    }

    public PreparedTexture readTrusted(String blobRelativePath) throws IOException {
        if (closed.get()) {
            throw new IOException("Prepared texture pack is closed");
        }
        String normalized = ResourceIndex.normalizeRelativePath(blobRelativePath);
        Range range = entries.get(normalized);
        if (range == null) {
            throw new IOException("Prepared texture pack has no entry for " + normalized);
        }
        if (range.length() < SPFT_PREFIX_BYTES + SPFT_CHECKSUM_BYTES) {
            throw new IOException("Prepared texture pack entry is too small for an SPFT blob: " + normalized);
        }

        long absolute = Math.addExact(payloadOffset, range.offset());
        long checksumOffset = Math.addExact(absolute, range.length() - SPFT_CHECKSUM_BYTES);
        long payloadStart = Math.addExact(absolute, SPFT_PREFIX_BYTES);
        PayloadVerifyingChannel verifying = new PayloadVerifyingChannel(channel, payloadStart, checksumOffset);
        PreparedTexture texture = PreparedTextureIO.readTrusted(
                verifying, absolute, range.length(), path + "!" + normalized);

        byte[] expectedChecksum = new byte[SPFT_CHECKSUM_BYTES];
        readFully(channel, checksumOffset, ByteBuffer.wrap(expectedChecksum));
        if (!MessageDigest.isEqual(expectedChecksum, verifying.finishDigest())) {
            throw new IOException("Prepared texture checksum mismatch: " + path + "!" + normalized);
        }
        return texture;
    }

    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            channel.close();
        }
    }

    private static void readFully(FileChannel source, long offset, ByteBuffer target) throws IOException {
        long position = offset;
        while (target.hasRemaining()) {
            int read = source.read(target, position);
            if (read < 0) {
                throw new IOException("Prepared texture pack ended inside an embedded checksum");
            }
            if (read == 0) {
                throw new IOException("Prepared texture pack checksum read made no progress");
            }
            position += read;
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    /**
     * Read-only view that hashes exactly the embedded SPFT payload while the existing trusted
     * decoder consumes it. The shared pack channel stays position-independent, so concurrent
     * texture reads retain the existing positional-I/O behavior and no payload is read twice.
     */
    private static final class PayloadVerifyingChannel extends FileChannel {
        private final FileChannel delegate;
        private final long digestStart;
        private final long digestEnd;
        private final MessageDigest digest = sha256();
        private long nextDigestPosition;

        private PayloadVerifyingChannel(FileChannel delegate, long digestStart, long digestEnd) {
            this.delegate = delegate;
            this.digestStart = digestStart;
            this.digestEnd = digestEnd;
            this.nextDigestPosition = digestStart;
        }

        @Override
        public int read(ByteBuffer destination, long position) throws IOException {
            int before = destination.position();
            int read = delegate.read(destination, position);
            if (read <= 0) {
                return read;
            }

            long readEnd = Math.addExact(position, read);
            long overlapStart = Math.max(position, digestStart);
            long overlapEnd = Math.min(readEnd, digestEnd);
            if (overlapStart < overlapEnd) {
                if (overlapStart != nextDigestPosition) {
                    throw new IOException("Prepared texture payload was not read sequentially during verification");
                }
                int relativeStart = Math.toIntExact(overlapStart - position);
                int relativeEnd = Math.toIntExact(overlapEnd - position);
                ByteBuffer hashed = destination.duplicate();
                hashed.position(before + relativeStart);
                hashed.limit(before + relativeEnd);
                digest.update(hashed);
                nextDigestPosition = overlapEnd;
            }
            return read;
        }

        private byte[] finishDigest() throws IOException {
            if (nextDigestPosition != digestEnd) {
                throw new IOException("Prepared texture payload was not completely read during verification");
            }
            return digest.digest();
        }

        @Override
        public int read(ByteBuffer destination) throws IOException {
            throw new IOException("Prepared texture verification requires positioned reads");
        }

        @Override
        public long read(ByteBuffer[] destinations, int offset, int length) throws IOException {
            throw new IOException("Prepared texture verification requires positioned reads");
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
            throw new IOException("Prepared texture verification requires positioned reads");
        }

        @Override
        public FileChannel position(long newPosition) throws IOException {
            throw new IOException("Prepared texture verification requires positioned reads");
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
            throw new IOException("Prepared texture verification view is read-only");
        }

        @Override
        public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
            throw new IOException("Prepared texture verification does not support transferTo");
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
            throw new IOException("Prepared texture verification does not support mapped reads");
        }

        @Override
        public FileLock lock(long position, long size, boolean shared) throws IOException {
            throw new IOException("Prepared texture verification does not support locking");
        }

        @Override
        public FileLock tryLock(long position, long size, boolean shared) throws IOException {
            throw new IOException("Prepared texture verification does not support locking");
        }

        @Override
        protected void implCloseChannel() {
            // This is a borrowed view. PreparedTexturePack owns and closes the shared channel.
        }
    }

    record Range(long offset, int length) {
        Range {
            if (offset < 0 || length <= 0) {
                throw new IllegalArgumentException("Prepared texture pack range is invalid");
            }
        }
    }
}