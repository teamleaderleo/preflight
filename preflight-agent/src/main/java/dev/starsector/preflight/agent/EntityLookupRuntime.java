package dev.starsector.preflight.agent;

import java.lang.reflect.Method;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * A mutation-tracked index for {@code BaseLocation.getEntityById}.
 *
 * <p>The shipped miss path scans every entity and lowercases both ids. Version 2 replaced that work
 * with maps but still walked every live entity before every answer to prove its snapshot current.
 * Version 3 gives the repository's {@code SectorEntityToken} list a mutation generation and tracks
 * the reviewed base {@code setId} method separately, so ordinary validation is two O(1) counter
 * comparisons. Direct list and sub-list edits increment the list generation too. If a custom entity
 * overrides the reviewed setter, its location retains the complete identity/id scan. All three
 * exact transformations must install before the gate can open, and any uncertainty delegates to the
 * preserved method.
 */
public final class EntityLookupRuntime {
    static final String PLAN_ID = "campaign-entity-index-v3";
    public static final String ENABLED_PROPERTY = "preflight.campaign.entityIndex";

    private static final int MINIMUM_ENTITIES = 16;
    private static final String BASE_ENTITY = "com.fs.starfarer.campaign.BaseCampaignEntity";
    private static final String LOCATION_TOKEN =
            "com.fs.starfarer.campaign.BaseLocation$LocationToken";
    private static final String SECTOR_ENTITY_TOKEN =
            "com.fs.starfarer.api.campaign.SectorEntityToken";
    private static final Object MISSING = new Object();

