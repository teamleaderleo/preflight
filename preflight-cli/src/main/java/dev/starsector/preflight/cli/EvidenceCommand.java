package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Reports and prunes diagnostic evidence independently from acceleration caches. */
final class EvidenceCommand {
    private static final String REPORT_FORMAT = "starsector-preflight-evidence-v1";
    private static final String PRUNE_FORMAT = "starsector-preflight-evidence-prune-v1";
    private static final String EXPORT_FORMAT = "starsector-preflight-diagnostics-export-v1";
    private static final String HISTORY_EXPORT_FORMAT = "starsector-preflight-play-history-export-v1";

    private EvidenceCommand() {
    }

    static int execute(String[] args, int offset) throws Exception {
        boolean prune = false;
        boolean export = false;
        boolean historyExport = false;
        boolean confirmed = false;
        boolean json = false;
        Integer keepRuns = null;
        Integer keepBenchmarks = null;
        int exportRuns = DiagnosticBundle.DEFAULT_RUNS;
        int exportBenchmarks = DiagnosticBundle.DEFAULT_BENCHMARKS;
        boolean countSelection = false;
        List<String> runSessions = new ArrayList<>();
        List<String> benchmarkSessions = new ArrayList<>();
        Path output = null;
        Path historyCsv = null;
        boolean exportOptions = false;
        boolean overwrite = false;
        for (int index = offset; index < args.length; index++) {
            switch (args[index]) {
                case "prune" -> prune = true;
                case "export" -> export = true;
                case "history-export" -> historyExport = true;
                case "--yes" -> confirmed = true;
                case "--json" -> json = true;
                case "--keep-runs" -> keepRuns = count(args, ++index, "--keep-runs");
                case "--keep-benchmarks" ->
                        keepBenchmarks = count(args, ++index, "--keep-benchmarks");
                case "--runs" -> {
                    exportOptions = true;
                    countSelection = true;
                    exportRuns = count(args, ++index, "--runs");
                }
                case "--benchmarks" -> {
                    exportOptions = true;
                    countSelection = true;
                    exportBenchmarks = count(args, ++index, "--benchmarks");
                }
                case "--run-session" -> {
                    exportOptions = true;
                    runSessions.add(sessionName(args, ++index, "--run-session"));
                }
                case "--benchmark-session" -> {
                    exportOptions = true;
                    benchmarkSessions.add(sessionName(args, ++index, "--benchmark-session"));
                }
                case "--output" -> {
                    exportOptions = true;
                    output = path(args, ++index, "--output");
                }
                case "--csv" -> {
                    exportOptions = true;
                    historyCsv = path(args, ++index, "--csv");
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
        int operations = (prune ? 1 : 0) + (export ? 1 : 0) + (historyExport ? 1 : 0);
        if (operations > 1) {
            throw new IllegalArgumentException(
                    "evidence prune, export, and history-export are separate operations");
        }
        if (historyExport) {
            if (confirmed || keepRuns != null || keepBenchmarks != null || countSelection
                    || !runSessions.isEmpty() || !benchmarkSessions.isEmpty() || overwrite) {
                throw new IllegalArgumentException(
                        "history-export accepts only --output, optional --csv, and optional --json");
            }
            if (output == null) {
                throw new IllegalArgumentException(
                        "evidence history-export requires --output <history.json>");
            }
            PlayHistoryExport.Result result = PlayHistoryExport.export(
                    PreflightHome.current(), output, historyCsv);
            return historyExported(result, json, System.out);
        }
        if (export) {
            if (historyCsv != null) {
                throw new IllegalArgumentException("--csv requires `preflight evidence history-export`");
            }
            if (confirmed || keepRuns != null || keepBenchmarks != null) {
                throw new IllegalArgumentException(
                        "--yes and retention counts require `preflight evidence prune`");
            }
            if (output == null) {
                throw new IllegalArgumentException("evidence export requires --output <bundle.zip>");
            }
            boolean explicitSelection = !runSessions.isEmpty() || !benchmarkSessions.isEmpty();
            if (explicitSelection && countSelection) {
                throw new IllegalArgumentException(
                        "--run-session/--benchmark-session cannot be combined with --runs/--benchmarks");
            }
            PreflightHome home = PreflightHome.current();
            EvidenceRetention.Inventory inventory = EvidenceRetention.inventory(home);
            if (explicitSelection) {
                inventory = selectSessions(inventory, runSessions, benchmarkSessions);
                exportRuns = inventory.runs().size();
                exportBenchmarks = inventory.benchmarks().size();
            }
            DiagnosticBundle.Result result = DiagnosticBundle.export(
                    home,
                    inventory,
                    output,
                    exportRuns,
                    exportBenchmarks,
                    overwrite);
            return exported(result, json, System.out);
        }
        if (exportOptions) {
            throw new IllegalArgumentException(
                    "export selectors and output options require `preflight evidence export` or `history-export`");
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

    static EvidenceRetention.Inventory selectSessions(
            EvidenceRetention.Inventory inventory,
            List<String> runNames,
            List<String> benchmarkNames) {
        return new EvidenceRetention.Inventory(
                selectSessions(inventory.runs(), runNames, "run"),
                selectSessions(inventory.benchmarks(), benchmarkNames, "benchmark"));
    }

    private static List<EvidenceRetention.Session> selectSessions(
            List<EvidenceRetention.Session> available,
            List<String> names,
            String kind) {
        List<EvidenceRetention.Session> selected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String name : names) {
            if (!seen.add(name)) {
                throw new IllegalArgumentException("Duplicate " + kind + " session: " + name);
            }
            EvidenceRetention.Session session = available.stream()
                    .filter(candidate -> candidate.path().getFileName().toString().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown " + kind + " evidence session: " + name));
            selected.add(session);
        }
        return List.copyOf(selected);
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
        out.printf(Locale.ROOT, "  %,d launch runs and %,d benchmark sessions included%n",
                result.runs(), result.benchmarks());
        out.printf(Locale.ROOT, "  %,d metadata files included; %,d present files skipped%n",
                result.included().size(), result.skipped().size());
        out.println("The ZIP contains a disclosure and manifest. Inspect it before sharing.");
        return 0;
    }

    static int historyExported(PlayHistoryExport.Result result, boolean json, PrintStream out) {
        if (json) {
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("format", HISTORY_EXPORT_FORMAT);
            receipt.put("output", result.json());
            receipt.put("csv", result.csv());
            receipt.put("events", result.events());
            receipt.put("totalMillis", result.totalMillis());
            out.println(Json.object(receipt));
            return 0;
        }
        out.printf(Locale.ROOT, "Saved play history to %s (%,d events, %s recorded).%n",
                result.json(), result.events(), Playtime.human(result.totalMillis()));
        if (result.csv() != null) {
            out.println("Saved spreadsheet view to " + result.csv() + '.');
        }
        out.println("This is a read-only export; the local launch ledger was not changed.");
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
            report.put("history", historyValues(home));
            out.println(Json.object(report));
            return 0;
        }
        out.printf(Locale.ROOT, "Diagnostic evidence: %s across %,d files%n",
                CacheFootprint.humanBytes(inventory.bytes()), inventory.files());
        out.printf(Locale.ROOT, "  %,d launch runs%n", inventory.runs().size());
        out.printf(Locale.ROOT, "  %,d benchmark sessions%n", inventory.benchmarks().size());
        reportHistory(home, out);
        out.println("Acceleration caches are separate and are never removed by this command.");
        return 0;
    }

    /**
     * The launch history, which pruning does not touch.
     *
     * <p>Printed beside the evidence totals because it is the reason those totals can be small: the
     * record that a launch happened outlives the megabyte of diagnostics it produced.
     */
    private static void reportHistory(PreflightHome home, PrintStream out) {
        // Off the launch path on purpose: importing past runs walks every run directory, which is
        // cheap once and pointless before every game. Reading the history is the natural moment.
        LaunchLedgerBackfill.runOnce(home);
        LaunchLedger.Summary summary;
        try {
            summary = LaunchLedger.summarize(LaunchLedger.read(home));
        } catch (IOException unreadable) {
            out.println("  launch history unreadable: " + unreadable.getMessage());
            return;
        }
        if (summary.launches() == 0) {
            return;
        }
        out.printf(Locale.ROOT, "  %,d launches recorded since %s, kept after pruning%n",
                summary.launches(), summary.first());
        out.printf(Locale.ROOT, "    %,d finished cleanly, %,d with fatal log evidence%n",
                summary.completed(), summary.fatal());
        Playtime.Summary playtime = playtime(home);
        if (playtime.launches() > 0) {
            out.printf(Locale.ROOT, "    %s played, longest session %s%n",
                    Playtime.human(playtime.totalMillis()),
                    Playtime.human(playtime.longestSessionMillis()));
        }
    }

    private static Playtime.Summary playtime(PreflightHome home) {
        try {
            return Playtime.of(LaunchLedger.read(home));
        } catch (IOException unreadable) {
            return Playtime.of(List.of());
        }
    }

    private static Map<String, Object> historyValues(PreflightHome home) {
        LaunchLedgerBackfill.runOnce(home);
        Map<String, Object> values = new LinkedHashMap<>();
        LaunchLedger.Summary summary;
        try {
            summary = LaunchLedger.summarize(LaunchLedger.read(home));
        } catch (IOException unreadable) {
            values.put("readable", false);
            values.put("problem", unreadable.getMessage());
            return values;
        }
        values.put("readable", true);
        values.put("path", LaunchLedger.path(home));
        values.put("launches", summary.launches());
        values.put("completed", summary.completed());
        values.put("fatal", summary.fatal());
        values.put("first", summary.first());
        values.put("last", summary.last());
        Playtime.Summary playtime = playtime(home);
        Map<String, Object> played = new LinkedHashMap<>();
        played.put("totalMillis", playtime.totalMillis());
        played.put("longestSessionMillis", playtime.longestSessionMillis());
        played.put("averageMillis", playtime.averageMillis());
        played.put("launches", playtime.launches());
        played.put("sessionsWithoutDuration", playtime.sessionsWithoutDuration());
        played.put("ignoredAttempts", playtime.ignoredAttempts());
        played.put("ledgerEntries", playtime.recorded());
        played.put("first", playtime.first());
        played.put("last", playtime.last());
        values.put("playtime", played);
        return values;
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
                confirmed ? "Removed" : "Preview: would remove",
                plan.removals().size(),
                plan.files(),
                CacheFootprint.humanBytes(plan.bytes()));
        if (!confirmed) {
            out.println("Preview only; nothing was removed. Re-run the same command with --yes to apply it.");
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

    private static String sessionName(String[] args, int index, String option) {
        if (index >= args.length || args[index].isBlank()) {
            throw new IllegalArgumentException(option + " requires a session name");
        }
        String value = args[index];
        if (value.equals(".") || value.equals("..") || value.contains("/") || value.contains("\\")) {
            throw new IllegalArgumentException(option + " requires a top-level session name");
        }
        return value;
    }

    private static Path path(String[] args, int index, String option) {
        if (index >= args.length || args[index].isBlank()) {
            throw new IllegalArgumentException(option + " requires a path");
        }
        return Path.of(args[index]);
    }
}
