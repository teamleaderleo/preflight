package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.BenchmarkScenarioMode;
import dev.starsector.preflight.core.BenchmarkScenarioResult;
import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads deterministic scenario records and produces one identity-checked comparison report. */
final class BenchmarkScenarioComparison {
    private static final String SCENARIO_SCHEMA = "starsector-preflight-benchmark-scenario";
    private static final String COMPARISON_SCHEMA = "starsector-preflight-benchmark-comparison";
    private static final int COMPARISON_VERSION = 1;
    private static final int MAX_INPUTS = 10_000;
    private static final long MAX_RESULT_BYTES = 1024L * 1024L;

    private BenchmarkScenarioComparison() {
    }

    static int execute(String[] args, int offset) throws IOException {
        Options options = parse(args, offset);
        List<LoadedResult> results = new ArrayList<>();
        for (Path input : options.inputs()) {
            results.add(new LoadedResult(input.toAbsolutePath().normalize(), read(input)));
        }
        validateComparable(results);
        results.sort(Comparator
                .comparingInt((LoadedResult value) -> value.result().mode().ordinal())
                .thenComparingInt(value -> value.result().iteration())
                .thenComparing(value -> value.result().runId()));

        BenchmarkScenarioResult first = results.get(0).result();
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("scenario", first.scenario());
        identity.put("profileFingerprint", first.profileFingerprint());

        List<Map<String, Object>> runs = new ArrayList<>();
        for (LoadedResult loaded : results) {
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("source", loaded.source());
            run.put("result", loaded.result().toMap());
            runs.add(run);
        }

        Map<String, Object> modes = new LinkedHashMap<>();
        for (BenchmarkScenarioMode mode : BenchmarkScenarioMode.values()) {
            List<BenchmarkScenarioResult> group = results.stream()
                    .map(LoadedResult::result)
                    .filter(result -> result.mode() == mode)
                    .toList();
            if (!group.isEmpty()) {
                modes.put(mode.toString(), modeSummary(group));
            }
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("schema", COMPARISON_SCHEMA);
        output.put("version", COMPARISON_VERSION);
        output.put("identity", identity);
        output.put("totalRuns", results.size());
        output.put("successfulRuns", results.stream().filter(value -> value.result().exitCode() == 0).count());
        output.put("failedRuns", results.stream().filter(value -> value.result().exitCode() != 0).count());
        output.put("runs", runs);
        output.put("modes", modes);

        String json = Json.object(output);
        if (options.output() == null) {
            System.out.println(json);
        } else {
            Path outputPath = options.output().toAbsolutePath().normalize();
            Path parent = outputPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(outputPath, json + System.lineSeparator());
            System.out.println(outputPath);
        }
        return 0;
    }

    static BenchmarkScenarioResult read(Path input) throws IOException {
        Path path = input.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IOException("Benchmark scenario result does not exist: " + path);
        }
        Map<String, Object> root = BoundedEvidenceJson.readObject(
                path, MAX_RESULT_BYTES, "Benchmark scenario result");
        requireKeys(root, Set.of("schema", "version", "identity", "milestones", "durationsMs", "telemetry", "exit"), "result");
        if (!SCENARIO_SCHEMA.equals(text(root, "schema"))) {
            throw invalid(path, "unexpected schema");
        }
        if (integer(root, "version") != BenchmarkScenarioResult.VERSION) {
            throw invalid(path, "unsupported version");
        }

        Map<String, Object> identity = object(root, "identity");
        requireKeys(identity, Set.of("runId", "scenario", "mode", "iteration", "profileFingerprint"), "identity");
        Map<String, Object> milestones = object(root, "milestones");
        requireKeys(milestones, Set.of("processStarted", "mainMenuReady", "campaignReady", "firstCombatReady"), "milestones");
        Map<String, Object> telemetry = object(root, "telemetry");
        requireKeys(telemetry, Set.of("adapterCounters", "cacheCounters", "disableReasons"), "telemetry");
        Map<String, Object> exit = object(root, "exit");
        requireKeys(exit, Set.of("code", "successful"), "exit");

        BenchmarkScenarioResult result = new BenchmarkScenarioResult(
                text(identity, "runId"),
                text(identity, "scenario"),
                BenchmarkScenarioMode.parse(text(identity, "mode")),
                integer(identity, "iteration"),
                optionalText(identity, "profileFingerprint"),
                instant(milestones, "processStarted"),
                instant(milestones, "mainMenuReady"),
                instant(milestones, "campaignReady"),
                instant(milestones, "firstCombatReady"),
                integer(exit, "code"),
                counters(telemetry, "adapterCounters"),
                counters(telemetry, "cacheCounters"),
                reasons(telemetry, "disableReasons"));

        boolean successful = bool(exit, "successful");
        if (successful != (result.exitCode() == 0)) {
            throw invalid(path, "exit.successful disagrees with exit.code");
        }
        verifyDurations(path, object(root, "durationsMs"), result);
        return result;
    }

