package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded session cache for GraphicsLib's immutable texture lookup keys. */
public final class GraphicsLibTextureKeyRuntime {
    private static final int OBJECT_TYPE_COUNT = 15;
    private static final int TURRET_ORDINAL = 1;
    private static final int HARDPOINT_ORDINAL = 7;
    private static final int MAX_BASE_KEYS = 65_536;
    private static final int MAX_ANIMATION_FRAMES = 1_024;
    private static final long MAX_CACHED_VALUES = 262_144L;

    private static final Map<String, PerKey> CACHE = new ConcurrentHashMap<>();
    private static final Object CREATION_LOCK = new Object();
    private static final AtomicLong CALLS = new AtomicLong();
    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong MISSES = new AtomicLong();
    private static final AtomicLong RECORDS = new AtomicLong();
    private static final AtomicLong BYPASSES = new AtomicLong();
    private static final AtomicLong CACHED_VALUES = new AtomicLong();

    private static volatile boolean installed;

    private GraphicsLibTextureKeyRuntime() {
    }

    static void beginSession() {
        CACHE.clear();
        CALLS.set(0);
        HITS.set(0);
        MISSES.set(0);
        RECORDS.set(0);
        BYPASSES.set(0);
        CACHED_VALUES.set(0);
        installed = false;
    }

    static void installed() {
        installed = true;
    }

    /** Returns a previously generated key without allocating, or {@code null} on a miss. */
    public static String lookup(String key, Object type, int frame) {
        CALLS.incrementAndGet();
        int ordinal = ordinal(type);
        if (key == null || ordinal < 0 || !validFrame(ordinal, frame)) {
            BYPASSES.incrementAndGet();
            return null;
        }
        PerKey values = CACHE.get(key);
        String cached = values == null ? null : values.lookup(ordinal, frame);
        if (cached == null) {
            MISSES.incrementAndGet();
        } else {
            HITS.incrementAndGet();
        }
        return cached;
    }

    /** Records the exact String returned by GraphicsLib and returns it to the caller. */
    public static String record(String key, Object type, int frame, String generated) {
        int ordinal = ordinal(type);
        if (key == null || generated == null || ordinal < 0 || !validFrame(ordinal, frame)) {
            BYPASSES.incrementAndGet();
            return generated;
        }
        PerKey values = CACHE.get(key);
        if (values == null) {
            synchronized (CREATION_LOCK) {
                values = CACHE.get(key);
                if (values == null) {
                    if (CACHE.size() >= MAX_BASE_KEYS) {
                        BYPASSES.incrementAndGet();
                        return generated;
                    }
                    values = new PerKey();
                    CACHE.put(key, values);
                }
            }
        }
        if (values.record(ordinal, frame, generated)) {
            RECORDS.incrementAndGet();
        }
        return generated;
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("installed", installed);
        values.put("calls", CALLS.get());
        values.put("hits", HITS.get());
        values.put("misses", MISSES.get());
        values.put("records", RECORDS.get());
        values.put("bypasses", BYPASSES.get());
        values.put("baseKeys", CACHE.size());
        values.put("cachedValues", CACHED_VALUES.get());
        return values;
    }

    private static int ordinal(Object type) {
        if (!(type instanceof Enum<?> value)) {
            return -1;
        }
        int ordinal = value.ordinal();
        return ordinal < OBJECT_TYPE_COUNT ? ordinal : -1;
    }

    private static boolean validFrame(int ordinal, int frame) {
        return !animated(ordinal) || (frame >= 0 && frame < MAX_ANIMATION_FRAMES);
    }

    private static boolean animated(int ordinal) {
        return ordinal == TURRET_ORDINAL || ordinal == HARDPOINT_ORDINAL;
    }

    private static final class PerKey {
        private volatile Object[] slots = new Object[OBJECT_TYPE_COUNT];

        String lookup(int ordinal, int frame) {
            Object value = slots[ordinal];
            if (!animated(ordinal)) {
                return (String) value;
            }
            String[] frames = (String[]) value;
            return frames == null || frame >= frames.length ? null : frames[frame];
        }

        synchronized boolean record(int ordinal, int frame, String generated) {
            Object[] current = slots;
            if (!animated(ordinal)) {
                if (current[ordinal] != null) {
                    return false;
                }
                if (!reserveValue()) {
                    return false;
                }
                Object[] updated = current.clone();
                updated[ordinal] = generated;
                slots = updated;
                return true;
            }
            String[] currentFrames = (String[]) current[ordinal];
            if (currentFrames != null
                    && frame < currentFrames.length
                    && currentFrames[frame] != null) {
                return false;
            }
            if (!reserveValue()) {
                return false;
            }
            int size = currentFrames == null ? 1 : currentFrames.length;
            while (size <= frame) {
                size = Math.min(MAX_ANIMATION_FRAMES, size * 2);
            }
            String[] updatedFrames = currentFrames == null
                    ? new String[size] : java.util.Arrays.copyOf(currentFrames, size);
            updatedFrames[frame] = generated;
            Object[] updated = current.clone();
            updated[ordinal] = updatedFrames;
            slots = updated;
            return true;
        }

        private static boolean reserveValue() {
            while (true) {
                long current = CACHED_VALUES.get();
                if (current >= MAX_CACHED_VALUES) {
                    BYPASSES.incrementAndGet();
                    return false;
                }
                if (CACHED_VALUES.compareAndSet(current, current + 1L)) {
                    return true;
                }
            }
        }
    }
}
