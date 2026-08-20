from pathlib import Path
import re


def read(path):
    return Path(path).read_text(encoding="utf-8")


def write(path, text):
    Path(path).write_text(text, encoding="utf-8")


def replace_once(path, old, new):
    text = read(path)
    if text.count(old) == 1:
        write(path, text.replace(old, new, 1))
        return
    tokens = [re.escape(token) for token in re.split(r"\s+", old.strip()) if token]
    pattern = r"\s+".join(tokens)
    matches = list(re.finditer(pattern, text, flags=re.MULTILINE))
    if len(matches) != 1:
        raise SystemExit(f"{path}: expected one bounded replacement, found {len(matches)}: {old[:100]!r}")
    match = matches[0]
    write(path, text[:match.start()] + new + text[match.end():])


def replace_between(path, start_marker, end_marker, replacement):
    text = read(path)
    start = text.index(start_marker)
    end = text.index(end_marker, start)
    write(path, text[:start] + replacement + "\n\n" + text[end:])


cli_help = '''package dev.starsector.preflight.cli;

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

    static void commandUsage(String command, List<String> usage, PrintStream output) {
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
'''
write("preflight-cli/src/main/java/dev/starsector/preflight/cli/CliHelp.java", cli_help)

replace_between(
    "preflight-cli/src/main/java/dev/starsector/preflight/cli/PreflightCli.java",
    "    static void commandUsage(",
    "    private static int unknownCommand",
    '''    static void commandUsage(String command, java.io.PrintStream output) {
        CliHelp.commandUsage(command, USAGE.get(command), output);
    }''')
replace_between(
    "preflight-cli/src/main/java/dev/starsector/preflight/cli/PreflightCli.java",
    "    private static void globalUsage(",
    "    private static String commandSummary",
    '''    private static void globalUsage(java.io.PrintStream output) {
        CliHelp.globalUsage(output, USAGE, PreflightCli::commandSummary);
    }''')

terminal_style = '''package dev.starsector.preflight.cli;

import java.io.PrintStream;
import java.util.Map;

/** Semantic ANSI decoration for human output on an interactive terminal only. */
final class TerminalStyle {
    private static final String ESCAPE = "\\u001B[";
    private static final String RESET = ESCAPE + "0m";

    enum Tone {
        SUCCESS("32"),
        PREVIEW("36"),
        WARNING("33"),
        ERROR("31"),
        EMPHASIS("1");

        private final String code;

        Tone(String code) {
            this.code = code;
        }
    }

    private TerminalStyle() {
    }

    static String semantic(PrintStream output, Tone tone, String text) {
        boolean terminal = output == System.out && System.console() != null;
        return semantic(terminal, System.getenv(), tone, text);
    }

    static String semantic(
            boolean terminal,
            Map<String, String> environment,
            Tone tone,
            String text) {
        if (!enabled(terminal, environment)) {
            return text;
        }
        return ESCAPE + tone.code + "m" + text + RESET;
    }

    static boolean enabled(boolean terminal, Map<String, String> environment) {
        if (!terminal) {
            return false;
        }
        if (environment.containsKey("NO_COLOR")) {
            return false;
        }
        String term = environment.get("TERM");
        return term == null || !"dumb".equalsIgnoreCase(term.trim());
    }
}
'''
write("preflight-cli/src/main/java/dev/starsector/preflight/cli/TerminalStyle.java", terminal_style)

evidence = "preflight-cli/src/main/java/dev/starsector/preflight/cli/EvidenceCommand.java"
replace_once(
    evidence,
    '''        out.printf(Locale.ROOT, "Saved diagnostics to %s (%s, %,d files).%n",
                result.output(), CacheFootprint.humanBytes(result.bytes()), result.files());''',
    '''        out.printf(Locale.ROOT, "%s to %s (%s, %,d files).%n",
                TerminalStyle.semantic(out, TerminalStyle.Tone.SUCCESS, "Saved diagnostics"),
                result.output(), CacheFootprint.humanBytes(result.bytes()), result.files());''')
replace_once(
    evidence,
    '''        out.printf(Locale.ROOT, "Diagnostic evidence: %s across %,d files%n",
                CacheFootprint.humanBytes(inventory.bytes()), inventory.files());''',
    '''        out.printf(Locale.ROOT, "%s %s across %,d files%n",
                TerminalStyle.semantic(out, TerminalStyle.Tone.EMPHASIS, "Diagnostic evidence:"),
                CacheFootprint.humanBytes(inventory.bytes()), inventory.files());''')
