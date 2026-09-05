package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.GpuTextureFootprint;
import dev.starsector.preflight.core.PreparedTexture;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.ImageProducer;
import java.awt.image.Raster;
import java.awt.image.SampleModel;
import java.awt.image.WritableRaster;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Runtime bridge for upload-ready SPFT pixels with bounded direct-buffer ownership. */
public final class TexturePreparedPixelRuntime {
    static final String PLAN_ID = "texture-prepared-pixels-v2";
    static final String COHERENT_ORIGINAL_CONVERT_PROPERTY =
            "preflight.preparedPixels.coherentOriginalConvert";
    static final String ORIGINAL_LAYOUT_PROBE_PROPERTY = "preflight.preparedPixels.originalLayoutProbe";
    public static final String COHERENT_DIRECT_PROPERTY =
            "preflight.preparedPixels.coherentDirect";
    public static final String WINDOWS_COLD_PROBE_PROPERTY =
            "preflight.texture.windowsPreparedColdProbe";
    public static final String ATTRIBUTION_PROPERTY = "preflight.texture.preparedLoadAttribution";
    private static volatile boolean attributionEnabled;
    private static long lookupNanos, layoutNanos, carrierNanos, packNanos, attributedLoads;
    static final int MAX_TEXTURE_BYTES = 32 * 1024 * 1024;
    static final long MAX_ACTIVE_DIRECT_BYTES = 64L * 1024 * 1024;
    static final int MAX_ACTIVE_BUFFERS = 1_024;
    private static final int MAX_COLD_PROBE_SAMPLES = 16;
    private static final String COLD_PROBE_TARGET_PATH = "graphics/cursors/cursor_blue.png";
    private static final int MAX_LAYOUT_OBSERVATIONS = 16;
    private static final int ZERO_CHUNK_BYTES = 8 * 1024;
    private static final String KALEIDOSCOPE_PREFIX = "graphics/kaleidoscope/";
    private static final int MAX_LEARNED_KALEIDOSCOPE_PATHS = 512;
    private static final long MAX_LEARNED_KALEIDOSCOPE_BYTES = 192L * 1024 * 1024;
    private static final byte[] ZERO_CHUNK = new byte[ZERO_CHUNK_BYTES];

    private static final Object LOCK = new Object();
    private static final IdentityHashMap<ByteBuffer, ActiveBuffer> ACTIVE = new IdentityHashMap<>();
    private static final IdentityHashMap<Thread, ArrayDeque<ByteBuffer>> IN_FLIGHT = new IdentityHashMap<>();
    private static final Set<String> PREFETCH_QUEUED = new HashSet<>();
    private static final Set<String> LEARNED_KALEIDOSCOPE_QUEUED = new LinkedHashSet<>();
    private static final Telemetry TELEMETRY = new Telemetry();
    private static final SeamTimer LOAD_CLOCK = new SeamTimer();
    private static final SeamTimer PREPARE_CLOCK = new SeamTimer();
    private static final AtomicInteger COLD_PROBE_CLAIMS = new AtomicInteger();
    private static final AtomicBoolean COLD_PROBE_TARGET_CLAIMED = new AtomicBoolean();
    private static final ThreadLocal<ColdProbeSample> ACTIVE_COLD_PROBE = new ThreadLocal<>();
    private static final ThreadLocal<OriginalDecodeSample> ACTIVE_ORIGINAL_DECODE = new ThreadLocal<>();
    private static final List<Map<String, Object>> COLD_PROBE_SAMPLES = new ArrayList<>();
    private static final List<OriginalDecodeSample> ORIGINAL_DECODE_TOP = new ArrayList<>();
    private static volatile boolean coldProbeEnabled;
    private static long originalDecodeStarts;
    private static long originalDecodeCalls;
    private static long originalDecodeAlternateExits;
    private static volatile boolean selected;
    private static long activeBytes;
    private static long peakBytes;
    private static int pendingBuffers;
    private static Map<?, ?> retainedLearnedKaleidoscopeResults;
    private static List<String> preferredPreparedPrefetchOrder = List.of();

    private TexturePreparedPixelRuntime() {
    }

    static void beginSession() {
        TexturePreparedResourceRuntime.beginSession();
        selected = false;
        coldProbeEnabled = Boolean.getBoolean(WINDOWS_COLD_PROBE_PROPERTY);
        attributionEnabled = Boolean.getBoolean(ATTRIBUTION_PROPERTY);
        synchronized (LOCK) { lookupNanos = layoutNanos = carrierNanos = packNanos = attributedLoads = 0; }
        synchronized (LOCK) {
            ACTIVE.clear();
            IN_FLIGHT.clear();
            PREFETCH_QUEUED.clear();
            LEARNED_KALEIDOSCOPE_QUEUED.clear();
            retainedLearnedKaleidoscopeResults = null;
            preferredPreparedPrefetchOrder = List.of();
            COLD_PROBE_SAMPLES.clear();
            ORIGINAL_DECODE_TOP.clear();
            originalDecodeStarts = 0L;
            originalDecodeCalls = 0L;
            originalDecodeAlternateExits = 0L;
            activeBytes = 0;
            peakBytes = 0;
            pendingBuffers = 0;
        }
        TELEMETRY.reset();
        TexturePrefetchShutdownRuntime.reset();
        LOAD_CLOCK.reset();
        PREPARE_CLOCK.reset();
        COLD_PROBE_CLAIMS.set(0);
        COLD_PROBE_TARGET_CLAIMED.set(false);
        ACTIVE_COLD_PROBE.remove();
        ACTIVE_ORIGINAL_DECODE.remove();
    }

    static void select(TextureAdapterMode mode) {
        selected = mode == TextureAdapterMode.PREPARED_PIXELS;
    }

    static boolean ready() {
        return selected && TextureCompatibilityRuntime.ready();
    }

    /**
     * True when the image handed back to the loader is a token rather than a readable image.
     *
     * <p>It used to be true whenever this mode was selected. The carrier was a 1x1 raster that
     * reported the texture's real dimensions, because the point of the mode is that the pixels
     * never become a {@link BufferedImage} at all. That contract held for exactly one consumer --
     * the conversion this plan rewrites -- and any other consumer that walks {@code 0..getWidth()}
     * calling {@code raster.getPixel} read off the end of a single pixel.
     *
     * <p>Nothing enforced it, and it survived only because the game's own prefetcher happened to
     * answer first for the textures other consumers read. That was luck, not design: routing those
     * paths to the cache crashed the load in {@code com.fs.graphics.oO0O}, a greyscale-to-alpha mask
     * converter, on 2026-08-01, and the prefetch bypass had to be disabled here as a result.
     *
     * <p>It is now false, because {@link CarrierImage} always has a full-size readable surface. Its
     * ordinary surface maps top-down raster reads onto immutable bottom-up prepared pixels without
     * copying them; it materialises a conventional writable raster before exposing one to another
     * consumer. The invariant itself is pinned by a test that walks the whole raster of a carrier,
     * not by this method.
     */
    static boolean servesUnreadableCarriers() {
        return false;
    }

    /** Returns a prepared-texture carrier, or {@code null} for original decode fallback. */
    public static BufferedImage load(String logicalPath) {
        long entry = LOAD_CLOCK.enter();
        try {
            return carrierFor(logicalPath);
        } finally {
            LOAD_CLOCK.exit(entry);
        }
    }

    /** Keeps only the first exact prepared enqueue; ordinary and generated paths stay untouched. */
    public static boolean shouldQueuePreparedPrefetch(String logicalPath) {
        String key = TextureCompatibilityRuntime.preparedPrefetchKey(logicalPath);
        if (key == null) {
            TELEMETRY.prefetchOriginalEnqueue();
            return true;
        }
        TextureAccessLearningRuntime.observePrefetch(key);
        boolean first;
        synchronized (LOCK) {
            first = PREFETCH_QUEUED.add(key);
        }
        if (first) {
            TELEMETRY.prefetchPreparedEnqueue();
            return true;
        }
        TELEMETRY.prefetchDuplicateDecline();
        return false;
    }

    /** Supplies a worker-owned carrier, or null so the exact original image decoder runs. */
    public static BufferedImage prefetchLoad(String logicalPath) {
        String key = TextureCompatibilityRuntime.preparedPrefetchKey(logicalPath);
        if (key == null) {
            TELEMETRY.prefetchOriginalDecode();
            return null;
        }
        boolean learnedKaleidoscope;
        synchronized (LOCK) {
            learnedKaleidoscope = LEARNED_KALEIDOSCOPE_QUEUED.contains(key);
        }
        BufferedImage image = TexturePreparedStagingRuntime.take(logicalPath);
        if (image == null) {
            image = load(logicalPath);
        }
        if (image == null) {
            TELEMETRY.prefetchOriginalDecode();
        } else {
            TELEMETRY.prefetchPreparedHit();
        }
        if (learnedKaleidoscope) {
            TELEMETRY.learnedKaleidoscopeWorkerResult(image != null);
        }
        return image;
    }

