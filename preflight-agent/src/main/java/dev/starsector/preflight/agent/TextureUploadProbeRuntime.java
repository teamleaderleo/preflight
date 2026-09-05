package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded discovery telemetry for the exact stock texture-upload calls. */
public final class TextureUploadProbeRuntime {
    public static final String ENABLED_PROPERTY = "preflight.texture.uploadProbe";
    public static final String CHECKPOINT_PROPERTY = "preflight.texture.uploadCheckpoint";

    private static final int MAX_SLOW_UPLOADS = 32;
    private static final long FIFTY_MILLIS = 50_000_000L;
    private static final long HUNDRED_MILLIS = 100_000_000L;
    private static final List<Upload> SLOW_UPLOADS = new ArrayList<>(MAX_SLOW_UPLOADS);

    private static int installedCallSites;
    private static long calls;
    private static long imageCalls;
    private static long subImageCalls;
    private static long totalBytes;
    private static long totalNanos;
    private static long maximumNanos;
    private static long over50Millis;
    private static long over100Millis;
    private static Path reportPath;
    private static boolean shutdownHookInstalled;
    static final long PENDING_THRESHOLD_NANOS = 10_000_000_000L;
    private static PendingUpload pendingUpload;
    private static PendingUpload reportedUpload;
    private static Thread checkpointWatchdog;
    private static final long[] observedUnpackAlignments = new long[4];

    private TextureUploadProbeRuntime() {
    }

    static boolean enabled() {
        return Boolean.getBoolean(ENABLED_PROPERTY);
    }

    static synchronized void installed(int callSites) {
        installedCallSites = callSites;
    }

    static synchronized void beginSession(Path report) {
        stopCheckpointWatchdog();
        resetCounters();
        reportPath = enabled() ? report : null;
        if (reportPath != null && Boolean.getBoolean(CHECKPOINT_PROPERTY)) {
            checkpointWatchdog = new Thread(TextureUploadProbeRuntime::watchPendingUpload,
                    "Preflight-Pending-Texture-Upload");
            checkpointWatchdog.setDaemon(true);
            checkpointWatchdog.start();
        }
        if (reportPath != null && !shutdownHookInstalled) {
            shutdownHookInstalled = true;
            Runtime.getRuntime().addShutdownHook(new Thread(
                    TextureUploadProbeRuntime::writeReport,
                    "Preflight-Texture-Upload-Probe-Report"));
        }
    }

    /** Called immediately before the native GL invocation. */
    public static long begin() {
        return System.nanoTime();
    }

    /** Opt-in pending-call breadcrumb. Retains metadata only, never the native buffer. */
    public static synchronized void checkpoint(int target, int level, int internalFormat,
            int width, int height, int border, int format, int type, ByteBuffer pixels,
            String path, boolean subImage) {
        checkpoint(target, level, internalFormat, width, height, border, format, type, pixels, path, subImage, -1);
    }

    public static synchronized void checkpoint(int target, int level, int internalFormat,
            int width, int height, int border, int format, int type, ByteBuffer pixels,
            String path, boolean subImage, int unpackAlignment) {
        if (!enabled() || !Boolean.getBoolean(CHECKPOINT_PROPERTY) || reportPath == null) return;
        if (unpackAlignment == 1 || unpackAlignment == 2 || unpackAlignment == 4 || unpackAlignment == 8) {
            observedUnpackAlignments[Integer.numberOfTrailingZeros(unpackAlignment)]++;
        }
        pendingUpload = new PendingUpload(System.nanoTime(), calls, path,
                Thread.currentThread().getId(), Thread.currentThread().getName(),
                target, level, internalFormat, width, height, border, format, type,
                pixels == null ? -1 : pixels.position(), pixels == null ? -1 : pixels.limit(),
                pixels == null ? -1 : pixels.capacity(), pixels != null && pixels.isDirect(), subImage, unpackAlignment);
    }

