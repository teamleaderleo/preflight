package dev.starsector.preflight.cli;

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

        Path baselineDirectory = session.resolve("measurement-only");
        Map<String, Object> baselineResult = runPhase(
                "measurement-only", baseline, baselineDirectory,
                cancellation, game, launcher, driver, clock);
        phases.add(phase("measurement-only", baselineResult));

        String status = status(baselineResult);
        if ("passed".equals(status)) {
            try {
                measuredIdentity = measuredIdentity(baselineDirectory);
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
            phases.add(phase("optimized", candidateResult));
            status = status(candidateResult);
            if ("passed".equals(status)) {
                try {
                    Map<String, Object> candidateIdentity = measuredIdentity(candidateDirectory);
                    if (!candidateIdentity.equals(measuredIdentity)) {
                        status = "failed";
                        benchmarkDiagnostics.add(
                                "The installation, profile, or launch settings changed between runs");
                    }
                } catch (IOException | IllegalArgumentException failure) {
                    status = "failed";
                    benchmarkDiagnostics.add(
                            "Optimized-run identity unavailable: " + failure.getMessage());
                }
            }
        }
        if (cancellationRequested(cancellation)) status = "cancelled";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", FORMAT);
        result.put("status", status);
        result.put("sessionDirectory", session);
        Map<String, Object> sealedIdentity = new LinkedHashMap<>(identity);
        sealedIdentity.put("measuredRun", measuredIdentity);
        result.put("identity", sealedIdentity);
        result.put("phases", List.copyOf(phases));
        result.put("complete", phases.size() == 2 && "passed".equals(status));
        List<String> diagnostics = new ArrayList<>(diagnostics(phases));
        diagnostics.addAll(benchmarkDiagnostics);
        result.put("diagnostics", List.copyOf(diagnostics));
        result.put("comparison", comparison(phases));
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
        try {
            return DesktopSmokeLaunch.launch(
                    scenario, runDirectory, game, launcher, driver, clock);
        } finally {
            finished.set(true);
            cancellationMirror.interrupt();
            cancellationMirror.join(1_000L);
            if (cancellationMirror.isAlive()) {
                throw new IOException("The " + phase + " cancellation monitor didn't stop");
            }
        }
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

    private static Map<String, Object> phase(String name, Map<String, Object> launch) {
        Map<String, Object> phase = new LinkedHashMap<>();
        phase.put("name", name);
        phase.put("status", status(launch));
        phase.put("runDirectory", launch.get("runDirectory"));
        phase.put("launch", launch);
        return phase;
    }

    private static String status(Map<String, Object> launch) {
        Object value = launch.get("status");
        return value instanceof String text ? text : "failed";
    }

    private static List<String> diagnostics(List<Map<String, Object>> phases) {
        if (phases.isEmpty()) return List.of();
        Object launch = phases.get(phases.size() - 1).get("launch");
        if (!(launch instanceof Map<?, ?> launchMap)) return List.of();
        Object values = launchMap.get("diagnostics");
        if (!(values instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .limit(100)
                .toList();
    }

    static Map<String, Object> measuredIdentity(Path runDirectory) throws IOException {
        Map<String, Object> run = boundedJson(runDirectory.resolve("run.json"), "run metadata");
        Map<String, Object> profile = boundedJson(
                runDirectory.resolve("profile.json"), "profile metadata");
        Object fingerprint = profile.get("profileFingerprint");
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
        Long baselineMillis = elapsedMillis(phases.get(0));
        Long optimizedMillis = elapsedMillis(phases.get(1));
        comparison.put("available", baselineMillis != null && optimizedMillis != null);
        comparison.put("measurementOnlyElapsedMs", baselineMillis);
        comparison.put("optimizedElapsedMs", optimizedMillis);
        comparison.put("elapsedDeltaMs", baselineMillis == null || optimizedMillis == null
                ? null
                : optimizedMillis - baselineMillis);
        comparison.put("elapsedImprovementPercent",
                improvementPercent(baselineMillis, optimizedMillis));
        return comparison;
    }

    private static Long elapsedMillis(Map<String, Object> phase) {
        Object rawLaunch = phase.get("launch");
        if (!(rawLaunch instanceof Map<?, ?> launch)) return null;
        Object rawEvidence = launch.get("evidence");
        if (!(rawEvidence instanceof Map<?, ?> evidence)) return null;
        Object startedAt = evidence.get("startedAt");
        Object completedAt = evidence.get("completedAt");
        if (!(startedAt instanceof Instant start) || !(completedAt instanceof Instant end)) return null;
        return Math.max(0L, Duration.between(start, end).toMillis());
    }

    private static Double improvementPercent(Long baseline, Long optimized) {
        if (baseline == null || optimized == null || baseline <= 0L) return null;
        return Math.round((baseline - optimized) * 10_000.0 / baseline) / 100.0;
    }
}
