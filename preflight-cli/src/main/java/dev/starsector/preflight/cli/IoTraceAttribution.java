package dev.starsector.preflight.cli;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Aggregates JFR file I/O by normalized path, extension, and startup-relevant category. */
final class IoTraceAttribution {
    private static final int TOP_LIMIT = 25;
    private static final String UNKNOWN_PATH = "<unknown>";

    private final Map<String, MutableMetric> readsByPath = new TreeMap<>();
    private final Map<String, MutableMetric> writesByPath = new TreeMap<>();
    private final Map<String, MutableMetric> readsByExtension = new TreeMap<>();
    private final Map<String, MutableMetric> writesByExtension = new TreeMap<>();
    private final Map<String, MutableMetric> readsByCategory = new TreeMap<>();
    private final Map<String, MutableMetric> writesByCategory = new TreeMap<>();
    private String workingDirectory;

    /**
     * The directory the recorded process was running in, if the recording states one.
     *
     * <p>Set from the agent's marker event, which can arrive after reads it applies to, so paths are
     * kept as recorded and folded at report time instead of on the way in.</p>
     */
    void resolveAgainst(String directory) {
        workingDirectory = directory == null || directory.isBlank() ? null : directory;
    }

    void recordRead(String path, long bytes, long nanos) {
        record(path, bytes, nanos, readsByPath, readsByExtension, readsByCategory);
    }

    void recordWrite(String path, long bytes, long nanos) {
        record(path, bytes, nanos, writesByPath, writesByExtension, writesByCategory);
    }