replace_once(
    evidence,
    '''        out.printf(Locale.ROOT, "%s %,d evidence sessions (%,d files), freeing %s.%n",
                confirmed ? "Removed" : "Preview: would remove",
                plan.removals().size(),
                plan.files(),
                CacheFootprint.humanBytes(plan.bytes()));
        if (!confirmed) {
            out.println("Preview only; nothing was removed. Re-run the same command with --yes to apply it.");
        }''',
    '''        String action = confirmed
                ? TerminalStyle.semantic(out, TerminalStyle.Tone.SUCCESS, "Removed")
                : TerminalStyle.semantic(out, TerminalStyle.Tone.PREVIEW, "Preview:") + " would remove";
        out.printf(Locale.ROOT, "%s %,d evidence sessions (%,d files), freeing %s.%n",
                action,
                plan.removals().size(),
                plan.files(),
                CacheFootprint.humanBytes(plan.bytes()));
        if (!confirmed) {
            out.println(TerminalStyle.semantic(out, TerminalStyle.Tone.PREVIEW, "Preview only;")
                    + " nothing was removed. Re-run the same command with --yes to apply it.");
        }''')

profile = "preflight-cli/src/main/java/dev/starsector/preflight/cli/ProfileCommand.java"
replace_once(
    profile,
    '''            out.printf(Locale.ROOT, "Created profile '%s' with %,d enabled mods.%n", name, profile.enabledMods().size());''',
    '''            long enabledModCount = profile.enabledMods().size();
            out.printf(Locale.ROOT, "Created profile '%s' with %,d enabled mod%s.%n",
                    name, enabledModCount, enabledModCount == 1 ? "" : "s");''')
replace_once(
    profile,
    '''        out.printf(Locale.ROOT, "  existing: %,d mods from %s%n",
                plan.get("existingModCount"), plan.get("existingInstallRoot"));
        out.printf(Locale.ROOT, "  proposed: %,d mods from %s%n",
                plan.get("proposedModCount"), plan.get("proposedInstallRoot"));''',
    '''        long existingModCount = ((Number) plan.get("existingModCount")).longValue();
        long proposedModCount = ((Number) plan.get("proposedModCount")).longValue();
        out.printf(Locale.ROOT, "  existing: %,d mod%s from %s%n",
                existingModCount, existingModCount == 1 ? "" : "s", plan.get("existingInstallRoot"));
        out.printf(Locale.ROOT, "  proposed: %,d mod%s from %s%n",
                proposedModCount, proposedModCount == 1 ? "" : "s", plan.get("proposedInstallRoot"));''')
replace_once(
    profile,
    '''            out.println("  None. Save the current mod set with `preflight profile save <name>`. ");''',
    '''            out.println("  None. Save the current mod set with `preflight profile save <name>`.");''')
replace_once(
    profile,
    '''            out.printf(Locale.ROOT, "  %-24s %-7s %,d mods%s%n",
                    profile.get("name"), marker, profile.get("modCount"),
                    missing.isEmpty() ? "" : ", missing " + missing.size());''',
    '''            long modCount = ((Number) profile.get("modCount")).longValue();
            out.printf(Locale.ROOT, "  %-24s %-7s %,d mod%s%s%n",
                    profile.get("name"), marker, modCount, modCount == 1 ? "" : "s",
                    missing.isEmpty() ? "" : ", missing " + missing.size());''')

