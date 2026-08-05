package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import sun.misc.Unsafe;

/** Compares three exact eMod-identity checks on Starsector's Rosetta JVM. */
final class EventModMapValidationBenchmark {
    private static final int WARMUP = 20_000_000;
    private static final int ITERATIONS = 100_000_000;
    private static final int MAP_COUNT = 1024;
    private static final Unsafe UNSAFE = unsafe();
    private static final long MOD_COUNT_OFFSET = modCountOffset();
    private static volatile int sink;

    public static void main(String[] args) {
        Object expected = new Object();
        @SuppressWarnings("unchecked")
        LinkedHashMap<String, Object>[] maps = new LinkedHashMap[MAP_COUNT];
        Object[] snapshots = new Object[MAP_COUNT];
        @SuppressWarnings("unchecked")
        Map.Entry<String, Object>[] entries = new Map.Entry[MAP_COUNT];
        int[] modCounts = new int[MAP_COUNT];
        for (int i = 0; i < MAP_COUNT; i++) {
            var map = new LinkedHashMap<String, Object>();
            map.put("other", new Object());
            map.put("eMod", expected);
            maps[i] = map;
            snapshots[i] = EventModMapSnapshotRuntime.capture(map, "eMod");
            if (snapshots[i] == null) {
                throw new IllegalStateException("snapshot unavailable; run with java.util opened");
            }
            entries[i] = map.entrySet().stream()
                    .filter(value -> "eMod".equals(value.getKey())).findFirst().orElseThrow();
            modCounts[i] = UNSAFE.getInt(map, MOD_COUNT_OFFSET);
        }

        runDirect(maps, expected, WARMUP);
        runSnapshot(snapshots, maps, expected, WARMUP);
        runUnsafe(maps, entries, expected, modCounts, WARMUP);
        report("direct-get", () -> runDirect(maps, expected, ITERATIONS));
        report("snapshot-varhandle", () ->
                runSnapshot(snapshots, maps, expected, ITERATIONS));
        report("snapshot-unsafe", () ->
                runUnsafe(maps, entries, expected, modCounts, ITERATIONS));
        System.out.println("sink=" + sink);
    }

    private static void runDirect(
            LinkedHashMap<String, Object>[] maps, Object expected, int count) {
        int matches = 0;
        for (int i = 0; i < count; i++) {
            if (maps[i & (MAP_COUNT - 1)].get("eMod") == expected) matches++;
        }
        sink = matches;
    }

    private static void runSnapshot(
            Object[] snapshots, LinkedHashMap<String, Object>[] maps, Object expected, int count) {
        int matches = 0;
        for (int i = 0; i < count; i++) {
            int index = i & (MAP_COUNT - 1);
            if (EventModMapSnapshotRuntime.unchanged(
                    snapshots[index], maps[index], expected)) matches++;
        }
        sink = matches;
    }

    private static void runUnsafe(LinkedHashMap<String, Object>[] maps,
            Map.Entry<String, Object>[] entries, Object expected, int[] modCounts, int count) {
        int matches = 0;
        for (int i = 0; i < count; i++) {
            int index = i & (MAP_COUNT - 1);
            if (UNSAFE.getInt(maps[index], MOD_COUNT_OFFSET) == modCounts[index]
                    && entries[index].getValue() == expected) matches++;
        }
        sink = matches;
    }

    private static Unsafe unsafe() {
        try {
            var field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static long modCountOffset() {
        try {
            return UNSAFE.objectFieldOffset(java.util.HashMap.class.getDeclaredField("modCount"));
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static void report(String label, Runnable work) {
        long started = System.nanoTime();
        work.run();
        double nanos = System.nanoTime() - started;
        System.out.printf(Locale.ROOT, "%s=%.3f ns/op%n", label, nanos / ITERATIONS);
    }
}
