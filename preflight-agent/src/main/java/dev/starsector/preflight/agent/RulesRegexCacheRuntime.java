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

    private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();
    private static final AtomicLong REPLACEMENTS = new AtomicLong();
    private static final AtomicLong SPLITS = new AtomicLong();

    private RulesRegexCacheRuntime() {
    }

    public static String replaceAll(String input, String regex, String replacement) {
        REPLACEMENTS.incrementAndGet();
        Objects.requireNonNull(input);
        return pattern(regex).matcher(input).replaceAll(replacement);
    }

    public static String[] split(String input, String regex) {
        SPLITS.incrementAndGet();
        Objects.requireNonNull(input);
        return pattern(regex).split(input, 0);
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
