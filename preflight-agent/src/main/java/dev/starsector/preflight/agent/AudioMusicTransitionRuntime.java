package dev.starsector.preflight.agent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Passive telemetry for Starsector's streaming-music fade and cleanup boundaries. */
public final class AudioMusicTransitionRuntime {
    static final String PLAN_ID = "audio-music-transition-probe-v1";
    private static final int EVENT_LIMIT = 64;
    private static final Object lock = new Object();
    private static final Deque<Map<String, Object>> events = new ArrayDeque<>();
    private static volatile boolean installed;
    private static volatile long startedNanos;
    private static long eventsDropped;

    private AudioMusicTransitionRuntime() {
    }

    static void installed() {
        installed = true;
    }

    public static void created(String id, int source) {
        record("created", id, 0, Float.NaN, source);
    }

    public static void fade(String kind, String id, int durationMillis) {
        record(kind, id, durationMillis, Float.NaN, -1);
    }

    public static void cleanup(String id, float fadeScale, int fadeOutMillis, int source) {
        record("cleanup", id, fadeOutMillis, fadeScale, source);
    }

    static void beginSession() {
        installed = false;
        startedNanos = System.nanoTime();
        synchronized (lock) {
            events.clear();
            eventsDropped = 0L;
        }
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", PLAN_ID);
        values.put("installed", installed);
        synchronized (lock) {
            values.put("events", List.copyOf(events));
            values.put("retentionPolicy", "latest-" + EVENT_LIMIT);
            values.put("eventsDropped", eventsDropped);
        }
        return values;
    }

    private static void record(
            String kind, String id, int durationMillis, float fadeScale, int source) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("elapsedMicros", Math.max(0L, (System.nanoTime() - startedNanos) / 1_000L));
        event.put("kind", kind);
        event.put("id", id == null ? "<null>" : id);
        if (durationMillis != 0) event.put("durationMillis", durationMillis);
        if (!Float.isNaN(fadeScale)) event.put("fadeScale", fadeScale);
        if (source >= 0) event.put("source", source);
        synchronized (lock) {
            if (events.size() == EVENT_LIMIT) {
                events.removeFirst();
                eventsDropped++;
            }
            events.addLast(Map.copyOf(event));
        }
    }
}