    private static volatile boolean LOCATION_INSTALLED;
    private static volatile boolean REPOSITORY_INSTALLED;
    private static volatile boolean ID_MUTATION_INSTALLED;

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
    private static final ClassValue<Boolean> TRACKED_ID_MUTATION = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            if (LOCATION_TOKEN.equals(type.getName())) {
                return true;
            }
            try {
                Method setter = type.getMethod("setId", String.class);
                return BASE_ENTITY.equals(setter.getDeclaringClass().getName());
            } catch (NoSuchMethodException untracked) {
                return false;
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable uncertainty) {
                return false;
            }
        }
    };

    private static final AtomicLong ID_EPOCH = new AtomicLong();
    private static final AtomicLong SERVED = new AtomicLong();
    private static final AtomicLong MISSING_SERVED = new AtomicLong();
    private static final AtomicLong DECLINED = new AtomicLong();
    private static final AtomicLong REBUILDS = new AtomicLong();
    private static final AtomicLong INDEXED_ENTITIES = new AtomicLong();
    private static final AtomicLong FAST_VALIDATIONS = new AtomicLong();
    private static final AtomicLong DEEP_VALIDATIONS = new AtomicLong();
    private static final AtomicLong VALIDATED_REFERENCES = new AtomicLong();
    private static final AtomicLong LIST_MUTATIONS = new AtomicLong();
    private static final AtomicLong ID_MUTATIONS = new AtomicLong();
    private static final AtomicLong UNSAFE_ID_ENTITIES = new AtomicLong();
    private static final AtomicLong TRACKED_REPOSITORY_LISTS = new AtomicLong();
    private static final AtomicLong UNTRACKED_LIST_VALIDATIONS = new AtomicLong();

    private EntityLookupRuntime() {
    }

    static boolean ready() {
        return true;
    }

    static void locationInstalled() {
        LOCATION_INSTALLED = true;
    }

    static void repositoryInstalled() {
        REPOSITORY_INSTALLED = true;
    }

    static void idMutationInstalled() {
        ID_MUTATION_INSTALLED = true;
    }

    public static boolean enabled() {
        return LOCATION_INSTALLED && REPOSITORY_INSTALLED && ID_MUTATION_INSTALLED
                && Boolean.getBoolean(ENABLED_PROPERTY);
    }

    /**
     * Factory woven into {@code ObjectRepository.getList}. Only the entity list is tracked; every
     * other repository list retains the shipped {@link ArrayList} implementation.
     */
    public static List<Object> newRepositoryList(Object classifiedType) {
        if (classifiedType instanceof Class<?> type && SECTOR_ENTITY_TOKEN.equals(type.getName())) {
            TRACKED_REPOSITORY_LISTS.incrementAndGet();
            return new TrackedEntityList();
        }
        return new ArrayList<>();
    }

    /** Called immediately before the reviewed base entity changes its id. */
    public static void entityIdChanging(Object ignored) {
        ID_EPOCH.incrementAndGet();
        ID_MUTATIONS.incrementAndGet();
    }

    /** The entity this location would resolve {@code id} to, or null to run the shipped method. */
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
            Locale locale = Locale.getDefault();
            Index index = indexFor(location, entities, size, locale);
            if (index == null) {
                DECLINED.incrementAndGet();
                return null;
            }
            if (!current(index, entities, size, locale)) {
                index = rebuild(location, entities, size, locale);
                if (index == null) {
                    DECLINED.incrementAndGet();
                    return null;
                }
            }
            Object found = index.exact.get(id);
            if (found == null) {
                found = index.lowercase.get(id.toLowerCase(locale));
            }
            if (found == null) {
                MISSING_SERVED.incrementAndGet();
                return MISSING;
            }
            SERVED.incrementAndGet();
            return found;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            DECLINED.incrementAndGet();
            return null;
        }
    }

    public static boolean missing(Object value) {
        return value == MISSING;
    }

    /** Reproduces the shipped exact/case-folded candidate validity split. */
    public static boolean validCandidate(Object candidate, String requestedId) {
        if (candidate == null || requestedId == null) {
            return false;
        }
        try {
            String currentId = idOf(candidate);
            if (currentId == null) {
                return false;
            }
            if (!currentId.equals(requestedId)) {
                // Deliberately the default locale, not Locale.ROOT: the shipped fallback folds both
                // sides with String.toLowerCase(), which is the default locale, and build() and
                // lookup() fold the index the same way. Under Turkish and Azeri that differs from
                // Locale.ROOT -- "ID" folds to "ıd" -- so pinning ROOT here would answer for ids the
                // game itself would not match. See docs/java-runtime-support.md.
                Locale locale = Locale.getDefault();
                return currentId.toLowerCase(locale).equals(requestedId.toLowerCase(locale));
            }
            Class<?> type = candidate.getClass();
            if (!isBaseCampaignEntity(type) && !LOCATION_TOKEN.equals(type.getName())) {
                return false;
            }
            Object containing = type.getMethod("getContainingLocation").invoke(candidate);
            if (containing == null) {
                return false;
            }
            Object repository = containing.getClass().getMethod("getObjects").invoke(containing);
            Object contained = repository.getClass().getMethod("contains", Object.class)
                    .invoke(repository, candidate);
            return Boolean.TRUE.equals(contained);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            return false;
        }
    }

    private static boolean isBaseCampaignEntity(Class<?> type) {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            if (BASE_ENTITY.equals(cursor.getName())) {
                return true;
            }
        }
        return false;
    }

    private static synchronized Index indexFor(
            Object location, List<?> entities, int size, Locale locale)
            throws ReflectiveOperationException {
        Index existing = INDEXES.get(location);
        if (existing != null && existing.size == size && existing.locale.equals(locale)) {
            return existing;
        }
        return rebuildLocked(location, entities, size, locale);
    }

    private static synchronized Index rebuild(
            Object location, List<?> entities, int size, Locale locale)
            throws ReflectiveOperationException {
        return rebuildLocked(location, entities, size, locale);
    }

    private static Index rebuildLocked(Object location, List<?> entities, int size, Locale locale)
            throws ReflectiveOperationException {
        Index rebuilt = build(entities, size, locale);
        if (rebuilt == null) {
            INDEXES.remove(location);
            return null;
        }
        INDEXES.put(location, rebuilt);
        REBUILDS.incrementAndGet();
        INDEXED_ENTITIES.addAndGet(size);
        return rebuilt;
    }

    private static Index build(List<?> entities, int size, Locale locale)
            throws ReflectiveOperationException {
        long versionBefore = versionOf(entities);
        long idEpochBefore = ID_EPOCH.get();
        Object[] snapshot = entities.toArray();
        long versionAfter = versionOf(entities);
        if (snapshot.length != size || versionBefore != versionAfter) {
            return null;
        }
        String[] ids = new String[size];
        Map<String, Object> exact = new HashMap<>(Math.max(16, size * 2));
        Map<String, Object> lowercase = new LinkedHashMap<>(Math.max(16, size * 2));
        boolean idsTracked = true;
        for (int i = 0; i < snapshot.length; i++) {
            Object entity = snapshot[i];
            if (entity == null) {
                continue;
            }
            if (!TRACKED_ID_MUTATION.get(entity.getClass())) {
                idsTracked = false;
                UNSAFE_ID_ENTITIES.incrementAndGet();
            }
            String entityId = idOf(entity);
            ids[i] = entityId;
            if (entityId == null) {
                continue;
            }
            exact.put(entityId, entity);
            lowercase.putIfAbsent(entityId.toLowerCase(locale), entity);
        }
        long idEpochAfter = ID_EPOCH.get();
        if (idEpochBefore != idEpochAfter) {
            return null;
        }
        return new Index(exact, lowercase, snapshot, ids, entities, versionAfter,
                idEpochAfter, idsTracked, locale, size);
    }

    private static boolean current(Index index, List<?> entities, int size, Locale locale)
            throws ReflectiveOperationException {
        if (index.size != size || !index.locale.equals(locale) || index.list != entities
                || entities.size() != size) {
            return false;
        }
        long version = versionOf(entities);
        if (version >= 0L) {
            if (index.listVersion != version) {
                return false;
            }
            if (index.idsTracked) {
                if (index.idEpoch != ID_EPOCH.get()) {
                    return false;
                }
                FAST_VALIDATIONS.incrementAndGet();
                return true;
            }
        } else {
            UNTRACKED_LIST_VALIDATIONS.incrementAndGet();
        }
        DEEP_VALIDATIONS.incrementAndGet();
        for (int i = 0; i < size; i++) {
            Object entity = entities.get(i);
            VALIDATED_REFERENCES.incrementAndGet();
            if (entity != index.entities[i]) {
                return false;
            }
            String entityId = entity == null ? null : idOf(entity);
            if (!Objects.equals(entityId, index.ids[i])) {
                return false;
            }
        }
        return true;
    }

    private static long versionOf(List<?> entities) {
        return entities instanceof TrackedEntityList tracked ? tracked.version : -1L;
    }

    private static String idOf(Object entity) throws ReflectiveOperationException {
        Method getId = GET_ID.get(entity.getClass());
        if (getId == null) {
            throw new NoSuchMethodException(entity.getClass().getName() + ".getId()");
        }
        Object raw = getId.invoke(entity);
        return raw instanceof String id ? id : null;
    }

    static Map<String, Object> counters() {
        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put("planId", PLAN_ID);
        counters.put("installed", LOCATION_INSTALLED && REPOSITORY_INSTALLED && ID_MUTATION_INSTALLED);
        counters.put("enabled", enabled());
        counters.put("validationStrategy", "tracked-repository-list-and-id-mutations");
        counters.put("served", SERVED.get());
        counters.put("missingServed", MISSING_SERVED.get());
        counters.put("declined", DECLINED.get());
        counters.put("rebuilds", REBUILDS.get());
        counters.put("indexedEntities", INDEXED_ENTITIES.get());
        counters.put("fastValidations", FAST_VALIDATIONS.get());
        counters.put("deepValidations", DEEP_VALIDATIONS.get());
        counters.put("validatedReferences", VALIDATED_REFERENCES.get());
        counters.put("listMutations", LIST_MUTATIONS.get());
        counters.put("idMutations", ID_MUTATIONS.get());
        counters.put("unsafeIdEntities", UNSAFE_ID_ENTITIES.get());
        counters.put("trackedRepositoryLists", TRACKED_REPOSITORY_LISTS.get());
        counters.put("untrackedListValidations", UNTRACKED_LIST_VALIDATIONS.get());
        return counters;
    }

    static void beginSession() {
        LOCATION_INSTALLED = false;
        REPOSITORY_INSTALLED = false;
        ID_MUTATION_INSTALLED = false;
        synchronized (EntityLookupRuntime.class) {
            INDEXES.clear();
        }
        ID_EPOCH.set(0);
        SERVED.set(0);
        MISSING_SERVED.set(0);
        DECLINED.set(0);
        REBUILDS.set(0);
        INDEXED_ENTITIES.set(0);
        FAST_VALIDATIONS.set(0);
        DEEP_VALIDATIONS.set(0);
        VALIDATED_REFERENCES.set(0);
        LIST_MUTATIONS.set(0);
        ID_MUTATIONS.set(0);
        UNSAFE_ID_ENTITIES.set(0);
        TRACKED_REPOSITORY_LISTS.set(0);
        UNTRACKED_LIST_VALIDATIONS.set(0);
    }

    /** ArrayList-compatible list whose semantic mutations carry an O(1) generation stamp. */
    private static final class TrackedEntityList extends ArrayList<Object> {
        private static final long serialVersionUID = 1L;
        private long version;

        private TrackedEntityList() {}

        private void changed() {
            version++;
            LIST_MUTATIONS.incrementAndGet();
        }

        @Override public Object set(int index, Object element) {
            changed();
            return super.set(index, element);
        }

        @Override public boolean add(Object value) {
            changed();
            return super.add(value);
        }

        @Override public void add(int index, Object element) {
            changed();
            super.add(index, element);
        }

        @Override public boolean addAll(Collection<?> values) {
            changed();
            return super.addAll(values);
        }

        @Override public boolean addAll(int index, Collection<?> values) {
            changed();
            return super.addAll(index, values);
        }

        @Override public Object remove(int index) {
            changed();
            return super.remove(index);
        }

        @Override public boolean remove(Object value) {
            changed();
            return super.remove(value);
        }

        @Override public void clear() {
            changed();
            super.clear();
        }

        @Override public boolean removeAll(Collection<?> values) {
            changed();
            return super.removeAll(values);
        }

        @Override public boolean retainAll(Collection<?> values) {
            changed();
            return super.retainAll(values);
        }

        @Override public boolean removeIf(Predicate<? super Object> filter) {
            changed();
            return super.removeIf(filter);
        }

        @Override public void replaceAll(UnaryOperator<Object> operator) {
            changed();
            super.replaceAll(operator);
        }

        @Override public void sort(Comparator<? super Object> comparator) {
            changed();
            super.sort(comparator);
        }

        @Override protected void removeRange(int fromIndex, int toIndex) {
            changed();
            super.removeRange(fromIndex, toIndex);
        }

        @Override public List<Object> subList(int fromIndex, int toIndex) {
            return new TrackedSubList(this, super.subList(fromIndex, toIndex));
        }

        @Override public Object clone() {
            return new ArrayList<>(this);
        }
    }

    /** Ensures same-size {@code subList().set(...)} edits advance the root generation too. */
    private static final class TrackedSubList extends AbstractList<Object> implements RandomAccess {
        private final TrackedEntityList owner;
        private final List<Object> delegate;

        private TrackedSubList(TrackedEntityList owner, List<Object> delegate) {
            this.owner = owner;
            this.delegate = delegate;
        }

        @Override public Object get(int index) {
            return delegate.get(index);
        }

        @Override public int size() {
            return delegate.size();
        }

        @Override public Object set(int index, Object element) {
            owner.changed();
            return delegate.set(index, element);
        }

        @Override public void add(int index, Object element) {
            owner.changed();
            delegate.add(index, element);
        }

        @Override public Object remove(int index) {
            owner.changed();
            return delegate.remove(index);
        }

        @Override public boolean addAll(Collection<?> values) {
            owner.changed();
            return delegate.addAll(values);
        }

        @Override public boolean addAll(int index, Collection<?> values) {
            owner.changed();
            return delegate.addAll(index, values);
        }

        @Override public void clear() {
            owner.changed();
            delegate.clear();
        }
    }

    private record Index(
            Map<String, Object> exact,
            Map<String, Object> lowercase,
            Object[] entities,
            String[] ids,
            List<?> list,
            long listVersion,
            long idEpoch,
            boolean idsTracked,
            Locale locale,
            int size) {
    }
}