cli_help_test = '''package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CliHelpTest {
    @Test
    void everydayGroupsLeadAndDesktopBridgeStaysHidden() throws Exception {
        String output = globalHelp(88);

        int play = output.indexOf("Play:");
        int mods = output.indexOf("Mods and preparation:");
        int maintenance = output.indexOf("Maintenance and support:");
        int advanced = output.indexOf("Advanced / development:");
        assertTrue(play >= 0, output);
        assertTrue(play < mods, output);
        assertTrue(mods < maintenance, output);
        assertTrue(maintenance < advanced, output);
        assertTrue(output.indexOf("doctor") < advanced, output);
        assertTrue(output.indexOf("benchmark") > advanced, output);
        assertFalse(output.contains("\\n  desktop "), output);
    }

    @Test
    void unknownFutureCommandsRemainDiscoverableUnderOther() throws Exception {
        Map<String, List<String>> usage = usage();
        usage.put("future-tool", List.of("preflight future-tool"));
        String output = renderGlobal(usage, 88);

        assertTrue(output.contains("Other:\\n"), output);
        assertTrue(output.contains("future-tool"), output);
    }

    @Test
    void narrowHelpStacksSummariesAndFitsFortyFourColumns() throws Exception {
        String output = globalHelp(44);

        assertTrue(output.contains("  launch-settings\\n    Read or update"), output);
        for (String line : output.lines().toList()) {
            assertTrue(line.length() <= 44, "line exceeds narrow width: " + line);
        }
    }

    @Test
    void runHelpLeadsWithCommonPathBeforeFullReference() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            CliHelp.commandUsage(
                    "run",
                    List.of("preflight run [--adapter-targets <path>]", "  --adapter-targets is advanced"),
                    output);
        }
        String rendered = bytes.toString(StandardCharsets.UTF_8);
        int common = rendered.indexOf("Common:");
        int reference = rendered.indexOf("Full reference:");

        assertTrue(common >= 0, rendered);
        assertTrue(reference > common, rendered);
        assertTrue(rendered.indexOf("preflight doctor", common) < reference, rendered);
        assertTrue(rendered.indexOf("preflight prepare --plan", common) < reference, rendered);
        assertTrue(rendered.indexOf("preflight run --direct", common) < reference, rendered);
        assertTrue(rendered.indexOf("--adapter-targets", reference) > reference, rendered);
    }

    @Test
    void ordinaryCommandHelpKeepsExistingUsageShape() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            CliHelp.commandUsage("cache", List.of("preflight cache [--json]"), output);
        }
        assertEquals("Usage:\\n  preflight cache [--json]\\n", bytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void columnsEnvironmentUsesSafeFallbacks() {
        assertEquals(88, CliHelp.terminalWidth(Map.of()));
        assertEquals(88, CliHelp.terminalWidth(Map.of("COLUMNS", "garbage")));
        assertEquals(88, CliHelp.terminalWidth(Map.of("COLUMNS", "0")));
        assertEquals(52, CliHelp.terminalWidth(Map.of("COLUMNS", "52")));
    }

    private static String globalHelp(int width) throws Exception {
        return renderGlobal(usage(), width);
    }

    private static String renderGlobal(Map<String, List<String>> usage, int width) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            CliHelp.globalUsage(output, usage, CliHelpTest::summary, width);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static Map<String, List<String>> usage() {
        Map<String, List<String>> usage = new LinkedHashMap<>();
        for (String command : List.of(
                "run", "stop", "doctor", "launch-settings",
                "prepare", "profile", "cache",
                "evidence", "install", "uninstall",
                "scan", "index", "texture", "font", "assets", "audio", "lint", "classpath",
                "benchmark", "analyze", "fingerprint", "summarize", "desktop")) {
            usage.put(command, List.of("preflight " + command));
        }
        return usage;
    }

    private static String summary(String command) {
        if ("launch-settings".equals(command)) {
            return "Read or update launch settings shared with the desktop application.";
        }
        return "Help summary for " + command + " used to verify readable command discovery.";
    }
}
'''
write("preflight-cli/src/test/java/dev/starsector/preflight/cli/CliHelpTest.java", cli_help_test)

terminal_style_test = '''package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TerminalStyleTest {
    @Test
    void stylesSemanticTextOnAnInteractiveTerminal() {
        assertEquals(
                "\\u001B[36mPreview:\\u001B[0m",
                TerminalStyle.semantic(true, Map.of("TERM", "xterm-256color"),
                        TerminalStyle.Tone.PREVIEW, "Preview:"));
        assertTrue(TerminalStyle.enabled(true, Map.of()));
    }

    @Test
    void noColorPresenceSuppressesAnsiEvenWhenItsValueIsEmpty() {
        assertEquals(
                "Preview:",
                TerminalStyle.semantic(true, Map.of("NO_COLOR", ""),
                        TerminalStyle.Tone.PREVIEW, "Preview:"));
        assertFalse(TerminalStyle.enabled(true, Map.of("NO_COLOR", "1")));
    }

    @Test
    void dumbTerminalSuppressesAnsi() {
        assertFalse(TerminalStyle.enabled(true, Map.of("TERM", "dumb")));
        assertEquals(
                "Removed",
                TerminalStyle.semantic(true, Map.of("TERM", "DUMB"),
                        TerminalStyle.Tone.SUCCESS, "Removed"));
    }

    @Test
    void redirectedOutputStaysPlain() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream redirected = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            redirected.println(TerminalStyle.semantic(
                    redirected, TerminalStyle.Tone.SUCCESS, "Removed"));
        }
        assertEquals("Removed\\n", bytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void nonTerminalOutputStaysPlain() {
        assertEquals(
                "Preview:",
                TerminalStyle.semantic(false, Map.of("TERM", "xterm-256color"),
                        TerminalStyle.Tone.PREVIEW, "Preview:"));
    }
}
'''
write("preflight-cli/src/test/java/dev/starsector/preflight/cli/TerminalStyleTest.java", terminal_style_test)

