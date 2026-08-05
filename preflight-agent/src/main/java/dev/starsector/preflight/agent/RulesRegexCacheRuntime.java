package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/** Reuses compiled regexes at the ten fixed String call sites in the campaign rules loader. */
public final class RulesRegexCacheRuntime {
    static final String PLAN_ID = "vanilla-rules-regex-cache-v1";
    private static final int MAX_PATTERNS = 64;
    private static final String CARRIAGE_RETURN = "\\r";
    private static final String LINE_FEED = "\\n";
    private static final String TRAILING_WHITESPACE = "\\s+$";

    private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();
    private static final AtomicLong REPLACEMENTS = new AtomicLong();
    private static final AtomicLong SPLITS = new AtomicLong();

    private RulesRegexCacheRuntime() {
    }

    public static String replaceAll(String input, String regex, String replacement) {
        REPLACEMENTS.incrementAndGet();
        Objects.requireNonNull(input);
        Objects.requireNonNull(regex);
        Objects.requireNonNull(replacement);
        if (replacement.isEmpty()) {
            if (CARRIAGE_RETURN.equals(regex)) {
                return remove(input, '\r');
            }
            if (LINE_FEED.equals(regex)) {
                return remove(input, '\n');
            }
            if (TRAILING_WHITESPACE.equals(regex)) {
                return removeTrailingRegexWhitespace(input);
            }
        }
        return pattern(regex).matcher(input).replaceAll(replacement);
    }

    public static String[] split(String input, String regex) {
        SPLITS.incrementAndGet();
        Objects.requireNonNull(input);
        Objects.requireNonNull(regex);
        return pattern(regex).split(input, 0);
    }

    private static String remove(String input, char removed) {
        int first = input.indexOf(removed);
        if (first < 0) {
            return input;
        }
        StringBuilder result = new StringBuilder(input.length() - 1);
        result.append(input, 0, first);
        for (int index = first + 1; index < input.length(); index++) {
            char value = input.charAt(index);
            if (value != removed) {
                result.append(value);
            }
        }
        return result.toString();
    }

    private static String removeTrailingRegexWhitespace(String input) {
        int end = input.length();
        while (end > 0 && isDefaultRegexWhitespace(input.charAt(end - 1))) {
            end--;
        }
        return end == input.length() ? input : input.substring(0, end);
    }

    private static boolean isDefaultRegexWhitespace(char value) {
        return value == ' ' || value == '\t' || value == '\n'
                || value == '\u000b' || value == '\f' || value == '\r';
    }

    private static Pattern pattern(String regex) {
        Pattern cached = PATTERNS.get(regex);
        if (cached != null) {
            return cached;
        }
        Pattern compiled = Pattern.compile(regex);
        if (PATTERNS.size() >= MAX_PATTERNS) {
            return compiled;
        }
        Pattern raced = PATTERNS.putIfAbsent(regex, compiled);
        return raced == null ? compiled : raced;
    }

    static void beginSession() {
        PATTERNS.clear();
        REPLACEMENTS.set(0);
        SPLITS.set(0);
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("patterns", PATTERNS.size());
        values.put("replacements", REPLACEMENTS.get());
        values.put("splits", SPLITS.get());
        return values;
    }
}
