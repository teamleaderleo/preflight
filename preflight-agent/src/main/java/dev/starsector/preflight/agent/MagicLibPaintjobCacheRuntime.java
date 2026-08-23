package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.PreparedMagicPaintjobCache;
import dev.starsector.preflight.core.PreparedMagicPaintjobCacheIO;
import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Reconstructs fresh MagicLib paintjob objects from an exact-profile catalog. */
public final class MagicLibPaintjobCacheRuntime {
    static final String PLAN_ID = "magiclib-paintjob-catalog-v1";
    static final String ORIGINAL_METHOD = "preflight$original$loadPaintjobs";

    private static final int PAYLOAD_VERSION = 1;
    private static final int MAX_PAINTJOBS = 100_000;
    private static final int MAX_WEAPON_PAINTJOBS = 100_000;
    private static final int MAX_COLLECTION_ITEMS = 100_000;
    private static final int MAX_STRING_BYTES = 4 * 1024 * 1024;
    private static final SeamTimer REPLAY_CLOCK = new SeamTimer();
    private static final SeamTimer CAPTURE_CLOCK = new SeamTimer();

    private static volatile State state = State.disabled();
    private static volatile Accessor accessor;
    private static volatile boolean installed;

    private MagicLibPaintjobCacheRuntime() {
    }

    static void beginSession() {
        state = State.disabled();
        accessor = null;
        installed = false;
        REPLAY_CLOCK.reset();
        CAPTURE_CLOCK.reset();
    }

