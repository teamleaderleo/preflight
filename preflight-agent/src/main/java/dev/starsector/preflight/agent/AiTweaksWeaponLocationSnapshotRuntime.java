package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Reuses one weapon-location value only while AI Tweaks selects one target synchronously. */
public final class AiTweaksWeaponLocationSnapshotRuntime {
    static final String PLAN_ID = "aitweaks-weapon-location-selection-snapshot-v1";
    static final String ENABLED_PROPERTY = "preflight.combat.aiTweaksSelectionLocation";

    private static final ThreadLocal<Context> CONTEXT = new ThreadLocal<>();
    private static final AtomicLong selectionContexts = new AtomicLong();
    private static final AtomicLong locationMisses = new AtomicLong();
    private static final AtomicLong locationHits = new AtomicLong();
    private static final AtomicLong abandonedContexts = new AtomicLong();

    private static volatile boolean enabled;
    private static volatile boolean autofireInstalled;
    private static volatile boolean weaponHandleInstalled;
    private static volatile boolean activePlan;

    private AiTweaksWeaponLocationSnapshotRuntime() {
    }

    static void beginSession() {
        enabled = Boolean.getBoolean(ENABLED_PROPERTY);
        autofireInstalled = false;
        weaponHandleInstalled = false;
        activePlan = false;
        selectionContexts.set(0L);
        locationMisses.set(0L);
        locationHits.set(0L);
        abandonedContexts.set(0L);
        CONTEXT.remove();
    }

    static void autofireInstalled() {
        autofireInstalled = true;
        refreshActivePlan();
    }

    static void weaponHandleInstalled() {
        weaponHandleInstalled = true;
        refreshActivePlan();
    }

    private static void refreshActivePlan() {
        activePlan = enabled && autofireInstalled && weaponHandleInstalled;
    }

    /** Opens the narrow synchronous region in which the reviewed getter calls share one value. */
    public static void begin(Object weapon) {
        if (!activePlan) return;
        Context context = CONTEXT.get();
        if (context == null) {
            context = new Context();
            CONTEXT.set(context);
        } else if (context.active) {
            finish(context);
            abandonedContexts.incrementAndGet();
        }
        context.active = true;
        context.weapon = weapon;
        context.location = null;
        context.hits = 0L;
        context.misses = 0L;
        selectionContexts.incrementAndGet();
    }

    /** Returns the saved value only for the same weapon in the active selection on this thread. */
    public static Object cachedLocation(Object weapon) {
        if (!activePlan) return null;
        Context context = CONTEXT.get();
        if (context == null || !context.active || context.weapon != weapon) return null;
        if (context.location == null) {
            context.misses++;
            return null;
        }
        context.hits++;
        return context.location;
    }

    /** Retains the original getter result after the first active cache miss. */
    public static void rememberLocation(Object weapon, Object location) {
        if (!activePlan || location == null) return;
        Context context = CONTEXT.get();
        if (context != null && context.active
                && context.weapon == weapon && context.location == null) {
            context.location = location;
        }
    }

    /** Closes the target-selection region without retaining game objects. */
    public static void end() {
        if (!activePlan) return;
        Context context = CONTEXT.get();
        if (context == null || !context.active) return;
        finish(context);
    }

    private static void finish(Context context) {
        locationHits.addAndGet(context.hits);
        locationMisses.addAndGet(context.misses);
        context.active = false;
        context.weapon = null;
        context.location = null;
        context.hits = 0L;
        context.misses = 0L;
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("enabled", enabled);
        result.put("installed", autofireInstalled && weaponHandleInstalled);
        result.put("autofireInstalled", autofireInstalled);
        result.put("weaponHandleInstalled", weaponHandleInstalled);
        result.put("selectionContexts", selectionContexts.get());
        result.put("locationMisses", locationMisses.get());
        result.put("locationHits", locationHits.get());
        result.put("abandonedContexts", abandonedContexts.get());
        return result;
    }

    private static final class Context {
        private boolean active;
        private Object weapon;
        private Object location;
        private long hits;
        private long misses;
    }
}
