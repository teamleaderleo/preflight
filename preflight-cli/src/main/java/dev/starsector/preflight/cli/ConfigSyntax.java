package dev.starsector.preflight.cli;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Reads a Starsector config file far enough to answer one question: does the game see everything the
 * author wrote in it?
 *
 * <p>A mod's behaviour lives in JSON-shaped files — {@code .variant}, {@code .wpn}, {@code .ship},
 * {@code .faction} and the rest. The reviewed profile ships 15,353 of them across 91 mods, and
 * nothing in the toolchain reads them for syntax. A file that does not parse stops the game with a
 * message about a line number; a file that parses <em>partially</em> is worse, because it starts
 * cleanly and quietly does less than it says.</p>
 *
 * <h2>Why this is not a JSON parser</h2>
 *
 * <p>The dialect accepts a great deal that strict JSON rejects, all of it observed in shipping mods:
 * {@code #} and {@code //} comments, trailing commas, unquoted keys ({@code tips:[...]} in 26 mods'
 * {@code tips.json}), and numeric suffixes ({@code "duration":0.1f}). A strict parser pointed at this
 * ecosystem reports almost all of it as broken, which is the one outcome this tool cannot afford.
 * So this reads structure only — brackets, strings and comments — and stays incurious about types.</p>
 *
 * <h2>The correction that shaped it</h2>
 *
 * <p>A first version also required the file to end after its top-level value, and flagged 27 files
 * that ship in mods as widely used as MagicLib, Nexerelin and Arma Armatura. They are not broken.
 * The reader consumes one value and never looks at what follows, so a stray {@code }} at the end of
 * a file is invisible to the game, and reporting it would be describing normal practice as a defect.
 * Trailing brackets, commas and comments are therefore ignored here.</p>
 *
 * <p>What survives that correction is narrow and real: <em>content</em> after the top-level value
 * closes. Six files in the profile have it, every one hand-checked. In
 * {@code expsp_beat_msl.proj} a {@code PROXIMITY_FUSE} block sits past the closing brace, so the
 * missile has no proximity fuse in game; in {@code eusan_nation_kayak_R.wpn} it is a
 * {@code fireSoundTwo} that never plays. Whether the game ignores the remainder or refuses the file,
 * that configuration is not doing what it looks like it does.</p>
 */
final class ConfigSyntax {
    /** Enough of a look at the trailing content to recognise it, without reprinting the file. */
    private static final int PREVIEW_LIMIT = 60;

    private ConfigSyntax() {
    }

    enum Verdict {
        /** One complete top-level value, and nothing but punctuation after it. */
        WELL_FORMED,
        /** No reader could accept this: a bracket or string that never closes. */
        UNPARSEABLE,
        /** It parses, and carries configuration past the end of what a reader would consume. */
        TRAILING_CONTENT
    }

    /**
     * @param verdict what the file is
     * @param line 1-based line the finding points at, or 0 when there is nothing to point at
     * @param detail a sentence fragment naming the problem, safe to show an author
     */
    record Reading(Verdict verdict, int line, String detail) {
        static Reading wellFormed() {
            return new Reading(Verdict.WELL_FORMED, 0, "");
        }
    }

    static Reading read(String text) {
        return new Scanner(text).run();
    }

    /**
     * Deliberately iterative. Nesting depth here comes from files this tool did not write and cannot
     * vet, and a recursive descent would turn a corrupt file with fifty thousand open brackets into a
     * StackOverflowError in the middle of someone's lint run.
     */
    private static final class Scanner {
        private final String text;
        private final int length;
        private int index;
        private int line = 1;

        Scanner(String text) {
            // A UTF-8 BOM is a byte-order mark, not content, and several mods ship one.
            this.text = !text.isEmpty() && text.charAt(0) == '﻿' ? text.substring(1) : text;
            this.length = this.text.length();
        }

        Reading run() {
            String trivia = skipTrivia();
            if (trivia != null) {
                return new Reading(Verdict.UNPARSEABLE, line, trivia);
            }
            if (index >= length) {
                return new Reading(Verdict.UNPARSEABLE, 1, "the file holds no configuration");
            }
            char first = text.charAt(index);
            if (first == '}' || first == ']' || first == ',' || first == ':') {
                return new Reading(Verdict.UNPARSEABLE, line,
                        "the file begins with '" + first + "', which closes nothing");
            }
            if (first != '{' && first != '[') {
                // Every config file in the profile opens an object or an array. One that opens with a
                // bare word or number has a typo before its real content -- mayasuran_guard.json
                // begins "0{" -- and a reader would take that stray token as the whole file.
                return new Reading(Verdict.UNPARSEABLE, line,
                        "the file starts with '" + previewFrom(index)
                                + "' rather than opening an object or array");
            }

            Reading value = consumeValue();
            if (value != null) {
                return value;
            }
            return remainder();
        }

