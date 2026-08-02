package dev.starsector.preflight.agent;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An index for {@code BaseLocation.getEntityById}, which is otherwise three linear scans.
 *
 * <p>The shipped method has a fast path and a fallback, and the fallback is reached on <em>any</em>
 * failure of the fast path — including a plain map miss. It scans every entity in the location and
 * allocates two lowercased {@code String}s per entity per lookup. {@code CampaignEngine} then runs
 * that fallback over hyperspace and every star system in turn, so one unresolved id scans the whole
 * sector: <b>1.49 ms measured on a 100 MB save</b>, against a 16.7 ms frame. And the sector-wide map
 * it would otherwise hit is nulled only in {@code CampaignEngine}'s constructor, so every entity
 * created after the first lookup of a session — every fleet Nexerelin spawns — is permanently
 * outside it. See {@code docs/evidence/2026-08-02-a-failed-lookup-scans-the-sector.md}.
 *
 * <p><b>This can only ever return what the shipped method would.</b> It answers from an index built
 * out of the location's own {@code getAllEntities()}, in the same order and with the same
 * precedence — exact id first, then case-insensitive, first match winning, exactly as the scan does.
 * When it has no answer it returns null and the caller runs the original method verbatim. The
 * caller then confirms membership through {@code ObjectRepository.contains}, the game's own
 * {@code forFastContains} HashSet, before accepting anything from here.
 *
 * <p><b>Why a stale index cannot produce a wrong answer.</b> The index is rebuilt whenever the
 * entity list's size changes, which misses one case: an add and a remove between two lookups. In
 * that state the index can hold an entity that has since been removed, and can lack one just added.
 * The first is caught by the membership check at the call site, which reads live repository state.
 * The second is a miss, and a miss falls through to the original. Both directions degrade to
 * shipped behaviour rather than to a wrong entity.
 */
public final class EntityLookupRuntime {
    static final String PLAN_ID = "campaign-entity-index-v1";

    /** Absent or false means {@link #lookup} declines everything and the shipped method runs. */
    public static final String ENABLED_PROPERTY = "preflight.campaign.entityIndex";

    /**
     * A location with fewer entities than this is not worth indexing: the scan it replaces is short,
     * and the index would cost more to build than it saves. The median location in a live save holds
     * 33 entities and in a late-game one 185, so this excludes only the genuinely tiny ones.
     */
    private static final int MINIMUM_ENTITIES = 16;

    private static volatile boolean INSTALLED;

    private static final Map<Object, Index> INDEXES = new WeakHashMap<>();
    private static final ClassValue<Method> GET_ID = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> type) {
            try {
                Method method = type.getMethod("getId");
                method.setAccessible(true);
                return method;
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable error) {
                return null;
            }
        }
    };

    private static final AtomicLong SERVED = new AtomicLong();
    private static final AtomicLong DECLINED = new AtomicLong();
    private static final AtomicLong REBUILDS = new AtomicLong();
    private static final AtomicLong INDEXED_ENTITIES = new AtomicLong();

    private EntityLookupRuntime() {
    }

    static boolean ready() {
        return true;
    }

    /** Recorded when the wrapper is actually woven, so the counters cannot describe a plan that is not installed. */
    static void indexInstalled() {
        INSTALLED = true;
    }

    public static boolean enabled() {
        return INSTALLED && Boolean.getBoolean(ENABLED_PROPERTY);
    }

    /**
     * The entity this location would resolve {@code id} to, or null to run the shipped method.
     *
     * <p>Called from inside {@code BaseLocation}, which is why {@code entities} arrives already
     * fetched: {@code getAllEntities()} is a HashMap lookup returning a cached list, and doing it in
     * the caller keeps this class free of any compile-time dependency on the game.
     *
     * @param entities the location's own entity list
     * @param location the location, used only as an identity key for its index
     * @param id the id being resolved
     */
    public static Object lookup(List<?> entities, Object location, String id) {
        if (!enabled() || entities == null || location == null || id == null) {
            return null;
        }
        try {
            int size = entities.size();
            if (size < MINIMUM_ENTITIES) {
                DECLINED.incrementAndGet();
                return null;
            }
            Index index = indexFor(location, entities, size);
            if (index == null) {
                DECLINED.incrementAndGet();
                return null;
            }
            Object found = index.exact.get(id);
            if (found == null) {
                found = index.lowercase.get(id.toLowerCase(Locale.ROOT));
            }
            if (found == null) {
                DECLINED.incrementAndGet();
                return null;
            }
            SERVED.incrementAndGet();
            return found;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            // Anything unexpected costs one shipped lookup, which is the behaviour without us.
            DECLINED.incrementAndGet();
            return null;
        }
    }

    private static synchronized Index indexFor(Object location, List<?> entities, int size)
            throws ReflectiveOperationException {
        Index existing = INDEXES.get(location);
        if (existing != null && existing.size == size) {
            return existing;
        }
        Index rebuilt = build(entities, size);
        if (rebuilt == null) {
            INDEXES.remove(location);
            return null;
        }
        INDEXES.put(location, rebuilt);
        REBUILDS.incrementAndGet();
        INDEXED_ENTITIES.addAndGet(size);
        return rebuilt;
    }

    /**
     * Builds both maps in list order.
     *
     * <p>{@code rebuildIDToEntityMap} puts every entity in order, so the last duplicate wins the
     * exact map; the fallback scan returns the first match, so the first duplicate wins the
     * case-insensitive one. Getting that backwards would change which entity a duplicated id
     * resolves to, which is the one way an index like this can be wrong while looking right.
     */
    private static Index build(List<?> entities, int size) throws ReflectiveOperationException {
        Map<String, Object> exact = new HashMap<>(Math.max(16, size * 2));
        Map<String, Object> lowercase = new LinkedHashMap<>(Math.max(16, size * 2));
        for (Object entity : entities) {
            if (entity == null) {
                continue;
            }
            Method getId = GET_ID.get(entity.getClass());
            if (getId == null) {
                return null;
            }
            Object raw = getId.invoke(entity);
            if (!(raw instanceof String id)) {
                continue;
            }
            exact.put(id, entity);
            lowercase.putIfAbsent(id.toLowerCase(Locale.ROOT), entity);
        }
        return new Index(exact, lowercase, size);
    }

    /** Counters for the adapter report; all zero when the plan is not installed or not enabled. */
    static Map<String, Object> counters() {
        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put("installed", INSTALLED);
        counters.put("enabled", enabled());
        counters.put("served", SERVED.get());
        counters.put("declined", DECLINED.get());
        counters.put("rebuilds", REBUILDS.get());
        counters.put("indexedEntities", INDEXED_ENTITIES.get());
        return counters;
    }

    /** Test seam; the game never resets a session. */
    static void beginSession() {
        INSTALLED = false;
        synchronized (EntityLookupRuntime.class) {
            INDEXES.clear();
        }
        SERVED.set(0);
        DECLINED.set(0);
        REBUILDS.set(0);
        INDEXED_ENTITIES.set(0);
    }

    private record Index(Map<String, Object> exact, Map<String, Object> lowercase, int size) {
    }
}
