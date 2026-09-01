package dev.starsector.preflight.agent;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.RandomAccess;

/** State and lightweight counters for exact vanilla campaign maintenance shortcuts. */
public final class CampaignEntityMaintenanceRuntime {
    static final String PLAN_ID = "campaign-entity-maintenance-v1";
    static final String DISABLED_PROPERTY = "preflight.campaign.entityMaintenance.disabled";
    static final String STABLE_SNAPSHOTS_DISABLED_PROPERTY =
            "preflight.campaign.stableSnapshots.disabled";
    static final int MARKET_CONDITIONS = 0;
    static final int MARKET_INDUSTRIES = 1;
    static final int PAUSED_MARKET_CONDITIONS = 2;
    static final int PAUSED_LOCATION_ENTITIES = 3;
    static final int PAUSED_LOCATION_SCRIPTS = 4;
    static final int ACTIVE_LOCATION_ENTITIES = 5;
    static final int ACTIVE_LOCATION_TOKENS = 6;
    static final int ACTIVE_ENGAGEMENT_ENTITIES = 7;

    private static final Object[] EMPTY_SNAPSHOT = new Object[0];
    private static final int MAX_SNAPSHOT_OWNERS = 512;
    private static final IdentityHashMap<List<?>, Object[]> STABLE_SNAPSHOTS =
            new IdentityHashMap<>();
    private static final IdentityHashMap<Object[], SnapshotIterator> STABLE_SNAPSHOT_CURSORS =
            new IdentityHashMap<>();

    private static volatile boolean enabled;
    private static volatile boolean telemetryEnabled;
    private static volatile boolean stableSnapshotsEnabled;
    private static volatile boolean entityScriptsInstalled;
    private static volatile boolean fleetViewInstalled;
    private static volatile boolean marketSnapshotsInstalled;
    private static volatile boolean memoryInstalled;
    private static volatile boolean pausedConditionsInstalled;
    private static volatile boolean pausedLocationSnapshotsInstalled;
    private static volatile boolean activeLocationSnapshotsInstalled;
    private static volatile boolean hyperspaceAutomatonInstalled;
    private static long emptyScriptLists;
    private static long nonEmptyScriptLists;
    private static long emptyMarketConditions;
    private static long nonEmptyMarketConditions;
    private static long emptyMarketIndustries;
    private static long nonEmptyMarketIndustries;
    private static long emptyMemoryExpirations;
    private static long nonEmptyMemoryExpirations;
    private static long emptyMemoryRequirements;
    private static long nonEmptyMemoryRequirements;
    private static long emptyMemoryIdRestorations;
    private static long nonEmptyMemoryIdRestorations;
    private static long emptyPausedMarketConditions;
    private static long nonEmptyPausedMarketConditions;
    private static long emptyPausedLocationEntities;
    private static long nonEmptyPausedLocationEntities;
    private static long emptyPausedLocationScripts;
    private static long nonEmptyPausedLocationScripts;
    private static long emptyActiveLocationEntities;
    private static long nonEmptyActiveLocationEntities;
    private static long emptyActiveLocationTokens;
    private static long nonEmptyActiveLocationTokens;
    private static long emptyActiveEngagementEntities;
    private static long nonEmptyActiveEngagementEntities;
    private static long stableSnapshotHits;
    private static long stableSnapshotRebuilds;
    private static long stableSnapshotComparedElements;
    private static long stableSnapshotEvictions;
    private static long stableSnapshotFailures;
    private static long stableSnapshotDelegations;

    private CampaignEntityMaintenanceRuntime() {
    }

    static void beginSession() {
        beginSession(true);
    }

