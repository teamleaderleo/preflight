package dev.starsector.preflight.agent;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reuses core economy-group snapshots only inside the exact reviewed Nexerelin cache rebuild.
 *
 * <p>The target method and the core list supplier are transformed independently. Neither half can
 * activate the optimization alone. A shadow mode calls the original supplier every time and checks
 * exact list order and element identity before a performance run is allowed.
 */
public final class NexMarketListScopeRuntime {
    static final String PLAN_ID = "nexerelin-market-list-scope-v1";
    static final String ENABLED_PROPERTY = "preflight.campaign.nexMarketListScope";
    static final String SHADOW_PROPERTY = "preflight.campaign.nexMarketListScope.shadow";
    static final String DISABLED_PROPERTY = "preflight.campaign.nexMarketListScope.disabled";

    private static final ThreadLocal<Scope> current = new ThreadLocal<>();

    private static volatile boolean requested;
    private static volatile boolean shadowRequested;
    private static volatile boolean healthy;
    private static volatile boolean nexInstalled;
    private static volatile boolean coreInstalled;
    private static long scopesBegun;
    private static long scopesEnded;
    private static long nestedScopes;
    private static long outsideScopeDeclines;
    private static long misses;
    private static long stores;
    private static long hits;
    private static long shadowMatches;
    private static long shadowMismatches;
    private static long failures;
    private static long maximumEntries;

    private NexMarketListScopeRuntime() {
    }

    static void beginSession() {
        requested = Boolean.getBoolean(ENABLED_PROPERTY)
                && !Boolean.getBoolean(DISABLED_PROPERTY);
        shadowRequested = Boolean.getBoolean(SHADOW_PROPERTY)
                && !Boolean.getBoolean(DISABLED_PROPERTY);
        healthy = true;
        nexInstalled = false;
        coreInstalled = false;
        scopesBegun = scopesEnded = nestedScopes = outsideScopeDeclines = 0L;
        misses = stores = hits = shadowMatches = shadowMismatches = failures = 0L;
        maximumEntries = 0L;
        current.remove();
    }

    static void installedNex() {
        nexInstalled = true;
    }

    static void installedCore() {
        coreInstalled = true;
    }

    static boolean configured() {
        return requested || shadowRequested;
    }

    /** Cheap guard used by the transformed core method before consulting the scope cache. */
    public static boolean inScope() {
        return active() && current.get() != null;
    }

    /** Opens a current-thread scope. The wrapper always balances this in a finally block. */
    public static void beginScope() {
        if (!active()) return;
        Scope scope = current.get();
        if (scope == null) {
            current.set(new Scope());
            scopesBegun++;
        } else {
            scope.depth++;
            nestedScopes++;
        }
    }

    /** Closes one nested level and drops every retained game reference at the outer boundary. */
    public static void endScope() {
        Scope scope = current.get();
        if (scope == null) return;
        scope.depth--;
        if (scope.depth <= 0) {
            current.remove();
            scopesEnded++;
        }
    }

    /** Returns a reviewed snapshot in candidate mode, or null to execute the original supplier. */
    public static Object reuse(Object owner) {
        if (!active()) return null;
        Scope scope = current.get();
        if (scope == null) {
            outsideScopeDeclines++;
            return null;
        }
        if (shadowRequested) return null;
        try {
            Object cached = scope.snapshots.get(owner);
            if (cached != null) hits++;
            else misses++;
            return cached;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            failOpenToOriginal();
            return null;
        }
    }

    /** Stores a first result, or validates a fresh result against the stored shadow snapshot. */
    public static Object observe(Object owner, Object supplied) {
        if (!active()) return supplied;
        Scope scope = current.get();
        if (scope == null || owner == null || !(supplied instanceof List<?> fresh)) return supplied;
        try {
            Object existing = scope.snapshots.get(owner);
            if (existing == null) {
                scope.snapshots.put(owner, fresh);
                stores++;
                maximumEntries = Math.max(maximumEntries, scope.snapshots.size());
                return supplied;
            }
            if (!shadowRequested) return supplied;
            if (sameIdentityOrder((List<?>) existing, fresh)) {
                shadowMatches++;
            } else {
                shadowMismatches++;
                healthy = false;
                current.remove();
            }
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            failOpenToOriginal();
        }
        return supplied;
    }

    static synchronized Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", PLAN_ID);
        values.put("requested", requested);
        values.put("shadowRequested", shadowRequested);
        values.put("enabled", active() && requested && !shadowRequested);
        values.put("shadowEnabled", active() && shadowRequested);
        values.put("healthy", healthy);
        values.put("nexInstalled", nexInstalled);
        values.put("coreInstalled", coreInstalled);
        values.put("scopesBegun", scopesBegun);
        values.put("scopesEnded", scopesEnded);
        values.put("nestedScopes", nestedScopes);
        values.put("outsideScopeDeclines", outsideScopeDeclines);
        values.put("misses", misses);
        values.put("stores", stores);
        values.put("hits", hits);
        values.put("shadowMatches", shadowMatches);
        values.put("shadowMismatches", shadowMismatches);
        values.put("failures", failures);
        values.put("maximumEntries", maximumEntries);
        values.put("strategy", "exact-nex-scope-identity-keyed-market-list-snapshot");
        return values;
    }

    static void reset() {
        requested = false;
        shadowRequested = false;
        healthy = true;
        nexInstalled = false;
        coreInstalled = false;
        current.remove();
    }

    private static boolean active() {
        return healthy && nexInstalled && coreInstalled && (requested || shadowRequested);
    }

    private static boolean sameIdentityOrder(List<?> expected, List<?> actual) {
        if (expected.size() != actual.size()) return false;
        for (int index = 0; index < expected.size(); index++) {
            if (expected.get(index) != actual.get(index)) return false;
        }
        return true;
    }

    private static void failOpenToOriginal() {
        failures++;
        healthy = false;
        current.remove();
    }

    private static final class Scope {
        final IdentityHashMap<Object, Object> snapshots = new IdentityHashMap<>();
        int depth = 1;
    }
}