    Map<String, Object> toMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("topReadPaths", top(fold(readsByPath), "path"));
        values.put("topWritePaths", top(fold(writesByPath), "path"));
        values.put("readExtensions", top(readsByExtension, "extension"));
        values.put("writeExtensions", top(writesByExtension, "extension"));
        values.put("readCategories", top(readsByCategory, "category"));
        values.put("writeCategories", top(writesByCategory, "category"));
        return values;
    }

    private static void record(
            String rawPath,
            long rawBytes,
            long rawNanos,
            Map<String, MutableMetric> byPath,
            Map<String, MutableMetric> byExtension,
            Map<String, MutableMetric> byCategory) {
        String path = normalizePath(rawPath);
        long bytes = Math.max(0, rawBytes);
        long nanos = Math.max(0, rawNanos);
        byPath.computeIfAbsent(path, ignored -> new MutableMetric()).add(bytes, nanos);
        String extension = extension(path);
        byExtension.computeIfAbsent(extension, ignored -> new MutableMetric()).add(bytes, nanos);
        String category = category(extension);
        byCategory.computeIfAbsent(category, ignored -> new MutableMetric()).add(bytes, nanos);
    }

    static String normalizePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN_PATH;
        }
        String value = raw.trim().replace('\\', '/');
        StringBuilder normalized = new StringBuilder(value.length());
        boolean priorSlash = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '/') {
                if (!priorSlash) {
                    normalized.append(c);
                }
                priorSlash = true;
            } else {
                normalized.append(c);
                priorSlash = false;
            }
        }
        String result = normalized.toString();
        return result.isBlank() ? UNKNOWN_PATH : result;
    }

    /**
     * Merges the rows that are the same file spelled differently.
     *
     * <p>One file reaches a recording under up to three spellings: relative
     * ({@code data/hulls/afflictor.ship}), absolute with the traversal the game built it from
     * ({@code .../Contents/Resources/Java/../../../mods/Foo/bar.ogg}), and absolute and clean. Left
     * apart, a file read under two of them has its operations, bytes and duration split across two
     * rows, and each row understates it. On the reviewed profile 526 files are spelled more than one
     * way.</p>
     *
     * <p>Resolution is lexical rather than through the filesystem, because a recording is worth
     * reading on a machine that does not have the game installed — and on a different platform from
     * the one that produced it, where {@code Path} would not even agree about which paths are
     * absolute. So the recorded spelling decides, and a path that cannot be folded is left exactly as
     * it was rather than guessed at.</p>
     */
    private Map<String, MutableMetric> fold(Map<String, MutableMetric> byPath) {
        Map<String, MutableMetric> folded = new TreeMap<>();
        for (Map.Entry<String, MutableMetric> entry : byPath.entrySet()) {
            folded.computeIfAbsent(canonicalPath(entry.getKey()), ignored -> new MutableMetric())
                    .merge(entry.getValue());
        }
        return folded;
    }

    /** Lexically resolves a recorded path so that differing spellings of one file agree. */
    String canonicalPath(String path) {
        if (UNKNOWN_PATH.equals(path)) {
            return path;
        }
        String value = path;
        if (workingDirectory != null && !isAbsolute(value)) {
            String base = normalizePath(workingDirectory);
            value = base.endsWith("/") ? base + value : base + "/" + value;
        }
        boolean absolute = value.startsWith("/");
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment) && !segments.isEmpty() && !"..".equals(segments.peekLast())) {
                segments.removeLast();
            } else {
                segments.addLast(segment);
            }
        }
        String joined = String.join("/", segments);
        return absolute ? "/" + joined : joined;
    }

    /**
     * Whether a recorded path is absolute, decided from the spelling rather than from the platform
     * running the analysis, so a Windows recording read on macOS is not mistaken for relative.
     */
    private static boolean isAbsolute(String path) {
        if (path.startsWith("/")) {
            return true;
        }
        return path.length() >= 3
                && Character.isLetter(path.charAt(0))
                && path.charAt(1) == ':'
                && path.charAt(2) == '/';
    }

    static String extension(String path) {
        if (UNKNOWN_PATH.equals(path)) {
            return "<unknown>";
        }
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash || dot == path.length() - 1) {
            return "<none>";
        }
        return path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String category(String extension) {
        return switch (extension) {
            case "jar", "zip" -> "archive";
            case "spfi", "spft", "spfm", "spfj", "spfc" -> "preflight-cache";
            case "png", "jpg", "jpeg", "webp", "bmp", "gif", "tga" -> "image";
            case "ogg", "wav", "mp3", "flac" -> "sound";
            case "csv", "json", "xml", "faction", "variant", "ship", "skin", "weapon", "wpn",
                    "proj", "system", "rules" -> "data";
            case "class", "java", "kt" -> "code";
            case "<unknown>" -> "unknown";
            default -> "other";
        };
    }

    private static List<Map<String, Object>> top(Map<String, MutableMetric> source, String keyName) {
        List<Map.Entry<String, MutableMetric>> entries = new ArrayList<>(source.entrySet());
        entries.sort(Comparator
                .<Map.Entry<String, MutableMetric>>comparingLong(entry -> entry.getValue().bytes)
                .reversed()
                .thenComparing(Comparator
                        .<Map.Entry<String, MutableMetric>>comparingLong(entry -> entry.getValue().nanos)
                        .reversed())
                .thenComparing(Comparator
                        .<Map.Entry<String, MutableMetric>>comparingLong(entry -> entry.getValue().operations)
                        .reversed())
                .thenComparing(Map.Entry::getKey));

        List<Map<String, Object>> result = new ArrayList<>(Math.min(TOP_LIMIT, entries.size()));
        for (int i = 0; i < entries.size() && i < TOP_LIMIT; i++) {
            Map.Entry<String, MutableMetric> entry = entries.get(i);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put(keyName, entry.getKey());
            value.put("operations", entry.getValue().operations);
            value.put("bytes", entry.getValue().bytes);
            value.put("durationMs", entry.getValue().nanos / 1_000_000.0);
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static final class MutableMetric {
        private long operations;
        private long bytes;
        private long nanos;

        void add(long additionalBytes, long additionalNanos) {
            operations = Math.addExact(operations, 1);
            bytes = Math.addExact(bytes, additionalBytes);
            nanos = Math.addExact(nanos, additionalNanos);
        }

        void merge(MutableMetric other) {
            operations = Math.addExact(operations, other.operations);
            bytes = Math.addExact(bytes, other.bytes);
            nanos = Math.addExact(nanos, other.nanos);
        }
    }
}
