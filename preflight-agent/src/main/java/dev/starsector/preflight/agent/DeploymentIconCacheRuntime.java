package dev.starsector.preflight.agent;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Positive-only, snapshot-validated cache for the combat deployment member grid. */
public final class DeploymentIconCacheRuntime {
    static final String PLAN_ID = "deployment-member-icon-cache-v1";
    public static final String ENABLED_PROPERTY = "preflight.deployment.iconCache";

    private static volatile boolean installed;
    // UI owners are few and session-bounded; identity semantics are more important than weak-map
    // equals/hashCode behavior for an obfuscated UI hierarchy we do not control.
    private static final Map<Object, Index> INDEXES = new IdentityHashMap<>();
    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong DELEGATED = new AtomicLong();
    private static final AtomicLong SNAPSHOTS = new AtomicLong();
    private static final AtomicLong VALIDATED_REFERENCES = new AtomicLong();

    private DeploymentIconCacheRuntime() {
    }

    static boolean ready() {
        return true;
    }

    static void installed() {
        installed = true;
    }

    public static boolean enabled() {
        return installed && Boolean.getBoolean(ENABLED_PROPERTY);
    }

    /** Returns a cached positive candidate, or null so the preserved scan runs. */
    public static Object lookup(Object owner, Object member, List<?> members, List<?> items) {
        if (!enabled() || owner == null || member == null || members == null || items == null) {
            return null;
        }
        try {
            synchronized (DeploymentIconCacheRuntime.class) {
                Index index = INDEXES.get(owner);
                if (index == null || !current(index, members, items)) {
                    DELEGATED.incrementAndGet();
                    return null;
                }
                Object icon = index.icons.get(member);
                if (icon == null) {
                    DELEGATED.incrementAndGet();
                    return null;
                }
                HITS.incrementAndGet();
                return icon;
            }
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            DELEGATED.incrementAndGet();
            return null;
        }
    }

    /** Records only the original method's positive answer against exact live-list snapshots. */
    public static void record(
            Object owner, Object member, Object icon, List<?> members, List<?> items) {
        if (!enabled() || owner == null || member == null || icon == null
                || members == null || items == null) {
            return;
        }
        try {
            synchronized (DeploymentIconCacheRuntime.class) {
                Index index = INDEXES.get(owner);
                if (index == null || !current(index, members, items)) {
                    index = new Index(
                            members.toArray(), items.toArray(), new IdentityHashMap<>());
                    INDEXES.put(owner, index);
                    SNAPSHOTS.incrementAndGet();
                }
                index.icons.put(member, icon);
            }
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // The caller has already obtained the shipped answer. A cache write is dispensable.
        }
    }

    private static boolean current(Index index, List<?> members, List<?> items) {
        if (members.size() != index.members.length || items.size() != index.items.length) {
            return false;
        }
        long checked = 0;
        for (int i = 0; i < index.members.length; i++) {
            checked++;
            if (members.get(i) != index.members[i]) {
                VALIDATED_REFERENCES.addAndGet(checked);
                return false;
            }
        }
        for (int i = 0; i < index.items.length; i++) {
            checked++;
            if (items.get(i) != index.items[i]) {
                VALIDATED_REFERENCES.addAndGet(checked);
                return false;
            }
        }
        VALIDATED_REFERENCES.addAndGet(checked);
        return true;
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", PLAN_ID);
        values.put("installed", installed);
        values.put("enabled", enabled());
        values.put("hits", HITS.get());
        values.put("delegated", DELEGATED.get());
        values.put("snapshots", SNAPSHOTS.get());
        values.put("validatedReferences", VALIDATED_REFERENCES.get());
        return values;
    }

    static void beginSession() {
        installed = false;
        synchronized (DeploymentIconCacheRuntime.class) {
            INDEXES.clear();
        }
        HITS.set(0);
        DELEGATED.set(0);
        SNAPSHOTS.set(0);
        VALIDATED_REFERENCES.set(0);
    }

    private record Index(Object[] members, Object[] items, IdentityHashMap<Object, Object> icons) {
    }
}
