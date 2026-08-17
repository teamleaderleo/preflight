package dev.starsector.preflight.cli;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * How much Starsector has been played, derived from the launch ledger.
 *
 * <p>The ledger records each launch's wall-clock duration because that is what bounding a launch
 * requires anyway. Summed, it is the number every storefront and launcher already shows, and the
 * one people actually quote to each other about this game.
 *
 * <p>What it measures is time the game was open, which is the same thing Steam counts and carries
 * the same caveat: a session left at the main menu overnight counts. That is a known and accepted
 * property of every playtime figure anyone has seen, and pretending to a stricter definition would
 * make this number differ from the one the same person sees elsewhere for the same evening.
 *
 * <p>Derived rather than stored. Nothing here needs its own file, and a total that is recomputed
 * from the rows cannot drift away from them.
 */
final class Playtime {
    private Playtime() {
    }

    static Summary of(List<LaunchLedger.Entry> entries) {
        long totalMillis = 0;
        long longestMillis = 0;
        int counted = 0;
        int sessionsWithoutDuration = 0;
        int ignoredAttempts = 0;
        Instant first = null;
        Instant last = null;
        for (LaunchLedger.Entry entry : entries) {
            if (!isGameSession(entry.outcome())) {
                ignoredAttempts++;
                continue;
            }
            Long elapsed = entry.elapsedMillis();
            if (elapsed == null || elapsed < 0) {
                // A launch whose end was never recorded -- killed process, lost power -- has no
                // duration to add. It still happened, so it is not dropped from the ledger; it is
                // simply not counted toward a total that claims to be time played.
                sessionsWithoutDuration++;
                continue;
            }
            totalMillis += elapsed;
            longestMillis = Math.max(longestMillis, elapsed);
            counted++;
            if (first == null || entry.started().isBefore(first)) {
                first = entry.started();
            }
            Instant ended = entry.started().plusMillis(elapsed);
            if (last == null || ended.isAfter(last)) {
                last = ended;
            }
        }
        return new Summary(
                totalMillis,
                longestMillis,
                counted,
                entries.size(),
                sessionsWithoutDuration,
                ignoredAttempts,
                first,
                last);
    }

    /** Outcomes set only after the Starsector child process started and returned. */
    private static boolean isGameSession(String outcome) {
        if (outcome == null) return false;
        return switch (outcome) {
            case "COMPLETED", "FATAL_LOG_EVIDENCE", "LAUNCHER_EXIT_NONZERO", "PREFLIGHT_FAILED", "INTERRUPTED" -> true;
            default -> false;
        };
    }

    /**
     * A duration as somebody would say it out loud.
     *
     * <p>Deliberately coarse. Nobody wants their playtime to four significant figures, and a
     * rounded figure is also the honest presentation of a number assembled from sessions that each
     * include some minutes of menu.
     */
    static String human(long millis) {
        Duration duration = Duration.ofMillis(Math.max(0, millis));
        long hours = duration.toHours();
        if (hours >= 10) {
            return String.format(Locale.ROOT, "%,d hours", hours);
        }
        if (hours >= 1) {
            return String.format(Locale.ROOT, "%.1f hours", millis / 3_600_000.0);
        }
        long minutes = duration.toMinutes();
        if (minutes >= 1) {
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        return duration.toSeconds() + "s";
    }

    /**
     * @param launches launches with a recorded duration
     * @param recorded all ledger entries, including failed attempts and sessions without an end
     */
    record Summary(
            long totalMillis,
            long longestSessionMillis,
            int launches,
            int recorded,
            int sessionsWithoutDuration,
            int ignoredAttempts,
            Instant first,
            Instant last) {

        long averageMillis() {
            return launches == 0 ? 0 : totalMillis / launches;
        }
    }
}
