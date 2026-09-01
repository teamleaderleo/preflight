package dev.starsector.preflight.agent;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact Console Commands 4.0.9 bridge for one closed, in-memory combat benchmark recipe. */
final class ConsoleCombatFixtureRuntime {
    static final String ACTION = "campaign.prepare-combat-fixture";
    static final String VERIFY_ACTION = "campaign.verify-combat-fixture";
    static final String RECIPE_ID = "console-simulation-fleet-v5";
    static final int PLAYER_SHIPS_ADDED = 24;
    private static final String ARCHIVE_SHA256 =
            "a740eb869fac7c7eb1c9e78c6f6276ff0f75ca85317088310d1c89e5b81be805";
    private static final String CONSOLE = "org.lazywizard.console.Console";
    private static final String CONTEXT = "org.lazywizard.console.BaseCommand$CommandContext";
    private static final String RESULT = "org.lazywizard.console.BaseCommand$CommandResult";
    private static final String GLOBAL = "com.fs.starfarer.api.Global";
    private static final String SETTINGS = "com.fs.starfarer.api.SettingsAPI";
    private static final String MOD_MANAGER = "com.fs.starfarer.api.ModManagerAPI";
    private static final String MOD_SPEC = "com.fs.starfarer.api.ModSpecAPI";
    private static final String MOD_ID = "lw_console";
    private static final String MOD_VERSION = "4.0.9";
    private static final String MOD_JAR = "jars/lw_Console.jar";
    private static final Map<String, String> CLASS_HASHES = Map.of(
            CONSOLE, "1f9991b332d03c1ac5935ad43afb8d893cadd4053892f67ce6f4fb31f9ab2738",
            "org.lazywizard.console.commands.AddShip",
            "7bb8ce7cb2fc0fb25191045b611601d68ef3bae6fec862f57879956f3315cec3",
            "org.lazywizard.console.commands.Repair",
            "fe4c3b9cd7f7d2f45696b0f4ca22cb998ea3395eb9de62e23dbbdcff5736e7e1");
    private static final List<Command> COMMANDS = List.of(
            new Command("org.lazywizard.console.commands.AddShip", "onslaught_Elite 2"),
            new Command("org.lazywizard.console.commands.AddShip", "legion_Assault 2"),
            new Command("org.lazywizard.console.commands.AddShip", "dominator_Assault 4"),
            new Command("org.lazywizard.console.commands.AddShip", "eagle_Assault 4"),
            new Command("org.lazywizard.console.commands.AddShip", "hammerhead_Balanced 6"),
            new Command("org.lazywizard.console.commands.AddShip", "sunder_Assault 6"),
            new Command("org.lazywizard.console.commands.Repair", ""));

    private static boolean attempted;
    private static boolean prepared;
    private static boolean verificationAttempted;
    private static boolean verified;
    private static int crewAdded;
    private static int playerShipsAdded;
    private static int playerShipsMothballedBeforeNormalization;
    private static int playerShipsMothballedAfterNormalization;
    private static int playerShipsAtMaximumCr;
    private static List<Map<String, Object>> playerShipCrObservations = List.of();
    private static List<?> preparedMembers = List.of();
    private static String problem;

    private ConsoleCombatFixtureRuntime() {
    }