    private static Map<String, Object> modeSummary(List<BenchmarkScenarioResult> results) {
        List<BenchmarkScenarioResult> successful = results.stream()
                .filter(result -> result.exitCode() == 0)
                .toList();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalRuns", results.size());
        summary.put("successfulRuns", successful.size());
        summary.put("failedRuns", results.size() - successful.size());
        summary.put("statistics", successful.isEmpty() ? null : statistics(successful));
        return summary;
    }

    private static Map<String, Object> statistics(List<BenchmarkScenarioResult> results) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("processToMainMenuMs", metric(results, result -> millis(result.processStarted(), result.mainMenuReady())));
        values.put("mainMenuToCampaignReadyMs", metric(results, result -> millis(result.mainMenuReady(), result.campaignReady())));
        values.put("campaignToFirstCombatReadyMs", metric(results, result -> millis(result.campaignReady(), result.firstCombatReady())));
        values.put("processToCampaignReadyMs", metric(results, result -> millis(result.processStarted(), result.campaignReady())));
        values.put("processToFirstCombatReadyMs", metric(results, result -> millis(result.processStarted(), result.firstCombatReady())));
        return values;
    }

    private static Map<String, Object> metric(
            List<BenchmarkScenarioResult> results,
            Metric metric) {
        List<Long> values = results.stream().map(metric::value).sorted().toList();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("minimum", values.get(0));
        summary.put("median", median(values));
        summary.put("maximum", values.get(values.size() - 1));
        return summary;
    }

    private static Number median(List<Long> sorted) {
        int middle = sorted.size() / 2;
        if ((sorted.size() & 1) == 1) {
            return sorted.get(middle);
        }
        return BigDecimal.valueOf(sorted.get(middle - 1))
                .add(BigDecimal.valueOf(sorted.get(middle)))
                .divide(BigDecimal.valueOf(2))
                .setScale(1);
    }

    private static void validateComparable(List<LoadedResult> loaded) {
        if (loaded.size() < 2) {
            throw new IllegalArgumentException("Benchmark comparison requires at least two scenario result files");
        }
        BenchmarkScenarioResult first = loaded.get(0).result();
        if (first.profileFingerprint() == null) {
            throw new IllegalArgumentException("Benchmark comparison requires a profile fingerprint in every result");
        }
        Set<String> runIds = new LinkedHashSet<>();
        Set<String> iterations = new LinkedHashSet<>();
        for (LoadedResult value : loaded) {
            BenchmarkScenarioResult result = value.result();
            if (!first.scenario().equals(result.scenario())) {
                throw new IllegalArgumentException("Benchmark scenario mismatch: " + result.scenario()
                        + " differs from " + first.scenario());
            }
            if (!first.profileFingerprint().equals(result.profileFingerprint())) {
                throw new IllegalArgumentException("Benchmark profile mismatch for run " + result.runId());
            }
            if (!runIds.add(result.runId())) {
                throw new IllegalArgumentException("Duplicate benchmark run ID: " + result.runId());
            }
            String iteration = result.mode() + "#" + result.iteration();
            if (!iterations.add(iteration)) {
                throw new IllegalArgumentException("Duplicate benchmark mode iteration: " + iteration);
            }
        }
    }

    private static void verifyDurations(
            Path source,
            Map<String, Object> durations,
            BenchmarkScenarioResult result) {
        requireKeys(durations, Set.of(
                "processToMainMenuMs",
                "mainMenuToCampaignReadyMs",
                "campaignToFirstCombatReadyMs",
                "processToCampaignReadyMs",
                "processToFirstCombatReadyMs"), "durationsMs");
        verifyDuration(source, durations, "processToMainMenuMs", millis(result.processStarted(), result.mainMenuReady()));
        verifyDuration(source, durations, "mainMenuToCampaignReadyMs", millis(result.mainMenuReady(), result.campaignReady()));
        verifyDuration(source, durations, "campaignToFirstCombatReadyMs", millis(result.campaignReady(), result.firstCombatReady()));
        verifyDuration(source, durations, "processToCampaignReadyMs", millis(result.processStarted(), result.campaignReady()));
        verifyDuration(source, durations, "processToFirstCombatReadyMs", millis(result.processStarted(), result.firstCombatReady()));
    }

    private static void verifyDuration(Path source, Map<String, Object> values, String name, long expected) {
        if (longValue(values, name) != expected) {
            throw invalid(source, "duration " + name + " disagrees with milestones");
        }
    }

    private static Options parse(String[] args, int offset) {
        List<Path> inputs = new ArrayList<>();
        Path output = null;
        for (int i = offset; i < args.length; i++) {
            if ("--output".equals(args[i])) {
                if (output != null) {
                    throw new IllegalArgumentException("Duplicate --output option");
                }
                if (++i >= args.length) {
                    throw new IllegalArgumentException("Missing value for --output");
                }
                output = Path.of(args[i]);
            } else if (args[i].startsWith("--")) {
                throw new IllegalArgumentException("Unknown benchmark comparison option: " + args[i]);
            } else {
                if (inputs.size() >= MAX_INPUTS) {
                    throw new IllegalArgumentException("Benchmark comparison exceeds " + MAX_INPUTS + " input files");
                }
                inputs.add(Path.of(args[i]));
            }
        }
        if (inputs.size() < 2) {
            throw new IllegalArgumentException(
                    "Expected: benchmark compare <scenario-result.json> <scenario-result.json>... [--output <comparison.json>]");
        }
        return new Options(List.copyOf(inputs), output);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> parent, String name) {
        Object value = parent.get(name);
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected object field " + name);
        }
        return (Map<String, Object>) map;
    }

    private static String text(Map<String, Object> values, String name) {
        String value = optionalText(values, name);
        if (value == null) {
            throw new IllegalArgumentException("Expected string field " + name);
        }
        return value;
    }

    private static String optionalText(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("Expected string or null field " + name);
        }
        return text;
    }

    private static int integer(Map<String, Object> values, String name) {
        long value = longValue(values, name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Integer field is out of range: " + name);
        }
        return (int) value;
    }

    private static long longValue(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (!(value instanceof Long number)) {
            throw new IllegalArgumentException("Expected integer field " + name);
        }
        return number;
    }

    private static boolean bool(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (!(value instanceof Boolean flag)) {
            throw new IllegalArgumentException("Expected boolean field " + name);
        }
        return flag;
    }

    private static Instant instant(Map<String, Object> values, String name) {
        try {
            return Instant.parse(text(values, name));
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException("Invalid instant field " + name, error);
        }
    }

    private static Map<String, Long> counters(Map<String, Object> telemetry, String name) {
        Map<String, Object> values = object(telemetry, name);
        Map<String, Long> counters = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!(entry.getValue() instanceof Long number)) {
                throw new IllegalArgumentException("Expected integer counter " + name + "." + entry.getKey());
            }
            counters.put(entry.getKey(), number);
        }
        return counters;
    }

    private static List<String> reasons(Map<String, Object> telemetry, String name) {
        Object value = telemetry.get(name);
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("Expected array field " + name);
        }
        List<String> reasons = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String reason)) {
                throw new IllegalArgumentException("Expected string entries in " + name);
            }
            reasons.add(reason);
        }
        return reasons;
    }

    private static void requireKeys(Map<String, Object> values, Set<String> expected, String name) {
        if (!values.keySet().equals(expected)) {
            throw new IllegalArgumentException("Unexpected " + name + " fields: " + values.keySet());
        }
    }

    private static IllegalArgumentException invalid(Path source, String reason) {
        return new IllegalArgumentException("Invalid benchmark scenario result " + source + ": " + reason);
    }

    private static long millis(Instant start, Instant end) {
        return Duration.between(start, end).toMillis();
    }

    private record Options(List<Path> inputs, Path output) {
    }

    private record LoadedResult(Path source, BenchmarkScenarioResult result) {
    }

    @FunctionalInterface
    private interface Metric {
        long value(BenchmarkScenarioResult result);
    }
}