    /** Captures the exact stock-prioritized resource path order before the worker starts. */
    public static void rememberPreparedPrefetchOrder(List<?> resources) {
        if (!Boolean.getBoolean(TexturePreparedPrefetchPlan.WINDOWS_RESOURCE_ORDER_PROPERTY)
                || resources == null) {
            return;
        }
        try {
            Field pathField = resourcePathField(resources);
            if (pathField == null) {
                TELEMETRY.preparedPriorityError();
                return;
            }
            LinkedHashSet<String> ordered = new LinkedHashSet<>();
            for (Object resource : resources) {
                if (resource == null) continue;
                Object value = pathField.get(resource);
                if (value instanceof String path && !path.isEmpty()) {
                    ordered.add(path);
                }
            }
            synchronized (LOCK) {
                preferredPreparedPrefetchOrder = List.copyOf(ordered);
            }
            TELEMETRY.preparedPriorityCaptured(resources.size(), ordered.size(),
                    ordered.isEmpty() ? null : ordered.iterator().next());
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            synchronized (LOCK) {
                preferredPreparedPrefetchOrder = List.of();
            }
            TELEMETRY.preparedPriorityError();
        }
    }

    /** Stably aligns known prepared paths and leaves every unknown path in relative order. */
    public static void reorderPreparedPrefetches(List<String> imageQueue) {
        if (!Boolean.getBoolean(TexturePreparedPrefetchPlan.WINDOWS_RESOURCE_ORDER_PROPERTY)
                || imageQueue == null) {
            return;
        }
        try {
            List<String> preferred;
            synchronized (LOCK) {
                preferred = preferredPreparedPrefetchOrder;
                preferredPreparedPrefetchOrder = List.of();
            }
            if (preferred.isEmpty()) {
                TELEMETRY.preparedPriorityError();
                return;
            }
            Map<String, Integer> ranks = new HashMap<>(preferred.size() * 4 / 3 + 1);
            for (int index = 0; index < preferred.size(); index++) {
                ranks.putIfAbsent(preferred.get(index), index);
            }
            synchronized (imageQueue) {
                List<String> before = List.copyOf(imageQueue);
                Set<String> queued = new HashSet<>(imageQueue);
                String firstDesired = null;
                for (String path : preferred) {
                    if (queued.contains(path)) {
                        firstDesired = path;
                        break;
                    }
                }
                imageQueue.sort((left, right) -> Integer.compare(
                        ranks.getOrDefault(left, Integer.MAX_VALUE),
                        ranks.getOrDefault(right, Integer.MAX_VALUE)));
                int matched = 0;
                int moved = 0;
                for (int index = 0; index < imageQueue.size(); index++) {
                    String path = imageQueue.get(index);
                    if (ranks.containsKey(path)) matched++;
                    if (!Objects.equals(before.get(index), path)) moved++;
                }
                TELEMETRY.preparedPriorityReordered(
                        imageQueue.size(), matched, moved,
                        firstDesired,
                        before.isEmpty() ? null : before.get(0),
                        imageQueue.isEmpty() ? null : imageQueue.get(0));
            }
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            TELEMETRY.preparedPriorityError();
        }
    }

