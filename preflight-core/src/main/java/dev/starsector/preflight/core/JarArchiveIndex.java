package dev.starsector.preflight.core;

import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/** Immutable content-addressed inventory of one JAR's non-directory entries. */
public final class JarArchiveIndex {
    public static final int FORMAT_VERSION = 1;

    private final String sourceSha256;
    private final long sourceBytes;
    private final NavigableMap<String, Entry> entries;
    private final long classEntries;
    private final long resourceEntries;
    private final long uncompressedBytes;
    private final long compressedBytes;

    public JarArchiveIndex(String sourceSha256, long sourceBytes, Map<String, Entry> entries) {
        Hashes.decodeSha256(sourceSha256);
        if (sourceBytes < 0) {
            throw new IllegalArgumentException("JAR source size may not be negative");
        }
        this.sourceSha256 = sourceSha256.toLowerCase(java.util.Locale.ROOT);
        this.sourceBytes = sourceBytes;

        TreeMap<String, Entry> copy = new TreeMap<>();
        long classes = 0;
        long resources = 0;
        long uncompressed = 0;
        long compressed = 0;
        for (Map.Entry<String, Entry> item : entries.entrySet()) {
            String name = normalizeEntryName(item.getKey());
            Entry entry = item.getValue();
            if (!name.equals(entry.name())) {
                throw new IllegalArgumentException("JAR entry map key differs from entry name: " + name);
            }
            if (copy.put(name, entry) != null) {
                throw new IllegalArgumentException("Duplicate JAR entry: " + name);
            }
            if (entry.classEntry()) {
                classes++;
            } else {
                resources++;
            }
            uncompressed = Math.addExact(uncompressed, entry.uncompressedBytes());
            compressed = Math.addExact(compressed, entry.compressedBytes());
        }
        this.entries = Collections.unmodifiableNavigableMap(copy);
        this.classEntries = classes;
        this.resourceEntries = resources;
        this.uncompressedBytes = uncompressed;
        this.compressedBytes = compressed;
    }

    public String sourceSha256() {
        return sourceSha256;
    }

    public long sourceBytes() {
        return sourceBytes;
    }

    public NavigableMap<String, Entry> entries() {
        return entries;
    }

    public int entryCount() {
        return entries.size();
    }

    public long classEntries() {
        return classEntries;
    }

    public long resourceEntries() {
        return resourceEntries;
    }

    public long uncompressedBytes() {
        return uncompressedBytes;
    }

    public long compressedBytes() {
        return compressedBytes;
    }

    public Optional<Entry> entry(String name) {
        return Optional.ofNullable(entries.get(normalizeEntryName(name)));
    }

    public boolean contains(String name) {
        return entry(name).isPresent();
    }

    public static String normalizeEntryName(String raw) {
        // JAR names and resource-relative paths have the same grammar. The shared scanner returns
        // an already-clean name unchanged and avoids the old regex, split array, deque and join.
        return ResourceIndex.normalizeRelativePath(raw);
    }

    public record Entry(
            String name,
            long uncompressedBytes,
            long compressedBytes,
            long crc32,
            int compressionMethod) {
        public Entry {
            name = normalizeEntryName(name);
            if (uncompressedBytes < 0 || compressedBytes < 0) {
                throw new IllegalArgumentException("JAR entry sizes may not be negative: " + name);
            }
            if (crc32 < -1 || crc32 > 0xffff_ffffL) {
                throw new IllegalArgumentException("JAR entry CRC-32 is invalid: " + name);
            }
            if (compressionMethod < -1) {
                throw new IllegalArgumentException("JAR entry compression method is invalid: " + name);
            }
        }

        public boolean classEntry() {
            return name.endsWith(".class") && !name.equals("module-info.class");
        }

        public Optional<String> className() {
            return classEntry()
                    ? Optional.of(name.substring(0, name.length() - 6).replace('/', '.'))
                    : Optional.empty();
        }
    }
}
