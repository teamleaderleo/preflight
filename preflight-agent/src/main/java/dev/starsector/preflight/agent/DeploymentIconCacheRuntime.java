package dev.starsector.preflight.agent;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Positive-only, mutation-tracked cache for the combat deployment member grid. */
public final class DeploymentIconCacheRuntime {
    static final String PLAN_ID = "deployment-member-icon-cache-v2";
    public static final String ENABLED_PROPERTY = "preflight.deployment.iconCache";

    private static volatile boolean installed;
    // UI owners are few and session-bounded; identity semantics are more important than weak-map
    // equals/hashCode behavior for an obfuscated UI hierarchy we do not control.
    private static final Map<Object, Index> INDEXES = new IdentityHashMap<>();
    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong DELEGATED = new AtomicLong();
    private static final AtomicLong ADDITIONS = new AtomicLong();
    private static final AtomicLong REMOVALS = new AtomicLong();
    private static final AtomicLong CLEARS = new AtomicLong();
    private static final AtomicLong FALLBACK_RECORDS = new AtomicLong();

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
    public static Object lookup(Object owner, Object member) {
        if (!enabled() || owner == null || member == null) {
            return null;
        }
        try {
            synchronized (DeploymentIconCacheRuntime.class) {
                Index index = INDEXES.get(owner);
                if (index == null) {
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

    /** Records a positive answer returned by the preserved original lookup. */
    public static void record(Object owner, Object member, Object icon) {
        if (!enabled() || owner == null || member == null || icon == null) {
            return;
        }
        try {
            synchronized (DeploymentIconCacheRuntime.class) {
                Index index = INDEXES.computeIfAbsent(owner, ignored -> new Index());
                index.icons.put(member, icon);
                FALLBACK_RECORDS.incrementAndGet();
            }
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // The caller has already obtained the shipped answer. A cache write is dispensable.
        }
    }

    /** Invalidates a member after the exact reviewed owner successfully adds an icon for it. */
    public static void added(Object owner, Object member) {
        if (!enabled() || owner == null || member == null) {
            return;
        }
        try {
            synchronized (DeploymentIconCacheRuntime.class) {
                Index index = INDEXES.get(owner);
                if (index != null) {
                    // Do not install the newly-added icon directly. If a duplicate member exists,
                    // vanilla returns the first icon already in the grid. The next query therefore
                    // runs the preserved scan once and records its authoritative answer.
                    index.icons.remove(member);
                }
                ADDITIONS.incrementAndGet();
            }
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // The preserved lookup remains available.
        }
    }

    /** Invalidates the removed member; a duplicate, if any, is repopulated by the original scan. */
    public static void removed(Object owner, Object member) {
        if (!enabled() || owner == null || member == null) {
            return;
        }
        try {
            synchronized (DeploymentIconCacheRuntime.class) {
                Index index = INDEXES.get(owner);
                if (index != null) {
                    index.icons.remove(member);
                }
                REMOVALS.incrementAndGet();
            }
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // The preserved lookup remains available.
        }
    }

    /** Drops every answer after the exact reviewed owner successfully clears its grid. */
    public static void cleared(Object owner) {
        if (!enabled() || owner == null) {
            return;
        }
        try {
            synchronized (DeploymentIconCacheRuntime.class) {
                INDEXES.remove(owner);
                CLEARS.incrementAndGet();
            }
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // The preserved lookup remains available.
        }
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", PLAN_ID);
        values.put("installed", installed);
        values.put("enabled", enabled());
        values.put("validationStrategy", "instrumented-owner-mutations");
        values.put("hits", HITS.get());
        values.put("delegated", DELEGATED.get());
        values.put("additions", ADDITIONS.get());
        values.put("removals", REMOVALS.get());
        values.put("clears", CLEARS.get());
        values.put("fallbackRecords", FALLBACK_RECORDS.get());
        // Retain the old counters so report consumers can compare schemas directly. The new
        // strategy deliberately performs neither whole-list snapshots nor reference validation.
        values.put("snapshots", 0L);
        values.put("validatedReferences", 0L);
        return values;
    }

    static void beginSession() {
        installed = false;
        synchronized (DeploymentIconCacheRuntime.class) {
            INDEXES.clear();
        }
        HITS.set(0);
        DELEGATED.set(0);
        ADDITIONS.set(0);
        REMOVALS.set(0);
        CLEARS.set(0);
        FALLBACK_RECORDS.set(0);
    }

    private static final class Index {
        private final IdentityHashMap<Object, Object> icons = new IdentityHashMap<>();
    }
}