    private static Field resourcePathField(List<?> resources) {
        Class<?> resourceClass = null;
        for (Object resource : resources) {
            if (resource != null) {
                resourceClass = resource.getClass();
                break;
            }
        }
        if (resourceClass == null) return null;
        Field found = null;
        for (Field field : resourceClass.getDeclaredFields()) {
            if (field.getType() != String.class || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (found != null) return null;
            found = field;
        }
        if (found != null && !found.trySetAccessible()) return null;
        return found;
    }

    /** Adds bounded callback-only Kaleidoscope textures before the reviewed Windows worker starts. */
    public static void seedLearnedKaleidoscopePrefetches(List<String> imageQueue) {
        if (!Boolean.getBoolean(TexturePreparedPrefetchPlan.WINDOWS_KALEIDOSCOPE_PROPERTY)
                || imageQueue == null) {
            return;
        }
        long acceptedBytes = 0L;
        int accepted = 0;
        try {
            for (String observed : TextureAccessLearningRuntime.accessSnapshot()) {
                if (observed == null || !observed.startsWith(KALEIDOSCOPE_PREFIX)) {
                    continue;
                }
                TELEMETRY.learnedKaleidoscopeCandidate();
                String key = TextureCompatibilityRuntime.preparedPrefetchKey(observed);
                int bytes = TextureCompatibilityRuntime.preparedPrefetchBytes(observed);
                if (key == null || bytes <= 0) {
                    TELEMETRY.learnedKaleidoscopeIneligible();
                    continue;
                }
                if (accepted >= MAX_LEARNED_KALEIDOSCOPE_PATHS
                        || acceptedBytes > MAX_LEARNED_KALEIDOSCOPE_BYTES - bytes) {
                    TELEMETRY.learnedKaleidoscopeBoundDecline();
                    continue;
                }
                synchronized (LOCK) {
                    if (!PREFETCH_QUEUED.add(key)) {
                        TELEMETRY.learnedKaleidoscopeDuplicate();
                        continue;
                    }
                    LEARNED_KALEIDOSCOPE_QUEUED.add(key);
                }
                try {
                    imageQueue.add(key);
                } catch (ThreadDeath | VirtualMachineError fatal) {
                    throw fatal;
                } catch (Throwable error) {
                    synchronized (LOCK) {
                        PREFETCH_QUEUED.remove(key);
                        LEARNED_KALEIDOSCOPE_QUEUED.remove(key);
                    }
                    TELEMETRY.learnedKaleidoscopeError();
                    continue;
                }
                accepted++;
                acceptedBytes += bytes;
                TELEMETRY.learnedKaleidoscopeSeed(bytes);
            }
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            TELEMETRY.learnedKaleidoscopeError();
        }
    }

    /** Replaces the stock clear with retention of only the learned callback results. */
    public static void retainLearnedKaleidoscopePrefetchResults(
            List<?> imageQueue, Map<?, ?> imageResults) {
        if (!Boolean.getBoolean(TexturePreparedPrefetchPlan.WINDOWS_KALEIDOSCOPE_PROPERTY)) {
            imageResults.clear();
            return;
        }
        try {
            Set<String> retainedKeys;
            synchronized (LOCK) {
                retainedKeys = Set.copyOf(LEARNED_KALEIDOSCOPE_QUEUED);
            }
            int queueBefore = imageQueue.size();
            imageQueue.removeAll(retainedKeys);
            TELEMETRY.learnedKaleidoscopePendingRemoved(queueBefore - imageQueue.size());
            Iterator<?> iterator = imageResults.keySet().iterator();
            while (iterator.hasNext()) {
                Object key = iterator.next();
                if (!(key instanceof String path) || !retainedKeys.contains(path)) {
                    iterator.remove();
                }
            }
            synchronized (LOCK) {
                retainedLearnedKaleidoscopeResults = imageResults;
            }
            TELEMETRY.learnedKaleidoscopeRetained(imageResults.size());
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            imageResults.clear();
            synchronized (LOCK) {
                retainedLearnedKaleidoscopeResults = null;
            }
            TELEMETRY.learnedKaleidoscopeError();
        }
    }

    /** Restores the stock empty-map invariant once application callbacks and the menu are ready. */
    static void completeLearnedKaleidoscopePrefetches() {
        Map<?, ?> results;
        int seeded;
        synchronized (LOCK) {
            results = retainedLearnedKaleidoscopeResults;
            retainedLearnedKaleidoscopeResults = null;
            seeded = LEARNED_KALEIDOSCOPE_QUEUED.size();
        }
        if (results == null && seeded == 0) {
            return;
        }
        int leftovers = results == null ? 0 : results.size();
        if (results != null) {
            results.clear();
        }
        synchronized (LOCK) {
            LEARNED_KALEIDOSCOPE_QUEUED.clear();
        }
        TELEMETRY.learnedKaleidoscopeComplete(seeded, leftovers);
    }

    static long preparedBytes(BufferedImage image) {
        return image instanceof CarrierImage carrier ? carrier.texture.pixelBytes() : 0L;
    }

    private static BufferedImage carrierFor(String logicalPath) {
        ColdProbeSample coldProbe = beginColdProbe(logicalPath);
        try {
            if (!ready()) {
                coldResult(coldProbe, "not-ready");
                return null;
            }
            long lookupStarted = coldNow(coldProbe);
            PreparedTexture texture = TextureCompatibilityRuntime.lookup(logicalPath);
            coldLookupFinished(coldProbe, lookupStarted);
            if (texture == null) {
                coldResult(coldProbe, "lookup-miss");
                return null;
            }
            long layoutStarted = coldNow(coldProbe);
            UploadLayout layout = uploadLayout(texture);
            coldLayoutFinished(coldProbe, layoutStarted);
            if (layout == null || layout.uploadBytes() > MAX_TEXTURE_BYTES) {
                coldResult(coldProbe, "layout-decline");
                TELEMETRY.dimensionFallback();
                TELEMETRY.fallback();
                TextureCompatibilityRuntime.declined(TextureCompatibilityRuntime.FallbackReason.UNSUPPORTED_TEXTURE);
                return null;
            }

            boolean npot = layout.paddingBytes() > 0;
            boolean coherentDirect = npot && Boolean.getBoolean(COHERENT_DIRECT_PROPERTY);
            boolean coherentOriginalConvert = npot
                    && !coherentDirect
                    && (Boolean.getBoolean(COHERENT_ORIGINAL_CONVERT_PROPERTY)
                        || TexturePaddingRuntime.originalConversionForWindowsCeiling(
                                texture.originalWidth(), texture.originalHeight()));
            long carrierStarted = coldNow(coldProbe);
            try {
                CarrierImage carrier = new CarrierImage(
                        logicalPath,
                        texture,
                        layout,
                        coherentOriginalConvert,
                        coherentDirect);
                coldCarrierFinished(coldProbe, carrierStarted);
                coldResult(coldProbe, "carrier");
                TELEMETRY.carrier(carrier.rasterBytes, carrier.coherent(), carrier.coherentDirect);
                return carrier;
            } catch (ThreadDeath | VirtualMachineError fatal) {
                coldCarrierFinished(coldProbe, carrierStarted);
                coldResult(coldProbe, "fatal");
                throw fatal;
            } catch (Throwable error) {
                coldCarrierFinished(coldProbe, carrierStarted);
                coldResult(coldProbe, "bridge-fallback");
                TELEMETRY.internalError();
                TELEMETRY.fallback();
                TextureCompatibilityRuntime.internalFailure();
                TextureCompatibilityRuntime.declined(TextureCompatibilityRuntime.FallbackReason.PREPARED_PIXEL_BRIDGE);
                return null;
            }
        } finally {
            finishColdProbe(coldProbe);
        }
    }

    public static void originalPrefetchDecodeStart(String logicalPath) {
        if (!coldProbeEnabled) {
            return;
        }
        long now = System.nanoTime();
        OriginalDecodeSample previous = ACTIVE_ORIGINAL_DECODE.get();
        if (previous != null) {
            previous.complete(now, "alternate-exit");
            synchronized (LOCK) {
                originalDecodeAlternateExits++;
            }
        }
        OriginalDecodeSample sample = new OriginalDecodeSample(
                logicalPath == null ? "" : logicalPath,
                Thread.currentThread().getName(),
                now);
        ACTIVE_ORIGINAL_DECODE.set(sample);
        synchronized (LOCK) {
            originalDecodeStarts++;
            if (ORIGINAL_DECODE_TOP.size() < MAX_COLD_PROBE_SAMPLES) {
                ORIGINAL_DECODE_TOP.add(sample);
            }
        }
    }

    public static void originalPrefetchDecodeEnd() {
        OriginalDecodeSample sample = ACTIVE_ORIGINAL_DECODE.get();
        if (sample == null) {
            return;
        }
        ACTIVE_ORIGINAL_DECODE.remove();
        sample.complete(System.nanoTime(), "return");
        synchronized (LOCK) {
            originalDecodeCalls++;
        }
    }

    private static ColdProbeSample beginColdProbe(String logicalPath) {
        if (!coldProbeEnabled) {
            return null;
        }
        int ordinal = COLD_PROBE_CLAIMS.incrementAndGet();
        boolean target = COLD_PROBE_TARGET_PATH.equals(logicalPath)
                && COLD_PROBE_TARGET_CLAIMED.compareAndSet(false, true);
        if (ordinal > MAX_COLD_PROBE_SAMPLES && !target) {
            return null;
        }
        if (target) {
            StartupPhaseRuntime.mark("prepared-cursor-carrier-start");
        }
        ColdProbeSample sample = new ColdProbeSample(ordinal, logicalPath, target, System.nanoTime());
        ACTIVE_COLD_PROBE.set(sample);
        return sample;
    }

    private static long coldNow(ColdProbeSample sample) {
        return sample == null && !attributionEnabled ? 0L : System.nanoTime();
    }

    private static void coldLookupFinished(ColdProbeSample sample, long started) {
        if (attributionEnabled && started != 0L) {
            synchronized (LOCK) { lookupNanos += Math.max(0L, System.nanoTime() - started); attributedLoads++; }
        }
        if (sample != null) {
            sample.lookupNanos = Math.max(0L, System.nanoTime() - started);
        }
    }

    private static void coldLayoutFinished(ColdProbeSample sample, long started) {
        if (attributionEnabled && started != 0L) {
            synchronized (LOCK) { layoutNanos += Math.max(0L, System.nanoTime() - started); }
        }
        if (sample != null) {
            sample.layoutNanos = Math.max(0L, System.nanoTime() - started);
        }
    }

    private static void coldCarrierFinished(ColdProbeSample sample, long started) {
        if (attributionEnabled && started != 0L) {
            synchronized (LOCK) { carrierNanos += Math.max(0L, System.nanoTime() - started); }
        }
        if (sample != null && sample.carrierNanos == 0L) {
            sample.carrierNanos = Math.max(0L, System.nanoTime() - started);
        }
    }

    private static void coldResult(ColdProbeSample sample, String result) {
        if (sample != null) {
            sample.result = result;
        }
    }

    static long beginColdPackRead() {
        return attributionEnabled || (coldProbeEnabled && ACTIVE_COLD_PROBE.get() != null) ? System.nanoTime() : 0L;
    }

    static void finishColdPackRead(long started) {
        if (started == 0L) {
            return;
        }
        if (attributionEnabled) {
            synchronized (LOCK) { packNanos += Math.max(0L, System.nanoTime() - started); }
        }
        ColdProbeSample sample = ACTIVE_COLD_PROBE.get();
        if (sample != null) {
            sample.packReadNanos = saturatedAdd(
                    sample.packReadNanos,
                    Math.max(0L, System.nanoTime() - started));
        }
    }

    private static void finishColdProbe(ColdProbeSample sample) {
        if (sample == null) {
            return;
        }
        sample.totalNanos = Math.max(0L, System.nanoTime() - sample.startedNanos);
        if (sample.result == null) {
            sample.result = "unknown";
        }
        ACTIVE_COLD_PROBE.remove();
        if (sample.target) {
            StartupPhaseRuntime.mark("prepared-cursor-carrier-complete");
        }
        synchronized (LOCK) {
            if (COLD_PROBE_SAMPLES.size() < MAX_COLD_PROBE_SAMPLES + 1) {
                COLD_PROBE_SAMPLES.add(sample.telemetry());
            }
        }
    }

    public static boolean isCarrier(BufferedImage image) {
        return image instanceof CarrierImage;
    }

    public static String originalPath(BufferedImage image) {
        return image instanceof CarrierImage carrier ? carrier.logicalPath : null;
    }

    /**
     * Returns true only for the explicit diagnostic that reconstructs a coherent cached image
     * but still executes Starsector's original converter and returns its original buffer.
     */
    public static boolean useCarrierForOriginalFallback(BufferedImage image) {
        if (!(image instanceof CarrierImage carrier) || !carrier.coherentOriginalConvert) {
            return false;
        }
        if (carrier.creditSharedHit()) {
            TextureCompatibilityRuntime.hit(carrier.texture.pixelBytes());
        }
        TELEMETRY.coherentOriginalDecodeBypass();
        return true;
    }

    /** The typed resource path explicitly chooses the coherent image with the installed converter. */
    static void creditPreparedResourceFallback(BufferedImage image) {
        if (image instanceof CarrierImage carrier && carrier.creditSharedHit()) {
            TextureCompatibilityRuntime.hit(carrier.texture.pixelBytes());
            TELEMETRY.coherentOriginalDecodeBypass();
        }
    }

    /** Creates one bounded direct upload buffer and returns stored derived colors. */
    public static PreparedPixel prepare(BufferedImage image) {
        long entry = PREPARE_CLOCK.enter();
        try {
            return prepareCarrier(image);
        } finally {
            PREPARE_CLOCK.exit(entry);
        }
    }

    private static PreparedPixel prepareCarrier(BufferedImage image) {
        if (!(image instanceof CarrierImage carrier)) {
            return null;
        }
        TELEMETRY.directAttempt();
        PreparedTexture texture = carrier.texture;
        UploadLayout layout = carrier.layout;

        // Padding removal serves the texture at its true size instead of declining it. This is the
        // other half of the invariant TexturePaddingRuntime governs: the buffer below is unpadded
        // only while the installed fold is also bypassed, so the glTexImage2D allocation agrees with
        // it. Neither half is safe alone, and both read the same gate for that reason.
        boolean unpadded = layout.paddingBytes() > 0
                && TexturePaddingRuntime.availableFor(texture.originalWidth(), texture.originalHeight());

        // The safe default keeps NPOT textures on Starsector's original path. The explicit
        // coherent-direct diagnostic is the only path allowed to supply a direct NPOT buffer.
        if (layout.paddingBytes() > 0 && !carrier.coherentDirect && !unpadded) {
            TELEMETRY.npotProbeFallback();
            if (carrier.coherentOriginalConvert) {
                TELEMETRY.coherentOriginalConvertFallback();
            }
            TELEMETRY.fallback();
            TextureCompatibilityRuntime.declined(TextureCompatibilityRuntime.FallbackReason.UNSUPPORTED_TEXTURE);
            return null;
        }

        int bytes = unpadded ? texture.pixelBytes() : layout.uploadBytes();
        if (!reserve(bytes)) {
            TELEMETRY.fallback();
            TextureCompatibilityRuntime.declined(TextureCompatibilityRuntime.FallbackReason.DIRECT_MEMORY_LIMIT);
            return null;
        }

        ByteBuffer buffer = null;
        boolean registered = false;
        try {
            buffer = ByteBuffer.allocateDirect(bytes);
            if (layout.paddingBytes() > 0 && !unpadded) {
                writeUploadPixels(buffer, texture, layout);
            } else {
                // Unpadded and already-power-of-two textures are the same case here: the stored
                // pixels are exactly what the driver reads, with no rows or columns to invent.
                buffer.put(texture.pixelsView());
            }
            buffer.flip();
            synchronized (LOCK) {
                pendingBuffers--;
                ACTIVE.put(buffer, new ActiveBuffer(bytes, unpadded ? 2 : 0));
                IN_FLIGHT.computeIfAbsent(Thread.currentThread(), ignored -> new ArrayDeque<>()).addLast(buffer);
                registered = true;
            }
            // The dimensions travel with the buffer because the wrapper writes them onto the texture
            // object, whose setters recompute the texture-coordinate ratio as source/stored. Serving
            // unpadded pixels while reporting padded dimensions would leave that ratio scaled for an
            // allocation that no longer exists -- correct-looking numbers, wrong pixels on screen.
            PreparedPixel result = new PreparedPixel(
                    buffer,
                    color(texture.color0Rgba()),
                    color(texture.color1Rgba()),
                    color(texture.color2Rgba()),
                    unpadded ? texture.originalWidth() : layout.uploadWidth(),
                    unpadded ? texture.originalHeight() : layout.uploadHeight(),
                    texture.channels(),
                    bytes);
            if (unpadded) {
                TexturePaddingRuntime.served(layout.paddingBytes());
            }
            TELEMETRY.hit(
                    texture.pixelBytes(),
                    bytes,
                    unpadded ? 0 : layout.paddingBytes(),
                    carrier.coherentDirect);
            if (carrier.creditSharedHit()) {
                TextureCompatibilityRuntime.hit(texture.pixelBytes());
            }
            return result;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            if (registered) {
                release(buffer);
            } else {
                undoReservation(bytes);
            }
            throw fatal;
        } catch (Throwable error) {
            if (registered) {
                release(buffer);
            } else {
                undoReservation(bytes);
            }
            TELEMETRY.internalError();
            TELEMETRY.fallback();
            TextureCompatibilityRuntime.internalFailure();
            TextureCompatibilityRuntime.declined(TextureCompatibilityRuntime.FallbackReason.PREPARED_PIXEL_BRIDGE);
            return null;
        }
    }

    /**
     * Records the exact original converter layout for an NPOT carrier without changing the
     * original buffer's position, limit, bytes, cleanup, or exception behavior.
     */
    public static void observeOriginalFallback(BufferedImage image, ByteBuffer originalBuffer) {
        if (!Boolean.getBoolean(ORIGINAL_LAYOUT_PROBE_PROPERTY)
                || !(image instanceof CarrierImage carrier)
                || carrier.layout.paddingBytes() <= 0
                || originalBuffer == null) {
            return;
        }
        try {
            TELEMETRY.layoutObservation(inspectOriginalLayout(carrier, originalBuffer));
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // This is evidence-only. Starsector's original buffer remains authoritative.
            TELEMETRY.layoutObservationError();
        }
    }

    /** Releases the newest prepared buffer owned by the current converter caller. */
    public static void releaseCurrentThreadBuffer() {
        ByteBuffer buffer = null;
        synchronized (LOCK) {
            ArrayDeque<ByteBuffer> buffers = IN_FLIGHT.get(Thread.currentThread());
            if (buffers != null) {
                buffer = buffers.pollLast();
                if (buffers.isEmpty()) {
                    IN_FLIGHT.remove(Thread.currentThread());
                }
            }
        }
        release(buffer);
    }

    /** Releases accounting after Starsector's original cleanup method has run. */
    public static void release(ByteBuffer buffer) {
        if (buffer == null) {
            return;
        }
        ActiveBuffer active;
        synchronized (LOCK) {
            removeTrackedLocked(buffer);
            active = ACTIVE.remove(buffer);
            if (active != null) {
                activeBytes -= active.bytes();
                if (activeBytes < 0) {
                    activeBytes = 0;
                }
            }
        }
        if (active != null) {
            TELEMETRY.release(active.bytes());
        }
    }

    /**
     * Whether the current loader thread owns a verified true-size prepared upload.
     *
     * <p>The extracted dimension fold runs after the converter returns and before its buffer is
     * cleaned up. Tying the fold to that exact in-flight buffer makes the allocation decision local
     * to one prepared hit. A cache miss or any contained prepared-path failure has no registered
     * buffer, so the original converter and the original power-of-two allocation remain paired.
     */
    static boolean currentThreadHasTrueSizeUpload() {
        synchronized (LOCK) {
            ArrayDeque<ByteBuffer> buffers = IN_FLIGHT.get(Thread.currentThread());
            if (buffers == null) {
                return false;
            }
            ByteBuffer buffer = buffers.peekLast();
            ActiveBuffer active = buffer == null ? null : ACTIVE.get(buffer);
            return active != null && active.trueSizeFoldsRemaining() > 0;
        }
    }

    /** Claims one of the exact loader's two allocation-dimension decisions. */
    static boolean claimCurrentThreadTrueSizeFold() {
        synchronized (LOCK) {
            ArrayDeque<ByteBuffer> buffers = IN_FLIGHT.get(Thread.currentThread());
            if (buffers == null) {
                return false;
            }
            ByteBuffer buffer = buffers.peekLast();
            ActiveBuffer active = buffer == null ? null : ACTIVE.get(buffer);
            if (active == null || active.trueSizeFoldsRemaining() <= 0) {
                return false;
            }
            active.claimTrueSizeFold();
            return true;
        }
    }

    static Map<String, Object> telemetry() {
        long currentBytes;
        long maximumBytes;
        int currentBuffers;
        int currentPending;
        synchronized (LOCK) {
            currentBytes = activeBytes;
            maximumBytes = peakBytes;
            currentBuffers = ACTIVE.size();
            currentPending = pendingBuffers;
        }
        Map<String, Object> values = new LinkedHashMap<>(TELEMETRY.snapshot(
                currentBytes,
                maximumBytes,
                currentBuffers,
                currentPending,
                ready()));
        values.putAll(LOAD_CLOCK.snapshot("load"));
        values.putAll(PREPARE_CLOCK.snapshot("prepare"));
        synchronized (LOCK) {
            values.put("loadAttribution", Map.of("enabled", attributionEnabled,
                    "loads", attributedLoads, "lookupMillis", lookupNanos / 1_000_000L,
                    "packMillis", packNanos / 1_000_000L, "layoutMillis", layoutNanos / 1_000_000L,
                    "carrierMillis", carrierNanos / 1_000_000L));
        }
        values.put("prefetchPool", TexturePreparedPrefetchPoolRuntime.report());
        values.put("prefetchShutdown", TexturePrefetchShutdownRuntime.report());
        values.put("prefetchStaging", TexturePreparedStagingRuntime.telemetry());
        values.put("preparedResources", TexturePreparedResourceRuntime.telemetry());
        values.put("uploadProbe", TextureUploadProbeRuntime.telemetry());
        values.put("coldProbe", coldProbeTelemetry());
        return Map.copyOf(values);
    }

    private static Map<String, Object> coldProbeTelemetry() {
        List<Map<String, Object>> samples;
        List<Map<String, Object>> originalDecodes;
        long originalStarts;
        long originalCalls;
        long alternateExits;
        synchronized (LOCK) {
            samples = List.copyOf(COLD_PROBE_SAMPLES);
            List<OriginalDecodeSample> sorted = new ArrayList<>(ORIGINAL_DECODE_TOP);
            long observedAt = System.nanoTime();
            sorted.sort((left, right) -> Long.compare(
                    right.observedDurationNanos(observedAt),
                    left.observedDurationNanos(observedAt)));
            originalDecodes = sorted.stream().map(sample -> sample.telemetry(observedAt)).toList();
            originalStarts = originalDecodeStarts;
            originalCalls = originalDecodeCalls;
            alternateExits = originalDecodeAlternateExits;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", coldProbeEnabled);
        values.put("sampleLimit", MAX_COLD_PROBE_SAMPLES);
        values.put("targetPath", COLD_PROBE_TARGET_PATH);
        values.put("targetClaimed", COLD_PROBE_TARGET_CLAIMED.get());
        values.put("claimed", COLD_PROBE_CLAIMS.get());
        values.put("retained", samples.size());
        values.put("samples", samples);
        values.put("originalDecodeStarts", originalStarts);
        values.put("originalDecodeReturns", originalCalls);
        values.put("originalDecodeAlternateExits", alternateExits);
        values.put("originalDecodeSlowest", originalDecodes);
        return Map.copyOf(values);
    }

    private static boolean reserve(int bytes) {
        synchronized (LOCK) {
            if (bytes <= 0
                    || bytes > MAX_TEXTURE_BYTES
                    || ACTIVE.size() + pendingBuffers >= MAX_ACTIVE_BUFFERS
                    || activeBytes > MAX_ACTIVE_DIRECT_BYTES - bytes) {
                return false;
            }
            pendingBuffers++;
            activeBytes += bytes;
            peakBytes = Math.max(peakBytes, activeBytes);
            return true;
        }
    }

    private static void undoReservation(int bytes) {
        synchronized (LOCK) {
            if (pendingBuffers > 0) {
                pendingBuffers--;
            }
            activeBytes -= bytes;
            if (activeBytes < 0) {
                activeBytes = 0;
            }
        }
    }

    /**
     * The padded dimension the engine will read. Delegates to {@link GpuTextureFootprint} so the
     * upload buffer and the VRAM reports cannot drift apart. The previous local implementation
     * returned 1 for a one-pixel edge, where the engine's {@code get2Fold} returns 2 — which would
     * have sized the buffer at half what {@code glTexImage2D} reads.
     */
    static int expectedUploadDimension(int sourceDimension) {
        return GpuTextureFootprint.uploadDimension(sourceDimension);
    }

    private static UploadLayout uploadLayout(PreparedTexture texture) {
        int originalWidth = texture.originalWidth();
        int originalHeight = texture.originalHeight();
        int uploadWidth = expectedUploadDimension(originalWidth);
        int uploadHeight = expectedUploadDimension(originalHeight);
        if (uploadWidth <= 0 || uploadHeight <= 0) {
            return null;
        }
        if (texture.uploadWidth() != originalWidth || texture.uploadHeight() != originalHeight) {
            return null;
        }
        try {
            long sourceBytes = Math.multiplyExact(
                    Math.multiplyExact((long) originalWidth, originalHeight),
                    texture.channels());
            long uploadBytes = Math.multiplyExact(
                    Math.multiplyExact((long) uploadWidth, uploadHeight),
                    texture.channels());
            if (sourceBytes != texture.pixelBytes() || uploadBytes > Integer.MAX_VALUE) {
                return null;
            }
            return new UploadLayout(
                    uploadWidth,
                    uploadHeight,
                    (int) uploadBytes,
                    Math.toIntExact(uploadBytes - sourceBytes));
        } catch (ArithmeticException error) {
            return null;
        }
    }

    private static void writeUploadPixels(
            ByteBuffer buffer,
            PreparedTexture texture,
            UploadLayout layout) {
        // A view rather than pixels(): this reads each row once, straight into the upload buffer,
        // and cloning the whole texture first to do that is the largest copy on the serving path.
        ByteBuffer source = texture.pixelsView();
        int sourceStride = Math.multiplyExact(texture.originalWidth(), texture.channels());
        int uploadStride = Math.multiplyExact(layout.uploadWidth(), texture.channels());
        int rightPadding = uploadStride - sourceStride;
        for (int row = 0; row < texture.originalHeight(); row++) {
            source.limit(row * sourceStride + sourceStride).position(row * sourceStride);
            buffer.put(source);
            putZeroes(buffer, rightPadding);
        }
        putZeroes(buffer, Math.multiplyExact(
                layout.uploadHeight() - texture.originalHeight(),
                uploadStride));
        if (buffer.position() != layout.uploadBytes()) {
            throw new IllegalStateException(
                    "Prepared upload wrote " + buffer.position() + " bytes; expected " + layout.uploadBytes());
        }
    }

    private static void putZeroes(ByteBuffer buffer, int count) {
        int remaining = count;
        while (remaining > 0) {
            int chunk = Math.min(remaining, ZERO_CHUNK.length);
            buffer.put(ZERO_CHUNK, 0, chunk);
            remaining -= chunk;
        }
    }

    private static Map<String, Object> inspectOriginalLayout(
            CarrierImage carrier,
            ByteBuffer originalBuffer) {
        PreparedTexture texture = carrier.texture;
        UploadLayout layout = carrier.layout;
        ByteBuffer sourceBuffer = originalBuffer.duplicate();
        int available = sourceBuffer.remaining();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("logicalPath", carrier.logicalPath);
        values.put("sourceWidth", texture.originalWidth());
        values.put("sourceHeight", texture.originalHeight());
        values.put("uploadWidth", layout.uploadWidth());
        values.put("uploadHeight", layout.uploadHeight());
        values.put("channels", texture.channels());
        values.put("sourceBytes", texture.pixelBytes());
        values.put("uploadBytes", layout.uploadBytes());
        values.put("bufferPosition", sourceBuffer.position());
        values.put("bufferLimit", sourceBuffer.limit());
        values.put("bufferCapacity", sourceBuffer.capacity());
        values.put("bufferRemaining", available);
        values.put("coherentOriginalConvert", carrier.coherentOriginalConvert);
        values.put("coherentDirect", carrier.coherentDirect);
        values.put("carrierRasterWidth", carrier.getRaster().getWidth());
        values.put("carrierRasterHeight", carrier.getRaster().getHeight());
        values.put("carrierSampleModelWidth", carrier.getSampleModel().getWidth());
        values.put("carrierSampleModelHeight", carrier.getSampleModel().getHeight());
        values.put("carrierColorComponents", carrier.getColorModel().getNumComponents());
        values.put("carrierHasAlpha", carrier.getColorModel().hasAlpha());
        if (available < layout.uploadBytes()) {
            values.put("status", "insufficient-original-buffer");
            values.put("candidateMatches", List.of());
            values.put("firstMismatchOffsets", Map.of());
            return Map.copyOf(values);
        }

        byte[] source = texture.pixels();
        CandidateLayout[] candidates = CandidateLayout.values();
        int[] firstMismatch = new int[candidates.length];
        Arrays.fill(firstMismatch, -1);
        int start = sourceBuffer.position();
        for (int offset = 0; offset < layout.uploadBytes(); offset++) {
            byte actual = sourceBuffer.get(start + offset);
            for (int index = 0; index < candidates.length; index++) {
                if (firstMismatch[index] < 0
                        && actual != candidates[index].expected(offset, source, texture, layout)) {
                    firstMismatch[index] = offset;
                }
            }
        }

        List<String> matches = new ArrayList<>();
        Map<String, Object> mismatches = new LinkedHashMap<>();
        for (int index = 0; index < candidates.length; index++) {
            if (firstMismatch[index] < 0) {
                matches.add(candidates[index].id);
            }
            mismatches.put(candidates[index].id, firstMismatch[index]);
        }
        values.put("status", matches.isEmpty() ? "unclassified" : "classified");
        values.put("candidateMatches", List.copyOf(matches));
        values.put("firstMismatchOffsets", Map.copyOf(mismatches));
        return Map.copyOf(values);
    }

    private static void removeTrackedLocked(ByteBuffer buffer) {
        var threadIterator = IN_FLIGHT.entrySet().iterator();
        while (threadIterator.hasNext()) {
            Map.Entry<Thread, ArrayDeque<ByteBuffer>> entry = threadIterator.next();
            var bufferIterator = entry.getValue().iterator();
            while (bufferIterator.hasNext()) {
                if (bufferIterator.next() == buffer) {
                    bufferIterator.remove();
                    if (entry.getValue().isEmpty()) {
                        threadIterator.remove();
                    }
                    return;
                }
            }
        }
    }

    private static Color color(int rgba) {
        return new Color(
                PreparedTexture.red(rgba),
                PreparedTexture.green(rgba),
                PreparedTexture.blue(rgba),
                PreparedTexture.alpha(rgba));
    }

    /** Typed bridge object consumed only by the exact transformed TextureLoader class. */
    private static final class OriginalDecodeSample {
        private final String path;
        private final String threadName;
        private final long startedNanos;
        private long durationNanos;
        private String result;

        private OriginalDecodeSample(String path, String threadName, long startedNanos) {
            this.path = path;
            this.threadName = threadName;
            this.startedNanos = startedNanos;
        }

        private void complete(long completedNanos, String completion) {
            if (result == null) {
                durationNanos = Math.max(0L, completedNanos - startedNanos);
                result = completion;
            }
        }

        private long observedDurationNanos(long observedAt) {
            return result == null ? Math.max(0L, observedAt - startedNanos) : durationNanos;
        }

        private Map<String, Object> telemetry(long observedAt) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("path", path);
            values.put("threadName", threadName);
            values.put("result", result == null ? "active-at-report" : result);
            long observedDuration = observedDurationNanos(observedAt);
            values.put("durationNanos", observedDuration);
            values.put("durationMillis", observedDuration / 1_000_000L);
            return Map.copyOf(values);
        }
    }

    private static final class ColdProbeSample {
        private final int ordinal;
        private final String path;
        private final String threadName;
        private final long threadId;
        private final boolean target;
        private final long startedNanos;
        private long totalNanos;
        private long lookupNanos;
        private long packReadNanos;
        private long layoutNanos;
        private long carrierNanos;
        private String result;

        private ColdProbeSample(int ordinal, String path, boolean target, long startedNanos) {
            this.ordinal = ordinal;
            this.path = path == null ? "" : path;
            this.threadName = Thread.currentThread().getName();
            this.threadId = Thread.currentThread().getId();
            this.target = target;
            this.startedNanos = startedNanos;
        }

        private Map<String, Object> telemetry() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("ordinal", ordinal);
            values.put("path", path);
            values.put("result", result);
            values.put("threadName", threadName);
            values.put("threadId", threadId);
            values.put("target", target);
            values.put("totalNanos", totalNanos);
            values.put("totalMillis", totalNanos / 1_000_000L);
            values.put("lookupNanos", lookupNanos);
            values.put("lookupMillis", lookupNanos / 1_000_000L);
            values.put("packReadNanos", packReadNanos);
            values.put("packReadMillis", packReadNanos / 1_000_000L);
            values.put("layoutNanos", layoutNanos);
            values.put("layoutMillis", layoutNanos / 1_000_000L);
            values.put("carrierNanos", carrierNanos);
            values.put("carrierMillis", carrierNanos / 1_000_000L);
            return Map.copyOf(values);
        }
    }

