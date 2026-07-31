package dev.starsector.preflight.agent;

import java.util.Locale;

/**
 * How much of the startup the agent records.
 *
 * <p>The choice matters more than it looks, because the recording is not free and what it costs is
 * not spread evenly. {@link #FULL} stack-traces every class load, class definition and file read,
 * which was measured at about 24% of startup on the reviewed installation. That cost falls hardest
 * on class loading -- so a profile taken this way inflates the very thing a reader is most likely to
 * be asking about, and cannot settle whether class loading is worth attacking.
 *
 * <p>{@link #SAMPLE} exists for that question. It keeps the periodic execution sampling that says
 * where threads actually are, and the threshold-gated blocking events that say whether a thread was
 * waiting on another, and drops the per-event stack traces that made the recording expensive.
 */
public enum RecordingMode {
    /** Everything: sampling, blocking, and stack-traced class loads, file reads and compilations. */
    FULL,
    /** Sampling and blocking only, for asking where the time goes without distorting the answer. */
    SAMPLE,
    /** No recording at all; the caches still run and the adapter still writes its report. */
    OFF;

    /**
     * Reads the {@code record=} agent option. An unrecognised value is treated as {@link #FULL}
     * rather than rejected, because the agent must never be the reason a launch fails.
     */
    static RecordingMode parse(String value) {
        if (value == null || value.isBlank()) {
            return FULL;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "off", "none", "disabled", "false", "0" -> OFF;
            case "sample", "sampling" -> SAMPLE;
            default -> FULL;
        };
    }

    /** Whether any recording is written at all. */
    public boolean records() {
        return this != OFF;
    }

    public String optionValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
