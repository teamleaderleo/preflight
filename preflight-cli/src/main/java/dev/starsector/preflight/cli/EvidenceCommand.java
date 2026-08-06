package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reports and prunes diagnostic evidence independently from acceleration caches. */
final class EvidenceCommand {
    private static final String REPORT_FORMAT = "starsector-preflight-evidence-v1";
    private static final String PRUNE_FORMAT = "starsector-preflight-evidence-prune-v1";
    private static final String EXPORT_FORMAT = "starsector-preflight-diagnostics-export-v1";

    private EvidenceCommand() {
    }

    static int execute(String[] args, int offset) throws Exception {
        boolean prune = false;
        boolean export = false;
        boolean confirmed = false;
        boolean json = false;
        Integer keepRuns = null;
        Integer keepBenchmarks = null;
        int exportRuns = DiagnosticBundle.DEFAULT_RUNS;
        int exportBenchmarks = DiagnosticBundle.DEFAULT_BENCHMARKS;
        Path output = null;
        boolean exportOptions = false;
        boolean overwrite = false;
        for (int index = offset; index < args.length; index++) {
            switch (args[index]) {
                case "prune" -> prune = true;
                case "export" -> export = true;
                case "--yes" -> confirmed = true;
                case "--json" -> json = true;
                case "--keep-runs" -> keepRuns = count(args, ++index, "--keep-runs");
                case "--keep-benchmarks" ->
                        keepBenchmarks = count(args, ++index, "--keep-benchmarks");
                case "--runs" -> {
                    exportOptions = true;
                    exportRuns = count(args, ++index, "--runs");
                }
                case "--benchmarks" -> {
                    exportOptions = true;
                    exportBenchmarks = count(args, ++index, "--benchmarks");
                }
                case "--output" -> {
                    exportOptions = true;
                    output = path(args, ++index, "--output");
                }
                case "--overwrite" -> {
                    exportOptions = true;
                    overwrite = true;
                }
                case "--help", "-h" -> {
                    PreflightCli.commandUsage("evidence", System.out);
                    return 0;
                }
                default -> throw new IllegalArgumentException(
                        "preflight evidence: unknown option: " + args[index]);
            }
        }
        if (prune && export) {
            throw new IllegalArgumentException("evidence prune and export are separate operations");
        }
        if (export) {
            if (confirmed || keepRuns != null || keepBenchmarks != null) {
                throw new IllegalArgumentException(
                        "--yes and retention counts require `preflight evidence prune`");
            }
            if (output == null) {
                throw new IllegalArgumentException("evidence export requires --output <bundle.zip>");
            }
            PreflightHome home = PreflightHome.current();
            DiagnosticBundle.Result result = DiagnosticBundle.export(
                    home,
                    EvidenceRetention.inventory(home),
                    output,
                    exportRuns,
                    exportBenchmarks,
                    overwrite);
            return exported(result, json, System.out);
        }
        if (exportOptions) {
            throw new IllegalArgumentException(
                    "--output, --overwrite, --runs, and --benchmarks require `preflight evidence export`");
        }
        if (!prune && (confirmed || keepRuns != null || keepBenchmarks != null)) {
            throw new IllegalArgumentException(
                    "--yes and retention counts require `preflight evidence prune`");
        }
        if (prune && keepRuns == null && keepBenchmarks == null) {
            throw new IllegalArgumentException(
                    "evidence prune requires --keep-runs, --keep-benchmarks, or both");
        }
        PreflightHome home = PreflightHome.current();
        EvidenceRetention.Inventory inventory = EvidenceRetention.inventory(home);
        if (!prune) {
            return report(home, inventory, json, System.out);
        }
        EvidenceRetention.Plan plan = EvidenceRetention.plan(inventory, keepRuns, keepBenchmarks);
        return prune(plan, confirmed, json, System.out);
    }

