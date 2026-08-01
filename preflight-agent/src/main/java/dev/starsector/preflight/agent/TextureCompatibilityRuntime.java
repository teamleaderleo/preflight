package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.PathContainment;
import dev.starsector.preflight.core.PreparedTexture;
import dev.starsector.preflight.core.PreparedTextureIO;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceIndexIO;
import dev.starsector.preflight.core.ResourceIndexValidator;
import dev.starsector.preflight.core.TextureManifest;
import dev.starsector.preflight.core.TextureManifestIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared fail-open cache lookup plus the decoded-image compatibility consumer. */
public final class TextureCompatibilityRuntime {
    static final String PLAN_ID = "texture-compatibility-v2";
    public static final int MAX_MANIFEST_ENTRIES = 100_000;
    public static final long MAX_INDEX_PROVIDERS = 500_000;
    static final int MAX_INTERNAL_ERRORS = 8;
    static final int MAX_QUARANTINES_PER_SESSION = 16;
    static final long MAX_RECONSTRUCTED_PIXELS = 16_777_216L;

    private static final Telemetry TELEMETRY = new Telemetry();
    private static volatile State state = State.disabled();

    private TextureCompatibilityRuntime() {
    }

    static synchronized void beginSession() {
        state = State.disabled();
        TELEMETRY.reset();
    }

    static synchronized boolean configure(Path cacheDirectory, Path manifestPath, Path indexPath) {
        if (cacheDirectory == null || manifestPath == null || indexPath == null) {
            disable(DisableReason.MISSING_CONFIGURATION);
            return false;
        }
        try {
            Path cacheRoot = PathContainment.realDirectory(cacheDirectory);
            Path manifestFile = PathContainment.existingInsideRealRoot(cacheRoot, manifestPath);
            Path indexFile = PathContainment.existingInsideRealRoot(cacheRoot, indexPath);
            if (!Files.isRegularFile(manifestFile) || !Files.isRegularFile(indexFile)) {
                disable(DisableReason.INVALID_CONFIGURATION);
                return false;
            }
            TextureManifest manifest = TextureManifestIO.read(manifestFile);
            ResourceIndex index = ResourceIndexIO.read(indexFile);
            if (manifest.entryCount() > MAX_MANIFEST_ENTRIES || index.entryCount() > MAX_MANIFEST_ENTRIES
                    || index.providerCount() > MAX_INDEX_PROVIDERS) {
                disable(DisableReason.MANIFEST_TOO_LARGE);
                return false;
            }
            if (!manifest.profileFingerprint().equals(index.profileFingerprint())) {
                disable(DisableReason.MANIFEST_INDEX_MISMATCH);
                return false;
            }
            ResourceIndexValidator.Result validation = ResourceIndexValidator.validate(index, 16);
            if (!validation.valid()) {
                disable(DisableReason.INDEX_STALE);
                return false;
            }
            List<Path> sourceRoots = new ArrayList<>(index.roots().size());
            for (ResourceIndex.Root root : index.roots()) {
                sourceRoots.add(PathContainment.realDirectory(root.path()));
            }
            state = new State(cacheRoot, manifest, index, List.copyOf(sourceRoots));
            TELEMETRY.configured();
            return true;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            disable(DisableReason.INVALID_CONFIGURATION);
            return false;
        }
    }

    static synchronized void disable(DisableReason reason) {
        state = State.disabled();
        TELEMETRY.disabled(reason);
    }

    static boolean ready() {
        State current = state;
        return current.ready && !current.circuitBreaker.get();
    }

    /** Returns a verified cache-backed image, or {@code null} so the caller can run the original method. */
    public static BufferedImage load(String logicalPath) {
        PreparedTexture texture = lookup(logicalPath);
        if (texture == null) {
            return null;
        }
        try {
            if (texture.originalWidth() != texture.uploadWidth()
                    || texture.originalHeight() != texture.uploadHeight()
                    || Math.multiplyExact((long) texture.originalWidth(), texture.originalHeight())
                    > MAX_RECONSTRUCTED_PIXELS) {
                declined(FallbackReason.UNSUPPORTED_TEXTURE);
                return null;
            }
            BufferedImage image = reconstruct(texture);
            hit(texture.pixelBytes());
            return image;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            internalFailure();
            declined(FallbackReason.INTERNAL_ERROR);
            return null;
        }
    }