    static synchronized String prepare() throws ReflectiveOperationException, IOException {
        if (attempted) throw new IllegalStateException("combat-fixture-already-attempted");
        attempted = true;
        try {
            ClassLoader loader = scriptClassLoader();
            Api api = verifyConsoleApi(loader, consoleArchive());
            Object context = invoke(api.console().getMethod("getContext"), null);
            if (context == null
                    || !CONTEXT.equals(context.getClass().getName())
                    || !"CAMPAIGN_MAP".equals(((Enum<?>) context).name())) {
                throw new IllegalStateException("console-campaign-context-unavailable");
            }

            Object sector = invoke(Class.forName(GLOBAL, false, loader).getMethod("getSector"), null);
            Object playerFleet = invoke(sector.getClass().getMethod("getPlayerFleet"), sector);
            Object fleetData = invoke(playerFleet.getClass().getMethod("getFleetData"), playerFleet);
            Method members = fleetData.getClass().getMethod("getMembersListCopy");
            List<?> before = requireList(invoke(members, fleetData));

            for (Command command : COMMANDS) {
                execute(api, command, context);
            }

            List<?> after = requireList(invoke(members, fleetData));
            playerShipsAdded = after.size() - before.size();
            if (playerShipsAdded != PLAYER_SHIPS_ADDED) {
                throw new IllegalStateException("combat-fixture-player-count-mismatch");
            }
            List<?> added = newMembers(before, after);
            crewAdded = addMinimumCrew(playerFleet, fleetData);
            normalizeForSimulation(added);
            preparedMembers = List.copyOf(added);
            prepared = true;
            return "prepared " + RECIPE_ID + ": added and repaired " + playerShipsAdded
                    + " fixed player ships plus " + crewAdded
                    + " minimum crew; awaiting settled verification";
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (ReflectiveOperationException | IOException | RuntimeException failure) {
            problem = bounded(failure);
            throw failure;
        }
    }

    static synchronized String verify() throws ReflectiveOperationException {
        if (!prepared || preparedMembers.size() != PLAYER_SHIPS_ADDED) {
            throw new IllegalStateException("combat-fixture-not-prepared");
        }
        if (verificationAttempted) {
            throw new IllegalStateException("combat-fixture-verification-already-attempted");
        }
        verificationAttempted = true;
        try {
            ClassLoader loader = scriptClassLoader();
            Object sector = invoke(Class.forName(GLOBAL, false, loader).getMethod("getSector"), null);
            Object playerFleet = invoke(sector.getClass().getMethod("getPlayerFleet"), sector);
            invoke(playerFleet.getClass().getMethod("forceSync"), playerFleet);
            playerShipsAtMaximumCr = maximumCrCount(preparedMembers);
            int deployable = deployableCount(preparedMembers);
            if (playerShipsAtMaximumCr != PLAYER_SHIPS_ADDED) {
                throw new IllegalStateException("combat-fixture-player-cr-mismatch");
            }
            if (deployable != PLAYER_SHIPS_ADDED) {
                throw new IllegalStateException("combat-fixture-player-deployable-mismatch");
            }
            verified = true;
            return "verified " + RECIPE_ID + " after settlement: " + deployable
                    + " player ships deployable at maximum CR";
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            problem = bounded(failure);
            throw failure;
        }
    }

    private static ClassLoader scriptClassLoader() throws ReflectiveOperationException {
        ClassLoader gameLoader = ConsoleCombatFixtureRuntime.class.getClassLoader();
        Class<?> global = Class.forName(GLOBAL, false, gameLoader);
        Class<?> settingsApi = Class.forName(SETTINGS, false, gameLoader);
        Method accessor = settingsApi.getMethod("getScriptClassLoader");
        if (accessor.getParameterCount() != 0
                || accessor.getReturnType() != ClassLoader.class) {
            throw new IllegalStateException("script-loader-accessor-shape-mismatch");
        }
        Object settings = invoke(global.getMethod("getSettings"), null);
        Object value = invoke(accessor, settings);
        if (!(value instanceof ClassLoader loader)) {
            throw new IllegalStateException("console-script-loader-unavailable");
        }
        return loader;
    }

    static Api verifyConsoleApi(ClassLoader loader, Path archive)
            throws ReflectiveOperationException, IOException {
        if (loader == null) throw new IllegalStateException("console-script-loader-unavailable");
        Class<?> console = null;
        ClassLoader consoleLoader = null;
        for (Map.Entry<String, String> expected : CLASS_HASHES.entrySet()) {
            Class<?> type = Class.forName(expected.getKey(), false, loader);
            if (!"java.net.URLClassLoader".equals(type.getClassLoader().getClass().getName())) {
                throw new IllegalStateException("console-loader-mismatch");
            }
            if (consoleLoader == null) consoleLoader = type.getClassLoader();
            if (consoleLoader != type.getClassLoader()) {
                throw new IllegalStateException("console-loader-split");
            }
            ClassSignature signature = ClassSignature.parse(classBytes(type));
            if (signature.majorVersion() != 61
                    || !expected.getKey().replace('.', '/').equals(signature.internalName())
                    || !expected.getValue().equals(signature.sha256())) {
                throw new IllegalStateException("console-class-identity-mismatch");
            }
            if (CONSOLE.equals(expected.getKey())) console = type;
        }
        if (archive == null || console == null || !Files.isRegularFile(archive)
                || !"lw_Console.jar".equals(archive.getFileName().toString())
                || !ARCHIVE_SHA256.equals(sha256(archive))) {
            throw new IllegalStateException("console-archive-identity-mismatch");
        }
        Class<?> context = Class.forName(CONTEXT, false, loader);
        Class<?> result = Class.forName(RESULT, false, loader);
        Method getContext = console.getMethod("getContext");
        if (getContext.getParameterCount() != 0 || getContext.getReturnType() != context) {
            throw new IllegalStateException("console-context-shape-mismatch");
        }
        for (Command command : COMMANDS) {
            Class<?> type = Class.forName(command.type(), false, loader);
            Method run = type.getMethod("runCommand", String.class, context);
            type.getConstructor();
            if (run.getReturnType() != result) {
                throw new IllegalStateException("console-command-shape-mismatch");
            }
        }
        return new Api(loader, console, context, result);
    }

    static synchronized Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("action", ACTION);
        values.put("recipeId", RECIPE_ID);
        values.put("attempted", attempted);
        values.put("prepared", prepared);
        values.put("verificationAttempted", verificationAttempted);
        values.put("verified", verified);
        values.put("crewAdded", crewAdded);
        values.put("playerShipsAdded", playerShipsAdded);
        values.put("playerShipsMothballedBeforeNormalization",
                playerShipsMothballedBeforeNormalization);
        values.put("playerShipsMothballedAfterNormalization",
                playerShipsMothballedAfterNormalization);
        values.put("playerShipsAtMaximumCr", playerShipsAtMaximumCr);
        values.put("playerShipCrObservations", playerShipCrObservations);
        values.put("problem", problem);
        return values;
    }

