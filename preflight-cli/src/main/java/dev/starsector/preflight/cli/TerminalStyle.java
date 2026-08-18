package dev.starsector.preflight.cli;

import java.io.PrintStream;
import java.util.Map;

/** Semantic ANSI decoration for human output on an interactive terminal only. */
final class TerminalStyle {
    private static final String ESCAPE = "\u001B[";
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