        /**
         * Consumes exactly one value — the whole of what a reader would take from this file. Entered
         * only with an opening bracket under the cursor.
         */
        private Reading consumeValue() {
            Deque<int[]> open = new ArrayDeque<>();
            while (true) {
                String trivia = skipTrivia();
                if (trivia != null) {
                    return new Reading(Verdict.UNPARSEABLE, line, trivia);
                }
                if (index >= length) {
                    int[] unclosed = open.peek();
                    return new Reading(Verdict.UNPARSEABLE, unclosed[1],
                            "the '" + (char) unclosed[0] + "' opened on line " + unclosed[1]
                                    + " is never closed");
                }
                char c = text.charAt(index);
                if (c == '{' || c == '[') {
                    open.push(new int[] {c, line});
                    advance();
                } else if (c == '}' || c == ']') {
                    int[] opened = open.peek();
                    char expected = opened[0] == '{' ? '}' : ']';
                    if (c != expected) {
                        return new Reading(Verdict.UNPARSEABLE, line,
                                "the '" + (char) opened[0] + "' opened on line " + opened[1]
                                        + " is closed by '" + c + "' on line " + line);
                    }
                    open.pop();
                    advance();
                    if (open.isEmpty()) {
                        return null;
                    }
                } else if (c == ',' || c == ':') {
                    advance();
                } else if (c == '"' || c == '\'') {
                    Reading error = consumeString();
                    if (error != null) {
                        return error;
                    }
                } else {
                    consumeBareToken();
                }
            }
        }

        /**
         * Everything after the one value a reader consumes. Brackets, commas and comments out here are
         * the ordinary debris of hand-edited config and are ignored; anything else is configuration
         * the file appears to set and does not.
         */
        private Reading remainder() {
            while (index < length) {
                if (skipTrivia() != null) {
                    return Reading.wellFormed();
                }
                if (index >= length) {
                    break;
                }
                char c = text.charAt(index);
                if (c == '}' || c == ']' || c == ',') {
                    advance();
                    continue;
                }
                return new Reading(Verdict.TRAILING_CONTENT, line,
                        "line " + line + " onwards sits outside the top-level value: "
                                + previewFrom(index));
            }
            return Reading.wellFormed();
        }

        /** The rest of the line from {@code start}, trimmed and capped — enough to recognise, not to reprint. */
        private String previewFrom(int start) {
            int stop = text.indexOf('\n', start);
            String preview = (stop < 0 ? text.substring(start) : text.substring(start, stop)).trim();
            return preview.length() > PREVIEW_LIMIT ? preview.substring(0, PREVIEW_LIMIT) + "..." : preview;
        }

        /** @return an error reading if the string never closes, or null */
        private Reading consumeString() {
            char quote = text.charAt(index);
            int opened = line;
            advance();
            while (index < length) {
                char c = text.charAt(index);
                if (c == '\\') {
                    advance();
                    advance();
                    continue;
                }
                if (c == quote) {
                    advance();
                    return null;
                }
                if (c == '\n') {
                    break;
                }
                advance();
            }
            return new Reading(Verdict.UNPARSEABLE, opened,
                    "the string opened on line " + opened + " is never closed");
        }

        /**
         * An unquoted key, number, or bare word. Always consumes at least one character: every
         * character in the stop set is handled by the caller before this is reached.
         */
        private void consumeBareToken() {
            while (index < length && "{}[],: \t\r\n".indexOf(text.charAt(index)) < 0) {
                advance();
            }
        }

        /** @return an error message if a block comment never closes, or null */
        private String skipTrivia() {
            while (index < length) {
                char c = text.charAt(index);
                if (Character.isWhitespace(c)) {
                    advance();
                } else if (c == '#') {
                    skipToEndOfLine();
                } else if (c == '/' && index + 1 < length && text.charAt(index + 1) == '/') {
                    skipToEndOfLine();
                } else if (c == '/' && index + 1 < length && text.charAt(index + 1) == '*') {
                    int opened = line;
                    advance();
                    advance();
                    while (index + 1 < length
                            && !(text.charAt(index) == '*' && text.charAt(index + 1) == '/')) {
                        advance();
                    }
                    if (index + 1 >= length) {
                        index = length;
                        return "the /* comment opened on line " + opened + " is never closed";
                    }
                    advance();
                    advance();
                } else {
                    return null;
                }
            }
            return null;
        }

        private void skipToEndOfLine() {
            while (index < length && text.charAt(index) != '\n') {
                advance();
            }
        }

        private void advance() {
            if (index < length && text.charAt(index) == '\n') {
                line++;
            }
            index++;
        }
    }
}
