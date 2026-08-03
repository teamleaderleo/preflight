package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * How long a seam takes, and how long its caller takes between two of them.
 *
 * <p>A launch can already say how many textures a seam served and how many bytes it handed over. It
 * cannot say how long the game took to do anything with them, and that is the number that decides
 * where to work next. The alternative -- charging the gaps between log lines to whichever logger
 * spoke last -- attributes every silent thing the caller does to the seam that happened to log
 * before it, which is how a block of texture time came to look like texture work.
 *
 * <p>So this records two series. <em>Inside</em> is the sum of each entry to its own exit. <em>Between</em>
 * is the sum of each exit to the next entry on the same thread, bucketed by how long the gap was:
 * a resource loaded right after the previous one leaves a gap measured in microseconds, and a gap
 * measured in seconds is a phase boundary with something else entirely in it. Bucketing rather than
 * capping means the split is visible instead of chosen -- nothing here has to decide what counts as
 * the caller's per-resource work, because the distribution says it.
 *
 * <p>Two {@code System.nanoTime()} calls, a thread-local read, and a handful of atomics per served
 * resource. Nothing here allocates on the serving path, blocks, or throws, so a seam that must fail
 * open stays able to.
 */
final class SeamTimer {
    /** Upper edge of each between-call bucket, in microseconds; the last bucket is everything above. */
    private static final long[] BUCKET_CEILING_MICROS = {100, 300, 1_000, 3_000, 10_000, 100_000};
    private static final String[] BUCKET_LABEL = {
        "under100us", "under300us", "under1ms", "under3ms", "under10ms", "under100ms", "over100ms",
    };
    private static final long NO_ENTRY = Long.MAX_VALUE;
    private static final long NO_EXIT = Long.MIN_VALUE;

    private final ThreadLocal<long[]> lastExit = ThreadLocal.withInitial(() -> new long[] {NO_EXIT});
    private final AtomicLong insideNanos = new AtomicLong();
    private final AtomicLong betweenNanos = new AtomicLong();
    private final AtomicLong firstEntryNanos = new AtomicLong(NO_ENTRY);
    private final AtomicLong lastExitNanos = new AtomicLong(NO_EXIT);
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLongArray bucketNanos = new AtomicLongArray(BUCKET_LABEL.length);
    private final AtomicLongArray bucketCounts = new AtomicLongArray(BUCKET_LABEL.length);

    /** The value to hand back to {@link #exit(long)}; never interpreted as a wall-clock instant. */
    long enter() {
        long now = System.nanoTime();
        long[] previous = lastExit.get();
        if (previous[0] != NO_EXIT) {
            long gap = now - previous[0];
            if (gap > 0) {
                betweenNanos.addAndGet(gap);
                int bucket = bucketFor(gap);
                bucketNanos.addAndGet(bucket, gap);
                bucketCounts.incrementAndGet(bucket);
            }
        }
        return now;
    }

    void exit(long entry) {
        long now = System.nanoTime();
        insideNanos.addAndGet(now - entry);
        firstEntryNanos.accumulateAndGet(entry, Math::min);
        lastExitNanos.accumulateAndGet(now, Math::max);
        calls.incrementAndGet();
        lastExit.get()[0] = now;
    }

    void reset() {
        insideNanos.set(0);
        betweenNanos.set(0);
        firstEntryNanos.set(NO_ENTRY);
        lastExitNanos.set(NO_EXIT);
        calls.set(0);
        for (int i = 0; i < BUCKET_LABEL.length; i++) {
            bucketNanos.set(i, 0);
            bucketCounts.set(i, 0);
        }
        // Threads that already served keep a stale exit stamp; the next gap they measure would run
        // from before the reset. Replacing the holder makes every thread start fresh.
        lastExit.remove();
    }

    static int bucketFor(long gapNanos) {
        long micros = gapNanos / 1_000L;
        for (int i = 0; i < BUCKET_CEILING_MICROS.length; i++) {
            if (micros < BUCKET_CEILING_MICROS[i]) {
                return i;
            }
        }
        return BUCKET_LABEL.length - 1;
    }

    /** First entry to last exit, or 0 before anything has been served. */
    long spanMillis() {
        long first = firstEntryNanos.get();
        long last = lastExitNanos.get();
        return first == NO_ENTRY || last == NO_EXIT ? 0L : Math.max(0L, (last - first) / 1_000_000L);
    }

    long insideMillis() {
        return insideNanos.get() / 1_000_000L;
    }

    long betweenMillis() {
        return betweenNanos.get() / 1_000_000L;
    }

    long callCount() {
        return calls.get();
    }

    Map<String, Object> snapshot(String prefix) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(prefix + "Calls", calls.get());
        values.put(prefix + "SpanMillis", spanMillis());
        values.put(prefix + "InsideMillis", insideMillis());
        values.put(prefix + "BetweenMillis", betweenMillis());
        Map<String, Object> between = new LinkedHashMap<>();
        for (int i = 0; i < BUCKET_LABEL.length; i++) {
            long millis = bucketNanos.get(i) / 1_000_000L;
            long count = bucketCounts.get(i);
            if (count == 0) {
                continue;
            }
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("gaps", count);
            bucket.put("millis", millis);
            between.put(BUCKET_LABEL[i], Map.copyOf(bucket));
        }
        values.put(prefix + "BetweenByGapSize", Map.copyOf(between));
        return values;
    }
}
