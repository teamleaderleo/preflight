package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code lint} — reports asset problems, grouped by the mod that ships them.
 *
 * <p>Unlike the evidence commands, the audience here is a person reading a terminal, so the default
 * output is prose and JSON is opt-in. The exit code is always 0: these are findings offered to
 * someone, not a build gate imposed on them.</p>
 */
final class AssetLintCommand {
    private static final int LISTED_PER_RULE = 5;

    private AssetLintCommand() {
    }

    static int execute(String[] args, int offset) throws Exception {
        Path game = null;
        Path standalone = null;
        Path output = null;
        String mod = null;
        boolean json = false;
        for (int i = offset; i < args.length; i++) {
            switch (args[i]) {
                case "--game" -> game = Path.of(requireValue(args, ++i, "--game"));
                case "--path" -> standalone = Path.of(requireValue(args, ++i, "--path"));
                case "--output" -> output = Path.of(requireValue(args, ++i, "--output"));
                case "--mod" -> mod = requireValue(args, ++i, "--mod");
                case "--json" -> json = true;
                default -> throw new IllegalArgumentException("Unknown lint option: " + args[i]);
            }
        }
        if (standalone != null && (game != null || mod != null)) {
            throw new IllegalArgumentException("--path lints one directory alone; it takes no --game or --mod");
        }

        AssetLint.Result result;
        if (standalone != null) {
            if (!Files.isDirectory(standalone)) {
                throw new IllegalArgumentException("--path is not a directory: " + standalone);
            }
            result = AssetLint.scanStandalone(standalone);
        } else {
            Path installRoot = InstallRoot.resolve(game);
            if (installRoot == null) {
                System.err.println("Preflight could not locate Starsector. Run `doctor` or provide --game.");
                return 3;
            }
            result = AssetLint.scan(installRoot, mod);
        }
        String text = Json.object(result.report());
        if (output != null) {
            Path resolved = output.toAbsolutePath().normalize();
            Path parent = resolved.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(resolved, text + System.lineSeparator(), StandardCharsets.UTF_8);
        }
        if (json) {
            System.out.println(text);
        } else {
            System.out.print(render(result, mod));
        }
        return 0;
    }

    /**
     * The report as prose. Each rule states why it exists once, and every listed file carries its own
     * measured cost, so no single file's numbers are ever presented as if they described the group.
     *
     * <p>Byte totals are reported per cost kind and never summed. Disk, decoded memory, and video
     * memory are different resources, and a combined headline would read as a bigger claim than the
     * evidence supports.</p>
     */
    static String render(AssetLint.Result result, String modFilter) {
        StringBuilder out = new StringBuilder();
        List<AssetLint.Finding> findings = result.findings();
        if (findings.isEmpty()) {
            return modFilter == null ? "No findings.\n" : "No findings for " + modFilter + ".\n";
        }

        out.append(findings.size()).append(" finding").append(findings.size() == 1 ? "" : "s");
        String totals = costSummary(findings);
        if (!totals.isEmpty()) {
            out.append(": ").append(totals);
        }
        out.append(".\n\n");

        Map<String, List<AssetLint.Finding>> byProvider = new LinkedHashMap<>();
        for (AssetLint.Finding finding : findings) {
            byProvider.computeIfAbsent(finding.provider(), ignored -> new ArrayList<>()).add(finding);
        }
        byProvider.entrySet().stream()
                .sorted(java.util.Comparator.comparingInt(
                                (Map.Entry<String, List<AssetLint.Finding>> entry) -> entry.getValue().size())
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .forEach(entry -> appendProvider(out, entry.getKey(), entry.getValue()));

        out.append("Nothing was changed. These come from file headers, and whether any of them is\n")
                .append("worth acting on is the author's call.\n");
        return out.toString();
    }

    /** "12.0 MB decoded at load, 3.4 MB in video memory" — each resource named, never added up. */
    private static String costSummary(List<AssetLint.Finding> findings) {
        List<String> parts = new ArrayList<>();
        AssetLint.byCost(findings).forEach((cost, bytes) -> {
            if (bytes > 0) {
                parts.add(AssetLint.megabytes(bytes) + " " + cost.label());
            }
        });
        return String.join(", ", parts);
    }

    private static void appendProvider(StringBuilder out, String provider, List<AssetLint.Finding> findings) {
        out.append(provider).append("  (").append(findings.size())
                .append(findings.size() == 1 ? " finding" : " findings");
        String totals = costSummary(findings);
        if (!totals.isEmpty()) {
            out.append("; ").append(totals);
        }
        out.append(")\n");

        Map<String, List<AssetLint.Finding>> byRule = new LinkedHashMap<>();
        for (AssetLint.Finding finding : findings) {
            byRule.computeIfAbsent(finding.rule(), ignored -> new ArrayList<>()).add(finding);
        }
        for (Map.Entry<String, List<AssetLint.Finding>> rule : byRule.entrySet()) {
            List<AssetLint.Finding> forRule = rule.getValue();
            out.append("  ").append(rule.getKey())
                    .append(" [").append(forRule.get(0).severity().name().toLowerCase(Locale.ROOT))
                    .append("]  ").append(forRule.size())
                    .append(forRule.size() == 1 ? " file" : " files").append('\n');
            String why = AssetLint.explain(rule.getKey());
            if (!why.isEmpty()) {
                out.append(wrap(why, "    ")).append('\n');
            }
            for (AssetLint.Finding finding : forRule.subList(0, Math.min(LISTED_PER_RULE, forRule.size()))) {
                out.append("      ").append(finding.path());
                if (!finding.detail().isEmpty()) {
                    out.append("  \u2014 ").append(finding.detail());
                }
                out.append('\n');
            }
            if (forRule.size() > LISTED_PER_RULE) {
                out.append("      ... and ").append(forRule.size() - LISTED_PER_RULE).append(" more\n");
            }
        }
        out.append('\n');
    }

    private static String wrap(String text, String indent) {
        StringBuilder out = new StringBuilder();
        int width = 84 - indent.length();
        int lineLength = 0;
        for (String word : text.split(" ")) {
            if (lineLength == 0) {
                out.append(indent);
            } else if (lineLength + 1 + word.length() > width) {
                out.append('\n').append(indent);
                lineLength = 0;
            } else {
                out.append(' ');
                lineLength++;
            }
            out.append(word);
            lineLength += word.length();
        }
        return out.toString();
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }
}
