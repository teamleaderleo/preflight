package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.Locale;

/** Compares the two exact eMod-identity checks on Starsector's Rosetta JVM. */
final class EventModMapValidationBenchmark {
    private static final int WARMUP = 20_000_000;
    private static final int ITERATIONS = 100_000_000;
    private static volatile int sink;

    public static void main(String[] args) {
        Object expected = new Object();
        var map = new LinkedHashMap<String, Object>();
        map.put("other", new Object());
        map.put("eMod", expected);
        Object snapshot = EventModMapSnapshotRuntime.capture(map, "eMod");
        if (snapshot == null) {
            throw new IllegalStateException("snapshot unavailable; run with java.util opened");
        }

        runDirect(map, expected, WARMUP);
        runSnapshot(snapshot, map, expected, WARMUP);
        report("direct-get", () -> runDirect(map, expected, ITERATIONS));
        report("snapshot-varhandle", () -> runSnapshot(snapshot, map, expected, ITERATIONS));
        System.out.println("sink=" + sink);
    }

    private static void runDirect(LinkedHashMap<String, Object> map, Object expected, int count) {
        int matches = 0;
        for (int i = 0; i < count; i++) {
            if (map.get("eMod") == expected) matches++;
        }
        sink = matches;
    }

    private static void runSnapshot(
            Object snapshot, LinkedHashMap<String, Object> map, Object expected, int count) {
        int matches = 0;
        for (int i = 0; i < count; i++) {
            if (EventModMapSnapshotRuntime.unchanged(snapshot, map, expected)) matches++;
        }
        sink = matches;
    }

    private static void report(String label, Runnable work) {
        long started = System.nanoTime();
        work.run();
        double nanos = System.nanoTime() - started;
        System.out.printf(Locale.ROOT, "%s=%.3f ns/op%n", label, nanos / ITERATIONS);
    }
}