    static void beginSession(boolean telemetryRequested) {
        enabled = !Boolean.getBoolean(DISABLED_PROPERTY);
        telemetryEnabled = telemetryRequested;
        stableSnapshotsEnabled = !Boolean.getBoolean(STABLE_SNAPSHOTS_DISABLED_PROPERTY);
        entityScriptsInstalled = false;
        fleetViewInstalled = false;
        marketSnapshotsInstalled = false;
        memoryInstalled = false;
        pausedConditionsInstalled = false;
        pausedLocationSnapshotsInstalled = false;
        activeLocationSnapshotsInstalled = false;
        hyperspaceAutomatonInstalled = false;
        emptyScriptLists = 0L;
        nonEmptyScriptLists = 0L;
        emptyMarketConditions = 0L;
        nonEmptyMarketConditions = 0L;
        emptyMarketIndustries = 0L;
        nonEmptyMarketIndustries = 0L;
        emptyMemoryExpirations = 0L;
        nonEmptyMemoryExpirations = 0L;
        emptyMemoryRequirements = 0L;
        nonEmptyMemoryRequirements = 0L;
        emptyMemoryIdRestorations = 0L;
        nonEmptyMemoryIdRestorations = 0L;
        emptyPausedMarketConditions = 0L;
        nonEmptyPausedMarketConditions = 0L;
        emptyPausedLocationEntities = 0L;
        nonEmptyPausedLocationEntities = 0L;
        emptyPausedLocationScripts = 0L;
        nonEmptyPausedLocationScripts = 0L;
        emptyActiveLocationEntities = 0L;
        nonEmptyActiveLocationEntities = 0L;
        emptyActiveLocationTokens = 0L;
        nonEmptyActiveLocationTokens = 0L;
        emptyActiveEngagementEntities = 0L;
        nonEmptyActiveEngagementEntities = 0L;
        synchronized (STABLE_SNAPSHOTS) {
            STABLE_SNAPSHOTS.clear();
        }
        synchronized (STABLE_SNAPSHOT_CURSORS) {
            STABLE_SNAPSHOT_CURSORS.clear();
        }
        stableSnapshotHits = 0L;
        stableSnapshotRebuilds = 0L;
        stableSnapshotComparedElements = 0L;
        stableSnapshotEvictions = 0L;
        stableSnapshotFailures = 0L;
        stableSnapshotDelegations = 0L;
    }

    static boolean enabled() {
        return enabled;
    }

    static void entityScriptsInstalled() {
        entityScriptsInstalled = true;
    }

    static void fleetViewInstalled() {
        fleetViewInstalled = true;
    }

    static void marketSnapshotsInstalled() {
        marketSnapshotsInstalled = true;
    }

    static void memoryInstalled() {
        memoryInstalled = true;
    }

    static void pausedConditionsInstalled() {
        pausedConditionsInstalled = true;
    }

    static void pausedLocationSnapshotsInstalled() {
        pausedLocationSnapshotsInstalled = true;
    }

    static void activeLocationSnapshotsInstalled() {
        activeLocationSnapshotsInstalled = true;
    }

    static void hyperspaceAutomatonInstalled() {
        hyperspaceAutomatonInstalled = true;
    }

    /** Counts live cells in vanilla's clamped 3x3 neighborhood, excluding the center cell. */
    public static int hyperspaceLiveNeighborCount(int[][] cells, int x, int y) {
        int minimumX = Math.max(0, x - 1);
        int maximumX = Math.min(x + 1, cells.length - 1);
        int minimumY = Math.max(0, y - 1);
        int maximumY = Math.min(y + 1, cells[0].length - 1);
        int count = 0;
        for (int columnIndex = minimumX; columnIndex <= maximumX; columnIndex++) {
            int[] column = cells[columnIndex];
            for (int rowIndex = minimumY; rowIndex <= maximumY; rowIndex++) {
                if ((columnIndex != x || rowIndex != y) && column[rowIndex] == 1) count++;
            }
        }
        return count;
    }

    public static void emptyScriptList() {
        if (telemetryEnabled) emptyScriptLists++;
    }

    public static void nonEmptyScriptList() {
        if (telemetryEnabled) nonEmptyScriptLists++;
    }

    /** Retains vanilla's stable snapshot while omitting its otherwise unused ArrayList wrapper. */
    public static Iterator<?> marketSnapshotIterator(List<?> values, int kind) {
        if (values.isEmpty()) {
            if (telemetryEnabled) {
                if (kind == MARKET_CONDITIONS) emptyMarketConditions++;
                if (kind == MARKET_INDUSTRIES) emptyMarketIndustries++;
                if (kind == PAUSED_MARKET_CONDITIONS) emptyPausedMarketConditions++;
            }
            return Collections.emptyIterator();
        }
        if (telemetryEnabled) {
            if (kind == MARKET_CONDITIONS) nonEmptyMarketConditions++;
            if (kind == MARKET_INDUSTRIES) nonEmptyMarketIndustries++;
            if (kind == PAUSED_MARKET_CONDITIONS) nonEmptyPausedMarketConditions++;
        }
        return stableSnapshotIterator(stableSnapshot(values));
    }

    public static boolean memoryExpirationsPresent(List<?> values) {
        if (values.isEmpty()) {
            if (telemetryEnabled) emptyMemoryExpirations++;
            return false;
        }
        if (telemetryEnabled) nonEmptyMemoryExpirations++;
        return true;
    }