    static int exported(DiagnosticBundle.Result result, boolean json, PrintStream out) {
        if (json) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("format", EXPORT_FORMAT);
            report.put("output", result.output());
            report.put("bytes", result.bytes());
            report.put("sha256", result.sha256());
            report.put("files", result.files());
            report.put("runs", result.runs());
            report.put("benchmarks", result.benchmarks());
            report.put("included", result.included().stream()
                    .map(DiagnosticBundle.Included::view).toList());
            report.put("skipped", result.skipped().stream()
                    .map(DiagnosticBundle.Skipped::view).toList());
            out.println(Json.object(report));
            return 0;
        }
        out.printf(Locale.ROOT, "Saved diagnostics to %s (%s, %,d files).%n",
                result.output(), CacheFootprint.humanBytes(result.bytes()), result.files());
        out.printf(Locale.ROOT, "  newest %,d launch runs and %,d benchmark sessions%n",
                result.runs(), result.benchmarks());
        out.printf(Locale.ROOT, "  %,d metadata files included; %,d present files skipped%n",
                result.included().size(), result.skipped().size());
        out.println("The ZIP contains a disclosure and manifest. Inspect it before sharing.");
        return 0;
    }

    static int report(
            PreflightHome home,
            EvidenceRetention.Inventory inventory,
            boolean json,
            PrintStream out) {
        if (json) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("format", REPORT_FORMAT);
            report.put("root", home.root());
            report.put("bytes", inventory.bytes());
            report.put("files", inventory.files());
            report.put("runs", sessions(inventory.runs()));
            report.put("benchmarks", sessions(inventory.benchmarks()));
            out.println(Json.object(report));
            return 0;
        }
        out.printf(Locale.ROOT, "Diagnostic evidence: %s across %,d files%n",
                CacheFootprint.humanBytes(inventory.bytes()), inventory.files());
        out.printf(Locale.ROOT, "  %,d launch runs%n", inventory.runs().size());
        out.printf(Locale.ROOT, "  %,d benchmark sessions%n", inventory.benchmarks().size());
        out.println("Acceleration caches are separate and are never removed by this command.");
        return 0;
    }

    static int prune(
            EvidenceRetention.Plan plan,
            boolean confirmed,
            boolean json,
            PrintStream out) throws Exception {
        long removed = 0;
        if (confirmed && !plan.removals().isEmpty()) {
            removed = EvidenceRetention.apply(plan);
        }
        if (json) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("format", PRUNE_FORMAT);
            report.put("applied", confirmed);
            report.put("keepRuns", plan.keepRuns());
            report.put("keepBenchmarks", plan.keepBenchmarks());
            report.put("bytes", plan.bytes());
            report.put("files", plan.files());
            report.put("removedBytes", removed);
            report.put("sessions", sessions(plan.removals()));
            out.println(Json.object(report));
            return 0;
        }
        out.printf(Locale.ROOT, "%s %,d evidence sessions (%,d files), freeing %s.%n",
                confirmed ? "Removed" : "Would remove",
                plan.removals().size(),
                plan.files(),
                CacheFootprint.humanBytes(plan.bytes()));
        if (!confirmed) {
            out.println("Nothing was removed. Re-run the same command with --yes to apply it.");
        }
        return 0;
    }

    private static List<Map<String, Object>> sessions(List<EvidenceRetention.Session> sessions) {
        return sessions.stream().map(session -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("kind", session.kind());
            value.put("name", session.path().getFileName().toString());
            value.put("path", session.path());
            value.put("bytes", session.bytes());
            value.put("files", session.files());
            value.put("modifiedMillis", session.modifiedMillis());
            return value;
        }).toList();
    }

    private static int count(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " requires a count");
        }
        try {
            int value = Integer.parseInt(args[index]);
            if (value < 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(option + " requires a nonnegative integer");
        }
    }

    private static Path path(String[] args, int index, String option) {
        if (index >= args.length || args[index].isBlank()) {
            throw new IllegalArgumentException(option + " requires a path");
        }
        return Path.of(args[index]);
    }
}
