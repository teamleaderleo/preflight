package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Opt-in discovery sampler for the exact reviewed vanilla combat engine.
 *
 * <p>The sampler runs before the timed CombatEngine.advance region. Its own overhead is reported
 * separately, while {@code advanceMicros} measures the engine tick following the sampled workload
 * snapshot. Reflection keeps the agent independent of the licensed game API jar.
 */
public final class CombatWorkloadRuntime {
    static final String PLAN_ID = "vanilla-combat-workload-scaling-probe-v1";
    static final String ENABLE_PROPERTY = "preflight.combatScaling";
    static final String OUTPUT_PROPERTY = "preflight.combatScaling.output";
    static final String RUN_ID_PROPERTY = "preflight.combatScaling.runId";
    static final String CELL_ID_PROPERTY = "preflight.combatScaling.cellId";
    static final String BATTLE_DP_PROPERTY = "preflight.combatScaling.battleDp";
    static final String EVERY_PROPERTY = "preflight.combatScaling.every";
    static final String SAMPLE_LIMIT_PROPERTY = "preflight.combatScaling.sampleLimit";
    static final String DENSITY_SAMPLE_PROPERTY = "preflight.combatScaling.densitySamples";
    static final String DENSITY_BOX_PROPERTY = "preflight.combatScaling.densityBox";

    private static final int DEFAULT_EVERY = 60;
    private static final int DEFAULT_SAMPLE_LIMIT = 4096;
    private static final int DEFAULT_DENSITY_SAMPLES = 8;
    private static final float DEFAULT_DENSITY_BOX = 2000f;
    private static final int INTERNAL_SCAN_LIMIT = 8192;
    private static final int INTERNAL_COLLECTION_REPORT_LIMIT = 32;

