package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reports and prunes diagnostic evidence independently from acceleration caches. */
final class EvidenceCommand {
    private static final String REPORT_FORMAT = "starsector-preflight-evidence-v1";
    private static final String PRUNE_FORMAT = "starsector-preflight-evidence-prune-v1";

    private EvidenceCommand() {
    }

    static int execute(String[] args, int offset) throws Exception {
        boolean prune = false;
        boolean confirmed = false;
        boolean json = false;
        Integer keepRuns = null;
        Integer keepBenchmarks = null;
        for (int index = offset; index < args.length; index++) {
            switch (args[index]) {
                case "prune" -> prune = true;
                case "--yes" -> confirmed = true;
                case "--json" -> json = true;
                case "--keep-runs" -> keepRuns = count(args, ++index, "--keep-runs");
                case "--keep-benchmarks" ->
                        keepBenchmarks = count(args, ++index, "--keep-benchmarks");
                case "--help", "-h" -> {
                    PreflightCli.commandUsage("evidence", System.out);
                    return 0;
                }
                default -> throw new IllegalArgumentException(
                        "preflight evidence: unknown option: " + args[index]);
            }
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
}
