import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/**
 * Fixed-duration, self-contained probe for jdk.ExecutionSample wall-clock coverage.
 *
 * <p>The target workload is deliberately pure Java integer work. The probe records wall time with
 * System.nanoTime(), emits start/end marker events for the JFR clock, and reports only execution
 * samples whose sampledThread is the dedicated worker. That keeps native/intrinsic blind spots out
 * of the primary measurement while still reporting NativeMethodSample events as an auxiliary
 * diagnostic.
 */
public final class ExecutionSampleCoverageProbe {
    private static final String WORKER_NAME = "jfr-sample-coverage-worker";
    private static final String MARKER_EVENT =
            "dev.starsector.preflight.JfrSampleCoverageMarker";
    private static final byte[] CHUNK_MAGIC = {'F', 'L', 'R', 0};
    private static final int CHUNK_HEADER_PREFIX = 16;
    private static volatile long sink;

    @Name(MARKER_EVENT)
    @Label("JFR sample coverage marker")
    static final class CoverageMarker extends Event {
        @Label("Phase")
        String phase;

        @Label("Monotonic elapsed nanoseconds")
        long elapsedNanos;
    }

    private ExecutionSampleCoverageProbe() {}

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        Files.createDirectories(config.output.toAbsolutePath().getParent());
        warmUp(config.warmupMillis);

        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch startGate = new CountDownLatch(1);
        Thread worker = new Thread(() -> runWorker(startGate, stop), WORKER_NAME);
        worker.setDaemon(false);

        int periodicDumpsCompleted = 0;
        long wallStart;
        long wallEnd;
        try (Recording recording = new Recording()) {
            recording.setName(config.policy);
            recording.setToDisk(true);
            recording.enable(CoverageMarker.class);
            recording.enable("jdk.ExecutionSample")
                    .withPeriod(Duration.ofMillis(config.samplePeriodMillis));
            enablePeriod(recording, "jdk.NativeMethodSample", config.samplePeriodMillis);
            enablePeriod(recording, "jdk.CPULoad", 1_000);
            enable(recording, "jdk.DataLoss");
            enable(recording, "jdk.SafepointBegin");
            enable(recording, "jdk.SafepointEnd");

            recording.start();
            worker.start();

            wallStart = System.nanoTime();
            mark("start", 0);
            startGate.countDown();

            long deadline = wallStart + Duration.ofSeconds(config.durationSeconds).toNanos();
            long nextDump = config.periodicDumpSeconds > 0
                    ? wallStart + Duration.ofSeconds(config.periodicDumpSeconds).toNanos()
                    : Long.MAX_VALUE;
            while (true) {
                long now = System.nanoTime();
                if (now >= deadline) {
                    break;
                }
                if (now >= nextDump) {
                    periodicDumpsCompleted++;
                    Path partial = partialPath(config.output, periodicDumpsCompleted);
                    Files.deleteIfExists(partial);
                    recording.dump(partial);
                    nextDump += Duration.ofSeconds(config.periodicDumpSeconds).toNanos();
                    continue;
                }
                long untilDeadline = deadline - now;
                long untilDump = nextDump - now;
                long sleepNanos = Math.min(untilDeadline, untilDump);
                if (sleepNanos > 0) {
                    long sleepMillis = Math.max(1, Math.min(100, sleepNanos / 1_000_000L));
                    Thread.sleep(sleepMillis);
                }
            }

            wallEnd = System.nanoTime();
            mark("end", wallEnd - wallStart);
            stop.set(true);
            worker.join();
            recording.stop();
            Files.deleteIfExists(config.output);
            recording.dump(config.output);
        } finally {
            stop.set(true);
            startGate.countDown();
            worker.join(5_000);
        }