    /**
     * True when this path need not go on the game's own prefetch queue, because the cache has it.
     *
     * <p>Called once per image resource while the loader builds that queue, so it must stay cheap:
     * a normalize and one manifest lookup, and deliberately <em>not</em> the source hash, which is
     * the expensive part of {@link #lookup} and belongs on the path that actually serves an image.
     * Being wrong here costs no correctness either way. A path wrongly skipped is decoded
     * synchronously by the game exactly as an uncached path always was; a path wrongly kept is
     * prefetched exactly as it always was.
     */
    public static boolean prefetchRedundant(String logicalPath) {
        State current = state;
        if (!current.ready || current.circuitBreaker.get()) {
            return false;
        }
        try {
            String normalized;
            try {
                normalized = ResourceIndex.normalizeLogicalPath(logicalPath);
            } catch (IllegalArgumentException error) {
                TELEMETRY.prefetchKept();
                return false;
            }
            TextureManifest.Entry entry = current.manifest.entry(normalized).orElse(null);
            // Only an identity entry is servable by load(); anything else falls back, and a path
            // that is going to fall back is better left prefetched than decoded on the loader.
            if (entry == null || entry.transformation() != PreparedTexture.Transformation.IDENTITY) {
                TELEMETRY.prefetchKept();
                return false;
            }
            TELEMETRY.prefetchSkipped();
            return true;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            internalFailure();
            return false;
        }
    }

    /** Shared exact lookup used by independently selectable texture consumers. */
    static PreparedTexture lookup(String logicalPath) {
        TELEMETRY.attempt();
        State current = state;
        if (!current.ready || current.circuitBreaker.get()) {
            TELEMETRY.fallback(FallbackReason.DISABLED);
            return null;
        }
        try {
            String normalized;
            try {
                normalized = ResourceIndex.normalizeLogicalPath(logicalPath);
            } catch (IllegalArgumentException error) {
                TELEMETRY.fallback(FallbackReason.PATH_INVALID);
                return null;
            }

            TextureManifest.Entry entry = current.manifest.entry(normalized).orElse(null);
            if (entry == null) {
                TELEMETRY.fallback(FallbackReason.ENTRY_MISSING);
                return null;
            }
            ResourceIndex.Provider winner = current.index.winner(normalized).orElse(null);
            if (winner == null) {
                TELEMETRY.fallback(FallbackReason.SOURCE_MISSING);
                return null;
            }
            Path source;
            try {
                source = PathContainment.existingInsideRealRoot(
                        current.sourceRoots.get(winner.rootIndex()),
                        current.index.resolve(winner));
            } catch (IllegalArgumentException error) {
                TELEMETRY.fallback(FallbackReason.PATH_INVALID);
                return null;
            } catch (IOException error) {
                TELEMETRY.fallback(FallbackReason.SOURCE_MISSING);
                return null;
            }
            if (!Files.isRegularFile(source)) {
                TELEMETRY.fallback(FallbackReason.SOURCE_MISSING);
                return null;
            }
            if (!entry.sourceSha256().equals(Hashes.sha256(source))) {
                TELEMETRY.fallback(FallbackReason.SOURCE_CHANGED);
                return null;
            }
            if (entry.transformation() != PreparedTexture.Transformation.IDENTITY) {
                TELEMETRY.fallback(FallbackReason.UNSUPPORTED_TEXTURE);
                return null;
            }

            Path blob;
            try {
                Path candidate = current.cacheRoot.resolve(entry.blobRelativePath()).normalize();
                blob = PathContainment.existingInsideRealRoot(current.cacheRoot, candidate);
            } catch (IllegalArgumentException | IOException error) {
                TELEMETRY.fallback(FallbackReason.BLOB_MISSING);
                return null;
            }
            if (!Files.isRegularFile(blob)) {
                TELEMETRY.fallback(FallbackReason.BLOB_MISSING);
                return null;
            }

            PreparedTexture texture;
            try {
                texture = PreparedTextureIO.read(blob);
            } catch (IOException error) {
                TELEMETRY.corruption();
                quarantine(current, blob, "corrupt");
                TELEMETRY.fallback(FallbackReason.BLOB_CORRUPT);
                return null;
            }
            if (!matches(entry, texture)) {
                TELEMETRY.corruption();
                quarantine(current, blob, "identity-mismatch");
                TELEMETRY.fallback(FallbackReason.BLOB_IDENTITY_MISMATCH);
                return null;
            }
            return texture;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            internalFailure();
            TELEMETRY.fallback(FallbackReason.INTERNAL_ERROR);
            return null;
        }
    }

    static void hit(long bytes) {
        TELEMETRY.hit(bytes);
    }

