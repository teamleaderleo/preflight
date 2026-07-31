package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.GpuTextureFootprint;
import dev.starsector.preflight.core.PreparedTexture;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runtime bridge for upload-ready SPFT pixels with bounded direct-buffer ownership. */
public final class TexturePreparedPixelRuntime {
    static final String PLAN_ID = "texture-prepared-pixels-v2";
    static final String COHERENT_ORIGINAL_CONVERT_PROPERTY =
            "preflight.preparedPixels.coherentOriginalConvert";
    public static final String COHERENT_DIRECT_PROPERTY =
            "preflight.preparedPixels.coherentDirect";
    static final int MAX_TEXTURE_BYTES = 32 * 1024 * 1024;
    static final long MAX_ACTIVE_DIRECT_BYTES = 64L * 1024 * 1024;
    static final int MAX_ACTIVE_BUFFERS = 1_024;
    private static final int MAX_LAYOUT_OBSERVATIONS = 16;
    private static final int ZERO_CHUNK_BYTES = 8 * 1024;
    private static final byte[] ZERO_CHUNK = new byte[ZERO_CHUNK_BYTES];

    private static final Object LOCK = new Object();
    private static final IdentityHashMap<ByteBuffer, Integer> ACTIVE = new IdentityHashMap<>();
    private static final IdentityHashMap<Thread, ArrayDeque<ByteBuffer>> IN_FLIGHT = new IdentityHashMap<>();
    private static final Telemetry TELEMETRY = new Telemetry();
    private static volatile boolean selected;
    private static long activeBytes;
    private static long peakBytes;
    private static int pendingBuffers;

    private TexturePreparedPixelRuntime() {
    }

    static void beginSession() {
        selected = false;
        synchronized (LOCK) {
            ACTIVE.clear();
            IN_FLIGHT.clear();
            activeBytes = 0;
            peakBytes = 0;
            pendingBuffers = 0;
        }
        TELEMETRY.reset();
    }

    static void select(TextureAdapterMode mode) {
        selected = mode == TextureAdapterMode.PREPARED_PIXELS;
    }

    static boolean ready() {
        return selected && TextureCompatibilityRuntime.ready();
    }

    /** Returns a prepared-texture carrier, or {@code null} for original decode fallback. */
    public static BufferedImage load(String logicalPath) {
        if (!ready()) {
            return null;
        }
        PreparedTexture texture = TextureCompatibilityRuntime.lookup(logicalPath);
        if (texture == null) {
            return null;
        }
        UploadLayout layout = uploadLayout(texture);
        if (layout == null || layout.uploadBytes() > MAX_TEXTURE_BYTES) {
            TELEMETRY.dimensionFallback();
            TELEMETRY.fallback();
            TextureCompatibilityRuntime.declined(TextureCompatibilityRuntime.FallbackReason.UNSUPPORTED_TEXTURE);
            return null;
        }

        boolean npot = layout.paddingBytes() > 0;
        boolean coherentDirect = npot && Boolean.getBoolean(COHERENT_DIRECT_PROPERTY);
        boolean coherentOriginalConvert = npot
                && !coherentDirect
                && Boolean.getBoolean(COHERENT_ORIGINAL_CONVERT_PROPERTY);
        try {
            CarrierImage carrier = new CarrierImage(
                    logicalPath,
                    texture,
                    layout,
                    coherentOriginalConvert,
                    coherentDirect);
            TELEMETRY.carrier(carrier.rasterBytes, carrier.coherent(), carrier.coherentDirect);
            return carrier;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            TELEMETRY.internalError();
            TELEMETRY.fallback();
            TextureCompatibilityRuntime.internalFailure();
            TextureCompatibilityRuntime.declined(TextureCompatibilityRuntime.FallbackReason.PREPARED_PIXEL_BRIDGE);
            return null;
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

    /** Creates one bounded direct upload buffer and returns stored derived colors. */
    public static PreparedPixel prepare(BufferedImage image) {
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
        boolean unpadded = layout.paddingBytes() > 0 && TexturePaddingRuntime.enabled();

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
                buffer.put(texture.pixels());
            }
            buffer.flip();
            synchronized (LOCK) {
                pendingBuffers--;
                ACTIVE.put(buffer, bytes);
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
        if (!(image instanceof CarrierImage carrier)
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
        Integer bytes;
        synchronized (LOCK) {
            removeTrackedLocked(buffer);
            bytes = ACTIVE.remove(buffer);
            if (bytes != null) {
                activeBytes -= bytes;
                if (activeBytes < 0) {
                    activeBytes = 0;
                }
            }
        }
        if (bytes != null) {
            TELEMETRY.release(bytes);
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
        return TELEMETRY.snapshot(
                currentBytes,
                maximumBytes,
                currentBuffers,
                currentPending,
                ready());
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
        byte[] source = texture.pixels();
        int sourceStride = Math.multiplyExact(texture.originalWidth(), texture.channels());
        int uploadStride = Math.multiplyExact(layout.uploadWidth(), texture.channels());
        int rightPadding = uploadStride - sourceStride;
        for (int row = 0; row < texture.originalHeight(); row++) {
            buffer.put(source, row * sourceStride, sourceStride);
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
                    coherentOriginalConvert || coherentDirect
                            ? TexturePreparedPixelCarrierSurface.coherent(texture)
                            : TexturePreparedPixelCarrierSurface.legacy(texture.channels()),
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

        @Override
        public int getWidth() {
            return texture.originalWidth();
        }

        @Override
        public int getHeight() {
            return texture.originalHeight();
        }
    }

    private static final class Telemetry {
        private long carriers;
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
        private final List<Map<String, Object>> originalLayoutObservations = new ArrayList<>();

        synchronized void reset() {
            carriers = 0;
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
            originalLayoutObservations.clear();
        }

        synchronized void carrier(long rasterBytes, boolean coherent, boolean coherentDirect) {
            carriers++;
            if (coherent) {
                coherentCarriers++;
                coherentCarrierBytes = saturatedAdd(coherentCarrierBytes, rasterBytes);
            }
            if (coherentDirect) {
                coherentDirectCarriers++;
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
