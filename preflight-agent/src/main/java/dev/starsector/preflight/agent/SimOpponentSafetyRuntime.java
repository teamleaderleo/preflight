package dev.starsector.preflight.agent;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Removes nonexistent ship specifications from the vanilla refit simulator's opponent list. */
public final class SimOpponentSafetyRuntime {
    static final String PLAN_ID = "sim-opponent-safety-v1";
    public static final String DISABLED_PROPERTY = "preflight.simOpponentSafety.disabled";

    private static final String HULL_VARIANT =
            "com.fs.starfarer.loading.specs.HullVariantSpec";
    private static final String FIGHTER_WING =
            "com.fs.starfarer.loading.specs.FighterWingSpec";
    private static final int MAX_REPORTED_IDS = 256;
    private static final int MAX_TRACKED_DIALOG_GENERATIONS = 32;

    private static volatile boolean installed;
    private static final AtomicLong CALLS = new AtomicLong();
    private static final AtomicLong CANDIDATES = new AtomicLong();
    private static final AtomicLong REMOVED = new AtomicLong();
    private static final AtomicLong FAIL_OPEN = new AtomicLong();
    private static final AtomicLong ADD_RESULTS = new AtomicLong();
    private static final AtomicLong NULL_ADD_RESULTS = new AtomicLong();
    private static final AtomicLong BEFORE_LOAD_FLEET_SIZE = new AtomicLong(-1L);
    private static final AtomicLong AFTER_LOAD_FLEET_SIZE = new AtomicLong(-1L);
    private static final AtomicLong FLEET_INSPECTION_FAILURES = new AtomicLong();
    private static final AtomicLong POST_INIT_ENEMY_RESERVES = new AtomicLong(-1L);
    private static final AtomicLong POST_INIT_ENEMY_NON_ALLY_RESERVES = new AtomicLong(-1L);
    private static final AtomicLong POST_INIT_ENEMY_ALLY_RESERVES = new AtomicLong(-1L);
    private static final AtomicLong POST_INIT_ENEMY_DEPLOYED = new AtomicLong(-1L);
    private static final AtomicLong COMBAT_INSPECTION_FAILURES = new AtomicLong();
    private static final AtomicLong DIALOG_OBSERVATIONS = new AtomicLong();
    private static final AtomicLong DIALOG_GRID_BUILDS = new AtomicLong();
    private static final AtomicLong DIALOG_LAYOUT_OBSERVATIONS = new AtomicLong();
    private static final AtomicLong DIALOG_POST_ADVANCE_OBSERVATIONS = new AtomicLong();
    private static final List<WeakReference<Object>> DIALOG_POST_ADVANCE_DIALOGS = new ArrayList<>();
    private static final AtomicBoolean DIALOG_NULL_POST_ADVANCE_CAPTURED = new AtomicBoolean();
    private static final AtomicLong DIALOG_OWNER_ID = new AtomicLong(-1L);
    private static final AtomicLong DIALOG_RESERVES = new AtomicLong(-1L);
    private static final AtomicLong DIALOG_RESERVE_GRID_MEMBERS = new AtomicLong(-1L);
    private static final AtomicLong DIALOG_DEPLOYED_GRID_MEMBERS = new AtomicLong(-1L);
    private static final AtomicLong DIALOG_INSPECTION_FAILURES = new AtomicLong();
    private static final AtomicLong CATEGORY_UPDATE_CALLS = new AtomicLong();
    private static final AtomicLong CATEGORY_UPDATE_FAILURES = new AtomicLong();
    private static final Map<String, Object> DIALOG_PRESENTATION = new LinkedHashMap<>();
    private static final List<Map<String, Object>> DIALOG_TRANSITIONS = new ArrayList<>();
    private static final Map<String, Long> INVALID_IDS = new LinkedHashMap<>();
    private static final AtomicBoolean INVALID_IDS_TRUNCATED = new AtomicBoolean();

