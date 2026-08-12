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
    void runHelpSeparatesCrashSafeFileOnlyLogsFromBufferedQuietLogs() throws Exception {
        String output = capture(new String[] {"run", "--help"}).standardOutput();

        assertTrue(output.contains("--optimization-preset recommended"), output);
        assertTrue(output.contains("--fast is its backwards-compatible alias"), output);
        assertTrue(output.contains("--optimization-preset conservative"), output);
        assertTrue(output.contains("--disable-optimization-domain"), output);
        assertTrue(output.contains("Other adapters and correctness repairs are unchanged"), output);
        assertTrue(output.contains("--file-only-logs"), output);
        assertTrue(output.contains("--quiet-logs"), output);
        assertTrue(output.contains("about 0.40s"), output);
        assertTrue(output.contains("synchronous, crash-safe INFO writes"), output);
        assertTrue(output.contains("hard crash can lose the final 64 KiB"), output);
        assertTrue(output.contains("explicit upgrade over --fast"), output);
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