    public record PreparedPixel(
            ByteBuffer buffer,
            Color color0,
            Color color1,
            Color color2,
            int width,
            int height,
            int channels,
            int pixelBytes) {
    }

    private record UploadLayout(
            int uploadWidth,
            int uploadHeight,
            int uploadBytes,
            int paddingBytes) {
    }

    private static final class ActiveBuffer {
        private final int bytes;
        private int trueSizeFoldsRemaining;

        private ActiveBuffer(int bytes, int trueSizeFoldsRemaining) {
            this.bytes = bytes;
            this.trueSizeFoldsRemaining = trueSizeFoldsRemaining;
        }

        private int bytes() {
            return bytes;
        }

        private int trueSizeFoldsRemaining() {
            return trueSizeFoldsRemaining;
        }

        private void claimTrueSizeFold() {
            trueSizeFoldsRemaining--;
        }
    }

    private enum CandidateLayout {
        ROW_PAD_SOURCE_THEN_ZERO_ROWS("row-pad-source-then-zero-rows"),
        ZERO_ROWS_THEN_ROW_PAD_SOURCE("zero-rows-then-row-pad-source"),
        ROW_PAD_REVERSED_SOURCE_THEN_ZERO_ROWS("row-pad-reversed-source-then-zero-rows"),
        ZERO_ROWS_THEN_ROW_PAD_REVERSED_SOURCE("zero-rows-then-row-pad-reversed-source"),
        CONTIGUOUS_SOURCE_THEN_ZERO("contiguous-source-then-zero"),
        ZERO_THEN_CONTIGUOUS_SOURCE("zero-then-contiguous-source");