    private SimOpponentSafetyRuntime() {
    }

    static boolean ready() {
        return true;
    }

    static void installed() {
        installed = true;
    }

    static boolean enabled() {
        return installed && !Boolean.getBoolean(DISABLED_PROPERTY);
    }

    /**
     * Returns the shipped list unchanged when every entry exists or validation is unavailable.
     * A filtered copy is returned only when Starsector's own registry rejects an entry.
     */
    public static List<?> filter(List<?> source, Class<?> specStoreClass) {
        if (!enabled() || source == null || specStoreClass == null) {
            return source;
        }
        CALLS.incrementAndGet();
        try {
            if (source.isEmpty()) {
                return source;
            }
            VariantLookup lookup = lookup(specStoreClass);
            return filter(source, lookup);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            FAIL_OPEN.incrementAndGet();
            return source;
        }
    }

    static List<?> filter(List<?> source, VariantLookup lookup) throws Throwable {
        List<Object> valid = null;
        List<String> invalid = new ArrayList<>();
        int size = source.size();
        for (int i = 0; i < size; i++) {
            Object candidate = source.get(i);
            if (!(candidate instanceof String id)) {
                throw new IllegalArgumentException("Non-string simulation opponent id");
            }
            boolean wing = id.endsWith("_wing");
            if (lookup.exists(id, wing)) {
                if (valid != null) {
                    valid.add(candidate);
                }
                continue;
            }
            if (valid == null) {
                valid = new ArrayList<>(size - 1);
                for (int prior = 0; prior < i; prior++) {
                    valid.add(source.get(prior));
                }
            }
            invalid.add(id);
        }
        if (source.size() != size) {
            throw new IllegalStateException("Simulation opponent list changed during validation");
        }
        CANDIDATES.addAndGet(size);
        REMOVED.addAndGet(invalid.size());
        for (String id : invalid) {
            recordInvalid(id);
        }
        return valid == null ? source : valid;
    }

    private static VariantLookup lookup(Class<?> specStoreClass) throws ReflectiveOperationException {
        ClassLoader loader = specStoreClass.getClassLoader();
        Class<?> hullVariant = Class.forName(HULL_VARIANT, false, loader);
        Class<?> fighterWing = Class.forName(FIGHTER_WING, false, loader);
        Method exists = specStoreClass.getMethod("new", Class.class, String.class);
        if (exists.getReturnType() != boolean.class || !Modifier.isStatic(exists.getModifiers())) {
            throw new NoSuchMethodException("SpecStore.new(Class,String):boolean");
        }
        return (id, wing) -> invokeExists(
                exists, wing ? fighterWing : hullVariant, id);
    }

