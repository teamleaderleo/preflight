package dev.starsector.preflight.cli;

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
                "\u001B[36mPreview:\u001B[0m",
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
        assertEquals("Removed\n", bytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void nonTerminalOutputStaysPlain() {
        assertEquals(
                "Preview:",
                TerminalStyle.semantic(false, Map.of("TERM", "xterm-256color"),
                        TerminalStyle.Tone.PREVIEW, "Preview:"));
    }
}
