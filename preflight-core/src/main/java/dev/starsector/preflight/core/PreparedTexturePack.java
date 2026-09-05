package dev.starsector.preflight.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.file.Path;
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
    public static final String READ_AHEAD_PROPERTY = "preflight.texture.packReadAhead";
    private final Path path;
    private final String profileFingerprint;
    private final FileChannel channel;
    private final PreparedPackReadAhead readAhead;
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
        this.readAhead = Boolean.getBoolean(READ_AHEAD_PROPERTY)
                ? new PreparedPackReadAhead(channel, fileBytes) : null;
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
        long absolute = Math.addExact(payloadOffset, range.offset());
        if (readAhead != null) {
            synchronized (readAhead) {
                readAhead.beginEntry(absolute, range.length());
                return PreparedTexturePackIntegrity.readTrusted(readAhead, absolute, range.length(),
                        range.crc32c(), path + "!" + normalized);
            }
        }
        return PreparedTexturePackIntegrity.readTrusted(
                channel,
                absolute,
                range.length(),
                range.crc32c(),
                path + "!" + normalized);
    }

    CopiedEntry copyVerifiedEntry(
            String blobRelativePath, FileChannel output, ByteBuffer copyBuffer) throws IOException {
        if (closed.get()) {
            throw new IOException("Prepared texture pack is closed");
        }
        String normalized = ResourceIndex.normalizeRelativePath(blobRelativePath);
        Range range = entries.get(normalized);
        if (range == null) {
            throw new IOException("Prepared texture pack has no entry for " + normalized);
        }
        int copiedCrc32c = PreparedTexturePackIntegrity.copyVerifiedSpftRange(
                channel,
                Math.addExact(payloadOffset, range.offset()),
                range.length(),
                fileBytes,
                output,
                copyBuffer,
                path + "!" + normalized);
        if (copiedCrc32c != range.crc32c()) {
            throw new IOException("Prepared texture pack entry CRC32C mismatch: " + normalized);
        }
        return new CopiedEntry(range.length(), range.crc32c());
    }

    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            try { if (readAhead != null) readAhead.close(); }
            finally { channel.close(); }
        }
    }

    public Map<String, Object> readAheadTelemetry() {
        return readAhead == null ? Map.of("enabled", false) : readAhead.telemetry();
    }

    record Range(long offset, int length, int crc32c) {
        Range {
            if (offset < 0 || length <= 0) {
                throw new IllegalArgumentException("Prepared texture pack range is invalid");
            }
        }
    }

    record CopiedEntry(int length, int crc32c) {
    }
}