    static synchronized void reset() {
        attempted = false;
        prepared = false;
        verificationAttempted = false;
        verified = false;
        crewAdded = 0;
        playerShipsAdded = 0;
        playerShipsMothballedBeforeNormalization = 0;
        playerShipsMothballedAfterNormalization = 0;
        playerShipsAtMaximumCr = 0;
        playerShipCrObservations = List.of();
        preparedMembers = List.of();
        problem = null;
    }

    static List<Command> commands() {
        return COMMANDS;
    }

    private static void execute(Api api, Command command, Object context)
            throws ReflectiveOperationException {
        Class<?> type = Class.forName(command.type(), true, api.loader());
        Object receiver = type.getConstructor().newInstance();
        Object result = invoke(type.getMethod("runCommand", String.class, api.context()),
                receiver, command.arguments(), context);
        if (result == null || result.getClass() != api.result()
                || !"SUCCESS".equals(((Enum<?>) result).name())) {
            throw new IllegalStateException("console-command-failed:" + type.getSimpleName());
        }
    }

    private static List<?> requireList(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("campaign-member-list-shape-mismatch");
        }
        return list;
    }

    private static List<?> newMembers(List<?> before, List<?> after) {
        java.util.Set<Object> prior = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        prior.addAll(before);
        return after.stream().filter(member -> !prior.contains(member)).toList();
    }

    private static int addMinimumCrew(Object playerFleet, Object fleetData)
            throws ReflectiveOperationException {
        invoke(fleetData.getClass().getMethod("setSyncNeeded"), fleetData);
        invoke(playerFleet.getClass().getMethod("forceSync"), playerFleet);
        int required = (int) Math.ceil(number(
                invoke(fleetData.getClass().getMethod("getMinCrew"), fleetData)));
        Object cargo = invoke(playerFleet.getClass().getMethod("getCargo"), playerFleet);
        int available = ((Number) invoke(cargo.getClass().getMethod("getCrew"), cargo)).intValue();
        int shortfall = Math.max(0, required - available);
        if (shortfall > 0) {
            invoke(cargo.getClass().getMethod("addCrew", int.class), cargo, shortfall);
        }
        invoke(fleetData.getClass().getMethod("setSyncNeeded"), fleetData);
        invoke(playerFleet.getClass().getMethod("forceSync"), playerFleet);
        return shortfall;
    }

    private static int deployableCount(List<?> members) throws ReflectiveOperationException {
        int count = 0;
        for (Object member : members) {
            if (Boolean.TRUE.equals(invoke(
                    member.getClass().getMethod("canBeDeployedForCombat"), member))) {
                count++;
            }
        }
        return count;
    }

    private static int maximumCrCount(List<?> members) throws ReflectiveOperationException {
        int count = 0;
        java.util.ArrayList<Map<String, Object>> observations = new java.util.ArrayList<>();
        for (Object member : members) {
            Object tracker = invoke(member.getClass().getMethod("getRepairTracker"), member);
            float maximum = number(invoke(tracker.getClass().getMethod("getMaxCR"), tracker));
            float cr = number(invoke(tracker.getClass().getMethod("getCR"), tracker));
            boolean atMaximum = cr + 0.0001f >= maximum;
            if (atMaximum) count++;
            Map<String, Object> observation = new LinkedHashMap<>();
            observation.put("specId", String.valueOf(
                    invoke(member.getClass().getMethod("getSpecId"), member)));
            observation.put("cr", Float.toString(cr));
            observation.put("maximumCr", Float.toString(maximum));
            observation.put("finite", Float.isFinite(cr) && Float.isFinite(maximum));
            observation.put("atMaximum", atMaximum);
            observation.put("deployable", Boolean.TRUE.equals(invoke(
                    member.getClass().getMethod("canBeDeployedForCombat"), member)));
            observations.add(Map.copyOf(observation));
        }
        playerShipCrObservations = List.copyOf(observations);
        return count;
    }

    private static void normalizeForSimulation(List<?> members) throws ReflectiveOperationException {
        playerShipsMothballedBeforeNormalization = mothballedCount(members);
        for (Object member : members) {
            Object tracker = invoke(member.getClass().getMethod("getRepairTracker"), member);
            invoke(tracker.getClass().getMethod("setCrashMothballed", boolean.class), tracker, false);
            invoke(tracker.getClass().getMethod("setMothballed", boolean.class), tracker, false);
            invoke(member.getClass().getMethod("updateStats"), member);
            Method maximum = tracker.getClass().getMethod("getMaxCR");
            Method set = tracker.getClass().getMethod("setCR", float.class);
            invoke(set, tracker, number(invoke(maximum, tracker)));
        }
        playerShipsMothballedAfterNormalization = mothballedCount(members);
        if (playerShipsMothballedAfterNormalization != 0) {
            throw new IllegalStateException("combat-fixture-player-mothball-mismatch");
        }
    }

    private static int mothballedCount(List<?> members) throws ReflectiveOperationException {
        int count = 0;
        for (Object member : members) {
            Object tracker = invoke(member.getClass().getMethod("getRepairTracker"), member);
            if (Boolean.TRUE.equals(invoke(tracker.getClass().getMethod("isMothballed"), tracker))) {
                count++;
            }
        }
        return count;
    }

    private static float number(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("campaign-member-cr-shape-mismatch");
        }
        return number.floatValue();
    }

    private static Path consoleArchive() throws ReflectiveOperationException, IOException {
        ClassLoader gameLoader = ConsoleCombatFixtureRuntime.class.getClassLoader();
        Class<?> global = Class.forName(GLOBAL, false, gameLoader);
        Class<?> settingsApi = Class.forName(SETTINGS, false, gameLoader);
        Class<?> modManagerApi = Class.forName(MOD_MANAGER, false, gameLoader);
        Class<?> modSpecApi = Class.forName(MOD_SPEC, false, gameLoader);
        Object settings = invoke(global.getMethod("getSettings"), null);
        Object manager = invoke(settingsApi.getMethod("getModManager"), settings);
        Object enabled = invoke(modManagerApi.getMethod("isModEnabled", String.class), manager, MOD_ID);
        Object spec = invoke(modManagerApi.getMethod("getModSpec", String.class), manager, MOD_ID);
        if (!Boolean.TRUE.equals(enabled) || spec == null
                || !MOD_ID.equals(invoke(modSpecApi.getMethod("getId"), spec))
                || !MOD_VERSION.equals(invoke(modSpecApi.getMethod("getVersion"), spec))) {
            throw new IOException("reviewed Console Commands mod is not enabled");
        }
        Object jars = invoke(modSpecApi.getMethod("getJars"), spec);
        if (!(jars instanceof List<?> list) || !List.of(MOD_JAR).equals(list)) {
            throw new IOException("Console Commands JAR declaration changed");
        }
        Object rootValue = invoke(modSpecApi.getMethod("getPath"), spec);
        if (!(rootValue instanceof String rootText) || rootText.isBlank()) {
            throw new IOException("Console Commands mod path is unavailable");
        }
        Path root = Path.of(rootText).toRealPath();
        Path archive = root.resolve(MOD_JAR).normalize();
        if (!archive.startsWith(root) || !Files.isRegularFile(archive)) {
            throw new IOException("Console Commands archive is unavailable");
        }
        return archive.toRealPath();
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("console class resource is unavailable");
            byte[] bytes = input.readNBytes(2 * 1024 * 1024 + 1);
            if (bytes.length == 0 || bytes.length > 2 * 1024 * 1024) {
                throw new IOException("console class resource size is invalid");
            }
            return bytes;
        }
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) >= 0; ) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Object invoke(Method method, Object receiver, Object... arguments)
            throws ReflectiveOperationException {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw failed;
        }
    }

    private static String bounded(Throwable failure) {
        String value = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        return value.length() <= 1_000 ? value : value.substring(0, 1_000);
    }

    record Command(String type, String arguments) {
    }

    record Api(ClassLoader loader, Class<?> console, Class<?> context, Class<?> result) {
    }
}