    static void declined(FallbackReason reason) {
        TELEMETRY.fallback(reason);
    }

    static void internalFailure() {
        State current = state;
        int failures = current.internalErrors.incrementAndGet();
        TELEMETRY.internalError();
        if (failures >= MAX_INTERNAL_ERRORS && current.circuitBreaker.compareAndSet(false, true)) {
            TELEMETRY.disabled(DisableReason.CIRCUIT_BREAKER);
        }
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>(TELEMETRY.snapshot(ready()));
        values.put("preparedPixels", TexturePreparedPixelRuntime.telemetry());
        return Map.copyOf(values);
    }

    private static boolean matches(TextureManifest.Entry entry, PreparedTexture texture) {
        return entry.sourceSha256().equals(texture.sourceSha256())
                && entry.transformation() == texture.transformation()
                && entry.width() == texture.uploadWidth()
                && entry.height() == texture.uploadHeight()
                && entry.channels() == texture.channels()
                && entry.pixelBytes() == texture.pixelBytes();
    }

    /**
     * Rebuilds the image the game would have decoded, bottom-up rows flipped back into place.
     *
     * <p>The packed pixels become the returned image's backing store rather than being pushed
     * through {@link BufferedImage#setRGB}, which copies the whole array a second time through the
     * colour model. Measured on the game's own JVM over the 645 MB a launch actually serves, that
     * second copy cost 0.92 s against 0.19 s for this form -- all of it on the loading thread, which
     * is the one thread the launch is waiting on. The masks below are the ones {@code TYPE_INT_ARGB}
     * and {@code TYPE_INT_RGB} are defined as, so {@link BufferedImage#getType()} still reports those
     * types and callers cannot tell the two constructions apart.
     */
    private static BufferedImage reconstruct(PreparedTexture texture) {
        int width = texture.originalWidth();
        int height = texture.originalHeight();
        int channels = texture.channels();
        byte[] pixels = texture.pixels();
        int[] argb = new int[Math.multiplyExact(width, height)];
        for (int y = 0; y < height; y++) {
            int sourceRow = height - 1 - y;
            int target = y * width;
            int source = sourceRow * width * channels;
            for (int x = 0; x < width; x++) {
                int offset = source + x * channels;
                int red = Byte.toUnsignedInt(pixels[offset]);
                int green = Byte.toUnsignedInt(pixels[offset + 1]);
                int blue = Byte.toUnsignedInt(pixels[offset + 2]);
                int alpha = channels == 4 ? Byte.toUnsignedInt(pixels[offset + 3]) : 255;
                argb[target + x] = (alpha << 24) | (red << 16) | (green << 8) | blue;
            }
        }
        DirectColorModel colorModel = channels == 4
                ? new DirectColorModel(32, 0x00ff0000, 0x0000ff00, 0x000000ff, 0xff000000)
                : new DirectColorModel(24, 0x00ff0000, 0x0000ff00, 0x000000ff);
        WritableRaster raster = Raster.createPackedRaster(
                new DataBufferInt(argb, argb.length), width, height, width, colorModel.getMasks(), null);
        return new BufferedImage(colorModel, raster, false, null);
    }

    private static void quarantine(State current, Path blob, String reason) {
        int ordinal = current.quarantines.getAndIncrement();
        if (ordinal >= MAX_QUARANTINES_PER_SESSION) {
            return;
        }
        try {
            Path candidate = current.cacheRoot.resolve("quarantine").normalize();
            Files.createDirectories(candidate);
            Path directory = PathContainment.existingInsideRealRoot(current.cacheRoot, candidate);
            String name = blob.getFileName() + "." + reason + "." + Instant.now().toEpochMilli() + "." + ordinal;
            Path target = directory.resolve(name).normalize();
            if (!target.startsWith(directory)) {
                return;
            }
            Files.move(blob, target, StandardCopyOption.REPLACE_EXISTING);
            TELEMETRY.quarantined();
        } catch (IOException | IllegalArgumentException ignored) {
            // The original Starsector path remains authoritative even when quarantine cannot be written.
        }
    }

    enum DisableReason {
        MISSING_CONFIGURATION,
        INVALID_CONFIGURATION,
        MANIFEST_INDEX_MISMATCH,
        INDEX_STALE,
        MANIFEST_TOO_LARGE,
        KILL_SWITCH,
        CIRCUIT_BREAKER
    }

