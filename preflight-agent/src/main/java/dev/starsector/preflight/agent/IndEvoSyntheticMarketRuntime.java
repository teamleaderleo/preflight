package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Telemetry for exact IndEvo guards around world side effects on Codex's synthetic markets. */
public final class IndEvoSyntheticMarketRuntime {
    static final String PLAN_ID = "indevo-synthetic-market-safety-v1";
    private static final Map<String, AtomicLong> BY_SITE = new ConcurrentHashMap<>();
    private static final AtomicLong BYPASSES = new AtomicLong();
    private static final AtomicLong INSTALLED_CLASSES = new AtomicLong();

    private IndEvoSyntheticMarketRuntime() {
    }

    static void beginSession() {
        BY_SITE.clear();
        BYPASSES.set(0L);
        INSTALLED_CLASSES.set(0L);
    }

    static void installed() {
        INSTALLED_CLASSES.incrementAndGet();
    }

    /** Records only the null-world branch; real markets continue through untouched mod code. */
    public static void bypass(String site) {
        BYPASSES.incrementAndGet();
        BY_SITE.computeIfAbsent(site == null ? "unknown" : site, ignored -> new AtomicLong())
                .incrementAndGet();
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> sites = new LinkedHashMap<>();
        BY_SITE.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sites.put(entry.getKey(), entry.getValue().get()));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", PLAN_ID);
        values.put("installedClasses", INSTALLED_CLASSES.get());
        values.put("bypasses", BYPASSES.get());
        values.put("bySite", sites);
        return values;
    }
}
