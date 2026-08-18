package dev.starsector.preflight.cli;

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
        assertFalse(output.contains("\n  desktop "), output);
    }

    @Test
    void unknownFutureCommandsRemainDiscoverableUnderOther() throws Exception {
        Map<String, List<String>> usage = usage();
        usage.put("future-tool", List.of("preflight future-tool"));
        String output = renderGlobal(usage, 88);

        assertTrue(output.contains("Other:\n"), output);
        assertTrue(output.contains("future-tool"), output);
    }

    @Test
    void narrowHelpStacksSummariesAndFitsFortyFourColumns() throws Exception {
        String output = globalHelp(44);

        assertTrue(output.contains("  launch-settings\n    Read or update"), output);
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
        assertEquals(
                "Usage:\n  preflight cache [--json]\n",
                bytes.toString(StandardCharsets.UTF_8));
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
