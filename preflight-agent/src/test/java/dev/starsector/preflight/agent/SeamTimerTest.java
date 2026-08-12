package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SeamTimerTest {
    @Test
    void reportsZeroesBeforeAnythingHasBeenServed() {
        SeamTimer timer = new SeamTimer();

        Map<String, Object> snapshot = timer.snapshot("serve");

        assertEquals(0L, snapshot.get("serveCalls"));
        assertEquals(0L, snapshot.get("serveSpanMillis"));
        assertEquals(0L, snapshot.get("serveInsideMillis"));
        assertEquals(0L, snapshot.get("serveBetweenMillis"));
    }

    @Test
    void chargesTheCallerTheTimeBetweenTwoServes() throws Exception {
        // Two 30 ms serves separated by 60 ms of the caller's own work: the seam is answerable for
        // the first number and not the second, and telling them apart is the whole point.
        SeamTimer timer = new SeamTimer();

        long first = timer.enter();
        Thread.sleep(30);
        timer.exit(first);
        Thread.sleep(60);
        long second = timer.enter();
        Thread.sleep(30);
        timer.exit(second);

        assertEquals(2L, timer.callCount());
        long inside = timer.insideMillis();
        long span = timer.spanMillis();
        long between = timer.betweenMillis();
        // Sleeps overrun on a loaded machine and never come back short, so only the lower bounds can
        // be stated in absolute time. A CI runner charged 208 ms for these 60 ms of serves.
        assertTrue(inside >= 55, "inside was " + inside + " ms");
        assertTrue(span >= 115, "span was " + span + " ms");
        assertTrue(between >= 40, "between was " + between + " ms");
        // What the seam actually claims is a split, not a duration: inside and between are counted
        // from separate clocks, and every millisecond of the span has to land in exactly one of them.
        // Overrun inflates all three together and leaves this untouched, while a seam charging the
        // caller's own work to itself would show up here however fast the machine is.
        assertTrue(Math.abs(span - (inside + between)) <= 10,
                "span " + span + " ms is not inside " + inside + " ms plus between " + between + " ms");
        assertEquals(between, timer.snapshot("serve").get("serveBetweenMillis"));
    }

    @Test
    void countsWorkPerThreadRatherThanSharingOneWallClock() throws Exception {
        SeamTimer timer = new SeamTimer();
        CountDownLatch ready = new CountDownLatch(2);
        Runnable serve = () -> {
            long entry = timer.enter();
            ready.countDown();
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            timer.exit(entry);
        };
        Thread one = new Thread(serve);
        Thread two = new Thread(serve);
        one.start();
        two.start();
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        one.join();
        two.join();

        Map<String, Object> snapshot = timer.snapshot("prepare");
        long span = (Long) snapshot.get("prepareSpanMillis");
        long inside = (Long) snapshot.get("prepareInsideMillis");
        assertEquals(2L, snapshot.get("prepareCalls"));
        // Two threads inside at once sum to more wall clock than elapsed, which is correct for
        // "how much work" and would be nonsense as a share of the span. Neither is capped against
        // the other, so the report has to be readable with inside above span.
        assertTrue(inside >= span, "inside " + inside + " ms should cover both threads");
        // Neither thread saw a previous exit of its own, so nothing is charged to the caller.
        assertEquals(0L, snapshot.get("prepareBetweenMillis"));
    }

    @Test
    void sortsEachGapIntoTheBucketThatDescribesIt() {
        assertEquals(0, SeamTimer.bucketFor(50_000L));
        assertEquals(1, SeamTimer.bucketFor(150_000L));
        assertEquals(2, SeamTimer.bucketFor(500_000L));
        assertEquals(3, SeamTimer.bucketFor(2_000_000L));
        assertEquals(4, SeamTimer.bucketFor(5_000_000L));
        assertEquals(5, SeamTimer.bucketFor(50_000_000L));
        assertEquals(6, SeamTimer.bucketFor(5_000_000_000L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void countsTheGapBetweenTwoServesInOneBucket() throws Exception {
        SeamTimer timer = new SeamTimer();
        long first = timer.enter();
        timer.exit(first);
        Thread.sleep(20);
        long second = timer.enter();
        timer.exit(second);

        Map<String, Object> buckets =
                (Map<String, Object>) timer.snapshot("serve").get("serveBetweenByGapSize");
        Map<String, Object> bucket = (Map<String, Object>) buckets.get("under100ms");
        assertEquals(1L, bucket.get("gaps"));
        assertTrue((Long) bucket.get("millis") >= 15);
    }

    @Test
    void resetClearsEverySeries() throws Exception {
        SeamTimer timer = new SeamTimer();
        long entry = timer.enter();
        Thread.sleep(10);
        timer.exit(entry);

        timer.reset();

        assertEquals(0L, timer.callCount());
        assertEquals(0L, timer.spanMillis());
        assertEquals(0L, timer.insideMillis());
        assertEquals(0L, timer.betweenMillis());
    }
}
