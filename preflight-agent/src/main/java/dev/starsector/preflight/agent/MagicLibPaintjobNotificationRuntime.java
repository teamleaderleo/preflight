package dev.starsector.preflight.agent;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Maintains an invalidated set snapshot for MagicLib's private notification list. */
public final class MagicLibPaintjobNotificationRuntime {
    static final String PLAN_ID = "magiclib-paintjob-notification-set-v1";

    private static final AtomicLong notified = new AtomicLong();
    private static final AtomicLong notNotified = new AtomicLong();
    private static final AtomicLong rebuilds = new AtomicLong();
    private static final AtomicLong mutations = new AtomicLong();
    private static final AtomicLong delegated = new AtomicLong();
    private static final AtomicLong failures = new AtomicLong();
    private static volatile Snapshot snapshot;
    private static volatile boolean installed;

    private MagicLibPaintjobNotificationRuntime() {
    }

    static void installed() {
        installed = true;
    }

    public static boolean contains(List<?> values, Object value) {
        try {
            Snapshot current = snapshot;
            if (current == null || current.source != values) {
                current = rebuild(values);
            }
            boolean found = current.values.contains(value);
            (found ? notified : notNotified).incrementAndGet();
            return found;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            failures.incrementAndGet();
            delegated.incrementAndGet();
            return values.contains(value);
        }
    }

    public static void mutated() {
        mutations.incrementAndGet();
        snapshot = null;
    }

    public static boolean mutated(boolean result) {
        mutated();
        return result;
    }

    private static synchronized Snapshot rebuild(List<?> values) {
        Snapshot current = snapshot;
        if (current == null || current.source != values) {
            current = new Snapshot(values, new HashSet<>(values));
            snapshot = current;
            rebuilds.incrementAndGet();
        }
        return current;
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("installed", installed);
        result.put("notified", notified.get());
        result.put("notNotified", notNotified.get());
        result.put("rebuilds", rebuilds.get());
        result.put("mutations", mutations.get());
        result.put("delegated", delegated.get());
        result.put("failures", failures.get());
        return result;
    }

    static void reset() {
        snapshot = null;
        installed = false;
        notified.set(0);
        notNotified.set(0);
        rebuilds.set(0);
        mutations.set(0);
        delegated.set(0);
        failures.set(0);
    }

    private record Snapshot(List<?> source, Set<?> values) {
    }
}
