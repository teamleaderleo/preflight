package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reuses LunaLib's internal renderer snapshot until either source list actually changes. */
public final class LunaCampaignRendererSnapshotRuntime {
    static final String PLAN_ID = "lunalib-campaign-renderer-snapshot-v1";
    private static final int MAX_OWNERS = 8;

    private static final IdentityHashMap<Object, Snapshot> snapshots = new IdentityHashMap<>();
    private static long requests;
    private static long hits;
    private static long rebuilds;
    private static long comparedElements;
    private static long copiedElements;
    private static long evictions;
    private static long failures;
    private static boolean scriptInstalled;
    private static boolean entityInstalled;

    private LunaCampaignRendererSnapshotRuntime() {
    }

    static synchronized void scriptInstalled() {
        scriptInstalled = true;
    }

    static synchronized void entityInstalled() {
        entityInstalled = true;
    }

    /**
     * Returns a private snapshot used only by the rewritten renderer entity.
     *
     * <p>LunaLib's public {@code getRenderers()} method intentionally remains untouched, so callers
     * that expect a fresh mutable list retain that behavior. A nested renderer mutation causes a
     * new snapshot to replace the cache while an outer iterator safely retains its old snapshot.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static synchronized ArrayList snapshot(
            Object owner, List transientRenderers, List persistentRenderers) {
        requests++;
        try {
            Snapshot current = snapshots.get(owner);
            if (current != null && current.matches(transientRenderers, persistentRenderers)) {
                hits++;
                comparedElements += current.combined.size();
                return current.combined;
            }
            ArrayList combined = originalSnapshot(transientRenderers, persistentRenderers);
            if (current == null && snapshots.size() >= MAX_OWNERS) {
                // Campaign renderer scripts are normally singletons. Keep unexpected churn bounded
                // rather than retaining every historical campaign through renderer values.
                snapshots.clear();
                evictions++;
            }
            snapshots.put(owner, new Snapshot(transientRenderers, persistentRenderers, combined));
            rebuilds++;
            copiedElements += combined.size();
            return combined;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            failures++;
            // Preserve LunaLib's original allocation/copy behavior if the cache cannot prove a hit.
            return originalSnapshot(transientRenderers, persistentRenderers);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ArrayList originalSnapshot(List transientRenderers, List persistentRenderers) {
        ArrayList combined = new ArrayList();
        combined.addAll(transientRenderers);
        combined.addAll(persistentRenderers);
        return combined;
    }

    static synchronized Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("installed", scriptInstalled && entityInstalled);
        result.put("scriptInstalled", scriptInstalled);
        result.put("entityInstalled", entityInstalled);
        result.put("requests", requests);
        result.put("hits", hits);
        result.put("rebuilds", rebuilds);
        result.put("comparedElements", comparedElements);
        result.put("copiedElements", copiedElements);
        result.put("evictions", evictions);
        result.put("failures", failures);
        result.put("owners", snapshots.size());
        return result;
    }

    static synchronized void reset() {
        snapshots.clear();
        requests = 0;
        hits = 0;
        rebuilds = 0;
        comparedElements = 0;
        copiedElements = 0;
        evictions = 0;
        failures = 0;
        scriptInstalled = false;
        entityInstalled = false;
    }

    private static final class Snapshot {
        private final List<?> transientSource;
        private final List<?> persistentSource;
        private final ArrayList<?> combined;

        private Snapshot(
                List<?> transientSource, List<?> persistentSource, ArrayList<?> combined) {
            this.transientSource = transientSource;
            this.persistentSource = persistentSource;
            this.combined = combined;
        }

        private boolean matches(List<?> transientRenderers, List<?> persistentRenderers) {
            if (transientSource != transientRenderers || persistentSource != persistentRenderers) {
                return false;
            }
            int transientSize = transientRenderers.size();
            int persistentSize = persistentRenderers.size();
            if (combined.size() != transientSize + persistentSize) {
                return false;
            }
            for (int i = 0; i < transientSize; i++) {
                if (combined.get(i) != transientRenderers.get(i)) return false;
            }
            for (int i = 0; i < persistentSize; i++) {
                if (combined.get(transientSize + i) != persistentRenderers.get(i)) return false;
            }
            return true;
        }
    }
}
