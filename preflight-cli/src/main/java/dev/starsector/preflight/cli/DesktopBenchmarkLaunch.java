package dev.starsector.preflight.cli;

import dev.starsector.preflight.agent.FrameTimeTelemetry;
import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns one identity-checked measurement-only/optimized desktop benchmark pair. */
final class DesktopBenchmarkLaunch {
    static final String FORMAT = "starsector-preflight-desktop-benchmark-v1";
    static final String RESULT_FILE = "benchmark-result.json";
    static final long MIN_SETTLED_CAMPAIGN_ACTIVE_NANOS = Duration.ofSeconds(30).toNanos();
    static final long MIN_SETTLED_CAMPAIGN_FRAMES = 100L;

    private DesktopBenchmarkLaunch() {
    }

    static Map<String, Object> launch(
            DesktopSmokeScenario baseline,
            DesktopSmokeScenario candidate,
            Path requestedDirectory,
            Path game,
            Path launcher,
            DesktopSmokeDriver driver,
            Clock clock) throws Exception {
        Map<String, Object> identity = validatePair(baseline, candidate);
        Path session = DesktopSmokeLaunch.freshRunDirectory(requestedDirectory);
        Path cancellation = session.resolve(DesktopSmokeLaunch.CANCELLATION_FILE);
        List<Map<String, Object>> phases = new ArrayList<>();
        List<String> benchmarkDiagnostics = new ArrayList<>();
        Map<String, Object> measuredIdentity = null;
        Map<String, Object> selectedSave = null;

        Path baselineDirectory = session.resolve("measurement-only");
        Map<String, Object> baselineResult = runPhase(
                "measurement-only", baseline, baselineDirectory,
                cancellation, game, launcher, driver, clock);
        Map<String, Object> baselinePhase = phase("measurement-only", baselineResult);
        phases.add(baselinePhase);

        String status = status(baselinePhase);
        if ("passed".equals(status)) {
            try {
                measuredIdentity = measuredIdentity(baselineDirectory);
                selectedSave = optionalSelectedSave(baselinePhase);
            } catch (IOException | IllegalArgumentException failure) {
                status = "failed";
                benchmarkDiagnostics.add("Measurement identity unavailable: " + failure.getMessage());
            }
        }
        if ("passed".equals(status) && !cancellationRequested(cancellation)) {
            Path candidateDirectory = session.resolve("optimized");
            Map<String, Object> candidateResult = runPhase(
                    "optimized", candidate, candidateDirectory,
                    cancellation, game, launcher, driver, clock);
            Map<String, Object> candidatePhase = phase("optimized", candidateResult);
            phases.add(candidatePhase);
            status = status(candidatePhase);
            if ("passed".equals(status)) {
                try {
                    Map<String, Object> candidateIdentity = measuredIdentity(candidateDirectory);
                    if (!candidateIdentity.equals(measuredIdentity)) {
                        status = "failed";
                        benchmarkDiagnostics.add(
                                "The installation, profile, or launch settings changed between runs");
                    }
                    Map<String, Object> candidateSave = optionalSelectedSave(candidatePhase);
                    if (!java.util.Objects.equals(candidateSave, selectedSave)) {
                        status = "failed";
                        benchmarkDiagnostics.add("The selected save changed between benchmark runs");
                    }
                } catch (IOException | IllegalArgumentException failure) {
                    status = "failed";
                    benchmarkDiagnostics.add(
                            "Optimized-run identity unavailable: " + failure.getMessage());
                }
            }
        }
        if (cancellationRequested(cancellation)) status = "cancelled";

        Map<String, Object> storage = null;
        try {
            storage = storageContext();
        } catch (IOException failure) {
            benchmarkDiagnostics.add("Prepared-data storage context unavailable: " + failure.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", FORMAT);
        result.put("status", status);
        result.put("sessionDirectory", session);
        Map<String, Object> sealedIdentity = new LinkedHashMap<>(identity);
        sealedIdentity.put("measuredRun", measuredIdentity);
        sealedIdentity.put("selectedSave", selectedSave);
        result.put("identity", sealedIdentity);
        result.put("phases", List.copyOf(phases));
        result.put("complete", phases.size() == 2 && "passed".equals(status));
        List<String> diagnostics = new ArrayList<>(diagnostics(phases));
        diagnostics.addAll(benchmarkDiagnostics);
        result.put("diagnostics", List.copyOf(diagnostics));
        Map<String, Object> comparison = comparison(phases);
        comparison.put("context", comparisonContext(phases, storage));
        result.put("comparison", comparison);
        DesktopSmokeLaunch.atomicWrite(
                session.resolve(RESULT_FILE), Json.object(result) + System.lineSeparator());
        return result;
    }

    static Map<String, Object> validatePair(
            DesktopSmokeScenario baseline, DesktopSmokeScenario candidate) {
        if (!"measurement-only".equals(baseline.launchPreset())) {
            throw new IllegalArgumentException(
                    "The first benchmark scenario must use measurement-only mode");
        }
        if (!"fast".equals(candidate.launchPreset())) {
            throw new IllegalArgumentException(
                    "The second benchmark scenario must use the optimized fast mode");
        }
        Map<String, Object> baselineIdentity = baseline.benchmarkIdentity();
        Map<String, Object> candidateIdentity = candidate.benchmarkIdentity();
        if (!baselineIdentity.equals(candidateIdentity)) {
            throw new IllegalArgumentException(
                    "Benchmark scenarios must have identical settings and interaction steps");
        }
        String canonical = Json.object(baselineIdentity);
        Map<String, Object> result = new LinkedHashMap<>(baselineIdentity);
        result.put("sha256", Hashes.sha256(canonical.getBytes(StandardCharsets.UTF_8)));
        return result;
    }

    private static Map<String, Object> runPhase(
            String phase,
            DesktopSmokeScenario scenario,
            Path runDirectory,
            Path sessionCancellation,
            Path game,
            Path launcher,
            DesktopSmokeDriver driver,
            Clock clock) throws Exception {
        AtomicBoolean finished = new AtomicBoolean();
        Thread cancellationMirror = cancellationMirror(
                sessionCancellation,
                runDirectory.resolve(DesktopSmokeLaunch.CANCELLATION_FILE),
                finished);
        Throwable primaryFailure = null;
        try {
            return DesktopSmokeLaunch.launch(
                    scenario, runDirectory, game, launcher, driver, clock);
        } catch (Exception | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            stopCancellationMirror(phase, cancellationMirror, finished, primaryFailure);
        }
    }

    /**
     * Stops the mirror thread without letting its own failure stand in for the phase's.
     *
     * <p>A launch that already failed is the thing worth reading. A monitor that then refuses to
     * stop is worth knowing about too, but as a suppressed detail on that failure rather than as
     * the exception that replaces it and sends the reader towards cleanup.
     */
    static void stopCancellationMirror(
            String phase, Thread cancellationMirror, AtomicBoolean finished, Throwable primaryFailure)
            throws IOException {
        finished.set(true);
        cancellationMirror.interrupt();
        try {
            cancellationMirror.join(1_000L);
        } catch (InterruptedException interruption) {
            // The caller was interrupted, so this never waited the full second. Whether the
            // daemon mirror stopped is unknown rather than failed; restore the status and let
            // the caller decide.
            Thread.currentThread().interrupt();
            return;
        }
        if (!cancellationMirror.isAlive()) {
            return;
        }
        IOException cleanupFailure =
                new IOException("The " + phase + " cancellation monitor didn't stop");
        if (primaryFailure != null) {
            primaryFailure.addSuppressed(cleanupFailure);
            return;
        }
        throw cleanupFailure;
    }

    static Thread cancellationMirror(
            Path sessionCancellation, Path phaseCancellation, AtomicBoolean finished) {
        Thread thread = new Thread(() -> {
            while (!finished.get()) {
                if (cancellationRequested(sessionCancellation)) {
                    try {
                        if (Files.isDirectory(
                                phaseCancellation.getParent(), LinkOption.NOFOLLOW_LINKS)) {
                            Files.writeString(
                                    phaseCancellation,
                                    "cancel\n",
                                    StandardOpenOption.CREATE_NEW,
                                    StandardOpenOption.WRITE);
                            return;
                        }
                    } catch (FileAlreadyExistsException ignored) {
                        // The phase has already observed the same idempotent request.
                        return;
                    } catch (IOException ignored) {
                        // Retry while the phase still owns a live exact-process cleanup path.
                    }
                }
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "preflight-benchmark-cancellation");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    static boolean cancellationRequested(Path marker) {
        return Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(marker);
    }

    static Map<String, Object> phase(String name, Map<String, Object> launch) {
        Map<String, Object> phase = new LinkedHashMap<>();
        phase.put("name", name);
        phase.put("runDirectory", launch.get("runDirectory"));
        phase.put("launch", launch);
        String status = status(launch);
        Map<String, Object> summary = null;
        String summaryError = null;
        if ("passed".equals(status)) {
            try {
                summary = summary(launch);
            } catch (IOException | IllegalArgumentException failure) {
                status = "failed";
                summaryError = "Could not seal benchmark metrics: " + failure.getMessage();
            }
        }
        phase.put("status", status);
        phase.put("summary", summary);
        phase.put("summaryError", summaryError);
        return phase;
    }

    private static String status(Map<String, Object> launch) {
        Object value = launch.get("status");
        return value instanceof String text ? text : "failed";
    }

    private static List<String> diagnostics(List<Map<String, Object>> phases) {
        if (phases.isEmpty()) return List.of();
        List<String> diagnostics = new ArrayList<>();
        for (Map<String, Object> phase : phases) {
            if (phase.get("summaryError") instanceof String error) diagnostics.add(error);
        }
        Object launch = phases.get(phases.size() - 1).get("launch");
        if (!(launch instanceof Map<?, ?> launchMap)) return List.copyOf(diagnostics);
        Object values = launchMap.get("diagnostics");
        if (values instanceof List<?> list) {
            list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .limit(100)
                    .forEach(diagnostics::add);
        }
        return List.copyOf(diagnostics);
    }

    static Map<String, Object> measuredIdentity(Path runDirectory) throws IOException {
        Map<String, Object> run = boundedJson(runDirectory.resolve("run.json"), "run metadata");
        Object fingerprint = measuredProfileFingerprint(runDirectory, run);
        if (!(fingerprint instanceof String text) || !text.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("profile fingerprint is missing or invalid");
        }
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("profileFingerprint", text);
        identity.put("installRoot", run.get("installRoot"));
        identity.put("launcher", run.get("launcher"));
        identity.put("launcherKind", run.get("launcherKind"));
        identity.put("platform", run.get("platform"));
        identity.put("wrapperRuntime", run.get("wrapperRuntime"));
        identity.put("directLaunchSettings", run.get("directLaunchSettings"));
        identity.put("preflightJarSha256", run.get("preflightJarSha256"));
        if (identity.values().stream().anyMatch(value -> value == null)) {
            throw new IllegalArgumentException("run metadata lacks a comparison identity field");
        }
        String canonical = Json.object(identity);
        identity.put("sha256", Hashes.sha256(canonical.getBytes(StandardCharsets.UTF_8)));
        return identity;
    }

    private static Object measuredProfileFingerprint(
            Path runDirectory, Map<String, Object> run) throws IOException {
        Path profileFile = runDirectory.resolve("profile.json");
        if (Files.isRegularFile(profileFile, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(profileFile)) {
            return boundedJson(profileFile, "profile metadata").get("profileFingerprint");
        }

        // The measurement-only preset deliberately skips the broad diagnostic census so it cannot
        // contend with the game during startup. Seal that run against the same profile only after
        // timing has stopped. This keeps the pair exact without putting report work on either
        // measured launch's critical path.
        Object installRoot = run.get("installRoot");
        if (!(installRoot instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("run metadata lacks an install root");
        }
        return ProfileCensus.scan(Path.of(text)).values().get("profileFingerprint");
    }

    private static Map<String, Object> boundedJson(Path source, String label) throws IOException {
        Path absolute = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(absolute)) {
            throw new IOException(label + " isn't a regular file");
        }
        if (Files.size(absolute) > 2L * 1024L * 1024L) {
            throw new IOException(label + " exceeds 2 MiB");
        }
        return StrictJson.object(Files.readString(absolute, StandardCharsets.UTF_8));
    }

    static Map<String, Object> comparison(List<Map<String, Object>> phases) {
        Map<String, Object> comparison = new LinkedHashMap<>();
        if (phases.size() != 2) {
            comparison.put("available", false);
            return comparison;
        }
        Map<String, Object> baseline = object(phases.get(0).get("summary"));
        Map<String, Object> optimized = object(phases.get(1).get("summary"));
        if (baseline == null || optimized == null) {
            comparison.put("available", false);
            return comparison;
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        metric(metrics, "processToMainMenuMs", baseline, optimized, false);
        metric(metrics, "processToCampaignReadyMs", baseline, optimized, false);
        metric(metrics, "routeElapsedMs", baseline, optimized, false);
        metric(metrics, "stutterBurdenMillisPerSecond", baseline, optimized, false);
        metric(metrics, "repeatedSlowFramesPercent", baseline, optimized, false);
        metric(metrics, "slowFramesPerMinute", baseline, optimized, false);
        metric(metrics, "longestSlowFrameClusterMillis", baseline, optimized, false);
        metric(metrics, "over33_33Millis", baseline, optimized, false);
        metric(metrics, "over50Millis", baseline, optimized, false);
        metric(metrics, "over100Millis", baseline, optimized, false);
        metric(metrics, "p99FrameMicros", baseline, optimized, false);
        metric(metrics, "p95FrameMicros", baseline, optimized, false);
        metric(metrics, "pointOnePercentLowFps", baseline, optimized, true);
        metric(metrics, "onePercentLowFps", baseline, optimized, true);
        metric(metrics, "averageFps", baseline, optimized, true);
        metric(metrics, "medianFps", baseline, optimized, true);
        comparison.put("available", !metrics.isEmpty());
        comparison.put("smoothnessPriority", List.of(
                "stutterBurdenMillisPerSecond",
                "repeatedSlowFramesPercent",
                "slowFramesPerMinute",
                "longestSlowFrameClusterMillis",
                "over33_33Millis",
                "over50Millis",
                "over100Millis",
                "p99FrameMicros",
                "pointOnePercentLowFps",
                "onePercentLowFps",
                "averageFps",
                "medianFps"));
        comparison.put("metrics", metrics);
        return comparison;
    }

    private static Map<String, Object> summary(Map<String, Object> launch) throws IOException {
        Map<String, Object> evidence = object(launch.get("evidence"));
        if (evidence == null) throw new IOException("Passed benchmark phase lacks sealed evidence");
        Object runtimePath = launch.get("runtimeProcess");
        if (!(runtimePath instanceof Path path)) {
            throw new IOException("Passed benchmark phase lacks its runtime identity path");
        }
        Object processStarted = RuntimeProcessIdentity.read(path).inspect().get("startedAt");
        if (!(processStarted instanceof Instant processStart)) {
            throw new IOException("Benchmark runtime lacks a process start instant");
        }
        Instant mainMenu = stepCompleted(evidence, "menu");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("processToMainMenuMs", millis(processStart, mainMenu));
        if (!hasStep(evidence, "campaign")) {
            return summary;
        }
        Instant campaign = stepCompleted(evidence, "campaign");
        Instant routeStart = instant(evidence, "startedAt");
        Instant routeEnd = instant(evidence, "completedAt");
        Map<String, Object> frameTimes = frameTimes(evidence);
        Map<String, Object> campaignFrames = object(
                frameTimes.get(FrameTimeTelemetry.CAMPAIGN_AFTER_30_SECONDS_ACTIVE));
        if (campaignFrames == null) {
            throw new IOException("Benchmark frame report lacks settled campaign frames");
        }
        Long settledFrames = number(campaignFrames, "frames");
        Long settledActiveNanos = number(campaignFrames, FrameTimeTelemetry.TOTAL_ACTIVE_NANOS);
        if (settledFrames == null || settledFrames < MIN_SETTLED_CAMPAIGN_FRAMES
                || settledActiveNanos == null
                || settledActiveNanos < MIN_SETTLED_CAMPAIGN_ACTIVE_NANOS) {
            throw new IOException(
                    "Benchmark settled campaign coverage requires at least "
                            + MIN_SETTLED_CAMPAIGN_FRAMES + " frames and "
                            + Duration.ofNanos(MIN_SETTLED_CAMPAIGN_ACTIVE_NANOS).toSeconds()
                            + " active seconds");
        }
        Map<String, Object> measurement = object(frameTimes.get(FrameTimeTelemetry.MEASUREMENT_OVERHEAD));

        summary.put("processToCampaignReadyMs", millis(processStart, campaign));
        summary.put("routeElapsedMs", millis(routeStart, routeEnd));
        summary.put("selectedSave", selectedSave(evidence, launch));
        summary.put("runtimeContext", healthContext(evidence));
        summary.put("campaignWindow", "after-first-30-seconds");
        summary.put("campaignFrames", settledFrames);
        summary.put("campaignActiveNanos", settledActiveNanos);
        summary.put("campaignCoverage", Map.of(
                "minimumFrames", MIN_SETTLED_CAMPAIGN_FRAMES,
                "minimumActiveNanos", MIN_SETTLED_CAMPAIGN_ACTIVE_NANOS,
                "accepted", true));
        copyNumber(campaignFrames, summary, "averageFps", "averageFps");
        copyNumber(campaignFrames, summary, "medianFps", "medianFps");
        copyNumber(campaignFrames, summary, "onePercentLowFps", "onePercentLowFps");
        copyNumber(campaignFrames, summary, "pointOnePercentLowFps", "pointOnePercentLowFps");
        copyNumber(campaignFrames, summary, "p95Micros", "p95FrameMicros");
        copyNumber(campaignFrames, summary, "p99Micros", "p99FrameMicros");
        copyNumber(campaignFrames, summary, "over33_33Millis", "over33_33Millis");
        copyNumber(campaignFrames, summary, "over50Millis", "over50Millis");
        copyNumber(campaignFrames, summary, "over100Millis", "over100Millis");
        Map<String, Object> stutter = object(campaignFrames.get("stutterProfile"));
        copyNumber(stutter, summary, "slowFramesPerMinute", "slowFramesPerMinute");
        copyNumber(stutter, summary,
                "stutterBurdenMillisPerSecond", "stutterBurdenMillisPerSecond");
        copyNumber(stutter, summary,
                "repeatedSlowFramesPercent", "repeatedSlowFramesPercent");
        copyNumber(stutter, summary,
                "longestSlowFrameClusterMillis", "longestSlowFrameClusterMillis");
        copyNumber(
                campaignFrames, summary, "framesMeeting60FpsPercent", "framesMeeting60FpsPercent");
        summary.put("measurementOverhead", measurementOverhead(
                measurement, ((Number) summary.get("routeElapsedMs")).longValue()));
        return summary;
    }

    private static Map<String, Object> measurementOverhead(
            Map<String, Object> measurement, long routeElapsedMs) throws IOException {
        Long samples = number(measurement, "samples");
        Long totalNanos = number(measurement, "totalNanos");
        Object averageValue = measurement == null
                ? null
                : measurement.get(FrameTimeTelemetry.AVERAGE_MICROS);
        Object maximumValue = measurement == null ? null : measurement.get("maximumMicros");
        if (samples == null || samples <= 0L || totalNanos == null || totalNanos < 0L
                || !(averageValue instanceof Number average)
                || !(maximumValue instanceof Number maximum)
                || routeElapsedMs <= 0L) {
            throw new IOException("Benchmark frame report lacks measurement-overhead evidence");
        }
        double routeSharePercent = 100.0 * totalNanos / (routeElapsedMs * 1_000_000.0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("samples", samples);
        result.put("totalNanos", totalNanos);
        result.put("averageMicros", round(average.doubleValue()));
        result.put("maximumMicros", round(maximum.doubleValue()));
        result.put("routeSharePercent", round(routeSharePercent));
        result.put("withinBudget", routeSharePercent <= 1.0 && average.doubleValue() <= 250.0);
        result.put("budget", Map.of("maximumRouteSharePercent", 1.0, "maximumAverageMicros", 250.0));
        return result;
    }

    private static Map<String, Object> comparisonContext(
            List<Map<String, Object>> phases, Map<String, Object> storage) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (!phases.isEmpty()) context.put("measurementOnly", phaseRuntimeContext(phases.get(0)));
        if (phases.size() > 1) context.put("optimized", phaseRuntimeContext(phases.get(1)));
        Map<String, Object> overhead = new LinkedHashMap<>();
        if (!phases.isEmpty()) {
            overhead.put("measurementOnly", phaseMeasurementOverhead(phases.get(0)));
        }
        if (phases.size() > 1) {
            overhead.put("optimized", phaseMeasurementOverhead(phases.get(1)));
        }
        context.put("measurementOverhead", overhead);
        context.put("storage", storage);
        return context;
    }

    private static Map<String, Object> phaseRuntimeContext(Map<String, Object> phase) {
        Map<String, Object> summary = object(phase.get("summary"));
        return summary == null ? null : object(summary.get("runtimeContext"));
    }

    private static Map<String, Object> phaseMeasurementOverhead(Map<String, Object> phase) {
        Map<String, Object> summary = object(phase.get("summary"));
        return summary == null ? null : object(summary.get("measurementOverhead"));
    }

    private static Map<String, Object> healthContext(Map<String, Object> evidence)
            throws IOException {
        Path healthPath = artifact(evidence, "adapter-health", "adapter health report");
        Map<String, Object> health = boundedJson(healthPath, "adapter health report");
        if (!"starsector-preflight-runtime-adapter-health-v1".equals(health.get("format"))) {
            throw new IOException("Benchmark adapter health format is unsupported");
        }
        Map<String, Object> transformations = object(health.get("adapterTransformationCache"));
        Map<String, Object> merged = object(health.get("mergedReadCache"));
        Map<String, Object> textures = object(health.get("preparedTextures"));
        Map<String, Object> audio = object(health.get("preparedAudio"));
        Map<String, Object> memory = object(health.get("memoryPressure"));

        long cacheHits = sum(
                number(transformations, "hits"),
                number(merged, "hits"),
                number(textures, "hits"),
                number(audio, "servedFromCache"));
        long cacheMisses = sum(
                number(transformations, "misses"),
                number(merged, "misses"),
                number(textures, "misses"),
                number(audio, "decodedByTheGame"));
        long fallbacks = sum(
                number(textures, "fallbacks"),
                number(audio, "decodedByTheGame"));
        long failures = sum(
                number(transformations, "readFailures"),
                number(transformations, "writeFailures"),
                number(textures, "corruptions"),
                number(textures, "internalErrors"),
                number(textures, "packFailures"),
                number(audio, "failures"));
        Long availablePercent = number(memory, "sessionAvailablePercent");

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("adapterMode", health.get("adapterMode"));
        context.put("cacheHits", cacheHits);
        context.put("cacheMisses", cacheMisses);
        context.put("fallbacks", fallbacks);
        context.put("failures", failures);
        context.put("memoryAvailablePercent",
                availablePercent != null && availablePercent >= 0 && availablePercent <= 100
                        ? availablePercent
                        : null);
        return context;
    }

    private static Map<String, Object> storageContext() throws IOException {
        CacheFootprint.Report footprint = CacheFootprint.measure(PreflightHome.current());
        long bytes = 0L;
        long files = 0L;
        List<Map<String, Object>> categories = new ArrayList<>();
        for (CacheFootprint.Entry entry : footprint.entries()) {
            if (!"acceleration".equals(entry.group())) continue;
            bytes = Math.addExact(bytes, entry.usage().bytes());
            files = Math.addExact(files, entry.usage().files());
            categories.add(Map.of(
                    "path", entry.path(),
                    "bytes", entry.usage().bytes(),
                    "files", entry.usage().files()));
        }
        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("scope", "all-prepared-data");
        storage.put("bytes", bytes);
        storage.put("files", files);
        storage.put("categories", List.copyOf(categories));
        return storage;
    }

    private static Long number(Map<String, Object> source, String field) {
        if (source == null) return null;
        Object value = source.get(field);
        return value instanceof Number number ? number.longValue() : null;
    }

    private static long sum(Long... values) {
        long total = 0L;
        for (Long value : values) {
            if (value != null && value > 0) total = Math.addExact(total, value);
        }
        return total;
    }

    static Map<String, Object> selectedSave(Map<String, Object> phase) throws IOException {
        Map<String, Object> summary = object(phase.get("summary"));
        Map<String, Object> selected = summary == null ? null : object(summary.get("selectedSave"));
        if (selected == null || selected.isEmpty()) {
            throw new IOException("Benchmark phase lacks a selected-save identity");
        }
        return selected;
    }

    private static Map<String, Object> optionalSelectedSave(Map<String, Object> phase) {
        Map<String, Object> summary = object(phase.get("summary"));
        return summary == null ? null : object(summary.get("selectedSave"));
    }

    static Map<String, Object> selectedSave(
            Map<String, Object> evidence, Map<String, Object> launch) throws IOException {
        Path logTail = artifact(evidence, "log-tail", "benchmark log tail");
        if (Files.size(logTail) > 2L * 1024L * 1024L) {
            throw new IOException("Benchmark log tail exceeds 2 MiB");
        }
        String log = Files.readString(logTail, StandardCharsets.UTF_8);
        String marker = "Reading save data from [";
        int start = log.lastIndexOf(marker);
        int end = start < 0 ? -1 : log.indexOf(']', start + marker.length());
        if (start < 0 || end < 0) {
            throw new IOException("Benchmark log doesn't identify the loaded save");
        }
        String declared = log.substring(start + marker.length(), end).strip();
        if (declared.isEmpty()) throw new IOException("Benchmark log has an empty save path");

        Object runDirectory = launch.get("runDirectory");
        if (!(runDirectory instanceof Path run)) {
            throw new IOException("Passed benchmark phase lacks its run directory");
        }
        Map<String, Object> runMetadata = boundedJson(run.resolve("run.json"), "run metadata");
        Object installValue = runMetadata.get("installRoot");
        if (!(installValue instanceof String installText) || installText.isBlank()) {
            throw new IOException("Run metadata lacks the installation root");
        }
        Path saves = Path.of(installText).toAbsolutePath().normalize().resolve("saves").toRealPath();
        Path declaredDescriptor = Path.of(declared).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(declaredDescriptor)) {
            throw new IOException("Loaded save descriptor must not be a symbolic link");
        }
        Path descriptor = declaredDescriptor.toRealPath();
        Path saveDirectory = descriptor.getParent();
        if (!"descriptor.xml".equals(descriptor.getFileName().toString())
                || saveDirectory == null
                || saveDirectory.getParent() == null
                || !saveDirectory.getParent().equals(saves)
                || !Files.isRegularFile(descriptor, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(descriptor)) {
            throw new IOException("Loaded save descriptor isn't a regular file inside this installation");
        }
        Map<String, Object> selected = new LinkedHashMap<>();
        selected.put("directory", saveDirectory.getFileName().toString());
        selected.put("descriptorSha256", Hashes.sha256(descriptor));
        return selected;
    }

    private static Map<String, Object> frameTimes(Map<String, Object> evidence)
            throws IOException {
        Path frameReport = artifact(evidence, "frame-report", "frame report");
        Map<String, Object> report = boundedJson(frameReport, "frame report");
        if (!FrameTimeTelemetry.FRAME_REPORT_FORMAT.equals(report.get("format"))) {
            throw new IOException("Benchmark frame report format is unsupported");
        }
        Map<String, Object> frameTimes = object(report.get(FrameTimeTelemetry.REPORT));
        if (frameTimes == null) throw new IOException("Benchmark frame report lacks frame telemetry");
        return frameTimes;
    }

    private static Path artifact(Map<String, Object> evidence, String kind, String label)
            throws IOException {
        Object artifacts = evidence.get("artifacts");
        if (artifacts instanceof List<?> list) {
            for (Object raw : list) {
                Map<String, Object> artifact = object(raw);
                if (artifact != null && kind.equals(artifact.get("kind"))) {
                    Object path = artifact.get("path");
                    if (path instanceof Path candidate
                            && Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                            && !Files.isSymbolicLink(candidate)) {
                        return candidate.toAbsolutePath().normalize();
                    }
                }
            }
        }
        throw new IOException("Benchmark evidence lacks its " + label);
    }

    private static Instant stepCompleted(Map<String, Object> evidence, String id)
            throws IOException {
        Object steps = evidence.get("steps");
        if (steps instanceof List<?> list) {
            for (Object raw : list) {
                Map<String, Object> step = object(raw);
                if (step != null && id.equals(step.get("id"))) {
                    return instant(step, "completedAt");
                }
            }
        }
        throw new IOException("Benchmark evidence lacks the " + id + " milestone");
    }

    private static boolean hasStep(Map<String, Object> evidence, String id) {
        Object steps = evidence.get("steps");
        if (!(steps instanceof List<?> list)) return false;
        for (Object raw : list) {
            Map<String, Object> step = object(raw);
            if (step != null && id.equals(step.get("id"))) return true;
        }
        return false;
    }

    private static Instant instant(Map<String, Object> value, String field) throws IOException {
        Object raw = value.get(field);
        if (raw instanceof Instant instant) return instant;
        throw new IOException("Benchmark evidence lacks " + field);
    }

    private static long millis(Instant start, Instant end) throws IOException {
        if (end.isBefore(start)) throw new IOException("Benchmark milestone order is invalid");
        return Duration.between(start, end).toMillis();
    }

    private static void copyNumber(
            Map<String, Object> source,
            Map<String, Object> destination,
            String sourceName,
            String destinationName) {
        if (source == null) {
            destination.put(destinationName, null);
            return;
        }
        Object value = source.get(sourceName);
        destination.put(destinationName, value instanceof Number ? value : null);
    }

    private static void metric(
            Map<String, Object> metrics,
            String name,
            Map<String, Object> baseline,
            Map<String, Object> optimized,
            boolean higherIsBetter) {
        Object baselineValue = baseline.get(name);
        Object optimizedValue = optimized.get(name);
        if (!(baselineValue instanceof Number before) || !(optimizedValue instanceof Number after)) {
            return;
        }
        double beforeNumber = before.doubleValue();
        double afterNumber = after.doubleValue();
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("measurementOnly", before);
        metric.put("optimized", after);
        metric.put("delta", round(afterNumber - beforeNumber));
        metric.put("improvementPercent", beforeNumber <= 0.0
                ? null
                : round(100.0 * (higherIsBetter
                        ? afterNumber - beforeNumber
                        : beforeNumber - afterNumber) / beforeNumber));
        metrics.put(name, metric);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