    static void configure(Path artifact) {
        if (artifact == null) {
            state = State.disabled();
            return;
        }
        Path absolute = artifact.toAbsolutePath().normalize();
        String fileName = absolute.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!fileName.matches("[0-9a-f]{64}\\.spmp")) {
            state = State.disabled();
            return;
        }
        String profile = fileName.substring(0, 64);
        byte[] payload = null;
        String diagnostic = "capture";
        if (Files.isRegularFile(absolute)) {
            try {
                PreparedMagicPaintjobCache stored = PreparedMagicPaintjobCacheIO.read(absolute);
                if (profile.equals(stored.profileIdentitySha256())) {
                    payload = stored.payload();
                    diagnostic = "hit";
                } else {
                    diagnostic = "profile-mismatch";
                }
            } catch (Exception error) {
                diagnostic = "rejected:" + message(error);
            }
        }
        state = new State(absolute, profile, payload, diagnostic);
    }

    static boolean ready() {
        return state.artifact != null;
    }

    static String status() {
        return state.diagnostic;
    }

    static void installed() {
        installed = true;
    }

    /** Returns a fresh Kotlin Pair, or null so the wrapper executes MagicLib unchanged. */
    public static Object replay(Class<?> managerClass) {
        // A transformed class can come from the persistent bytecode cache, in which case the
        // transformer is not invoked this JVM and cannot mark the plan installed. Reaching the
        // wrapper is the stronger runtime fact and keeps the receipt truthful on warm launches.
        installed = true;
        State current = state;
        byte[] payload = current.payload;
        if (current.artifact == null || payload == null || current.badEntry.get()) {
            current.misses.incrementAndGet();
            return null;
        }
        long started = REPLAY_CLOCK.enter();
        try {
            Accessor access = accessor(managerClass);
            Object result = decode(payload, access);
            current.hits.incrementAndGet();
            return result;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            current.badEntry.set(true);
            current.misses.incrementAndGet();
            current.diagnose("cached MagicLib paintjob catalog could not be reconstructed: "
                    + message(error));
            return null;
        } finally {
            REPLAY_CLOCK.exit(started);
        }
    }

    /** Captures only a Pair returned normally by MagicLib's preserved loader. */
    public static void capture(Object pair, Class<?> managerClass) {
        installed = true;
        State current = state;
        if (current.artifact == null || pair == null
                || !current.captured.compareAndSet(false, true)) {
            return;
        }
        long started = CAPTURE_CLOCK.enter();
        try {
            byte[] payload = encode(pair, accessor(managerClass));
            PreparedMagicPaintjobCacheIO.write(
                    current.artifact,
                    new PreparedMagicPaintjobCache(current.profileIdentity, payload));
            current.writes.incrementAndGet();
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            current.diagnose("MagicLib paintjob catalog could not be captured: " + message(error));
        } finally {
            CAPTURE_CLOCK.exit(started);
        }
    }

    static Map<String, Object> telemetry() {
        State current = state;
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", PLAN_ID);
        values.put("installed", installed);
        values.put("status", current.diagnostic);
        values.put("profileIdentity", current.profileIdentity);
        values.put("artifact", current.artifact);
        values.put("prepared", current.payload != null);
        values.put("hits", current.hits.get());
        values.put("misses", current.misses.get());
        values.put("writes", current.writes.get());
        values.putAll(REPLAY_CLOCK.snapshot("replay"));
        values.putAll(CAPTURE_CLOCK.snapshot("capture"));
        return values;
    }

    private static Accessor accessor(Class<?> managerClass) throws ReflectiveOperationException {
        Accessor current = accessor;
        if (current == null || current.managerClass != managerClass) {
            current = Accessor.resolve(managerClass);
            accessor = current;
        }
        return current;
    }

    private static byte[] encode(Object pair, Accessor access) throws Exception {
        Object paintjobValue = access.pairFirst.invoke(pair);
        Object weaponValue = access.pairSecond.invoke(pair);
        if (!(paintjobValue instanceof Map<?, ?> paintjobs)
                || !(weaponValue instanceof Map<?, ?> weaponPaintjobs)) {
            throw new IOException("MagicLib paintjob loader did not return two maps");
        }
        requireCount(paintjobs.size(), MAX_PAINTJOBS, "paintjob");
        requireCount(weaponPaintjobs.size(), MAX_WEAPON_PAINTJOBS, "weapon paintjob");

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(PAYLOAD_VERSION);
            output.writeInt(paintjobs.size());
            for (Map.Entry<?, ?> entry : paintjobs.entrySet()) {
                Object spec = entry.getValue();
                if (spec == null || !access.paintjobClass.isInstance(spec)) {
                    throw new IOException("MagicLib paintjob map contains an unexpected value");
                }
                Object id = access.paintjobId.get(spec);
                if (!(entry.getKey() instanceof String key) || !key.equals(id)) {
                    throw new IOException("MagicLib paintjob map key does not match its spec ID");
                }
                writePaintjob(output, spec, access);
            }
            output.writeInt(weaponPaintjobs.size());
            for (Map.Entry<?, ?> entry : weaponPaintjobs.entrySet()) {
                Object spec = entry.getValue();
                if (spec == null || !access.weaponClass.isInstance(spec)) {
                    throw new IOException("MagicLib weapon paintjob map contains an unexpected value");
                }
                Object id = access.weaponId.get(spec);
                if (!(entry.getKey() instanceof String key) || !key.equals(id)) {
                    throw new IOException(
                            "MagicLib weapon paintjob map key does not match its spec ID");
                }
                writeWeaponPaintjob(output, spec, access);
            }
        }
        return bytes.toByteArray();
    }

    private static Object decode(byte[] payload, Accessor access) throws Exception {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            int version = input.readInt();
            if (version != PAYLOAD_VERSION) {
                throw new IOException("unsupported MagicLib catalog payload version: " + version);
            }
            int paintjobCount = readCount(input, MAX_PAINTJOBS, "paintjob");
            Map<String, Object> paintjobs = new HashMap<>();
            Set<String> automaticUnlocks = new LinkedHashSet<>();
            for (int index = 0; index < paintjobCount; index++) {
                Object spec = readPaintjob(input, access);
                String id = (String) access.paintjobId.get(spec);
                if ((boolean) access.paintjobUnlockedAutomatically.get(spec)
                        && (boolean) access.paintjobUnlockable.get(spec)) {
                    automaticUnlocks.add(id);
                }
                if (paintjobs.put(id, spec) != null) {
                    throw new IOException("duplicate MagicLib paintjob ID in cached catalog: " + id);
                }
            }
            int weaponCount = readCount(input, MAX_WEAPON_PAINTJOBS, "weapon paintjob");
            Map<String, Object> weaponPaintjobs = new HashMap<>();
            for (int index = 0; index < weaponCount; index++) {
                Object spec = readWeaponPaintjob(input, access);
                String id = (String) access.weaponId.get(spec);
                if (weaponPaintjobs.put(id, spec) != null) {
                    throw new IOException(
                            "duplicate MagicLib weapon paintjob ID in cached catalog: " + id);
                }
            }
            if (input.read() != -1) {
                throw new IOException("MagicLib catalog payload has trailing bytes");
            }
            @SuppressWarnings("unchecked")
            Set<String> unlocked = (Set<String>) access.unlockedPaintjobs.get(null);
            unlocked.addAll(automaticUnlocks);
            return access.pairConstructor.newInstance(paintjobs, weaponPaintjobs);
        }
    }

    private static void writePaintjob(DataOutputStream output, Object spec, Accessor access)
            throws Exception {
        writeString(output, (String) access.paintjobModId.get(spec));
        writeString(output, (String) access.paintjobModName.get(spec));
        writeString(output, (String) access.paintjobId.get(spec));
        writeNullableString(output, (String) access.paintjobHullId.get(spec));
        writeStrings(output, list(access.paintjobHullIds.get(spec), "hull IDs"));
        writeString(output, (String) access.paintjobName.get(spec));
        writeString(output, (String) access.paintjobUnlockConditions.get(spec));
        writeString(output, (String) access.paintjobDescription.get(spec));
        output.writeBoolean((boolean) access.paintjobUnlockedAutomatically.get(spec));
        writeString(output, (String) access.paintjobSpriteId.get(spec));
        writeStrings(output, list(access.paintjobTags.get(spec), "tags"));
        writeEngine(output, access.paintjobEngine.get(spec), access);
        writeShield(output, access.paintjobShield.get(spec), access);
        writeNullableString(output, (String) access.paintjobFamily.get(spec));
    }

    private static Object readPaintjob(DataInputStream input, Accessor access) throws Exception {
        return access.paintjobConstructor.newInstance(
                readString(input),
                readString(input),
                readString(input),
                readNullableString(input),
                readStrings(input),
                readString(input),
                readString(input),
                readString(input),
                input.readBoolean(),
                readString(input),
                readStrings(input),
                readEngine(input, access),
                readShield(input, access),
                readNullableString(input));
    }

    private static void writeWeaponPaintjob(DataOutputStream output, Object spec, Accessor access)
            throws Exception {
        writeString(output, (String) access.weaponModId.get(spec));
        writeString(output, (String) access.weaponId.get(spec));
        writeStrings(output, set(access.weaponFamilies.get(spec), "paintjob families"));
        writeStrings(output, set(access.weaponIds.get(spec), "weapon IDs"));
        Object rawSprites = access.weaponSprites.get(spec);
        if (!(rawSprites instanceof Map<?, ?> sprites)) {
            throw new IOException("MagicLib weapon sprite map has an unexpected value");
        }
        requireCount(sprites.size(), MAX_COLLECTION_ITEMS, "weapon sprite");
        output.writeInt(sprites.size());
        for (Map.Entry<?, ?> entry : sprites.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String value)) {
                throw new IOException("MagicLib weapon sprite map contains a non-string entry");
            }
            writeString(output, key);
            writeString(output, value);
        }
    }

    private static Object readWeaponPaintjob(DataInputStream input, Accessor access) throws Exception {
        String modId = readString(input);
        String id = readString(input);
        Set<String> families = new LinkedHashSet<>(readStrings(input));
        Set<String> weaponIds = new LinkedHashSet<>(readStrings(input));
        int spriteCount = readCount(input, MAX_COLLECTION_ITEMS, "weapon sprite");
        Map<String, String> sprites = new LinkedHashMap<>();
        for (int index = 0; index < spriteCount; index++) {
            if (sprites.put(readString(input), readString(input)) != null) {
                throw new IOException("MagicLib weapon sprite map contains a duplicate key");
            }
        }
        return access.weaponConstructor.newInstance(modId, id, families, weaponIds, sprites);
    }

    private static void writeEngine(DataOutputStream output, Object value, Accessor access)
            throws Exception {
        output.writeBoolean(value != null);
        if (value == null) {
            return;
        }
        writeColor(output, (Color) access.engineColor.get(value));
        writeColor(output, (Color) access.engineContrailColor.get(value));
        writeFloat(output, (Float) access.engineContrailSpawn.get(value));
        writeFloat(output, (Float) access.engineContrailWidth.get(value));
        writeColor(output, (Color) access.engineGlowColor.get(value));
        writeFloat(output, (Float) access.engineGlowSize.get(value));
    }

    private static Object readEngine(DataInputStream input, Accessor access) throws Exception {
        if (!input.readBoolean()) {
            return null;
        }
        return access.engineConstructor.newInstance(
                readColor(input), readColor(input), readFloat(input),
                readFloat(input), readColor(input), readFloat(input));
    }

    private static void writeShield(DataOutputStream output, Object value, Accessor access)
            throws Exception {
        output.writeBoolean(value != null);
        if (value == null) {
            return;
        }
        writeColor(output, (Color) access.shieldInnerColor.get(value));
        writeColor(output, (Color) access.shieldRingColor.get(value));
        writeFloat(output, (Float) access.shieldInnerRotation.get(value));
        writeFloat(output, (Float) access.shieldRingRotation.get(value));
    }

    private static Object readShield(DataInputStream input, Accessor access) throws Exception {
        if (!input.readBoolean()) {
            return null;
        }
        return access.shieldConstructor.newInstance(
                readColor(input), readColor(input), readFloat(input), readFloat(input));
    }

    private static void writeColor(DataOutputStream output, Color value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) {
            output.writeInt(value.getRGB());
        }
    }

    private static Color readColor(DataInputStream input) throws IOException {
        return input.readBoolean() ? new Color(input.readInt(), true) : null;
    }

    private static void writeFloat(DataOutputStream output, Float value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) {
            output.writeFloat(value);
        }
    }

    private static Float readFloat(DataInputStream input) throws IOException {
        return input.readBoolean() ? input.readFloat() : null;
    }

    private static List<String> list(Object value, String label) throws IOException {
        if (!(value instanceof List<?> raw)) {
            throw new IOException("MagicLib " + label + " are not a list");
        }
        return strings(raw, label);
    }

    private static List<String> set(Object value, String label) throws IOException {
        if (!(value instanceof Set<?> raw)) {
            throw new IOException("MagicLib " + label + " are not a set");
        }
        return strings(raw, label);
    }

    private static List<String> strings(Iterable<?> raw, String label) throws IOException {
        List<String> values = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof String text)) {
                throw new IOException("MagicLib " + label + " contain a non-string value");
            }
            values.add(text);
            requireCount(values.size(), MAX_COLLECTION_ITEMS, label);
        }
        return values;
    }

    private static void writeStrings(DataOutputStream output, List<String> values) throws IOException {
        requireCount(values.size(), MAX_COLLECTION_ITEMS, "string collection");
        output.writeInt(values.size());
        for (String value : values) {
            writeString(output, value);
        }
    }

    private static List<String> readStrings(DataInputStream input) throws IOException {
        int count = readCount(input, MAX_COLLECTION_ITEMS, "string collection");
        List<String> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(readString(input));
        }
        return values;
    }

    private static void writeNullableString(DataOutputStream output, String value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) {
            writeString(output, value);
        }
    }

    private static String readNullableString(DataInputStream input) throws IOException {
        return input.readBoolean() ? readString(input) : null;
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        if (value == null) {
            throw new IOException("MagicLib catalog contains an unexpected null string");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("MagicLib catalog string exceeds its safety limit");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("MagicLib catalog string length is invalid: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("MagicLib catalog ended inside a string");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new IOException("MagicLib catalog string is not valid UTF-8", error);
        }
    }

    private static int readCount(DataInputStream input, int maximum, String label) throws IOException {
        int count = input.readInt();
        requireCount(count, maximum, label);
        return count;
    }

    private static void requireCount(int count, int maximum, String label) throws IOException {
        if (count < 0 || count > maximum) {
            throw new IOException("MagicLib " + label + " count is invalid: " + count);
        }
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static String message(Throwable error) {
        Throwable cause = error instanceof java.lang.reflect.InvocationTargetException invocation
                && invocation.getCause() != null ? invocation.getCause() : error;
        String text = cause.getMessage();
        return text == null || text.isBlank() ? cause.getClass().getSimpleName() : text;
    }

    private record Accessor(
            Class<?> managerClass,
            Constructor<?> pairConstructor,
            Method pairFirst,
            Method pairSecond,
            Class<?> paintjobClass,
            Constructor<?> paintjobConstructor,
            Field paintjobModId,
            Field paintjobModName,
            Field paintjobId,
            Field paintjobHullId,
            Field paintjobHullIds,
            Field paintjobName,
            Field paintjobUnlockConditions,
            Field paintjobDescription,
            Field paintjobUnlockedAutomatically,
            Field paintjobSpriteId,
            Field paintjobTags,
            Field paintjobEngine,
            Field paintjobShield,
            Field paintjobFamily,
            Field paintjobUnlockable,
            Constructor<?> engineConstructor,
            Field engineColor,
            Field engineContrailColor,
            Field engineContrailSpawn,
            Field engineContrailWidth,
            Field engineGlowColor,
            Field engineGlowSize,
            Constructor<?> shieldConstructor,
            Field shieldInnerColor,
            Field shieldRingColor,
            Field shieldInnerRotation,
            Field shieldRingRotation,
            Class<?> weaponClass,
            Constructor<?> weaponConstructor,
            Field weaponModId,
            Field weaponId,
            Field weaponFamilies,
            Field weaponIds,
            Field weaponSprites,
            Field unlockedPaintjobs) {

        private static Accessor resolve(Class<?> manager) throws ReflectiveOperationException {
            ClassLoader loader = manager.getClassLoader();
            Method original = manager.getDeclaredMethod(ORIGINAL_METHOD);
            Class<?> pair = original.getReturnType();
            Constructor<?> pairConstructor = pair.getConstructor(Object.class, Object.class);
            Method pairFirst = pair.getMethod("getFirst");
            Method pairSecond = pair.getMethod("getSecond");

            Class<?> paintjob = Class.forName(
                    "org.magiclib.paintjobs.MagicPaintjobSpec", false, loader);
            Class<?> engine = Class.forName(
                    "org.magiclib.paintjobs.MagicPaintjobSpec$PaintjobEngineSpec", false, loader);
            Class<?> shield = Class.forName(
                    "org.magiclib.paintjobs.MagicPaintjobSpec$PaintjobShieldSpec", false, loader);
            Class<?> weapon = Class.forName(
                    "org.magiclib.paintjobs.MagicWeaponPaintjobSpec", false, loader);

            Constructor<?> paintjobConstructor = paintjob.getConstructor(
                    String.class, String.class, String.class, String.class, List.class,
                    String.class, String.class, String.class, boolean.class, String.class,
                    List.class, engine, shield, String.class);
            Constructor<?> engineConstructor = engine.getConstructor(
                    Color.class, Color.class, Float.class, Float.class, Color.class, Float.class);
            Constructor<?> shieldConstructor = shield.getConstructor(
                    Color.class, Color.class, Float.class, Float.class);
            Constructor<?> weaponConstructor = weapon.getConstructor(
                    String.class, String.class, Set.class, Set.class, Map.class);

            return new Accessor(
                    manager,
                    pairConstructor,
                    pairFirst,
                    pairSecond,
                    paintjob,
                    paintjobConstructor,
                    field(paintjob, "modId"),
                    field(paintjob, "modName"),
                    field(paintjob, "id"),
                    field(paintjob, "hullId"),
                    field(paintjob, "hullIds"),
                    field(paintjob, "name"),
                    field(paintjob, "unlockConditions"),
                    field(paintjob, "description"),
                    field(paintjob, "unlockedAutomatically"),
                    field(paintjob, "spriteId"),
                    field(paintjob, "tags"),
                    field(paintjob, "engineSpec"),
                    field(paintjob, "shieldSpec"),
                    field(paintjob, "paintjobFamily"),
                    field(paintjob, "isUnlockable"),
                    engineConstructor,
                    field(engine, "color"),
                    field(engine, "contrailColor"),
                    field(engine, "contrailSpawnDistMult"),
                    field(engine, "contrailWidthMultiplier"),
                    field(engine, "glowAlternateColor"),
                    field(engine, "glowSizeMult"),
                    shieldConstructor,
                    field(shield, "innerColor"),
                    field(shield, "ringColor"),
                    field(shield, "innerRotationRate"),
                    field(shield, "ringRotationRate"),
                    weapon,
                    weaponConstructor,
                    field(weapon, "modId"),
                    field(weapon, "id"),
                    field(weapon, "paintjobFamilies"),
                    field(weapon, "weaponIds"),
                    field(weapon, "spriteMap"),
                    field(manager, "unlockedPaintjobsInner"));
        }
    }

    private static final class State {
        private final Path artifact;
        private final String profileIdentity;
        private final byte[] payload;
        private final String diagnostic;
        private final AtomicBoolean badEntry = new AtomicBoolean();
        private final AtomicBoolean captured = new AtomicBoolean();
        private final AtomicBoolean diagnosed = new AtomicBoolean();
        private final AtomicLong hits = new AtomicLong();
        private final AtomicLong misses = new AtomicLong();
        private final AtomicLong writes = new AtomicLong();

        private State(Path artifact, String profileIdentity, byte[] payload, String diagnostic) {
            this.artifact = artifact;
            this.profileIdentity = profileIdentity;
            this.payload = payload;
            this.diagnostic = diagnostic;
        }

        private static State disabled() {
            return new State(null, "", null, "disabled");
        }

        private void diagnose(String problem) {
            if (diagnosed.compareAndSet(false, true)) {
                System.err.println("[Preflight] " + problem + "; MagicLib fallback remains active");
            }
        }
    }
}