    public static boolean memoryRequirementsPresent(Map<?, ?> values) {
        if (values.isEmpty()) {
            if (telemetryEnabled) emptyMemoryRequirements++;
            return false;
        }
        if (telemetryEnabled) nonEmptyMemoryRequirements++;
        return true;
    }

    /** Retains vanilla's stable key traversal while omitting its otherwise unused ArrayList. */
    public static Iterator<?> memoryIdSnapshotIterator(Map<?, ?> values) {
        if (values.isEmpty()) {
            if (telemetryEnabled) emptyMemoryIdRestorations++;
            return Collections.emptyIterator();
        }
        if (telemetryEnabled) nonEmptyMemoryIdRestorations++;
        return new SnapshotIterator(values.keySet().toArray());
    }

    /** Retains the stable location snapshot while omitting its otherwise unused ArrayList. */
    public static Object[] locationSnapshot(List<?> values, int kind) {
        if (values.isEmpty()) {
            if (telemetryEnabled) {
                if (kind == PAUSED_LOCATION_ENTITIES) emptyPausedLocationEntities++;
                if (kind == PAUSED_LOCATION_SCRIPTS) emptyPausedLocationScripts++;
                if (kind == ACTIVE_LOCATION_ENTITIES) emptyActiveLocationEntities++;
                if (kind == ACTIVE_LOCATION_TOKENS) emptyActiveLocationTokens++;
                if (kind == ACTIVE_ENGAGEMENT_ENTITIES) emptyActiveEngagementEntities++;
            }
            return EMPTY_SNAPSHOT;
        }
        if (telemetryEnabled) {
            if (kind == PAUSED_LOCATION_ENTITIES) nonEmptyPausedLocationEntities++;
            if (kind == PAUSED_LOCATION_SCRIPTS) nonEmptyPausedLocationScripts++;
            if (kind == ACTIVE_LOCATION_ENTITIES) nonEmptyActiveLocationEntities++;
            if (kind == ACTIVE_LOCATION_TOKENS) nonEmptyActiveLocationTokens++;
            if (kind == ACTIVE_ENGAGEMENT_ENTITIES) nonEmptyActiveEngagementEntities++;
        }
        return stableSnapshot(values);
    }

    /** Supplies an independent traversal cursor, reusing one only after observed exhaustion. */
    public static Iterator<?> locationSnapshotIterator(Object[] values) {
        return values.length == 0 ? Collections.emptyIterator() : stableSnapshotIterator(values);
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("enabled", enabled);
        result.put("telemetryEnabled", telemetryEnabled);
        result.put("stableSnapshotsEnabled", stableSnapshotsEnabled);
        result.put("entityScriptsInstalled", entityScriptsInstalled);
        result.put("fleetViewInstalled", fleetViewInstalled);
        result.put("marketSnapshotsInstalled", marketSnapshotsInstalled);
        result.put("memoryInstalled", memoryInstalled);
        result.put("pausedConditionsInstalled", pausedConditionsInstalled);
        result.put("pausedLocationSnapshotsInstalled", pausedLocationSnapshotsInstalled);
        result.put("activeLocationSnapshotsInstalled", activeLocationSnapshotsInstalled);
        result.put("hyperspaceAutomatonInstalled", hyperspaceAutomatonInstalled);
        result.put("emptyScriptLists", emptyScriptLists);
        result.put("nonEmptyScriptLists", nonEmptyScriptLists);
        result.put("emptyMarketConditions", emptyMarketConditions);
        result.put("nonEmptyMarketConditions", nonEmptyMarketConditions);
        result.put("emptyMarketIndustries", emptyMarketIndustries);
        result.put("nonEmptyMarketIndustries", nonEmptyMarketIndustries);
        result.put("emptyMemoryExpirations", emptyMemoryExpirations);
        result.put("nonEmptyMemoryExpirations", nonEmptyMemoryExpirations);
        result.put("emptyMemoryRequirements", emptyMemoryRequirements);
        result.put("nonEmptyMemoryRequirements", nonEmptyMemoryRequirements);
        result.put("emptyMemoryIdRestorations", emptyMemoryIdRestorations);
        result.put("nonEmptyMemoryIdRestorations", nonEmptyMemoryIdRestorations);
        result.put("emptyPausedMarketConditions", emptyPausedMarketConditions);
        result.put("nonEmptyPausedMarketConditions", nonEmptyPausedMarketConditions);
        result.put("emptyPausedLocationEntities", emptyPausedLocationEntities);
        result.put("nonEmptyPausedLocationEntities", nonEmptyPausedLocationEntities);
        result.put("emptyPausedLocationScripts", emptyPausedLocationScripts);
        result.put("nonEmptyPausedLocationScripts", nonEmptyPausedLocationScripts);
        result.put("emptyActiveLocationEntities", emptyActiveLocationEntities);
        result.put("nonEmptyActiveLocationEntities", nonEmptyActiveLocationEntities);
        result.put("emptyActiveLocationTokens", emptyActiveLocationTokens);
        result.put("nonEmptyActiveLocationTokens", nonEmptyActiveLocationTokens);
        result.put("emptyActiveEngagementEntities", emptyActiveEngagementEntities);
        result.put("nonEmptyActiveEngagementEntities", nonEmptyActiveEngagementEntities);
        result.put("stableSnapshotHits", stableSnapshotHits);
        result.put("stableSnapshotRebuilds", stableSnapshotRebuilds);
        result.put("stableSnapshotComparedElements", stableSnapshotComparedElements);
        synchronized (STABLE_SNAPSHOTS) {
            result.put("stableSnapshotOwners", STABLE_SNAPSHOTS.size());
        }
        synchronized (STABLE_SNAPSHOT_CURSORS) {
            result.put("stableSnapshotCursors", STABLE_SNAPSHOT_CURSORS.size());
        }
        result.put("stableSnapshotEvictions", stableSnapshotEvictions);
        result.put("stableSnapshotFailures", stableSnapshotFailures);
        result.put("stableSnapshotDelegations", stableSnapshotDelegations);
        return result;
    }