        private final String id;

        CandidateLayout(String id) {
            this.id = id;
        }

        private byte expected(
                int offset,
                byte[] source,
                PreparedTexture texture,
                UploadLayout layout) {
            int sourceStride = texture.originalWidth() * texture.channels();
            int uploadStride = layout.uploadWidth() * texture.channels();
            int uploadRow = offset / uploadStride;
            int rowOffset = offset % uploadStride;
            int leadingRows = layout.uploadHeight() - texture.originalHeight();
            return switch (this) {
                case ROW_PAD_SOURCE_THEN_ZERO_ROWS -> rowByte(
                        source, sourceStride, rowOffset, uploadRow, texture.originalHeight(), false);
                case ZERO_ROWS_THEN_ROW_PAD_SOURCE -> rowByte(
                        source,
                        sourceStride,
                        rowOffset,
                        uploadRow - leadingRows,
                        texture.originalHeight(),
                        false);
                case ROW_PAD_REVERSED_SOURCE_THEN_ZERO_ROWS -> rowByte(
                        source, sourceStride, rowOffset, uploadRow, texture.originalHeight(), true);
                case ZERO_ROWS_THEN_ROW_PAD_REVERSED_SOURCE -> rowByte(
                        source,
                        sourceStride,
                        rowOffset,
                        uploadRow - leadingRows,
                        texture.originalHeight(),
                        true);
                case CONTIGUOUS_SOURCE_THEN_ZERO -> offset < source.length ? source[offset] : 0;
                case ZERO_THEN_CONTIGUOUS_SOURCE -> {
                    int sourceOffset = offset - (layout.uploadBytes() - source.length);
                    yield sourceOffset >= 0 ? source[sourceOffset] : 0;
                }
            };
        }

