package dev.starsector.preflight.cli;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Reads {@code data/config/sounds.json} for the one distinction prepared audio turns on: whether a
 * file is a fully decoded effect or streamed music.
 *
 * <p>Directory naming does not answer that. The reviewed profile has {@code sounds/nsp_templar_theme.ogg}
 * — seven minutes of music with no {@code music/} anywhere in its path — and mods that keep short
 * effects under a {@code music} directory. The config is the only authority: entries inside the
 * top-level {@code "music"} object are music, and everything else is an effect.</p>
 *
 * <p>An entry may name a {@code "source"}, in which case {@code "file"} is a member of that archive
 * or directory rather than a path in the profile. Those are recorded as sources, because a directory
 * source sweeps in every file beneath it — which is how most music in the reviewed profile is
 * declared, and why classifying by {@code "file"} alone leaves 337 files looking unreferenced.</p>
 *
 * <p>Starsector's JSON permits {@code #} and {@code //} comments and trailing commas. Commented-out
 * entries are not loaded, so they must not be collected.</p>
 */
final class SoundsConfig {
    private SoundsConfig() {
    }

    /**
     * @param effectFiles logical paths declared outside the music object
     * @param musicFiles logical paths declared inside the music object without a source
     * @param musicSources archive or directory prefixes the music object draws from
     */
    record Declaration(Set<String> effectFiles, Set<String> musicFiles, Set<String> musicSources) {
        Declaration {
            effectFiles = Set.copyOf(effectFiles);
            musicFiles = Set.copyOf(musicFiles);
            musicSources = Set.copyOf(musicSources);
        }

        static Declaration empty() {
            return new Declaration(Set.of(), Set.of(), Set.of());
        }

        boolean isEmpty() {
            return effectFiles.isEmpty() && musicFiles.isEmpty() && musicSources.isEmpty();
        }
    }

    static Declaration parse(String json) {
        if (json == null || json.isBlank()) {
            return Declaration.empty();
        }
        String text = blankComments(json);
        int[] span = musicSpan(text);

        Set<String> effectFiles = new LinkedHashSet<>();
        Set<String> musicFiles = new LinkedHashSet<>();
        Set<String> musicSources = new LinkedHashSet<>();
        if (span == null) {
            collect(text, 0, text.length(), effectFiles, musicSources);
        } else {
            collect(text, span[0], span[1], musicFiles, musicSources);
            collect(text, 0, span[0], effectFiles, new LinkedHashSet<>());
            collect(text, span[1], text.length(), effectFiles, new LinkedHashSet<>());
        }
        return new Declaration(effectFiles, musicFiles, musicSources);
    }

    /**
     * Replaces comment characters with spaces rather than deleting them, so every offset in the
     * result still indexes the original text. A {@code #} inside a string literal is content.
     */
    private static String blankComments(String json) {
        char[] out = json.toCharArray();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < out.length; i++) {
            char c = out[i];
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '#' || (c == '/' && i + 1 < out.length && out[i + 1] == '/')) {
                while (i < out.length && out[i] != '\n') {
                    out[i++] = ' ';
                }
            }
        }
        return new String(out);
    }

    /** Brace-matched extent of the top-level {@code "music"} object value, or null if absent. */
    private static int[] musicSpan(String text) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                if (depth == 1 && text.startsWith("\"music\"", i)) {
                    int open = indexOfValue(text, i + "\"music\"".length());
                    if (open >= 0 && text.charAt(open) == '{') {
                        int close = matchBrace(text, open);
                        if (close >= 0) {
                            return new int[] {open, close + 1};
                        }
                    }
                }
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
        }
        return null;
    }

    private static int indexOfValue(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ':') {
                for (int j = i + 1; j < text.length(); j++) {
                    if (!Character.isWhitespace(text.charAt(j))) {
                        return j;
                    }
                }
                return -1;
            }
            if (!Character.isWhitespace(c)) {
                return -1;
            }
        }
        return -1;
    }

    private static int matchBrace(String text, int open) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Collects every innermost {@code { ... }} object in the region. Sound entries never nest, so an
     * object containing no braces of its own is exactly one entry, and reading only those keeps
     * enclosing objects — the {@code "sounds": [ ... ]} wrappers — from being mistaken for entries.
     */
    private static void collect(String text, int from, int to, Set<String> files, Set<String> sources) {
        Deque<Integer> starts = new ArrayDeque<>();
        boolean nested = false;
        boolean inString = false;
        boolean escaped = false;
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                starts.push(i);
                nested = false;
            } else if (c == '}') {
                if (starts.isEmpty()) {
                    continue;
                }
                int start = starts.pop();
                if (!nested) {
                    readEntry(text.substring(start + 1, i), files, sources);
                }
                nested = true;
            }
        }
    }

    private static void readEntry(String entry, Set<String> files, Set<String> sources) {
        String file = field(entry, "file");
        if (file == null) {
            return;
        }
        String source = field(entry, "source");
        if (source != null) {
            sources.add(normalize(source));
        } else {
            files.add(normalize(file));
        }
    }

    /** Reads {@code "key": "value"} from a flat object body, ignoring matches inside other values. */
    private static String field(String entry, String key) {
        String quoted = '"' + key + '"';
        int at = 0;
        while (true) {
            at = entry.indexOf(quoted, at);
            if (at < 0) {
                return null;
            }
            if (isKeyPosition(entry, at)) {
                int value = indexOfValue(entry, at + quoted.length());
                if (value >= 0 && entry.charAt(value) == '"') {
                    int end = value + 1;
                    StringBuilder text = new StringBuilder();
                    while (end < entry.length() && entry.charAt(end) != '"') {
                        if (entry.charAt(end) == '\\' && end + 1 < entry.length()) {
                            end++;
                        }
                        text.append(entry.charAt(end++));
                    }
                    return text.toString();
                }
            }
            at += quoted.length();
        }
    }

    /** A quoted key must not itself sit inside an earlier string value. */
    private static boolean isKeyPosition(String entry, int at) {
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < at; i++) {
            char c = entry.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            }
        }
        return !inString;
    }

    static String normalize(String path) {
        String value = path.replace('\\', '/').trim().toLowerCase(Locale.ROOT);
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