    private static Object[] stableSnapshot(List<?> values) {
        if (!stableSnapshotsEnabled) {
            if (telemetryEnabled) stableSnapshotDelegations++;
            return values.toArray();
        }
        synchronized (STABLE_SNAPSHOTS) {
            try {
                Object[] current = STABLE_SNAPSHOTS.get(values);
                if (current != null && matches(values, current)) {
                    if (telemetryEnabled) {
                        stableSnapshotHits++;
                        stableSnapshotComparedElements += current.length;
                    }
                    return current;
                }
                Object[] replacement = values.toArray();
                if (current == null && STABLE_SNAPSHOTS.size() >= MAX_SNAPSHOT_OWNERS) {
                    STABLE_SNAPSHOTS.clear();
                    if (telemetryEnabled) stableSnapshotEvictions++;
                }
                STABLE_SNAPSHOTS.put(values, replacement);
                if (telemetryEnabled) stableSnapshotRebuilds++;
                return replacement;
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable failure) {
                if (telemetryEnabled) stableSnapshotFailures++;
                // A stable cache is optional. Preserve the original per-call snapshot on doubt.
                return values.toArray();
            }
        }
    }

    private static boolean matches(List<?> values, Object[] snapshot) {
        if (!(values instanceof RandomAccess) || values.size() != snapshot.length) return false;
        for (int index = 0; index < snapshot.length; index++) {
            if (values.get(index) != snapshot[index]) return false;
        }
        return true;
    }

    /**
     * Reuses a cursor only after its prior traversal observed exhaustion. An overlapping or
     * reentrant traversal gets a private cursor, so neither pass can move the other's position.
     */
    private static Iterator<?> stableSnapshotIterator(Object[] values) {
        // A disabled stable-snapshot cache produces a new array for every pass, so retaining a
        // cursor for that one-shot identity would add state without creating a reuse opportunity.
        if (!stableSnapshotsEnabled) return new SnapshotIterator(values);
        synchronized (STABLE_SNAPSHOT_CURSORS) {
            SnapshotIterator cursor = STABLE_SNAPSHOT_CURSORS.get(values);
            if (cursor == null) {
                if (STABLE_SNAPSHOT_CURSORS.size() >= MAX_SNAPSHOT_OWNERS * 2) {
                    STABLE_SNAPSHOT_CURSORS.clear();
                }
                cursor = new SnapshotIterator(values);
                STABLE_SNAPSHOT_CURSORS.put(values, cursor);
                return cursor;
            }
            if (cursor.restartAfterExhaustion()) return cursor;
            return new SnapshotIterator(values);
        }
    }

    private static final class SnapshotIterator implements Iterator<Object> {
        private final Object[] values;
        private int next;
        private boolean exhausted;

        private SnapshotIterator(Object[] values) {
            this.values = values;
        }

        @Override
        public boolean hasNext() {
            if (next < values.length) return true;
            exhausted = true;
            return false;
        }

        @Override
        public Object next() {
            if (!hasNext()) throw new NoSuchElementException();
            return values[next++];
        }

        private boolean restartAfterExhaustion() {
            if (!exhausted) return false;
            next = 0;
            exhausted = false;
            return true;
        }
    }
}