        private static byte rowByte(
                byte[] source,
                int sourceStride,
                int rowOffset,
                int sourceRow,
                int sourceHeight,
                boolean reversed) {
            if (sourceRow < 0 || sourceRow >= sourceHeight || rowOffset >= sourceStride) {
                return 0;
            }
            int row = reversed ? sourceHeight - 1 - sourceRow : sourceRow;
            return source[row * sourceStride + rowOffset];
        }
    }

    private static final class CarrierImage extends BufferedImage {
        private final String logicalPath;
        private final PreparedTexture texture;
        private final UploadLayout layout;
        private final boolean coherentOriginalConvert;
        private final boolean coherentDirect;
        private final int rasterBytes;
        private final AtomicBoolean sharedHitCredited = new AtomicBoolean();
        private volatile BufferedImage materialized;

        private CarrierImage(
                String logicalPath,
                PreparedTexture texture,
                UploadLayout layout,
                boolean coherentOriginalConvert,
                boolean coherentDirect) {
            this(
                    logicalPath,
                    texture,
                    layout,
                    // Full-size and readable from construction, but backed by the immutable SPFT
                    // array through a bottom-up row mapping. A conventional DataBufferByte surface
                    // is created only if another consumer asks for a raster or mutation API.
                    TexturePreparedPixelCarrierSurface.lazy(texture),
                    coherentOriginalConvert,
                    coherentDirect);
        }

        private CarrierImage(
                String logicalPath,
                PreparedTexture texture,
                UploadLayout layout,
                TexturePreparedPixelCarrierSurface.Surface surface,
                boolean coherentOriginalConvert,
                boolean coherentDirect) {
            super(surface.colorModel(), surface.raster(), false, null);
            this.logicalPath = logicalPath;
            this.texture = texture;
            this.layout = layout;
            this.coherentOriginalConvert = coherentOriginalConvert && surface.coherent();
            this.coherentDirect = coherentDirect && surface.coherent();
            this.rasterBytes = surface.rasterBytes();
        }

        private boolean coherent() {
            return coherentOriginalConvert || coherentDirect;
        }

        private boolean creditSharedHit() {
            return sharedHitCredited.compareAndSet(false, true);
        }

        private BufferedImage materialized() {
            BufferedImage existing = materialized;
            if (existing != null) {
                return existing;
            }
            synchronized (this) {
                existing = materialized;
                if (existing == null) {
                    TexturePreparedPixelCarrierSurface.Surface surface =
                            TexturePreparedPixelCarrierSurface.coherent(texture);
                    existing = new BufferedImage(surface.colorModel(), surface.raster(), false, null);
                    materialized = existing;
                    TELEMETRY.materialized(surface.rasterBytes(), coherent());
                }
            }
            return existing;
        }

        @Override
        public int getWidth() {
            return texture.originalWidth();
        }

        @Override
        public int getHeight() {
            return texture.originalHeight();
        }

        @Override
        public WritableRaster getRaster() {
            return materialized().getRaster();
        }

        @Override
        public WritableRaster getAlphaRaster() {
            return materialized().getAlphaRaster();
        }

        @Override
        public SampleModel getSampleModel() {
            return materialized().getSampleModel();
        }

        @Override
        public Raster getTile(int tileX, int tileY) {
            return materialized().getTile(tileX, tileY);
        }

        @Override
        public Raster getData() {
            BufferedImage existing = materialized;
            if (existing != null) return existing.getData();
            Raster snapshot = TexturePreparedPixelCarrierSurface.snapshot(texture);
            TELEMETRY.snapshot(texture.pixelBytes());
            return snapshot;
        }

        @Override
        public Raster getData(Rectangle rectangle) {
            return materialized().getData(rectangle);
        }

        @Override
        public WritableRaster copyData(WritableRaster destination) {
            return materialized().copyData(destination);
        }

        @Override
        public WritableRaster getWritableTile(int tileX, int tileY) {
            return materialized().getWritableTile(tileX, tileY);
        }

        @Override
        public void setData(Raster source) {
            materialized().setData(source);
        }

        @Override
        public void setRGB(int x, int y, int rgb) {
            materialized().setRGB(x, y, rgb);
        }

        @Override
        public void setRGB(int startX, int startY, int width, int height,
                int[] rgbArray, int offset, int scansize) {
            materialized().setRGB(startX, startY, width, height, rgbArray, offset, scansize);
        }

