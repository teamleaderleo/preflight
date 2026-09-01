package dev.starsector.preflight.core.bisect;

import java.util.Locale;

/**
 * State machine stages for the Mod Bisect Assistant session.
 */
public enum BisectStatus {
    INACTIVE,
    INITIALIZING,
    TESTING,
    VERIFYING,
    CULPRIT_FOUND,
    COMPLETED,
    ABORTED;

    public static BisectStatus parse(String text) {
        if (text == null) {
            return INACTIVE;
        }
        try {
            return BisectStatus.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return INACTIVE;
        }
    }
}