        Analysis analysis = analyze(config.output, wallEnd - wallStart);
        Map<String, Object> report = report(config, periodicDumpsCompleted, analysis);
        String json = toJson(report);
        System.out.println(json);
        if (config.jsonOutput != null) {
            Files.createDirectories(config.jsonOutput.toAbsolutePath().getParent());
            Files.writeString(config.jsonOutput, json + System.lineSeparator());
        }
    }

    private static void enable(Recording recording, String eventName) {
        try {
            recording.enable(eventName);
        } catch (IllegalArgumentException ignored) {
            // The report remains usable on a runtime that lacks an auxiliary event.
        }
    }

    private static void enablePeriod(Recording recording, String eventName, long periodMillis) {
        try {
            recording.enable(eventName).withPeriod(Duration.ofMillis(periodMillis));
        } catch (IllegalArgumentException ignored) {
            // The report remains usable on a runtime that lacks an auxiliary event.
        }
    }

    private static void mark(String phase, long elapsedNanos) {
        CoverageMarker marker = new CoverageMarker();
        marker.phase = phase;
        marker.elapsedNanos = elapsedNanos;
        marker.commit();
    }

    private static void warmUp(long millis) {
        long deadline = System.nanoTime() + Duration.ofMillis(millis).toNanos();
        long value = 0x9E3779B97F4A7C15L;
        while (System.nanoTime() < deadline) {
            value = burnBatch(value);
        }
        sink = value;
    }

    private static void runWorker(CountDownLatch startGate, AtomicBoolean stop) {
        try {
            startGate.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        }
        long value = 0xD1B54A32D192ED03L;
        while (!stop.get()) {
            value = burnBatch(value);
            sink = value;
        }
    }

    private static long burnBatch(long value) {
        for (int i = 0; i < 20_000; i++) {
            value ^= value << 13;
            value ^= value >>> 7;
            value ^= value << 17;
            value *= 0x2545F4914F6CDD1DL;
            value += i + 0x9E3779B97F4A7C15L;
        }
        return value;
    }

    private static Path partialPath(Path output, int index) {
        return output.resolveSibling(
                output.getFileName() + String.format(Locale.ROOT, ".partial-%02d.jfr", index));
    }

    private static Analysis analyze(Path recording, long observedWallNanos) throws IOException {
        Instant markerStart = null;
        Instant markerEnd = null;
        List<Instant> samples = new ArrayList<>();
        long allExecutionSamples = 0;
        long targetNativeSamples = 0;
        List<Instant> cpuLoadEvents = new ArrayList<>();
        long dataLossEvents = 0;
        long dataLossBytes = 0;
        long safepointBeginEvents = 0;
        long safepointEndEvents = 0;
        Map<String, Long> states = new LinkedHashMap<>();

        try (RecordingFile file = new RecordingFile(recording)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                String name = event.getEventType().getName();
                if (MARKER_EVENT.equals(name)) {
                    String phase = safeString(event, "phase");
                    if ("start".equals(phase)) {
                        markerStart = event.getStartTime();
                    } else if ("end".equals(phase)) {
                        markerEnd = event.getStartTime();
                    }
                    continue;
                }
                if ("jdk.ExecutionSample".equals(name)) {
                    allExecutionSamples++;
                    if (WORKER_NAME.equals(threadName(event))) {
                        samples.add(event.getStartTime());
                        String state = safeValueString(event, "state");
                        states.merge(state == null ? "(unreported)" : state, 1L, Long::sum);
                    }
                    continue;
                }
                if ("jdk.NativeMethodSample".equals(name)) {
                    if (WORKER_NAME.equals(threadName(event))) {
                        targetNativeSamples++;
                    }
                    continue;
                }
                if ("jdk.CPULoad".equals(name)) {
                    cpuLoadEvents.add(event.getStartTime());
                    continue;
                }
                if ("jdk.DataLoss".equals(name)) {
                    dataLossEvents++;
                    dataLossBytes += safeLong(event, "amount");
                    continue;
                }
                if ("jdk.SafepointBegin".equals(name)) {
                    safepointBeginEvents++;
                    continue;
                }
                if ("jdk.SafepointEnd".equals(name)) {
                    safepointEndEvents++;
                }
            }
        }

        Collections.sort(samples);
        Instant first = samples.isEmpty() ? null : samples.get(0);
        Instant last = samples.isEmpty() ? null : samples.get(samples.size() - 1);
        long coverageSpanNanos = first == null || last == null
                ? 0
                : Duration.between(first, last).toNanos();
        long markerSpanNanos = markerStart == null || markerEnd == null
                ? 0
                : Duration.between(markerStart, markerEnd).toNanos();
        long leadingNanos = first == null || markerStart == null
                ? markerSpanNanos
                : Math.max(0, Duration.between(markerStart, first).toNanos());
        long trailingNanos = last == null || markerEnd == null
                ? markerSpanNanos
                : Math.max(0, Duration.between(last, markerEnd).toNanos());
        long maxGapNanos = 0;
        for (int i = 1; i < samples.size(); i++) {
            maxGapNanos = Math.max(
                    maxGapNanos,
                    Duration.between(samples.get(i - 1), samples.get(i)).toNanos());
        }
        Collections.sort(cpuLoadEvents);
        long cpuLoadSpanNanos = cpuLoadEvents.size() < 2
                ? 0
                : Duration.between(
                        cpuLoadEvents.get(0), cpuLoadEvents.get(cpuLoadEvents.size() - 1)).toNanos();
        double markerSpanWallRatio = ratio(markerSpanNanos, observedWallNanos);
        double wallToJfrClockFactor = markerSpanNanos <= 0
                ? 1.0
                : (double) observedWallNanos / (double) markerSpanNanos;

        return new Analysis(
                observedWallNanos,
                countChunks(recording),
                Files.size(recording),
                markerStart,
                markerEnd,
                markerSpanNanos,
                first,
                last,
                samples.size(),
                allExecutionSamples,
                targetNativeSamples,
                coverageSpanNanos,
                ratio(coverageSpanNanos, observedWallNanos),
                markerSpanWallRatio,
                ratio(coverageSpanNanos, markerSpanNanos),
                wallToJfrClockFactor,
                cpuLoadEvents.size(),
                cpuLoadSpanNanos,
                leadingNanos,
                trailingNanos,
                maxGapNanos,
                dataLossEvents,
                dataLossBytes,
                safepointBeginEvents,
                safepointEndEvents,
                states);
    }

    private static String threadName(RecordedEvent event) {
        for (String field : List.of("sampledThread", "eventThread", "thread")) {
            try {
                if (!event.hasField(field)) {
                    continue;
                }
                RecordedThread thread = event.getThread(field);
                if (thread != null && thread.getJavaName() != null) {
                    return thread.getJavaName();
                }
            } catch (IllegalArgumentException ignored) {
                // Try the next conventional thread field.
            }
        }
        return null;
    }

    private static String safeString(RecordedEvent event, String field) {
        try {
            return event.hasField(field) ? event.getString(field) : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String safeValueString(RecordedEvent event, String field) {
        try {
            Object value = event.hasField(field) ? event.getValue(field) : null;
            return value == null ? null : Objects.toString(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static long safeLong(RecordedEvent event, String field) {
        try {
            if (!event.hasField(field)) {
                return 0;
            }
            Object value = event.getValue(field);
            return value instanceof Number ? ((Number) value).longValue() : 0;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static double ratio(long numerator, long denominator) {
        return denominator <= 0 ? 0.0 : (double) numerator / (double) denominator;
    }

    private static Map<String, Object> report(Config config, int periodicDumpsCompleted, Analysis a) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", 1);
        out.put("policy", config.policy);
        out.put("durationSecondsRequested", config.durationSeconds);
        out.put("observedWallDurationMillis", nanosToMillis(a.observedWallNanos));
        out.put("samplePeriodMillis", config.samplePeriodMillis);
        out.put("periodicDumpSeconds", config.periodicDumpSeconds);
        out.put("periodicDumpsCompleted", periodicDumpsCompleted);
        out.put("recordingPath", config.output.toString());
        out.put("recordingBytes", a.recordingBytes);
        out.put("chunkCount", a.chunkCount);
        out.put("jfrOptions", flightRecorderOptions());
        out.put("runtimeInputArguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        out.put("markerStartTime", instant(a.markerStart));
        out.put("markerEndTime", instant(a.markerEnd));
        out.put("markerSpanMillis", nanosToMillis(a.markerSpanNanos));
        out.put("markerSpanWallRatio", a.markerSpanWallRatio);
        out.put("wallToJfrClockFactor", a.wallToJfrClockFactor);
        out.put("cpuLoadEventCount", a.cpuLoadEvents);
        out.put("cpuLoadSpanMillis", nanosToMillis(a.cpuLoadSpanNanos));
        double cpuLoadAveragePeriodMillis = a.cpuLoadEvents < 2
                ? 0.0
                : nanosToMillis(a.cpuLoadSpanNanos) / (a.cpuLoadEvents - 1);
        out.put("cpuLoadAveragePeriodMillis", cpuLoadAveragePeriodMillis);
        out.put(
                "cpuLoadWallCorrectionFactor",
                cpuLoadAveragePeriodMillis <= 0 ? 1.0 : 1000.0 / cpuLoadAveragePeriodMillis);
        out.put("firstExecutionSampleTime", instant(a.firstSample));
        out.put("lastExecutionSampleTime", instant(a.lastSample));
        out.put("executionSampleCount", a.targetExecutionSamples);
        out.put("allExecutionSampleCount", a.allExecutionSamples);
        out.put("targetNativeMethodSampleCount", a.targetNativeSamples);
        out.put("coverageSpanMillis", nanosToMillis(a.coverageSpanNanos));
        out.put("coverageSpanWallRatio", a.coverageSpanWallRatio);
        out.put("coverageSpanMarkerRatio", a.coverageSpanMarkerRatio);
        double expectedSamples = a.observedWallNanos / 1_000_000.0 / config.samplePeriodMillis;
        out.put("expectedSampleIntervals", expectedSamples);
        out.put(
                "sampleCountExpectedRatio",
                expectedSamples <= 0 ? 0.0 : a.targetExecutionSamples / expectedSamples);
        out.put("leadingSampleGapJfrMillis", nanosToMillis(a.leadingNanos));
        out.put("trailingSampleGapJfrMillis", nanosToMillis(a.trailingNanos));
        out.put("maxInterSampleGapJfrMillis", nanosToMillis(a.maxGapNanos));
        out.put(
                "leadingSampleGapCalibratedMillis",
                nanosToMillis(a.leadingNanos) * a.wallToJfrClockFactor);
        out.put(
                "trailingSampleGapCalibratedMillis",
                nanosToMillis(a.trailingNanos) * a.wallToJfrClockFactor);
        out.put(
                "maxInterSampleGapCalibratedMillis",
                nanosToMillis(a.maxGapNanos) * a.wallToJfrClockFactor);
        out.put("dataLossEventCount", a.dataLossEvents);
        out.put("dataLossBytes", a.dataLossBytes);
        out.put("safepointBeginEventCount", a.safepointBeginEvents);
        out.put("safepointEndEventCount", a.safepointEndEvents);
        out.put("sampleThreadStates", a.states);
        out.put("javaVendor", System.getProperty("java.vendor"));
        out.put("javaVersion", System.getProperty("java.version"));
        out.put("javaRuntimeVersion", System.getProperty("java.runtime.version"));
        out.put("javaVmName", System.getProperty("java.vm.name"));
        out.put("osName", System.getProperty("os.name"));
        out.put("osArch", System.getProperty("os.arch"));
        out.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        out.put("workload", "single dedicated pure-Java integer worker");
        out.put("workerName", WORKER_NAME);
        out.put("checksum", Long.toUnsignedString(sink));
        return out;
    }

    private static List<String> flightRecorderOptions() {
        List<String> options = new ArrayList<>();
        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (argument.startsWith("-XX:FlightRecorderOptions")) {
                options.add(argument);
            }
        }
        return options;
    }

    private static String instant(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static int countChunks(Path recording) throws IOException {
        if (!Files.isRegularFile(recording)) {
            return 0;
        }
        long length = Files.size(recording);
        if (length < CHUNK_HEADER_PREFIX) {
            return 0;
        }
        int chunks = 0;
        byte[] header = new byte[CHUNK_HEADER_PREFIX];
        try (InputStream in = Files.newInputStream(recording)) {
            long offset = 0;
            while (offset + CHUNK_HEADER_PREFIX <= length) {
                if (!readFully(in, header)) {
                    break;
                }
                for (int i = 0; i < CHUNK_MAGIC.length; i++) {
                    if (header[i] != CHUNK_MAGIC[i]) {
                        return chunks;
                    }
                }
                long size = 0;
                for (int i = 8; i < CHUNK_HEADER_PREFIX; i++) {
                    size = (size << 8) | (header[i] & 0xFFL);
                }
                if (size < CHUNK_HEADER_PREFIX || offset + size > length) {
                    return chunks + 1;
                }
                chunks++;
                offset += size;
                if (in.skip(size - CHUNK_HEADER_PREFIX) != size - CHUNK_HEADER_PREFIX) {
                    break;
                }
            }
        }
        return chunks;
    }

    private static boolean readFully(InputStream in, byte[] into) throws IOException {
        int read = 0;
        while (read < into.length) {
            int step = in.read(into, read, into.length - read);
            if (step < 0) {
                return false;
            }
            read += step;
        }
        return true;
    }

    private static String toJson(Object value) {
        StringBuilder out = new StringBuilder();
        appendJson(out, value);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendJson(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            out.append('"').append(escape((String) value)).append('"');
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                appendJson(out, Objects.toString(entry.getKey()));
                out.append(':');
                appendJson(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : (Iterable<Object>) value) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                appendJson(out, item);
            }
            out.append(']');
        } else {
            appendJson(out, Objects.toString(value));
        }
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"': out.append("\\\""); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    private record Config(
            long durationSeconds,
            long samplePeriodMillis,
            long periodicDumpSeconds,
            long warmupMillis,
            String policy,
            Path output,
            Path jsonOutput) {
        static Config parse(String[] args) {
            long durationSeconds = 30;
            long samplePeriodMillis = 10;
            long periodicDumpSeconds = 0;
            long warmupMillis = 2_000;
            String policy = "unspecified";
            Path output = Path.of("jfr-sample-coverage.jfr");
            Path jsonOutput = null;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--duration-seconds":
                        durationSeconds = Long.parseLong(requireValue(args, ++i, arg));
                        break;
                    case "--sample-period-ms":
                        samplePeriodMillis = Long.parseLong(requireValue(args, ++i, arg));
                        break;
                    case "--periodic-dump-seconds":
                        periodicDumpSeconds = Long.parseLong(requireValue(args, ++i, arg));
                        break;
                    case "--warmup-ms":
                        warmupMillis = Long.parseLong(requireValue(args, ++i, arg));
                        break;
                    case "--policy":
                        policy = requireValue(args, ++i, arg);
                        break;
                    case "--output":
                        output = Path.of(requireValue(args, ++i, arg));
                        break;
                    case "--json-out":
                        jsonOutput = Path.of(requireValue(args, ++i, arg));
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }
            if (durationSeconds <= 0
                    || samplePeriodMillis <= 0
                    || periodicDumpSeconds < 0
                    || warmupMillis < 0) {
                throw new IllegalArgumentException(
                        "durations and sample period must be positive; periodic dump may be zero");
            }
            return new Config(
                    durationSeconds,
                    samplePeriodMillis,
                    periodicDumpSeconds,
                    warmupMillis,
                    policy,
                    output,
                    jsonOutput);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }
    }

    private record Analysis(
            long observedWallNanos,
            int chunkCount,
            long recordingBytes,
            Instant markerStart,
            Instant markerEnd,
            long markerSpanNanos,
            Instant firstSample,
            Instant lastSample,
            long targetExecutionSamples,
            long allExecutionSamples,
            long targetNativeSamples,
            long coverageSpanNanos,
            double coverageSpanWallRatio,
            double markerSpanWallRatio,
            double coverageSpanMarkerRatio,
            double wallToJfrClockFactor,
            long cpuLoadEvents,
            long cpuLoadSpanNanos,
            long leadingNanos,
            long trailingNanos,
            long maxGapNanos,
            long dataLossEvents,
            long dataLossBytes,
            long safepointBeginEvents,
            long safepointEndEvents,
            Map<String, Long> states) {}
}