    private static boolean invokeExists(Method method, Class<?> type, String id) throws Throwable {
        try {
            return Boolean.TRUE.equals(method.invoke(null, type, id));
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    /** Records whether vanilla produced a member for a validated simulation-opponent row. */
    public static void recordAdded(Object member) {
        ADD_RESULTS.incrementAndGet();
        if (member == null) {
            NULL_ADD_RESULTS.incrementAndGet();
        }
    }

    /** Records the enemy mission-fleet size immediately before or after vanilla mission loading. */
    public static void recordMission(Object mission, boolean afterLoad) {
        if (mission == null) {
            FLEET_INSPECTION_FAILURES.incrementAndGet();
            return;
        }
        try {
            ClassLoader loader = mission.getClass().getClassLoader();
            Class<?> fleetSide = Class.forName(
                    "com.fs.starfarer.api.mission.FleetSide", false, loader);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object enemy = Enum.valueOf((Class<? extends Enum>) fleetSide.asSubclass(Enum.class),
                    "ENEMY");
            Method getFleet = mission.getClass().getMethod("getFleet", fleetSide);
            Object fleet = invoke(getFleet, mission, enemy);
            if (fleet == null) {
                throw new IllegalStateException("Simulation enemy fleet is null");
            }
            Method getMembers = fleet.getClass().getMethod("Ó00000");
            Object members = invoke(getMembers, fleet);
            if (!(members instanceof List<?> list)) {
                throw new IllegalStateException("Simulation enemy fleet members are unavailable");
            }
            (afterLoad ? AFTER_LOAD_FLEET_SIZE : BEFORE_LOAD_FLEET_SIZE).set(list.size());
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            FLEET_INSPECTION_FAILURES.incrementAndGet();
        }
    }

    /** Records the exact enemy collections consumed by the stock deployment dialog. */
    public static void recordCombatEngine(Object combatEngine) {
        if (combatEngine == null) {
            COMBAT_INSPECTION_FAILURES.incrementAndGet();
            return;
        }
        try {
            Method getFleetManager = combatEngine.getClass().getMethod("getFleetManager", int.class);
            Object enemyManager = invoke(getFleetManager, combatEngine, 1);
            if (enemyManager == null) {
                throw new IllegalStateException("Simulation enemy combat manager is null");
            }
            Collection<?> reserves = collection(enemyManager, "getReserves");
            Collection<?> deployed = collection(enemyManager, "getDeployed");
            long allies = 0L;
            for (Object member : reserves) {
                if (member == null) {
                    throw new IllegalStateException("Simulation enemy reserve member is null");
                }
                Method isAlly = member.getClass().getMethod("isAlly");
                if (Boolean.TRUE.equals(invoke(isAlly, member))) {
                    allies++;
                }
            }
            POST_INIT_ENEMY_RESERVES.set(reserves.size());
            POST_INIT_ENEMY_ALLY_RESERVES.set(allies);
            POST_INIT_ENEMY_NON_ALLY_RESERVES.set(reserves.size() - allies);
            POST_INIT_ENEMY_DEPLOYED.set(deployed.size());
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            COMBAT_INSPECTION_FAILURES.incrementAndGet();
        }
    }

    /** Records the stock dialog's source collection and both grids after a rebuild. */
    public static void recordDialog(Object dialog, int phase) {
        if (phase == 2 && InternalGameControlRuntime.enabled()
                && !InternalGameControlRuntime.simulationEngaged()) {
            RuntimeSemanticState.simulationReady();
            InternalGameControlRuntime.simulationDialog(dialog);
        }
        if (phase == 2 && !claimDialogPostAdvance(dialog)) {
            return;
        }
        DIALOG_OBSERVATIONS.incrementAndGet();
        if (phase == 0) {
            DIALOG_GRID_BUILDS.incrementAndGet();
        } else if (phase == 1) {
            DIALOG_LAYOUT_OBSERVATIONS.incrementAndGet();
        } else if (phase == 2) {
            DIALOG_POST_ADVANCE_OBSERVATIONS.incrementAndGet();
        }
        if (dialog == null) {
            DIALOG_INSPECTION_FAILURES.incrementAndGet();
            return;
        }
        try {
            Method getOwnerId = dialog.getClass().getMethod("getOwnerId");
            Method getReserves = dialog.getClass().getMethod("getReserves");
            Object owner = invoke(getOwnerId, dialog);
            Object reserves = invoke(getReserves, dialog);
            if (!(owner instanceof Number number) || !(reserves instanceof Collection<?> source)) {
                throw new IllegalStateException("Simulation dialog source is unavailable");
            }
            Object reserveGrid = field(dialog, "OÒÖ000");
            Object deployedGrid = field(dialog, "void.null$Object");
            DIALOG_OWNER_ID.set(number.longValue());
            DIALOG_RESERVES.set(source.size());
            DIALOG_RESERVE_GRID_MEMBERS.set(collection(reserveGrid, "getMembers").size());
            DIALOG_DEPLOYED_GRID_MEMBERS.set(collection(deployedGrid, "getMembers").size());
            transition("dialog", phase, source.size(),
                    collection(reserveGrid, "getMembers").size(), null, -1L);
            if (phase == 1 || phase == 2) {
                recordPresentation(dialog, reserveGrid, deployedGrid, phase);
            }
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            DIALOG_INSPECTION_FAILURES.incrementAndGet();
        }
    }

    /** Records the category refresh immediately before vanilla mutates the reserve grid. */
    public static void recordCategoryUpdate(Object dialog, Object variants, Object category) {
        CATEGORY_UPDATE_CALLS.incrementAndGet();
        try {
            long variantCount = variants instanceof Collection<?> values ? values.size() : -1L;
            String categoryId = stringField(category, "id");
            String categoryName = stringField(category, "name");
            boolean custom = publicBooleanField(category, "custom");
            boolean nonFaction = publicBooleanField(category, "nonFactionCategory");
            long reserves = collection(dialog, "getReserves").size();
            Object reserveGrid = field(dialog, "OÒÖ000");
            long gridMembers = collection(reserveGrid, "getMembers").size();
            synchronized (DIALOG_PRESENTATION) {
                DIALOG_PRESENTATION.put("dialogLastCategoryId", categoryId);
                DIALOG_PRESENTATION.put("dialogLastCategoryName", categoryName);
                DIALOG_PRESENTATION.put("dialogLastCategoryVariants", variantCount);
                DIALOG_PRESENTATION.put("dialogLastCategoryCustom", custom);
                DIALOG_PRESENTATION.put("dialogLastCategoryNonFaction", nonFaction);
            }
            transition("category-update", -1, reserves, gridMembers, categoryId, variantCount);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            CATEGORY_UPDATE_FAILURES.incrementAndGet();
        }
    }

    private static boolean claimDialogPostAdvance(Object dialog) {
        if (dialog == null) {
            return DIALOG_NULL_POST_ADVANCE_CAPTURED.compareAndSet(false, true);
        }
        synchronized (DIALOG_POST_ADVANCE_DIALOGS) {
            for (int index = DIALOG_POST_ADVANCE_DIALOGS.size() - 1; index >= 0; index--) {
                Object observed = DIALOG_POST_ADVANCE_DIALOGS.get(index).get();
                if (observed == null) {
                    DIALOG_POST_ADVANCE_DIALOGS.remove(index);
                } else if (observed == dialog) {
                    return false;
                }
            }
            if (DIALOG_POST_ADVANCE_DIALOGS.size() >= MAX_TRACKED_DIALOG_GENERATIONS) {
                DIALOG_POST_ADVANCE_DIALOGS.remove(0);
            }
            DIALOG_POST_ADVANCE_DIALOGS.add(new WeakReference<>(dialog));
            return true;
        }
    }

    private static void transition(
            String kind, int phase, long reserves, long gridMembers,
            String categoryId, long categoryVariants) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("kind", kind);
        if (phase >= 0) event.put("phase", phase);
        event.put("reserves", reserves);
        event.put("gridMembers", gridMembers);
        if (categoryId != null) event.put("categoryId", categoryId);
        if (categoryVariants >= 0L) event.put("categoryVariants", categoryVariants);
        synchronized (DIALOG_TRANSITIONS) {
            if (DIALOG_TRANSITIONS.size() < 32) {
                DIALOG_TRANSITIONS.add(event);
            }
        }
    }

    private static String stringField(Object receiver, String name)
            throws ReflectiveOperationException {
        Object value = publicFieldValue(receiver, name);
        return value == null ? null : String.valueOf(value);
    }

    private static boolean publicBooleanField(Object receiver, String name)
            throws ReflectiveOperationException {
        Object value = publicFieldValue(receiver, name);
        if (!(value instanceof Boolean result)) {
            throw new IllegalStateException(name + " is not boolean");
        }
        return result;
    }

    private static Object publicFieldValue(Object receiver, String name)
            throws ReflectiveOperationException {
        if (receiver == null) throw new IllegalStateException("Category is null");
        return receiver.getClass().getField(name).get(receiver);
    }

    private static void recordPresentation(
            Object dialog, Object reserveGrid, Object deployedGrid, int phase) throws Throwable {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("dialogPresentationPhase", phase);
        values.put("dialogSimulationTabs", booleanField(dialog, "ÓÒÖ000"));
        values.put("dialogCampaignMode", booleanField(dialog, "ÖOÖ000"));

        Object alliesTab = fieldValue(dialog, "String.null$Object");
        Object opponentsTab = fieldValue(dialog, "ÕÒÖ000");
        values.put("dialogAlliesTabPresent", alliesTab != null);
        values.put("dialogOpponentsTabPresent", opponentsTab != null);
        values.put("dialogAlliesTabHighlighted", highlighted(alliesTab));
        values.put("dialogOpponentsTabHighlighted", highlighted(opponentsTab));

        Object innerPanel = invokeNoArgs(dialog, "getInnerPanel");
        Collection<?> innerChildren = collection(innerPanel, "getChildrenCopy");
        Object reserveParent = invokeNoArgs(reserveGrid, "getParent");
        Object deployedParent = invokeNoArgs(deployedGrid, "getParent");
        values.put("dialogInnerPanelChildren", innerChildren.size());
        values.put("dialogInnerPanelContainsReserveGrid", containsIdentity(innerChildren, reserveGrid));
        values.put("dialogInnerPanelContainsDeployedGrid", containsIdentity(innerChildren, deployedGrid));
        values.put("dialogReserveGridDirectChild", reserveParent == innerPanel);
        values.put("dialogDeployedGridDirectChild", deployedParent == innerPanel);

        component(values, "dialogInnerPanel", innerPanel);
        component(values, "dialogReserveGrid", reserveGrid);
        component(values, "dialogDeployedGrid", deployedGrid);

        Object list = invokeNoArgs(reserveGrid, "getList");
        Collection<?> items = collection(list, "getItems");
        long enabledItems = 0L;
        long visibleItems = 0L;
        for (Object item : items) {
            if (Boolean.TRUE.equals(invokeNoArgs(item, "isEnabled"))) {
                enabledItems++;
            }
            Object opacity = invokeNoArgs(item, "getOpacity");
            if (opacity instanceof Number number && number.doubleValue() > 0d) {
                visibleItems++;
            }
        }
        values.put("dialogReserveListItems", items.size());
        values.put("dialogReserveListEnabledItems", enabledItems);
        values.put("dialogReserveListPositiveOpacityItems", visibleItems);
        values.put("dialogReserveListRows", number(invokeNoArgs(list, "getRows")));
        values.put("dialogReserveListColumns", number(invokeNoArgs(list, "getColumns")));
        values.put("dialogReserveListItemWidth", number(invokeNoArgs(list, "getItemWidth")));
        values.put("dialogReserveListItemHeight", number(invokeNoArgs(list, "getItemHeight")));
        component(values, "dialogReserveList", list);

        Object scroller = invokeNoArgs(list, "getScroller");
        values.put("dialogReserveScrollerXOffset", number(invokeNoArgs(scroller, "getXOffset")));
        values.put("dialogReserveScrollerYOffset", number(invokeNoArgs(scroller, "getYOffset")));
        component(values, "dialogReserveScroller", scroller);

        synchronized (DIALOG_PRESENTATION) {
            DIALOG_PRESENTATION.clear();
            DIALOG_PRESENTATION.putAll(values);
        }
    }

    private static void component(Map<String, Object> values, String prefix, Object component)
            throws Throwable {
        values.put(prefix + "Width", number(invokeNoArgs(component, "getWidth")));
        values.put(prefix + "Height", number(invokeNoArgs(component, "getHeight")));
        values.put(prefix + "X", number(invokeNoArgs(component, "getX")));
        values.put(prefix + "Y", number(invokeNoArgs(component, "getY")));
        values.put(prefix + "Opacity", number(invokeNoArgs(component, "getOpacity")));
        values.put(prefix + "Enabled", Boolean.TRUE.equals(invokeNoArgs(component, "isEnabled")));
        Object parent = invokeNoArgs(component, "getParent");
        values.put(prefix + "Parented", parent != null);
        values.put(prefix + "ParentClass", parent == null ? null : parent.getClass().getName());
    }

    private static boolean highlighted(Object button) throws Throwable {
        return button != null && Boolean.TRUE.equals(invokeNoArgs(button, "isHighlighted"));
    }

    private static boolean containsIdentity(Collection<?> values, Object target) {
        for (Object value : values) {
            if (value == target) return true;
        }
        return false;
    }

    private static Number number(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Expected a numeric UI property");
        }
        return number;
    }

