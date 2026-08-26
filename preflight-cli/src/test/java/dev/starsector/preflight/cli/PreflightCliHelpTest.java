package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PreflightCliHelpTest {
    @Test
    void globalHelpIsConciseAndGoesToStandardOutput() throws Exception {
        Captured captured = capture(new String[] {"--help"});

        assertEquals(0, captured.status());
        assertTrue(captured.standardOutput().contains("preflight <command> [options]"), captured.standardOutput());
        // Each command and its summary share a row. The column is as wide as the longest command
        // name, so pinning the exact run of spaces here would fail the day one is added.
        assertTrue(listsCommand(captured.standardOutput(), "doctor", "Check installation"),
                captured.standardOutput());
        assertTrue(listsCommand(captured.standardOutput(), "lint", "Report actionable asset problems"),
                captured.standardOutput());
        assertFalse(captured.standardOutput().contains("--adapter-targets"), captured.standardOutput());
        assertFalse(captured.standardOutput().contains("desktop"), captured.standardOutput());
        assertEquals("", captured.standardError());
    }

    @Test
    void unknownCommandIsExplicitAndSuggestsLikelyTypo() throws Exception {
        Captured captured = capture(new String[] {"scna"});

        assertEquals(2, captured.status());
        assertEquals("", captured.standardOutput());
        assertTrue(captured.standardError().contains("unknown command `scna`"), captured.standardError());
        assertTrue(captured.standardError().contains("Did you mean `scan`?"), captured.standardError());
        assertTrue(captured.standardError().contains("preflight <command> [options]"), captured.standardError());
    }

    @Test
    void helpRejectsUnknownOrExtraCommandNames() throws Exception {
        Captured unknown = capture(new String[] {"help", "scna"});
        Captured extra = capture(new String[] {"help", "scan", "extra"});

        assertEquals(2, unknown.status());
        assertTrue(unknown.standardError().contains("Did you mean `scan`?"), unknown.standardError());
        assertEquals(2, extra.status());
        assertTrue(extra.standardError().contains("expected `preflight help [command]`"), extra.standardError());
    }

    @Test
    void detailedHelpIncludesPreviouslyHiddenSubcommands() throws Exception {
        Captured fonts = capture(new String[] {"font", "--help"});
        Captured assets = capture(new String[] {"assets", "--help"});

        assertEquals(0, fonts.status());
        assertTrue(fonts.standardOutput().contains("preflight font list-families"), fonts.standardOutput());
        assertTrue(fonts.standardOutput().contains("--family <installed-family>"), fonts.standardOutput());
        assertEquals(0, assets.status());
        assertTrue(assets.standardOutput().contains("preflight assets compression-probe"), assets.standardOutput());
    }

    @Test
    void runHelpExplainsTheNormalPathWithoutDumpingInternalSwitches() throws Exception {
        String output = capture(new String[] {"run", "--help"}).standardOutput();

        assertTrue(output.contains("--optimization-preset recommended"), output);
        assertTrue(output.contains("recommended|conservative|off"), output);
        assertTrue(output.contains("--direct skips the game's launcher"), output);
        assertTrue(output.contains("--dry-run prints the resolved command"), output);
        assertTrue(output.contains("--no-record is the normal fast path"), output);
        assertFalse(output.contains("--graphicslib-compact-replay"), output);
        assertFalse(output.contains("adapter-health.json"), output);
    }

    @Test
    void benchmarkHelpIncludesScenarioRecorderCollectorAndComparisons() throws Exception {
        String output = capture(new String[] {"benchmark", "--help"}).standardOutput();
        assertTrue(output.contains("preflight benchmark lookups"), output);
        assertTrue(output.contains("preflight benchmark scenario"), output);
        assertTrue(output.contains("preflight benchmark collect"), output);
        assertTrue(output.contains("preflight benchmark compare"), output);
        assertTrue(output.contains("preflight benchmark compare-runs"), output);
    }

    /**
     * An option explained in a command's help must also appear in that command's usage line.
     *
     * <p>Anything only explained below is invisible to a reader skimming the summary, and it stays
     * invisible: nothing else compares the two halves. {@code run} had two -- both accepted by the
     * parser, neither listed.
     */
    @Test
    void everyExplainedOptionIsAlsoInTheUsageLine() throws Exception {
        java.util.Map<String, java.util.List<String>> unlisted = new java.util.LinkedHashMap<>();
        for (String command : commandsInGlobalHelp()) {
            java.util.Set<String> summarised = new java.util.LinkedHashSet<>();
            java.util.Set<String> explained = new java.util.LinkedHashSet<>();
            for (String line : capture(new String[] {"help", command}).standardOutput().lines().toList()) {
                String trimmed = line.strip();
                java.util.regex.Matcher found = OPTION.matcher(trimmed);
                if (trimmed.startsWith("preflight")) {
                    while (found.find()) {
                        summarised.add(found.group());
                    }
                } else if (trimmed.startsWith("--") && found.find()) {
                    explained.add(found.group());
                }
            }
            explained.removeAll(summarised);
            if (!explained.isEmpty()) {
                unlisted.put(command, java.util.List.copyOf(explained));
            }
        }
        assertTrue(unlisted.isEmpty(), "options explained but never listed in their usage: " + unlisted);
    }

    private static final java.util.regex.Pattern OPTION =
            java.util.regex.Pattern.compile("--[a-z0-9][a-z0-9-]*");

    private static java.util.List<String> commandsInGlobalHelp() throws Exception {
        java.util.List<String> commands = new java.util.ArrayList<>();
        boolean listing = false;
        for (String line : capture(new String[] {"--help"}).standardOutput().lines().toList()) {
            if (line.startsWith("Commands:")) {
                listing = true;
                continue;
            }
            if (listing && line.isBlank()) {
                break;
            }
            if (listing && line.startsWith("  ")) {
                commands.add(line.strip().split("\\s+")[0]);
            }
        }
        assertFalse(commands.isEmpty(), "global help listed no commands");
        return commands;
    }

    /**
     * A mistyped option gets the same courtesy a mistyped command already got.
     *
     * <p>The candidates come from the command's own usage text rather than a hand-kept list, so this
     * also fails if {@code doctor} ever stops documenting the option it accepts.
     */
    @Test
    void aMistypedOptionPointsAtTheRealOne() {
        assertEquals("--game", Suggestions.closest("--gmae", documentedOptions("doctor")));
        assertEquals("--launcher", Suggestions.closest("--launchr", documentedOptions("doctor")));
    }

    /** Nothing near enough is offered nothing, rather than the least-wrong option on the list. */
    @Test
    void anOptionThatResemblesNothingGetsNoGuess() {
        assertNull(Suggestions.closest("--nonsense", documentedOptions("doctor")));
    }

    private static java.util.List<String> documentedOptions(String command) {
        java.util.LinkedHashSet<String> options = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher found = java.util.regex.Pattern.compile("--[a-z0-9][a-z0-9-]*")
                .matcher(capturedUsage(command));
        while (found.find()) {
            options.add(found.group());
        }
        assertFalse(options.isEmpty(), "no options documented for " + command);
        return java.util.List.copyOf(options);
    }

    private static String capturedUsage(String command) {
        try {
            return capture(new String[] {"help", command}).standardOutput();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    /** True when one line lists {@code command} with {@code summary} beside it, at any column width. */
    private static boolean listsCommand(String output, String command, String summary) {
        return java.util.regex.Pattern
                .compile("^\\s*" + command + "\\s+" + java.util.regex.Pattern.quote(summary),
                        java.util.regex.Pattern.MULTILINE)
                .matcher(output)
                .find();
    }

    private static Captured capture(String[] args) throws Exception {
        PrintStream originalOutput = System.out;
        PrintStream originalError = System.err;
        ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(outputBytes, true, StandardCharsets.UTF_8);
                PrintStream error = new PrintStream(errorBytes, true, StandardCharsets.UTF_8)) {
            System.setOut(output);
            System.setErr(error);
            int status = PreflightCli.run(args);
            return new Captured(
                    status,
                    outputBytes.toString(StandardCharsets.UTF_8),
                    errorBytes.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOutput);
            System.setErr(originalError);
        }
    }

    private record Captured(int status, String standardOutput, String standardError) {
    }
}
