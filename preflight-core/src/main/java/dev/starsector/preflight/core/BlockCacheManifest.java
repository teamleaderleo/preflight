package dev.starsector.preflight.core;

import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Mapping from logical resource paths to baked {@link BlockTexture} blobs.
 *
 * <p>Separate from {@link TextureManifest} for the same reason {@link BlockTexture} is separate from
 * {@link PreparedTexture}: one cache promises pixels identical to the loader's and the other is
 * deliberately lossy, and a single manifest listing both would make substituting the wrong one a
 * matter of forgetting a field.
 *
 * <p>Not every texture in a profile appears here, and that is the point. Block compression is a
 * per-texture decision — some art compresses below perceptibility and some does not, and shader maps
 * should not be judged by a perceptual metric at all — so a missing entry means "this one keeps the
 * ordinary path", which is a normal outcome rather than a cache miss to be explained.
 *
 * <p>The encoder version is held once for the whole manifest rather than per entry. A cache is baked
 * in a single run by a single encoder, so a version mismatch invalidates all of it at once; checking
 * one integer at startup is cheaper than checking ten thousand, and there is no coherent state in
 * which some entries are current and others are not.
 */
public final class BlockCacheManifest {
    public static final int FORMAT_VERSION = 1;

    private final String profileFingerprint;
    private final int codecVersion;
    private final NavigableMap<String, Entry> entries;

    public BlockCacheManifest(String profileFingerprint, int codecVersion, Map<String, Entry> entries) {
        if (profileFingerprint == null || profileFingerprint.isBlank()) {
            throw new IllegalArgumentException("profileFingerprint is required");
        }
        if (codecVersion <= 0) {
            throw new IllegalArgumentException("codecVersion must be positive");
        }
        this.profileFingerprint = profileFingerprint;
        this.codecVersion = codecVersion;
        TreeMap<String, Entry> copy = new TreeMap<>();
        for (Map.Entry<String, Entry> source : entries.entrySet()) {
            String path = ResourceIndex.normalizeLogicalPath(source.getKey());
            Entry value = Objects.requireNonNull(source.getValue(), "manifest entry");
            if (copy.put(path, value) != null) {
                throw new IllegalArgumentException("Duplicate normalized texture path: " + path);
            }
        }
        this.entries = Collections.unmodifiableNavigableMap(copy);
    }

    public String profileFingerprint() {
        return profileFingerprint;
    }

    /** The {@link BlockCompressor#CODEC_VERSION} that baked every blob this manifest lists. */
    public int codecVersion() {
        return codecVersion;
    }

    /**
     * Whether blobs baked under this manifest are still what the current encoder would produce.
     *
     * <p>A false answer is not corruption and not an error. It means the cache should be rebuilt, and
     * until it is, every texture takes the ordinary path.
     */
    public boolean matchesCurrentCodec() {
        return codecVersion == BlockCompressor.CODEC_VERSION;
    }

    public NavigableMap<String, Entry> entries() {
        return entries;
    }

    public int entryCount() {
        return entries.size();
    }

    public Optional<Entry> entry(String logicalPath) {
        return Optional.ofNullable(entries.get(ResourceIndex.normalizeLogicalPath(logicalPath)));
    }

    /** Total block bytes across the cache: what it costs on disk, and resident once uploaded. */
    public long blockBytes() {
        long total = 0;
        for (Entry entry : entries.values()) {
            total += entry.blockBytes();
        }
        return total;
    }

    /** What the same textures cost uncompressed, at four bytes per pixel — the figure this trades against. */
    public long uncompressedBytes() {
        long total = 0;
        for (Entry entry : entries.values()) {
            total += entry.uncompressedBytes();
        }
        return total;
    }

    /**
     * One baked texture.
     *
     * <p>The two fidelity figures are carried here as well as in the blob so that auditing the
     * policy — which textures were compressed, and how much they lost — does not require opening ten
     * thousand files. The blob remains the authority; this is a copy for reading in bulk.
     *
     * @param blockBytes total across every mip level, matching the blob
     * @param meanDeltaE alpha-weighted mean perceptual error measured at bake time
     * @param p99DeltaE the error the worst 1% of visible pixels exceeded
     */
    public record Entry(
            String sourceSha256,
            String blobRelativePath,
            BlockTexture.Format format,
            int width,
            int height,
            int levelCount,
            long blockBytes,
            double meanDeltaE,
            double p99DeltaE) {
        public Entry {
            Hashes.decodeSha256(sourceSha256);
            sourceSha256 = sourceSha256.toLowerCase(java.util.Locale.ROOT);
            blobRelativePath = ResourceIndex.normalizeRelativePath(blobRelativePath);
            format = Objects.requireNonNull(format, "format");
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Block cache dimensions must be positive");
            }
            if (levelCount <= 0 || levelCount > BlockTexture.levelCount(width, height)) {
                throw new IllegalArgumentException(
                        "A " + width + "x" + height + " texture cannot have " + levelCount + " mip levels");
            }
            long expected = 0;
            for (int level = 0; level < levelCount; level++) {
                expected = Math.addExact(expected, format.blockBytesFor(
                        BlockTexture.levelWidth(width, level), BlockTexture.levelHeight(height, level)));
            }
            if (blockBytes != expected) {
                throw new IllegalArgumentException(
                        "Block cache entry claims " + blockBytes + " bytes; " + levelCount
                                + " levels of " + width + "x" + height + " in " + format + " is " + expected);
            }
            if (!Double.isFinite(meanDeltaE) || !Double.isFinite(p99DeltaE)
                    || meanDeltaE < 0 || p99DeltaE < 0) {
                throw new IllegalArgumentException("Fidelity figures must be finite non-negative numbers");
            }
        }

        /** Describes {@code texture}, which must already be stored at {@code blobRelativePath}. */
        public static Entry of(BlockTexture texture, String blobRelativePath) {
            return new Entry(
                    texture.sourceSha256(),
                    blobRelativePath,
                    texture.format(),
                    texture.width(),
                    texture.height(),
                    texture.levelCount(),
                    texture.blockBytes(),
                    texture.fidelity().meanDeltaE(),
                    texture.fidelity().p99DeltaE());
        }

        public long uncompressedBytes() {
            long total = 0;
            for (int level = 0; level < levelCount; level++) {
                total += 4L * BlockTexture.levelWidth(width, level) * BlockTexture.levelHeight(height, level);
            }
            return total;
        }
    }
}