human_output_test = '''package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliHumanOutputTest {
    @TempDir
    Path directory;

    @Test
    void oneModProfileUsesSingularGrammarAndCleanHintWhitespace() throws Exception {
        PreflightHome home = PreflightHome.resolve(Platform.OTHER, directory.resolve("home"), Map.of());
        Path game = directory.resolve("game");
        Path mods = game.resolve("mods");
        Path demo = mods.resolve("demo");
        Files.createDirectories(game.resolve("starsector-core"));
        Files.createDirectories(demo);
        Files.writeString(demo.resolve("mod_info.json"), "{\\\"id\\\":\\\"demo\\\"}\\n");
        Files.writeString(mods.resolve("enabled_mods.json"), "{\\\"enabledMods\\\":[\\\"demo\\\"]}\\n");

        ByteArrayOutputStream saveBytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(saveBytes, true, StandardCharsets.UTF_8)) {
            assertEquals(0, ProfileCommand.save(home, game, "solo", false, out));
        }
        assertTrue(saveBytes.toString(StandardCharsets.UTF_8)
                .contains("Created profile 'solo' with 1 enabled mod."));

        ByteArrayOutputStream listBytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(listBytes, true, StandardCharsets.UTF_8)) {
            assertEquals(0, ProfileCommand.list(home, game, false, out));
        }
        String listed = listBytes.toString(StandardCharsets.UTF_8);
        assertTrue(listed.contains("1 mod"), listed);
        assertFalse(listed.contains("1 mods"), listed);
        assertFalse(listed.lines().anyMatch(line -> line.endsWith(" ")), listed);

        ByteArrayOutputStream updateBytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(updateBytes, true, StandardCharsets.UTF_8)) {
            assertEquals(0, ProfileCommand.update(home, game, "solo", null, null, false, false, out));
        }
        String update = updateBytes.toString(StandardCharsets.UTF_8);
        assertTrue(update.contains("existing: 1 mod from"), update);
        assertTrue(update.contains("proposed: 1 mod from"), update);
    }
}
'''
write("preflight-cli/src/test/java/dev/starsector/preflight/cli/CliHumanOutputTest.java", human_output_test)

handoff = "docs/next-llm-handoff.md"
replace_once(
    handoff,
    '''Every injected game JVM atomically publishes a
versioned `runtime-process.json` beside its run evidence with the PID, parent PID, available OS
start instant, and lifecycle state.''',
    '''Every injected game JVM publishes a completed same-directory staged
`runtime-process.json` beside its run evidence with the PID, parent PID, available OS start instant,
and lifecycle state. Replacement is atomic when the filesystem supports it and otherwise uses the
completed staged-file replacement fallback.''')
replace_once(
    handoff,
    '''calculates their sizes and SHA-256 digests, and atomically writes `smoke-evidence.json`.''',
    '''calculates their sizes and SHA-256 digests, and publishes `smoke-evidence.json` from a completed
same-directory staged file. Replacement is atomic when the filesystem supports it and otherwise uses
the completed staged-file replacement fallback.''')

closeout = "docs/release-gate-closeout.md"
replace_once(closeout, "**Prepared:** 2026-08-19", "**Prepared:** 2026-08-20")
replace_once(
    closeout,
    "**Source refreshed through:** `main` at `94bd0ac6cba220615af5af4d5be82c23675a70e9`",
    "**Source refreshed through:** `main` at `feca35828c33d06e36e00cbf24b6bcce3c56191d`")
text = read(closeout)
start = text.index("## #678 — current cross-process desktop race")
end = text.index("## Exact candidate preparation", start)
reconciled = '''## #678 — packaged desktop single-instance contract

#678 is complete in merged #795 with the smaller beta contract: one normal packaged Preflight
desktop lifetime, and therefore one native operation coordinator, per user session. The native
entrypoint acquires the cross-process guard before Tauri application state exists. Unix uses an
owner-private file lock; Windows uses a session-local named mutex.

A normal secondary invocation waits through the bounded two-second collision grace and exits cleanly
if the first lifetime still owns the guard. The same grace covers updater restart overlap: the
replacement process can become primary as soon as the old lifetime releases ownership. Focus,
reveal/unminimize, and handoff IPC remain optional post-beta polish rather than release blockers.

### Candidate package evidence

Exercise the implemented admission path on installed candidate packages for Linux, Windows, and macOS:

1. start Preflight and wait for the first native host/window;
2. launch a second normal invocation while the first owns the guard; require clean secondary exit and
   prove no second normal app lifetime or native operation coordinator was admitted;
3. repeat concurrent and burst invocations to exercise the owner-private file-lock path on Unix and the
   session-local named-mutex path on Windows;
4. exercise updater restart overlap and require the replacement process to acquire ownership within the
   bounded grace after the old lifetime releases it;
5. repeat singleton acquisition after install, update, and rollback, including burst launches during
   startup;
6. repeat while preparation/game/report/export ownership is active and require every secondary launch
   to remain outside normal application state;
7. after ordinary close, update restart, rollback, and removal, verify no stale guard or helper process
   prevents the next legitimate primary launch.

A focus/reveal handoff may be added later as product polish. Candidate acceptance for #678 follows the
clean-exit contract that merged in #795 and tests the file-lock/named-mutex implementation that ships.
'''
write(closeout, text[:start] + reconciled + "\n" + text[end:])
