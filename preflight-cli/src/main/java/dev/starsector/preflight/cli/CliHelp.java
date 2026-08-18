package dev.starsector.preflight.cli;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Human help rendering kept separate from command parsing and machine output. */
final class CliHelp {
    private static final int DEFAULT_WIDTH = 88;
    private static final int NARROW_WIDTH = 64;

    private static final List<Group> GROUPS = List.of(
            new Group("Play", List.of("run", "stop", "doctor", "launch-settings")),
            new Group("Mods and preparation", List.of("prepare", "profile", "cache")),
            new Group("Maintenance and support", List.of("evidence", "install", "uninstall")),
            new Group(
                    "Advanced / development",
                    List.of(
                            "scan",
                            "index",
                            "texture",
                            "font",
                            "assets",
                            "audio",
                            "lint",
                            "classpath",
                            "benchmark",
                            "analyze",
                            "fingerprint",
                            "summarize")));

    private CliHelp() {
    }

    static void commandUsage(
            String command,
            List<String> usage,
            PrintStream output) {
        output.println("Usage:");
        if ("run".equals(command)) {
            output.println("  preflight run");
            output.println();
            output.println("Common:");
            output.println("  preflight doctor");
            output.println("  preflight prepare --plan");
            output.println("  preflight run");
            output.println("  preflight run --direct");
            output.println();
            output.println("Full reference:");
        }
        for (String line : usage) {
            output.println("  " + line);
        }
    }

    static void globalUsage(
            PrintStream output,
            Map<String, List<String>> usage,
            Function<String, String> summary) {
        globalUsage(output, usage, summary, terminalWidth(System.getenv()));
    }

    static void globalUsage(
            PrintStream output,
            Map<String, List<String>> usage,
            Function<String, String> summary,
            int terminalWidth) {
        int width = Math.max(32, Math.min(160, terminalWidth));
        output.println("Usage:");
        output.println("  preflight <command> [options]");
        output.println("  preflight help <command>");
        output.println();
        output.println("Commands:");

        int commandWidth = usage.keySet().stream()
                .filter(command -> !"desktop".equals(command))
                .mapToInt(String::length)
                .max()
                .orElse(12);
        Set<String> rendered = new HashSet<>();
        for (Group group : GROUPS) {
            List<String> commands = group.commands().stream()
                    .filter(usage::containsKey)
                    .toList();
            if (commands.isEmpty()) {
                continue;
            }
            output.println(group.title() + ":");
            for (String command : commands) {
                printCommand(output, command, summary.apply(command), commandWidth, width);
                rendered.add(command);
            }
        }

        LinkedHashSet<String> other = new LinkedHashSet<>(usage.keySet());
        other.removeAll(rendered);
        other.remove("desktop");
        if (!other.isEmpty()) {
            output.println("Other:");
            for (String command : other) {
                printCommand(output, command, summary.apply(command), commandWidth, width);
            }
        }

        output.println();
        printWrapped(output, "Run `preflight <command> --help` for detailed usage.", width, 0);
        printWrapped(output, "Set PREFLIGHT_DEBUG=1 to include stack traces for unexpected failures.", width, 0);
    }

    static int terminalWidth(Map<String, String> environment) {
        String columns = environment.get("COLUMNS");
        if (columns == null || columns.isBlank()) {
            return DEFAULT_WIDTH;
        }
        try {
            int parsed = Integer.parseInt(columns.trim());
            return parsed > 0 ? parsed : DEFAULT_WIDTH;
        } catch (NumberFormatException ignored) {
            return DEFAULT_WIDTH;
        }
    }

    private static void printCommand(
            PrintStream output,
            String command,
            String summary,
            int commandWidth,
            int terminalWidth) {
        if (terminalWidth < NARROW_WIDTH) {
            output.println("  " + command);
            printWrapped(output, summary, terminalWidth, 4);
            return;
        }
        int summaryColumn = commandWidth + 4;
        int summaryWidth = Math.max(20, terminalWidth - summaryColumn);
        List<String> lines = wrapWords(summary, summaryWidth);
        output.printf("  %-" + commandWidth + "s  %s%n", command, lines.get(0));
        String indent = " ".repeat(summaryColumn);
        for (int index = 1; index < lines.size(); index++) {
            output.println(indent + lines.get(index));
        }
    }

    private static void printWrapped(
            PrintStream output,
            String text,
            int terminalWidth,
            int indent) {
        int contentWidth = Math.max(12, terminalWidth - indent);
        String prefix = " ".repeat(indent);
        for (String line : wrapWords(text, contentWidth)) {
            output.println(prefix + line);
        }
    }

    static List<String> wrapWords(String text, int width) {
        ArrayList<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines.isEmpty() ? List.of("") : List.copyOf(lines);
    }

    private record Group(String title, List<String> commands) {
    }
}