    private static void watchPendingUpload() {
        try {
            while (true) {
                Thread.sleep(10_000L);
                synchronized (TextureUploadProbeRuntime.class) {
                    if (Thread.currentThread() != checkpointWatchdog) return;
                    writePendingCheckpoint(System.nanoTime());
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** The observer writes once per overdue attempt; a completed call or new session clears it. */
    static synchronized void writePendingCheckpoint(long now) {
        PendingUpload attempt = pendingUpload;
        if (reportPath == null || attempt == null || attempt == reportedUpload
                || now - attempt.startedNanos() < PENDING_THRESHOLD_NANOS) return;
        reportedUpload = attempt;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("observedAt", java.time.Instant.now().toString());
        value.put("observation", "upload-did-not-complete-before-observation");
        value.put("pendingMillis", Math.max(0L, now - attempt.startedNanos()) / 1_000_000.0);
        value.put("completedCallsBeforeAttempt", attempt.completedCalls());
        value.put("logicalPath", attempt.path());
        value.put("operation", attempt.subImage() ? "glTexSubImage2D" : "glTexImage2D");
        value.put("thread", attempt.thread());
        value.put("target", attempt.target());
        value.put("level", attempt.level());
        value.put(attempt.subImage() ? "xOffset" : "internalFormat", attempt.internalFormat());
        value.put("width", attempt.subImage() ? attempt.height() : attempt.width());
        value.put("height", attempt.subImage() ? attempt.border() : attempt.height());
        value.put(attempt.subImage() ? "yOffset" : "border", attempt.subImage() ? attempt.width() : attempt.border());
        value.put("format", attempt.format());
        value.put("type", attempt.type());
        value.put("position", attempt.position());
        value.put("limit", attempt.limit());
        value.put("capacity", attempt.capacity());
        value.put("direct", attempt.direct());
        value.put("unpackAlignment", attempt.unpackAlignment());
        try {
            Path destination = reportPath.resolveSibling(reportPath.getFileName() + ".last-attempt.json");
            if (destination.getParent() != null) Files.createDirectories(destination.getParent());
            Files.writeString(destination, Json.object(value) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) {
            // Diagnostic I/O must never suppress an upload or retry without a bound.
        }
    }

    private static void stopCheckpointWatchdog() {
        if (checkpointWatchdog != null) checkpointWatchdog.interrupt();
        checkpointWatchdog = null;
    }

    private record PendingUpload(long startedNanos, long completedCalls, String path,
            long threadId, String thread, int target, int level, int internalFormat,
            int width, int height, int border, int format, int type,
            int position, int limit, int capacity, boolean direct, boolean subImage, int unpackAlignment) { }

    /** Called immediately after the native GL invocation. */
    public static synchronized void finish(
            long startedNanos,
            int width,
            int height,
            int format,
            int type,
            ByteBuffer pixels,
            String logicalPath,
            boolean subImage) {
        if (pendingUpload != null && pendingUpload.threadId() == Thread.currentThread().getId()) {
            pendingUpload = null;
        }
        long elapsed = Math.max(0L, System.nanoTime() - startedNanos);
        int bytes = pixels == null ? 0 : Math.max(0, pixels.remaining());
        calls++;
        if (subImage) {
            subImageCalls++;
        } else {
            imageCalls++;
        }
        totalBytes += bytes;
        totalNanos += elapsed;
        maximumNanos = Math.max(maximumNanos, elapsed);
        if (elapsed >= FIFTY_MILLIS) over50Millis++;
        if (elapsed >= HUNDRED_MILLIS) over100Millis++;
        retain(new Upload(
                elapsed,
                Math.max(0, width),
                Math.max(0, height),
                format,
                type,
                bytes,
                logicalPath == null || logicalPath.isBlank() ? "<unknown>" : logicalPath,
                subImage));
        if ((calls & 2047L) == 0L) {
            writeReport();
        }
    }

    static synchronized Map<String, Object> telemetry() {
        List<Upload> ordered = new ArrayList<>(SLOW_UPLOADS);
        ordered.sort(Comparator.comparingLong(Upload::durationNanos).reversed());
        List<Map<String, Object>> slowest = new ArrayList<>(ordered.size());
        for (Upload upload : ordered) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("logicalPath", upload.logicalPath());
            value.put("operation", upload.subImage() ? "glTexSubImage2D" : "glTexImage2D");
            value.put("durationMillis", upload.durationNanos() / 1_000_000.0);
            value.put("width", upload.width());
            value.put("height", upload.height());
            value.put("format", upload.format());
            value.put("type", upload.type());
            value.put("bytes", upload.bytes());
            slowest.add(Map.copyOf(value));
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", enabled());
        values.put("installedCallSites", installedCallSites);
        values.put("calls", calls);
        values.put("imageCalls", imageCalls);
        values.put("subImageCalls", subImageCalls);
        values.put("totalBytes", totalBytes);
        values.put("totalMillis", totalNanos / 1_000_000.0);
        values.put("maximumMillis", maximumNanos / 1_000_000.0);
        values.put("over50Millis", over50Millis);
        values.put("over100Millis", over100Millis);
        values.put("reportPath", reportPath == null ? "" : reportPath.toString());
        values.put("rgbUnpackAlignmentObservations", Map.of(
                "1", observedUnpackAlignments[0], "2", observedUnpackAlignments[1],
                "4", observedUnpackAlignments[2], "8", observedUnpackAlignments[3]));
        values.put("slowest", List.copyOf(slowest));
        return Map.copyOf(values);
    }

    static synchronized void resetForTests() {
        stopCheckpointWatchdog();
        reportPath = null;
        resetCounters();
    }

    private static void resetCounters() {
        pendingUpload = null;
        reportedUpload = null;
        java.util.Arrays.fill(observedUnpackAlignments, 0L);
        installedCallSites = 0;
        calls = 0L;
        imageCalls = 0L;
        subImageCalls = 0L;
        totalBytes = 0L;
        totalNanos = 0L;
        maximumNanos = 0L;
        over50Millis = 0L;
        over100Millis = 0L;
        SLOW_UPLOADS.clear();
    }

    private static synchronized void writeReport() {
        Path destination = reportPath;
        if (destination == null) return;
        try {
            Path parent = destination.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(
                    destination,
                    Json.object(telemetry()) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException ignored) {
            // Discovery output is optional; texture loading must survive report failures.
        }
    }

    private static void retain(Upload upload) {
        if (SLOW_UPLOADS.size() < MAX_SLOW_UPLOADS) {
            SLOW_UPLOADS.add(upload);
            return;
        }
        int shortest = 0;
        for (int index = 1; index < SLOW_UPLOADS.size(); index++) {
            if (SLOW_UPLOADS.get(index).durationNanos()
                    < SLOW_UPLOADS.get(shortest).durationNanos()) {
                shortest = index;
            }
        }
        if (upload.durationNanos() > SLOW_UPLOADS.get(shortest).durationNanos()) {
            SLOW_UPLOADS.set(shortest, upload);
        }
    }

    private record Upload(
            long durationNanos,
            int width,
            int height,
            int format,
            int type,
            int bytes,
            String logicalPath,
            boolean subImage) {
    }
}