    enum FallbackReason {
        DISABLED,
        PATH_INVALID,
        ENTRY_MISSING,
        SOURCE_MISSING,
        SOURCE_CHANGED,
        UNSUPPORTED_TEXTURE,
        BLOB_MISSING,
        BLOB_CORRUPT,
        BLOB_IDENTITY_MISMATCH,
        DIRECT_MEMORY_LIMIT,
        PREPARED_PIXEL_BRIDGE,
        INTERNAL_ERROR
    }

    private static final class State {
        private final Path cacheRoot;
        private final TextureManifest manifest;
        private final ResourceIndex index;
        private final List<Path> sourceRoots;
        private final boolean ready;
        private final AtomicInteger internalErrors = new AtomicInteger();
        private final AtomicInteger quarantines = new AtomicInteger();
        private final AtomicBoolean circuitBreaker = new AtomicBoolean();

        private State(Path cacheRoot, TextureManifest manifest, ResourceIndex index, List<Path> sourceRoots) {
            this.cacheRoot = cacheRoot;
            this.manifest = manifest;
            this.index = index;
            this.sourceRoots = sourceRoots;
            this.ready = true;
        }

        private State() {
            this.cacheRoot = null;
            this.manifest = null;
            this.index = null;
            this.sourceRoots = List.of();
            this.ready = false;
        }

        private static State disabled() {
            return new State();
        }
    }

    private static final class Telemetry {
        private final EnumMap<FallbackReason, Long> fallbackReasons = new EnumMap<>(FallbackReason.class);
        private final List<DisableReason> disableReasons = new ArrayList<>();
        private boolean configured;
        private long attempts;
        private long hits;
        private long misses;
        private long fallbacks;
        private long corruptions;
        private long quarantined;
        private long internalErrors;
        private long bytesServed;
        private long prefetchSkipped;
        private long prefetchKept;

        synchronized void reset() {
            fallbackReasons.clear();
            disableReasons.clear();
            configured = false;
            attempts = 0;
            hits = 0;
            misses = 0;
            fallbacks = 0;
            corruptions = 0;
            quarantined = 0;
            internalErrors = 0;
            bytesServed = 0;
            prefetchSkipped = 0;
            prefetchKept = 0;
        }

        synchronized void configured() {
            configured = true;
        }

        synchronized void disabled(DisableReason reason) {
            if (!disableReasons.contains(reason)) {
                disableReasons.add(reason);
            }
        }

        synchronized void attempt() {
            attempts++;
        }

        synchronized void hit(long bytes) {
            hits++;
            bytesServed = saturatedAdd(bytesServed, bytes);
        }

        synchronized void fallback(FallbackReason reason) {
            misses++;
            fallbacks++;
            fallbackReasons.merge(reason, 1L, TextureCompatibilityRuntime::saturatedAdd);
        }

        synchronized void corruption() {
            corruptions++;
        }

        synchronized void quarantined() {
            quarantined++;
        }

        synchronized void internalError() {
            internalErrors++;
        }

        synchronized void prefetchSkipped() {
            prefetchSkipped++;
        }

        synchronized void prefetchKept() {
            prefetchKept++;
        }

        synchronized Map<String, Object> snapshot(boolean ready) {
            Map<String, Object> reasons = new LinkedHashMap<>();
            for (FallbackReason reason : FallbackReason.values()) {
                long count = fallbackReasons.getOrDefault(reason, 0L);
                if (count > 0) {
                    reasons.put(label(reason), count);
                }
            }
            List<String> disabled = disableReasons.stream().map(TextureCompatibilityRuntime::label).toList();
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("planId", PLAN_ID);
            values.put("configured", configured);
            values.put("ready", ready);
            values.put("attempts", attempts);
            values.put("hits", hits);
            values.put("misses", misses);
            values.put("fallbacks", fallbacks);
            values.put("corruptions", corruptions);
            values.put("quarantined", quarantined);
            values.put("internalErrors", internalErrors);
            values.put("bytesServed", bytesServed);
            // Zero skips while the cache is ready means the prefetch bypass did not install, which
            // is the difference between a loading thread that waits on one decoder thread and one
            // that does not. It is not visible in hits, which count only what the cache served.
            values.put("prefetchSkipped", prefetchSkipped);
            values.put("prefetchKept", prefetchKept);
            values.put("circuitBreakerActive", disableReasons.contains(DisableReason.CIRCUIT_BREAKER));
            values.put("disableReasons", disabled);
            values.put("fallbackReasons", reasons);
            return Map.copyOf(values);
        }
    }

    private static String label(Enum<?> value) {
        return value.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    private static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
