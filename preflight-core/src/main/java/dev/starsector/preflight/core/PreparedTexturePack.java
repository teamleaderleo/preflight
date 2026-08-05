package dev.starsector.preflight.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
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
        long absolute = Math.addExact(payloadOffset, range.offset());
        return PreparedTextureIO.readTrusted(
                channel, absolute, range.length(), path + "!" + normalized);
    }

    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            channel.close();
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
