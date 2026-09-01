package dev.starsector.preflight.core.bisect;

import java.util.Locale;

/**
 * Outcome verdict for an individual mod bisect test partition run.
 */
public enum BisectVerdict {
    PASS,
    FAIL,
    SKIP;

    public static BisectVerdict parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Verdict cannot be null");
        }
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "pass", "good", "passed", "success" -> PASS;
            case "fail", "bad", "failed", "crash", "crashed" -> FAIL;
            case "skip", "skipped" -> SKIP;
            default -> throw new IllegalArgumentException("Unknown bisect verdict: " + text);
        };
    }
}