    private static boolean booleanField(Object receiver, String name)
            throws ReflectiveOperationException {
        Object value = fieldValue(receiver, name);
        if (!(value instanceof Boolean result)) {
            throw new IllegalStateException(name + " is not boolean");
        }
        return result;
    }

    private static Object fieldValue(Object receiver, String name)
            throws ReflectiveOperationException {
        Field field = receiver.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(receiver);
    }

    private static Object field(Object receiver, String name) throws ReflectiveOperationException {
        Object value = fieldValue(receiver, name);
        if (value == null) {
            throw new IllegalStateException(name + " is null");
        }
        return value;
    }

    private static Collection<?> collection(Object receiver, String methodName) throws Throwable {
        Method method = receiver.getClass().getMethod(methodName);
        Object value = invoke(method, receiver);
        if (!(value instanceof Collection<?> collection)) {
            throw new IllegalStateException(methodName + " did not return a collection");
        }
        return collection;
    }

    private static Object invokeNoArgs(Object receiver, String methodName) throws Throwable {
        Method method = receiver.getClass().getMethod(methodName);
        return invoke(method, receiver);
    }

    private static Object invoke(Method method, Object receiver, Object... arguments)
            throws Throwable {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    private static void recordInvalid(String id) {
        synchronized (INVALID_IDS) {
            Long prior = INVALID_IDS.get(id);
            if (prior != null) {
                INVALID_IDS.put(id, prior + 1L);
            } else if (INVALID_IDS.size() < MAX_REPORTED_IDS) {
                INVALID_IDS.put(id, 1L);
            } else {
                INVALID_IDS_TRUNCATED.set(true);
            }
        }
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", PLAN_ID);
        values.put("installed", installed);
        values.put("enabled", enabled());
        values.put("calls", CALLS.get());
        values.put("candidates", CANDIDATES.get());
        values.put("removed", REMOVED.get());
        values.put("failOpen", FAIL_OPEN.get());
        values.put("addResults", ADD_RESULTS.get());
        values.put("nullAddResults", NULL_ADD_RESULTS.get());
        values.put("beforeLoadEnemyFleetSize", BEFORE_LOAD_FLEET_SIZE.get());
        values.put("afterLoadEnemyFleetSize", AFTER_LOAD_FLEET_SIZE.get());
        values.put("fleetInspectionFailures", FLEET_INSPECTION_FAILURES.get());
        values.put("postInitEnemyReserves", POST_INIT_ENEMY_RESERVES.get());
        values.put("postInitEnemyNonAllyReserves", POST_INIT_ENEMY_NON_ALLY_RESERVES.get());
        values.put("postInitEnemyAllyReserves", POST_INIT_ENEMY_ALLY_RESERVES.get());
        values.put("postInitEnemyDeployed", POST_INIT_ENEMY_DEPLOYED.get());
        values.put("combatInspectionFailures", COMBAT_INSPECTION_FAILURES.get());
        values.put("dialogObservations", DIALOG_OBSERVATIONS.get());
        values.put("dialogGridBuilds", DIALOG_GRID_BUILDS.get());
        values.put("dialogLayoutObservations", DIALOG_LAYOUT_OBSERVATIONS.get());
        values.put("dialogPostAdvanceObservations", DIALOG_POST_ADVANCE_OBSERVATIONS.get());
        values.put("dialogOwnerId", DIALOG_OWNER_ID.get());
        values.put("dialogReserves", DIALOG_RESERVES.get());
        values.put("dialogReserveGridMembers", DIALOG_RESERVE_GRID_MEMBERS.get());
        values.put("dialogDeployedGridMembers", DIALOG_DEPLOYED_GRID_MEMBERS.get());
        values.put("dialogInspectionFailures", DIALOG_INSPECTION_FAILURES.get());
        values.put("categoryUpdateCalls", CATEGORY_UPDATE_CALLS.get());
        values.put("categoryUpdateFailures", CATEGORY_UPDATE_FAILURES.get());
        synchronized (DIALOG_PRESENTATION) {
            values.putAll(DIALOG_PRESENTATION);
        }
        synchronized (INVALID_IDS) {
            values.put("invalidVariantIds", new LinkedHashMap<>(INVALID_IDS));
            values.put("invalidVariantIdsTruncated", INVALID_IDS_TRUNCATED.get());
        }
        synchronized (DIALOG_TRANSITIONS) {
            values.put("dialogTransitions", new ArrayList<>(DIALOG_TRANSITIONS));
        }
        return values;
    }

    static void beginSession() {
        installed = false;
        CALLS.set(0L);
        CANDIDATES.set(0L);
        REMOVED.set(0L);
        FAIL_OPEN.set(0L);
        ADD_RESULTS.set(0L);
        NULL_ADD_RESULTS.set(0L);
        BEFORE_LOAD_FLEET_SIZE.set(-1L);
        AFTER_LOAD_FLEET_SIZE.set(-1L);
        FLEET_INSPECTION_FAILURES.set(0L);
        POST_INIT_ENEMY_RESERVES.set(-1L);
        POST_INIT_ENEMY_NON_ALLY_RESERVES.set(-1L);
        POST_INIT_ENEMY_ALLY_RESERVES.set(-1L);
        POST_INIT_ENEMY_DEPLOYED.set(-1L);
        COMBAT_INSPECTION_FAILURES.set(0L);
        DIALOG_OBSERVATIONS.set(0L);
        DIALOG_GRID_BUILDS.set(0L);
        DIALOG_LAYOUT_OBSERVATIONS.set(0L);
        DIALOG_POST_ADVANCE_OBSERVATIONS.set(0L);
        DIALOG_NULL_POST_ADVANCE_CAPTURED.set(false);
        synchronized (DIALOG_POST_ADVANCE_DIALOGS) {
            DIALOG_POST_ADVANCE_DIALOGS.clear();
        }
        DIALOG_OWNER_ID.set(-1L);
        DIALOG_RESERVES.set(-1L);
        DIALOG_RESERVE_GRID_MEMBERS.set(-1L);
        DIALOG_DEPLOYED_GRID_MEMBERS.set(-1L);
        DIALOG_INSPECTION_FAILURES.set(0L);
        CATEGORY_UPDATE_CALLS.set(0L);
        CATEGORY_UPDATE_FAILURES.set(0L);
        synchronized (DIALOG_PRESENTATION) {
            DIALOG_PRESENTATION.clear();
        }
        synchronized (DIALOG_TRANSITIONS) {
            DIALOG_TRANSITIONS.clear();
        }
        INVALID_IDS_TRUNCATED.set(false);
        synchronized (INVALID_IDS) {
            INVALID_IDS.clear();
        }
    }

    @FunctionalInterface
    interface VariantLookup {
        boolean exists(String id, boolean fighterWing) throws Throwable;
    }
}
