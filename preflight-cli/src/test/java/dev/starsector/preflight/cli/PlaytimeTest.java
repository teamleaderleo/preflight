package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlaytimeTest {
    @Test
    void explicitUserStopRetainsTheWholeRecordedSession() {
        Playtime.Summary summary = Playtime.of(List.of(
                launch("2026-08-01T10:00:00Z", 253624L, "USER_STOPPED")));
        assertEquals(253624L, summary.totalMillis());
        assertEquals(1, summary.launches());
        assertEquals(0, summary.ignoredAttempts());
    }

    @Test
    void sessionsSumIntoATotalWithTheLongestKeptSeparately() {
        Playtime.Summary summary = Playtime.of(List.of(
                launch("2026-08-01T10:00:00Z", 45 * 60_000L),
                launch("2026-08-02T10:00:00Z", 3 * 60 * 60_000L),
                launch("2026-08-03T10:00:00Z", 15 * 60_000L)));

        assertEquals(4 * 60 * 60_000L, summary.totalMillis());
        assertEquals(3 * 60 * 60_000L, summary.longestSessionMillis());
        assertEquals(3, summary.launches());
        assertEquals(80 * 60_000L, summary.averageMillis());
        assertEquals(Instant.parse("2026-08-01T10:00:00Z"), summary.first());
        assertEquals(Instant.parse("2026-08-03T10:15:00Z"), summary.last());
    }

    @Test
    void aLaunchWithNoRecordedEndCountsAsHistoryButNotAsTimePlayed() {
        // A killed process or a machine that lost power leaves a row with no duration. It happened,
        // so it stays in the ledger; it contributes nothing to a figure that claims to be hours.
        Playtime.Summary summary = Playtime.of(List.of(
                launch("2026-08-01T10:00:00Z", 60 * 60_000L),
                launch("2026-08-02T10:00:00Z", null)));

        assertEquals(60 * 60_000L, summary.totalMillis());
        assertEquals(1, summary.launches());
        assertEquals(2, summary.recorded());
        assertEquals(1, summary.sessionsWithoutDuration());
        assertEquals(0, summary.ignoredAttempts());
    }

    @Test
    void anEmptyHistoryHasNoTotalRatherThanAWrongOne() {
        Playtime.Summary summary = Playtime.of(List.of());

        assertEquals(0, summary.totalMillis());
        assertEquals(0, summary.launches());
        assertEquals(0, summary.averageMillis(), "no launches must not divide by zero");
        assertEquals(null, summary.first());
    }

    @Test
    void aFailedAttemptIsHistoryRatherThanPlaytime() {
        Playtime.Summary summary = Playtime.of(List.of(
                launch("2026-08-01T10:00:00Z", 15_000L, "LAUNCH_FAILED"),
                launch("2026-08-01T11:00:00Z", 60 * 60_000L, "COMPLETED"),
                launch("2026-08-01T12:00:00Z", 20_000L, "FUTURE_UNKNOWN_OUTCOME")));

        assertEquals(60 * 60_000L, summary.totalMillis());
        assertEquals(1, summary.launches());
        assertEquals(3, summary.recorded());
        assertEquals(0, summary.sessionsWithoutDuration());
        assertEquals(2, summary.ignoredAttempts());
    }

    @Test
    void durationsReadTheWaySomebodyWouldSayThem() {
        assertEquals("42s", Playtime.human(42_000));
        assertEquals("1 minute", Playtime.human(60_000));
        assertEquals("35 minutes", Playtime.human(35 * 60_000L));
        assertEquals("1.5 hours", Playtime.human(90 * 60_000L));
        assertEquals("1,204 hours", Playtime.human(1_204 * 60 * 60_000L));
        assertEquals("0s", Playtime.human(-5), "a negative total is not a duration");
    }

    private static LaunchLedger.Entry launch(String started, Long elapsedMillis) {
        return launch(started, elapsedMillis, "COMPLETED");
    }

    private static LaunchLedger.Entry launch(String started, Long elapsedMillis, String outcome) {
        return new LaunchLedger.Entry(
                LaunchIdentity.imported(Instant.parse(started), "run-directory"),
                Instant.parse(started),
                elapsedMillis,
                outcome,
                0,
                false,
                "recommended",
                List.of(),
                "run-directory",
                null);
    }
}