        @Override
        public Graphics getGraphics() {
            return materialized().getGraphics();
        }

        @Override
        public Graphics2D createGraphics() {
            return materialized().createGraphics();
        }

        @Override
        public ImageProducer getSource() {
            return materialized().getSource();
        }

        @Override
        public BufferedImage getSubimage(int x, int y, int width, int height) {
            return materialized().getSubimage(x, y, width, height);
        }

        @Override
        public void coerceData(boolean alphaPremultiplied) {
            // BufferedImage's constructor invokes this method virtually before CarrierImage's
            // fields are assigned. Both surfaces are created non-premultiplied and the constructor
            // passes false, so there is nothing to coerce during that one pre-initialisation call.
            if (texture == null) {
                return;
            }
            materialized().coerceData(alphaPremultiplied);
        }
    }

    private static final class Telemetry {
        private long carriers;
        private long carrierRasterBytes;
        private long carrierRasterMaterializations;
        private long carrierSnapshotCopies;
        private long carrierSnapshotBytes;
        private long coherentCarriers;
        private long coherentCarrierBytes;
        private long coherentDirectCarriers;
        private long coherentDirectHits;
        private long directAttempts;
        private long hits;
        private long fallbacks;
        private long dimensionFallbacks;
        private long npotProbeFallbacks;
        private long coherentOriginalConvertFallbacks;
        private long coherentOriginalDecodeBypasses;
        private long paddedUploads;
        private long paddingBytes;
        private long layoutObservationErrors;
        private long internalErrors;
        private long releases;
        private long bytesBypassed;
        private long uploadBytesSupplied;
        private long releasedBytes;
        private long prefetchPreparedEnqueues;
        private long prefetchOriginalEnqueues;
        private long prefetchDuplicateDeclines;
        private long prefetchPreparedHits;
        private long prefetchOriginalDecodes;
        private long learnedKaleidoscopeCandidates;
        private long learnedKaleidoscopeSeeded;
        private long learnedKaleidoscopeSeededBytes;
        private long learnedKaleidoscopeIneligible;
        private long learnedKaleidoscopeBoundDeclines;
        private long learnedKaleidoscopeDuplicates;
        private long learnedKaleidoscopeWorkerHits;
        private long learnedKaleidoscopeWorkerFallbacks;
        private long learnedKaleidoscopeRetainedAtStop;
        private long learnedKaleidoscopePendingRemovedAtStop;
        private long learnedKaleidoscopeConsumedAfterStop;
        private long learnedKaleidoscopeLeftoversCleared;
        private long learnedKaleidoscopeErrors;
        private long preparedPriorityCaptures;
        private long preparedPriorityResources;
        private long preparedPriorityPaths;
        private long preparedPriorityReorders;
        private long preparedPriorityQueueEntries;
        private long preparedPriorityMatched;
        private long preparedPriorityMoved;
        private long preparedPriorityErrors;
        private String preparedPriorityFirstDesired;
        private String preparedPriorityFirstBefore;
        private String preparedPriorityFirstAfter;
        private final List<Map<String, Object>> originalLayoutObservations = new ArrayList<>();

        synchronized void reset() {
            carriers = 0;
            carrierRasterBytes = 0;
            carrierRasterMaterializations = 0;
            carrierSnapshotCopies = carrierSnapshotBytes = 0;
            coherentCarriers = 0;
            coherentCarrierBytes = 0;
            coherentDirectCarriers = 0;
            coherentDirectHits = 0;
            directAttempts = 0;
            hits = 0;
            fallbacks = 0;
            dimensionFallbacks = 0;
            npotProbeFallbacks = 0;
            coherentOriginalConvertFallbacks = 0;
            coherentOriginalDecodeBypasses = 0;
            paddedUploads = 0;
            paddingBytes = 0;
            layoutObservationErrors = 0;
            internalErrors = 0;
            releases = 0;
            bytesBypassed = 0;
            uploadBytesSupplied = 0;
            releasedBytes = 0;
            prefetchPreparedEnqueues = 0;
            prefetchOriginalEnqueues = 0;
            prefetchDuplicateDeclines = 0;
            prefetchPreparedHits = 0;
            prefetchOriginalDecodes = 0;
            learnedKaleidoscopeCandidates = 0;
            learnedKaleidoscopeSeeded = 0;
            learnedKaleidoscopeSeededBytes = 0;
            learnedKaleidoscopeIneligible = 0;
            learnedKaleidoscopeBoundDeclines = 0;
            learnedKaleidoscopeDuplicates = 0;
            learnedKaleidoscopeWorkerHits = 0;
            learnedKaleidoscopeWorkerFallbacks = 0;
            learnedKaleidoscopeRetainedAtStop = 0;
            learnedKaleidoscopePendingRemovedAtStop = 0;
            learnedKaleidoscopeConsumedAfterStop = 0;
            learnedKaleidoscopeLeftoversCleared = 0;
            learnedKaleidoscopeErrors = 0;
            preparedPriorityCaptures = 0;
            preparedPriorityResources = 0;
            preparedPriorityPaths = 0;
            preparedPriorityReorders = 0;
            preparedPriorityQueueEntries = 0;
            preparedPriorityMatched = 0;
            preparedPriorityMoved = 0;
            preparedPriorityErrors = 0;
            preparedPriorityFirstDesired = null;
            preparedPriorityFirstBefore = null;
            preparedPriorityFirstAfter = null;
            originalLayoutObservations.clear();
        }

        synchronized void carrier(long rasterBytes, boolean coherent, boolean coherentDirect) {
            carriers++;
            carrierRasterBytes = saturatedAdd(carrierRasterBytes, rasterBytes);
            if (coherent) {
                coherentCarriers++;
                coherentCarrierBytes = saturatedAdd(coherentCarrierBytes, rasterBytes);
            }
            if (coherentDirect) {
                coherentDirectCarriers++;
            }
        }

        synchronized void snapshot(long bytes) {
            carrierSnapshotCopies++;
            carrierSnapshotBytes = saturatedAdd(carrierSnapshotBytes, bytes);
        }

        synchronized void materialized(long rasterBytes, boolean coherent) {
            carrierRasterMaterializations++;
            carrierRasterBytes = saturatedAdd(carrierRasterBytes, rasterBytes);
            if (coherent) {
                coherentCarrierBytes = saturatedAdd(coherentCarrierBytes, rasterBytes);
            }
        }

        synchronized void directAttempt() {
            directAttempts++;
        }

        synchronized void hit(long sourceBytes, long uploadBytes, long padding, boolean coherentDirect) {
            hits++;
            bytesBypassed = saturatedAdd(bytesBypassed, sourceBytes);
            uploadBytesSupplied = saturatedAdd(uploadBytesSupplied, uploadBytes);
            if (padding > 0) {
                paddedUploads++;
                paddingBytes = saturatedAdd(paddingBytes, padding);
            }
            if (coherentDirect) {
                coherentDirectHits++;
            }
        }

        synchronized void fallback() {
            fallbacks++;
        }

        synchronized void dimensionFallback() {
            dimensionFallbacks++;
        }

        synchronized void npotProbeFallback() {
            npotProbeFallbacks++;
        }

        synchronized void coherentOriginalConvertFallback() {
            coherentOriginalConvertFallbacks++;
        }

        synchronized void coherentOriginalDecodeBypass() {
            coherentOriginalDecodeBypasses++;
        }

        synchronized void layoutObservation(Map<String, Object> observation) {
            if (originalLayoutObservations.size() >= MAX_LAYOUT_OBSERVATIONS) {
                return;
            }
            Object path = observation.get("logicalPath");
            for (Map<String, Object> existing : originalLayoutObservations) {
                if (Objects.equals(existing.get("logicalPath"), path)) {
                    return;
                }
            }
            originalLayoutObservations.add(observation);
        }

        synchronized void layoutObservationError() {
            layoutObservationErrors++;
        }

        synchronized void internalError() {
            internalErrors++;
        }

        synchronized void release(long bytes) {
            releases++;
            releasedBytes = saturatedAdd(releasedBytes, bytes);
        }

        synchronized void prefetchPreparedEnqueue() {
            prefetchPreparedEnqueues++;
        }

        synchronized void prefetchOriginalEnqueue() {
            prefetchOriginalEnqueues++;
        }

        synchronized void prefetchDuplicateDecline() {
            prefetchDuplicateDeclines++;
        }

        synchronized void prefetchPreparedHit() {
            prefetchPreparedHits++;
        }

        synchronized void prefetchOriginalDecode() {
            prefetchOriginalDecodes++;
        }

        synchronized void learnedKaleidoscopeCandidate() {
            learnedKaleidoscopeCandidates++;
        }

        synchronized void learnedKaleidoscopeSeed(long bytes) {
            learnedKaleidoscopeSeeded++;
            learnedKaleidoscopeSeededBytes = saturatedAdd(learnedKaleidoscopeSeededBytes, bytes);
        }

        synchronized void learnedKaleidoscopeIneligible() {
            learnedKaleidoscopeIneligible++;
        }