    private static final ArrayDeque<Map<String, Object>> samples = new ArrayDeque<>();
    private static final ThreadLocal<Pending> pending = new ThreadLocal<>();
    private static final ClassValue<Map<String, List<Method>>> methods = new ClassValue<>() {
        @Override
        protected Map<String, List<Method>> computeValue(Class<?> type) {
            Map<String, List<Method>> result = new LinkedHashMap<>();
            for (Method method : type.getMethods()) {
                String key = method.getName() + "#" + method.getParameterCount();
                result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(method);
            }
            Map<String, List<Method>> frozen = new LinkedHashMap<>();
            result.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
            return Map.copyOf(frozen);
        }
    };
    private static final ClassValue<List<Field>> collectionFields = new ClassValue<>() {
        @Override
        protected List<Field> computeValue(Class<?> type) {
            List<Field> result = new ArrayList<>();
            for (Class<?> current = type; current != null && current != Object.class;
                    current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    Class<?> fieldType = field.getType();
                    if (!Collection.class.isAssignableFrom(fieldType)
                            && !Map.class.isAssignableFrom(fieldType)
                            && !fieldType.isArray()) continue;
                    try {
                        field.setAccessible(true);
                        result.add(field);
                    } catch (RuntimeException ignored) {
                        // A closed field is absent from the diagnostic internal collection census.
                    }
                }
            }
            result.sort(Comparator.comparing(CombatWorkloadRuntime::fieldName));
            return List.copyOf(result);
        }
    };

    private static volatile boolean installed;
    private static volatile boolean shutdownHookInstalled;
    private static Object lastEngine;
    private static long battleId;
    private static long ticksInBattle;
    private static long attemptedSamples;
    private static long completedSamples;
    private static long failedSamples;
    private static long sampleOverheadNanos;
    private static long maximumSampleOverheadNanos;
    private static long droppedSamples;
    private static String lastFailure;

    private CombatWorkloadRuntime() {
    }

    static boolean enabled() {
        return Boolean.getBoolean(ENABLE_PROPERTY);
    }

    static void installed() {
        installed = true;
    }

    /**
     * Samples the workload before a selected combat-engine tick and returns the timing origin for
     * that tick. Returning zero means this tick is outside the sampling cadence.
     */
    public static long begin(Object engine) {
        if (!enabled() || engine == null) return 0L;
        try {
            configureOutputHook();
            if (engine != lastEngine) {
                lastEngine = engine;
                battleId++;
                ticksInBattle = 0L;
            }
            ticksInBattle++;
            if (ticksInBattle % sampleEvery() != 0L) return 0L;

            attemptedSamples++;
            long overheadStarted = System.nanoTime();
            Map<String, Object> workload = sample(engine);
            long overhead = Math.max(0L, System.nanoTime() - overheadStarted);
            sampleOverheadNanos += overhead;
            maximumSampleOverheadNanos = Math.max(maximumSampleOverheadNanos, overhead);
            workload.put("sampleOverheadMicros", overhead / 1_000.0);
            pending.set(new Pending(workload));
            return System.nanoTime();
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable problem) {
            failedSamples++;
            lastFailure = message(problem);
            pending.remove();
            return 0L;
        }
    }

    /** Completes one sampled CombatEngine.advance timing. */
    public static void end(long startedNanos) {
        if (startedNanos == 0L) return;
        Pending value = pending.get();
        pending.remove();
        if (value == null) return;
        try {
            long duration = Math.max(0L, System.nanoTime() - startedNanos);
            value.workload().put("advanceMicros", duration / 1_000.0);
            synchronized (CombatWorkloadRuntime.class) {
                int limit = sampleLimit();
                while (samples.size() >= limit) {
                    samples.removeFirst();
                    droppedSamples++;
                }
                samples.addLast(value.workload());
                completedSamples++;
            }
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable problem) {
            failedSamples++;
            lastFailure = message(problem);
        }
    }

    static synchronized Map<String, Object> telemetry() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", PLAN_ID);
        result.put("enabled", enabled());
        result.put("installed", installed);
        result.put("runId", System.getProperty(RUN_ID_PROPERTY, ""));
        result.put("cellId", System.getProperty(CELL_ID_PROPERTY, ""));
        result.put("battleDp", battleDp());
        result.put("sampleEveryCombatTicks", sampleEvery());
        result.put("sampleLimit", sampleLimit());
        result.put("densitySamples", densitySamples());
        result.put("densityBox", densityBox());
        result.put("battlesObserved", battleId);
        result.put("attemptedSamples", attemptedSamples);
        result.put("completedSamples", completedSamples);
        result.put("failedSamples", failedSamples);
        result.put("droppedSamples", droppedSamples);
        result.put("sampleOverheadTotalMillis", sampleOverheadNanos / 1_000_000.0);
        result.put("sampleOverheadAverageMicros", attemptedSamples == 0L
                ? null : sampleOverheadNanos / 1_000.0 / attemptedSamples);
        result.put("sampleOverheadMaximumMicros", maximumSampleOverheadNanos / 1_000.0);
        result.put("lastFailure", lastFailure);
        result.put("samples", new ArrayList<>(samples));
        return result;
    }

    static synchronized void reset() {
        samples.clear();
        pending.remove();
        installed = false;
        lastEngine = null;
        battleId = 0L;
        ticksInBattle = 0L;
        attemptedSamples = 0L;
        completedSamples = 0L;
        failedSamples = 0L;
        sampleOverheadNanos = 0L;
        maximumSampleOverheadNanos = 0L;
        droppedSamples = 0L;
        lastFailure = null;
    }

    static void writeReport(Path path) throws IOException {
        if (path == null) return;
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = absolute.resolveSibling(absolute.getFileName()
                + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        boolean moved = false;
        try {
            Files.writeString(temporary, Json.value(telemetry()) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    private static Map<String, Object> sample(Object engine) throws Exception {
        List<?> shipEntities = list(invoke(engine, "getShips"));
        List<?> missileEntities = list(invoke(engine, "getMissiles"));
        List<?> projectileEntities = list(invoke(engine, "getProjectiles"));
        List<?> beamEntities = list(invoke(engine, "getBeams"));

        long ships = 0L;
        long fighters = 0L;
        long wrecks = 0L;
        long shipAi = 0L;
        long fighterAi = 0L;
        long shipsOwner0 = 0L;
        long shipsOwner1 = 0L;
        double liveDeployedDp = 0.0;
        double liveDeployedDpOwner0 = 0.0;
        double liveDeployedDpOwner1 = 0.0;
        double shipDpPresent = 0.0;
        long weapons = 0L;
        long firingWeapons = 0L;
        long beamWeapons = 0L;
        long weaponEffectPlugins = 0L;
        List<Object> densityCandidates = new ArrayList<>();

        for (Object ship : shipEntities) {
            if (ship == null) continue;
            boolean hulk = booleanValue(invoke(ship, "isHulk"));
            boolean fighter = booleanValue(invoke(ship, "isFighter"));
            int owner = numberValue(invokeOptional(ship, "getOwner")).intValue();
            double deployCost = numberValue(invokeOptional(ship, "getDeployCost")).doubleValue();
            if (hulk) {
                wrecks++;
            } else if (fighter) {
                fighters++;
            } else {
                ships++;
                if (owner == 0) shipsOwner0++;
                if (owner == 1) shipsOwner1++;
                liveDeployedDp += deployCost;
                if (owner == 0) liveDeployedDpOwner0 += deployCost;
                if (owner == 1) liveDeployedDpOwner1 += deployCost;
            }
            if (!fighter) shipDpPresent += deployCost;
            if (!hulk) {
                Object ai = invokeOptional(ship, "getAI");
                if (ai != null) {
                    if (fighter) fighterAi++;
                    else shipAi++;
                }
                densityCandidates.add(ship);
            }
            for (Object weapon : list(invokeOptional(ship, "getAllWeapons"))) {
                if (weapon == null) continue;
                weapons++;
                if (booleanValue(invokeOptional(weapon, "isFiring"))) firingWeapons++;
                if (booleanValue(invokeOptional(weapon, "isBeam"))) beamWeapons++;
                if (invokeOptional(weapon, "getEffectPlugin") != null) weaponEffectPlugins++;
            }
        }

        long missileAi = 0L;
        for (Object missile : missileEntities) {
            if (missile != null && invokeOptional(missile, "getAI") != null) missileAi++;
        }

        List<Object> densityAnchors = selectDensityAnchors(densityCandidates, densitySamples());
        Density density = density(engine, densityAnchors);
        InternalCensus census = internalCensus(engine);
        float elapsed = numberValue(invoke(engine, "getTotalElapsedTime", false)).floatValue();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sequence", attemptedSamples);
        result.put("battleId", battleId);
        result.put("combatTick", ticksInBattle);
        result.put("epochMillis", System.currentTimeMillis());
        result.put("combatElapsedSeconds", elapsed);
        result.put("battleDp", battleDp());
        result.put("ships", ships);
        result.put("shipsOwner0", shipsOwner0);
        result.put("shipsOwner1", shipsOwner1);
        result.put("fighters", fighters);
        result.put("wrecks", wrecks);
        result.put("liveDeployedDp", liveDeployedDp);
        result.put("liveDeployedDpOwner0", liveDeployedDpOwner0);
        result.put("liveDeployedDpOwner1", liveDeployedDpOwner1);
        result.put("shipDpPresent", shipDpPresent);
        result.put("missiles", missileEntities.size());
        result.put("projectiles", Math.max(0, projectileEntities.size() - missileEntities.size()));
        result.put("beams", beamEntities.size());
        result.put("shipAi", shipAi);
        result.put("fighterAi", fighterAi);
        result.put("missileAi", missileAi);
        result.put("weapons", weapons);
        result.put("firingWeapons", firingWeapons);
        result.put("beamWeapons", beamWeapons);
        result.put("weaponEffectPlugins", weaponEffectPlugins);
        result.put("nearbyEntitiesMean", density.allMean());
        result.put("nearbyEntitiesMax", density.allMax());
        result.put("nearbyShipsMean", density.shipMean());
        result.put("nearbyShipsMax", density.shipMax());
        result.put("nearbyMissilesMean", density.missileMean());
        result.put("nearbyMissilesMax", density.missileMax());
        result.put("densityAnchors", density.anchors());
        result.put("internalCollectionTotal", census.total());
        result.put("internalCollectionMax", census.maximum());
        result.put("effectLikeObjectsHeuristic", census.effectLike());
        result.put("pluginLikeObjectsHeuristic", census.pluginLike());
        result.put("particleLikeObjectsHeuristic", census.particleLike());
        result.put("debrisLikeObjectsHeuristic", census.debrisLike());
        result.put("internalObjectsScanned", census.scanned());
        result.put("engineCollectionSizes", census.largest());
        return result;
    }

    static List<Object> selectDensityAnchors(List<Object> candidates, int requested) {
        if (candidates.isEmpty() || requested <= 0) return List.of();
        int count = Math.min(requested, candidates.size());
        if (count == candidates.size()) return List.copyOf(candidates);
        List<Object> result = new ArrayList<>(count);
        if (count == 1) {
            result.add(candidates.get(candidates.size() / 2));
            return List.copyOf(result);
        }
        for (int index = 0; index < count; index++) {
            double fraction = index / (double) (count - 1);
            int candidate = (int) Math.round(fraction * (candidates.size() - 1));
            result.add(candidates.get(candidate));
        }
        return List.copyOf(result);
    }

    private static Density density(Object engine, List<Object> anchors) {
        if (anchors.isEmpty()) return Density.EMPTY;
        Object allGrid = invokeOptional(engine, "getAllObjectGrid");
        Object shipGrid = invokeOptional(engine, "getAiGridShips");
        Object missileGrid = invokeOptional(engine, "getAiGridMissiles");
        long allTotal = 0L;
        long shipTotal = 0L;
        long missileTotal = 0L;
        long allMax = 0L;
        long shipMax = 0L;
        long missileMax = 0L;
        int used = 0;
        float box = densityBox();
        for (Object anchor : anchors) {
            Object location = invokeOptional(anchor, "getLocation");
            if (location == null) continue;
            long all = iteratorCount(allGrid, location, box);
            long ships = iteratorCount(shipGrid, location, box);
            long missiles = iteratorCount(missileGrid, location, box);
            allTotal += all;
            shipTotal += ships;
            missileTotal += missiles;
            allMax = Math.max(allMax, all);
            shipMax = Math.max(shipMax, ships);
            missileMax = Math.max(missileMax, missiles);
            used++;
        }
        if (used == 0) return Density.EMPTY;
        return new Density(
                allTotal / (double) used, allMax,
                shipTotal / (double) used, shipMax,
                missileTotal / (double) used, missileMax, used);
    }

    private static long iteratorCount(Object grid, Object location, float box) {
        if (grid == null || location == null) return 0L;
        Object value = invokeOptional(grid, "getCheckIterator", location, box, box);
        if (!(value instanceof Iterator<?> iterator)) return 0L;
        long count = 0L;
        while (iterator.hasNext()) {
            iterator.next();
            count++;
        }
        return count;
    }

    private static InternalCensus internalCensus(Object engine) {
        Map<String, Integer> sizes = new LinkedHashMap<>();
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        long total = 0L;
        int maximum = 0;
        int scanned = 0;
        int effectLike = 0;
        int pluginLike = 0;
        int particleLike = 0;
        int debrisLike = 0;
        for (Field field : collectionFields.get(engine.getClass())) {
            Object value;
            try {
                value = field.get(engine);
            } catch (IllegalAccessException | RuntimeException ignored) {
                continue;
            }
            int size = containerSize(value);
            if (size < 0) continue;
            sizes.put(fieldName(field), size);
            total += size;
            maximum = Math.max(maximum, size);
            if (scanned >= INTERNAL_SCAN_LIMIT) continue;
            for (Object element : elements(value)) {
                if (element == null || seen.put(element, Boolean.TRUE) != null) continue;
                scanned++;
                String name = typeName(element).toLowerCase(Locale.ROOT);
                boolean plugin = name.contains("plugin");
                boolean particle = name.contains("particle") || name.contains("trail");
                boolean debris = name.contains("debris") || name.contains("wreck");
                boolean effect = name.contains("effect") || name.contains("explosion")
                        || name.contains("arc") || plugin || particle || debris;
                if (plugin) pluginLike++;
                if (particle) particleLike++;
                if (debris) debrisLike++;
                if (effect) effectLike++;
                if (scanned >= INTERNAL_SCAN_LIMIT) break;
            }
        }
        List<Map.Entry<String, Integer>> ordered = new ArrayList<>(sizes.entrySet());
        ordered.sort(Map.Entry.<String, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()));
        Map<String, Integer> largest = new LinkedHashMap<>();
        for (int index = 0; index < Math.min(INTERNAL_COLLECTION_REPORT_LIMIT, ordered.size()); index++) {
            Map.Entry<String, Integer> entry = ordered.get(index);
            largest.put(entry.getKey(), entry.getValue());
        }
        return new InternalCensus(total, maximum, effectLike, pluginLike,
                particleLike, debrisLike, scanned, largest);
    }

    private static Iterable<?> elements(Object value) {
        if (value instanceof Map<?, ?> map) return map.values();
        if (value instanceof Collection<?> collection) return collection;
        if (value != null && value.getClass().isArray()) {
            List<Object> result = new ArrayList<>(Array.getLength(value));
            for (int index = 0; index < Array.getLength(value); index++) {
                result.add(Array.get(value, index));
            }
            return result;
        }
        return List.of();
    }

    private static int containerSize(Object value) {
        if (value instanceof Map<?, ?> map) return map.size();
        if (value instanceof Collection<?> collection) return collection.size();
        if (value != null && value.getClass().isArray()) return Array.getLength(value);
        return -1;
    }

    private static String typeName(Object value) {
        StringBuilder result = new StringBuilder(value.getClass().getName());
        for (Class<?> face : value.getClass().getInterfaces()) result.append(' ').append(face.getName());
        return result.toString();
    }

    private static String fieldName(Field field) {
        return field.getDeclaringClass().getName() + "." + field.getName();
    }

    private static Object invoke(Object target, String name, Object... arguments) throws Exception {
        if (target == null) throw new IllegalArgumentException("null target for " + name);
        List<Method> candidates = methods.get(target.getClass()).get(name + "#" + arguments.length);
        if (candidates == null) {
            throw new NoSuchMethodException(target.getClass().getName() + "." + name
                    + "/" + arguments.length);
        }
        for (Method method : candidates) {
            if (argumentsMatch(method.getParameterTypes(), arguments)) return method.invoke(target, arguments);
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name
                + "/" + arguments.length + " compatible overload");
    }

    private static boolean argumentsMatch(Class<?>[] parameters, Object[] arguments) {
        for (int index = 0; index < parameters.length; index++) {
            Object argument = arguments[index];
            Class<?> parameter = boxed(parameters[index]);
            if (argument != null && !parameter.isInstance(argument)) return false;
            if (argument == null && parameters[index].isPrimitive()) return false;
        }
        return true;
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private static Object invokeOptional(Object target, String name, Object... arguments) {
        try {
            return target == null ? null : invoke(target, name, arguments);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<?> list(Object value) {
        if (value instanceof List<?> values) return values;
        if (value instanceof Collection<?> collection) return new ArrayList<>(collection);
        return List.of();
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean flag && flag;
    }

    private static Number numberValue(Object value) {
        return value instanceof Number number ? number : 0;
    }

    private static Double battleDp() {
        String raw = System.getProperty(BATTLE_DP_PROPERTY);
        if (raw == null || raw.isBlank()) return null;
        try {
            double value = Double.parseDouble(raw.trim());
            return Double.isFinite(value) && value >= 0.0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int sampleEvery() {
        return intProperty(EVERY_PROPERTY, DEFAULT_EVERY, 1, 3600);
    }

    private static int sampleLimit() {
        return intProperty(SAMPLE_LIMIT_PROPERTY, DEFAULT_SAMPLE_LIMIT, 128, 20_000);
    }

    private static int densitySamples() {
        return intProperty(DENSITY_SAMPLE_PROPERTY, DEFAULT_DENSITY_SAMPLES, 1, 64);
    }

    private static float densityBox() {
        String raw = System.getProperty(DENSITY_BOX_PROPERTY);
        if (raw == null || raw.isBlank()) return DEFAULT_DENSITY_BOX;
        try {
            float value = Float.parseFloat(raw.trim());
            return Math.max(100f, Math.min(20_000f, value));
        } catch (NumberFormatException ignored) {
            return DEFAULT_DENSITY_BOX;
        }
    }

    private static int intProperty(String name, int fallback, int minimum, int maximum) {
        String raw = System.getProperty(name);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int value = Integer.parseInt(raw.trim());
            return Math.max(minimum, Math.min(maximum, value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static void configureOutputHook() {
        if (shutdownHookInstalled) return;
        String raw = System.getProperty(OUTPUT_PROPERTY, "").trim();
        if (raw.isEmpty()) return;
        synchronized (CombatWorkloadRuntime.class) {
            if (shutdownHookInstalled) return;
            Path output = Path.of(raw).toAbsolutePath().normalize();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    writeReport(output);
                } catch (Throwable ignored) {
                    // Shutdown evidence is best effort and can never block game exit.
                }
            }, "preflight-combat-scaling-report"));
            shutdownHookInstalled = true;
        }
    }

    private static String message(Throwable problem) {
        Throwable cause = problem;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String detail = cause.getMessage();
        return cause.getClass().getName()
                + (detail == null || detail.isBlank() ? "" : ": " + detail);
    }

    private record Pending(Map<String, Object> workload) {
    }

    private record Density(
            double allMean, long allMax,
            double shipMean, long shipMax,
            double missileMean, long missileMax,
            int anchors) {
        private static final Density EMPTY = new Density(0.0, 0L, 0.0, 0L, 0.0, 0L, 0);
    }

    private record InternalCensus(
            long total,
            int maximum,
            int effectLike,
            int pluginLike,
            int particleLike,
            int debrisLike,
            int scanned,
            Map<String, Integer> largest) {
    }
}
