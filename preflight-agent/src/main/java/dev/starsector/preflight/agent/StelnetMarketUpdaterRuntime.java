package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Gives Stellar Networks one shuffled market-refresh pass per paused interval. */
public final class StelnetMarketUpdaterRuntime {
    static final String PLAN_ID = "stelnet-paused-market-refresh-pass-v1";

    private static final Object FALLBACK = new Object();
    private static final AtomicLong pauseIntervals = new AtomicLong();
    private static final AtomicLong marketsQueued = new AtomicLong();
    private static final AtomicLong marketsServed = new AtomicLong();
    private static final AtomicLong exhaustedFrames = new AtomicLong();
    private static final AtomicLong invalidations = new AtomicLong();
    private static final AtomicLong delegated = new AtomicLong();
    private static final AtomicLong failures = new AtomicLong();
    private static List<?> queue;
    private static int next;
    private static boolean paused;
    private static boolean fallback;
    private static volatile boolean installed;

    private StelnetMarketUpdaterRuntime() {
    }

    static void installed() {
        installed = true;
    }

    public static synchronized void observePaused(boolean value) {
        if (!value) {
            if (paused) {
                queue = null;
                next = 0;
                fallback = false;
            }
            paused = false;
            return;
        }
        if (!paused) {
            pauseIntervals.incrementAndGet();
        }
        paused = true;
    }

    public static synchronized boolean hasPending() {
        if (fallback || queue == null) {
            return true;
        }
        if (next < queue.size()) {
            return true;
        }
        exhaustedFrames.incrementAndGet();
        return false;
    }

    public static synchronized Object nextMarket(List<?> markets) {
        if (fallback) {
            delegated.incrementAndGet();
            return FALLBACK;
        }
        try {
            if (queue == null) {
                ArrayList<Object> shuffled = new ArrayList<>(markets);
                Collections.shuffle(shuffled);
                queue = shuffled;
                next = 0;
                marketsQueued.addAndGet(shuffled.size());
            }
            if (next >= queue.size()) {
                exhaustedFrames.incrementAndGet();
                return null;
            }
            Object market = queue.get(next++);
            marketsServed.incrementAndGet();
            return market;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            fallback = true;
            failures.incrementAndGet();
            delegated.incrementAndGet();
            return FALLBACK;
        }
    }

    public static boolean isFallback(Object value) {
        return value == FALLBACK;
    }

    public static synchronized void invalidate() {
        queue = null;
        next = 0;
        fallback = false;
        invalidations.incrementAndGet();
    }

    static synchronized Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("installed", installed);
        result.put("pauseIntervals", pauseIntervals.get());
        result.put("marketsQueued", marketsQueued.get());
        result.put("marketsServed", marketsServed.get());
        result.put("exhaustedFrames", exhaustedFrames.get());
        result.put("invalidations", invalidations.get());
        result.put("delegated", delegated.get());
        result.put("failures", failures.get());
        result.put("pending", queue == null ? -1 : queue.size() - next);
        return result;
    }

    static synchronized void reset() {
        queue = null;
        next = 0;
        paused = false;
        fallback = false;
        installed = false;
        pauseIntervals.set(0);
        marketsQueued.set(0);
        marketsServed.set(0);
        exhaustedFrames.set(0);
        invalidations.set(0);
        delegated.set(0);
        failures.set(0);
    }
}