        synchronized void learnedKaleidoscopeBoundDecline() {
            learnedKaleidoscopeBoundDeclines++;
        }

        synchronized void learnedKaleidoscopeDuplicate() {
            learnedKaleidoscopeDuplicates++;
        }

        synchronized void learnedKaleidoscopeWorkerResult(boolean hit) {
            if (hit) {
                learnedKaleidoscopeWorkerHits++;
            } else {
                learnedKaleidoscopeWorkerFallbacks++;
            }
        }

        synchronized void learnedKaleidoscopeRetained(int retained) {
            learnedKaleidoscopeRetainedAtStop = Math.max(0, retained);
        }

        synchronized void learnedKaleidoscopePendingRemoved(int pending) {
            learnedKaleidoscopePendingRemovedAtStop = Math.max(0, pending);
        }

        synchronized void learnedKaleidoscopeComplete(int seeded, int leftovers) {
            learnedKaleidoscopeLeftoversCleared = Math.max(0, leftovers);
            learnedKaleidoscopeConsumedAfterStop = Math.max(
                    0L, Math.min((long) seeded, learnedKaleidoscopeRetainedAtStop) - leftovers);
        }

        synchronized void learnedKaleidoscopeError() {
            learnedKaleidoscopeErrors++;
        }

        synchronized void preparedPriorityCaptured(
                int resources, int paths, String firstDesired) {
            preparedPriorityCaptures++;
            preparedPriorityResources = Math.max(0, resources);
            preparedPriorityPaths = Math.max(0, paths);
            preparedPriorityFirstDesired = firstDesired;
        }

        synchronized void preparedPriorityReordered(
                int queueEntries,
                int matched,
                int moved,
                String firstDesired,
                String firstBefore,
                String firstAfter) {
            preparedPriorityReorders++;
            preparedPriorityQueueEntries = Math.max(0, queueEntries);
            preparedPriorityMatched = Math.max(0, matched);
            preparedPriorityMoved = Math.max(0, moved);
            preparedPriorityFirstDesired = firstDesired;
            preparedPriorityFirstBefore = firstBefore;
            preparedPriorityFirstAfter = firstAfter;
        }

        synchronized void preparedPriorityError() {
            preparedPriorityErrors++;
        }

        synchronized Map<String, Object> snapshot(
                long activeDirectBytes,
                long peakDirectBytes,
                int activeBuffers,
                int pendingBuffers,
                boolean ready) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("planId", PLAN_ID);
            values.put("ready", ready);
            values.put("coherentOriginalConvertProperty", COHERENT_ORIGINAL_CONVERT_PROPERTY);
            values.put("coherentOriginalConvertEnabled", Boolean.getBoolean(COHERENT_ORIGINAL_CONVERT_PROPERTY));
            values.put("coherentDirectProperty", COHERENT_DIRECT_PROPERTY);
            values.put("coherentDirectEnabled", Boolean.getBoolean(COHERENT_DIRECT_PROPERTY));
            values.put("maxTextureBytes", MAX_TEXTURE_BYTES);
            values.put("maxActiveDirectBytes", MAX_ACTIVE_DIRECT_BYTES);
            values.put("maxActiveBuffers", MAX_ACTIVE_BUFFERS);
            values.put("maxLayoutObservations", MAX_LAYOUT_OBSERVATIONS);
            values.put("carriers", carriers);
            values.put("carrierRasterMaterializations", carrierRasterMaterializations);
            values.put("carrierSnapshotCopies", carrierSnapshotCopies);
            values.put("carrierSnapshotBytes", carrierSnapshotBytes);
            values.put("carrierRasterBytes", carrierRasterBytes);
            values.put("coherentCarriers", coherentCarriers);
            values.put("coherentCarrierBytes", coherentCarrierBytes);
            values.put("coherentDirectCarriers", coherentDirectCarriers);
            values.put("coherentDirectHits", coherentDirectHits);
            values.put("directAttempts", directAttempts);
            values.put("hits", hits);
            values.put("fallbacks", fallbacks);
            values.put("dimensionFallbacks", dimensionFallbacks);
            values.put("npotProbeFallbacks", npotProbeFallbacks);
            values.put("coherentOriginalConvertFallbacks", coherentOriginalConvertFallbacks);
            values.put("coherentOriginalDecodeBypasses", coherentOriginalDecodeBypasses);
            values.put("originalLayoutObservations", List.copyOf(originalLayoutObservations));
            values.put("layoutObservationErrors", layoutObservationErrors);
            values.put("paddedUploads", paddedUploads);
            values.put("paddingBytes", paddingBytes);
            values.put("internalErrors", internalErrors);
            values.put("releases", releases);
            values.put("bytesBypassed", bytesBypassed);
            values.put("uploadBytesSupplied", uploadBytesSupplied);
            values.put("releasedBytes", releasedBytes);
            values.put("prefetchPreparedEnqueues", prefetchPreparedEnqueues);
            values.put("prefetchOriginalEnqueues", prefetchOriginalEnqueues);
            values.put("prefetchDuplicateDeclines", prefetchDuplicateDeclines);
            values.put("prefetchPreparedHits", prefetchPreparedHits);
            values.put("prefetchOriginalDecodes", prefetchOriginalDecodes);
            values.put("learnedKaleidoscopeProperty",
                    TexturePreparedPrefetchPlan.WINDOWS_KALEIDOSCOPE_PROPERTY);
            values.put("learnedKaleidoscopeEnabled",
                    Boolean.getBoolean(TexturePreparedPrefetchPlan.WINDOWS_KALEIDOSCOPE_PROPERTY));
            values.put("learnedKaleidoscopeMaxPaths", MAX_LEARNED_KALEIDOSCOPE_PATHS);
            values.put("learnedKaleidoscopeMaxBytes", MAX_LEARNED_KALEIDOSCOPE_BYTES);
            values.put("learnedKaleidoscopeCandidates", learnedKaleidoscopeCandidates);
            values.put("learnedKaleidoscopeSeeded", learnedKaleidoscopeSeeded);
            values.put("learnedKaleidoscopeSeededBytes", learnedKaleidoscopeSeededBytes);
            values.put("learnedKaleidoscopeIneligible", learnedKaleidoscopeIneligible);
            values.put("learnedKaleidoscopeBoundDeclines", learnedKaleidoscopeBoundDeclines);
            values.put("learnedKaleidoscopeDuplicates", learnedKaleidoscopeDuplicates);
            values.put("learnedKaleidoscopeWorkerHits", learnedKaleidoscopeWorkerHits);
            values.put("learnedKaleidoscopeWorkerFallbacks", learnedKaleidoscopeWorkerFallbacks);
            values.put("learnedKaleidoscopeRetainedAtStop", learnedKaleidoscopeRetainedAtStop);
            values.put("learnedKaleidoscopePendingRemovedAtStop",
                    learnedKaleidoscopePendingRemovedAtStop);
            values.put("learnedKaleidoscopeConsumedAfterStop", learnedKaleidoscopeConsumedAfterStop);
            values.put("learnedKaleidoscopeLeftoversCleared", learnedKaleidoscopeLeftoversCleared);
            values.put("learnedKaleidoscopeErrors", learnedKaleidoscopeErrors);
            values.put("preparedPriorityProperty",
                    TexturePreparedPrefetchPlan.WINDOWS_RESOURCE_ORDER_PROPERTY);
            values.put("preparedPriorityEnabled", Boolean.getBoolean(
                    TexturePreparedPrefetchPlan.WINDOWS_RESOURCE_ORDER_PROPERTY));
            values.put("preparedPriorityCaptures", preparedPriorityCaptures);
            values.put("preparedPriorityResources", preparedPriorityResources);
            values.put("preparedPriorityPaths", preparedPriorityPaths);
            values.put("preparedPriorityReorders", preparedPriorityReorders);
            values.put("preparedPriorityQueueEntries", preparedPriorityQueueEntries);
            values.put("preparedPriorityMatched", preparedPriorityMatched);
            values.put("preparedPriorityMoved", preparedPriorityMoved);
            values.put("preparedPriorityErrors", preparedPriorityErrors);
            values.put("preparedPriorityFirstDesired",
                    preparedPriorityFirstDesired == null ? "" : preparedPriorityFirstDesired);
            values.put("preparedPriorityFirstBefore",
                    preparedPriorityFirstBefore == null ? "" : preparedPriorityFirstBefore);
            values.put("preparedPriorityFirstAfter",
                    preparedPriorityFirstAfter == null ? "" : preparedPriorityFirstAfter);
            values.put("activeDirectBytes", activeDirectBytes);
            values.put("peakDirectBytes", peakDirectBytes);
            values.put("activeBuffers", activeBuffers);
            values.put("pendingBuffers", pendingBuffers);
            values.put("imageDecodesBypassed", saturatedAdd(hits, coherentOriginalDecodeBypasses));
            values.put("conversionCallsBypassed", hits);
            values.put("derivedColorCalculationsBypassed", hits);
            return Map.copyOf(values);
        }
    }

    private static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
