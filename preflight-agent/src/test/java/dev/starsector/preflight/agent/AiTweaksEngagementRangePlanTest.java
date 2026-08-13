package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

class AiTweaksEngagementRangePlanTest {
    private static final String TARGET = AiTweaksEngagementRangePlan.TARGET_CLASS;
    private static final String WEAPON = "com/fs/starfarer/api/combat/WeaponAPI";
    private static final String WEAPON_DESCRIPTOR = "L" + WEAPON + ";";
    private static final String HANDLE = "com/genir/aitweaks/core/handles/WeaponHandle";
    private static final String GETTER = "getEngagementRange-impl";
    private static final String GETTER_DESCRIPTOR = "(" + WEAPON_DESCRIPTOR + ")F";
    private static final String RUNTIME =
            AiTweaksEngagementRangeRuntime.class.getName().replace('.', '/');

    @BeforeEach
    void reset() {
        AiTweaksEngagementRangeRuntime.beginSession();
    }

    @Test
    void snapshotsRangeOncePerSelectionAndReusesItWithinSelection() throws Exception {
        byte[] transformed = AiTweaksEngagementRangePlan.transform(signature(), fixture(4));
        assertNotNull(transformed);
        ClassNode owner = parse(transformed);
        var cache = owner.fields.stream()
                .filter(field -> "preflight$engagementRange".equals(field.name))
                .findFirst().orElseThrow();
        assertEquals("F", cache.desc);
        assertTrue((cache.access & Opcodes.ACC_PRIVATE) != 0);
        assertTrue((cache.access & Opcodes.ACC_FINAL) != 0);
        assertTrue((cache.access & Opcodes.ACC_SYNTHETIC) != 0);
        assertEquals("Ljava/lang/Float;", owner.fields.stream()
                .filter(field -> "preflight$engagementRangeBoxed".equals(field.name))
                .findFirst().orElseThrow().desc);
        assertEquals("Ljava/lang/Float;", owner.fields.stream()
                .filter(field -> "preflight$targetSearchRangeBoxed".equals(field.name))
                .findFirst().orElseThrow().desc);
        assertEquals(1, calls(owner, HANDLE, GETTER));
        assertEquals(2, calls(owner, "java/lang/Float", "valueOf"));
        assertEquals(1, calls(owner, RUNTIME, "snapshot"));

        Map<String, byte[]> classes = fixtureTypes();
        classes.put(TARGET.replace('/', '.'), transformed);
        ByteArrayLoader loader = new ByteArrayLoader(classes);
        Class<?> handle = loader.loadClass(HANDLE.replace('/', '.'));
        Class<?> target = loader.loadClass(TARGET.replace('/', '.'));
        Class<?> weaponType = loader.loadClass(WEAPON.replace('/', '.'));
        Object weapon = java.lang.reflect.Proxy.newProxyInstance(
                loader, new Class<?>[] {weaponType}, (proxy, method, arguments) -> null);

        handle.getField("current").setFloat(null, 10f);
        Object selection = target.getConstructors()[0].newInstance(
                weapon, null, null,
                loader.loadClass("com.genir.aitweaks.core.shipai.autofire.ballistics.BallisticParams")
                        .getConstructor().newInstance(),
                loader.loadClass("com.genir.aitweaks.core.shipai.global.TargetTracker")
                        .getConstructor().newInstance());
        handle.getField("current").setFloat(null, 20f);

        assertEquals(40f, target.getMethod("use").invoke(selection));
        assertEquals(1, handle.getField("calls").getInt(null));
        assertEquals(1L, AiTweaksEngagementRangeRuntime.telemetry().get("snapshots"));
        assertEquals(true, AiTweaksEngagementRangeRuntime.telemetry().get("installed"));
    }

    @Test
    void changedCallCountWrongHashAndSecondRewriteFailClosed() {
        ClassSignature exact = signature();
        assertNull(AiTweaksEngagementRangePlan.transform(new ClassSignature(
                exact.internalName(), "0".repeat(64), exact.majorVersion(), exact.access(),
                exact.methods()), fixture(4)));
        assertNull(AiTweaksEngagementRangePlan.transform(exact, fixture(3)));
        byte[] once = AiTweaksEngagementRangePlan.transform(exact, fixture(4));
        assertNotNull(once);
        assertNull(AiTweaksEngagementRangePlan.transform(exact, once));
    }

    @Test
    void targetPinsReviewedAiTweaksArchiveAndPlanAvailability() {
        AdapterTarget target = AdapterTargetRegistry.aiTweaksEngagementRangeTarget();
        assertTrue(AdapterTransformationRegistry.hasPlan(target.planId()));
        AdapterSourceIdentity source = new AdapterSourceIdentity(
                "file:/game/mods/AI%20Tweaks/jars/aitweaks-core.jar",
                "/game/mods/ai tweaks/jars/aitweaks-core.jar",
                "MOD",
                target.sourceSha256(),
                "",
                "com/genir/aitweaks/launcher/loading/CoreLoader",
                "");
        assertTrue(target.match(signature(), source).exact());
    }

    private static ClassSignature signature() {
        return new ClassSignature(
                TARGET,
                AiTweaksEngagementRangePlan.ORIGINAL_SHA256,
                61,
                Opcodes.ACC_PUBLIC,
                List.of(new ClassSignature.Method(
                        AiTweaksEngagementRangePlan.CONSTRUCTOR,
                        AiTweaksEngagementRangePlan.CONSTRUCTOR_DESCRIPTOR,
                        Opcodes.ACC_PUBLIC)));
    }

    private static byte[] fixture(int useCalls) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "weapon", WEAPON_DESCRIPTOR, null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "targetSearchRange", "F", null, null).visitEnd();

        MethodNode constructor = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "<init>",
                AiTweaksEngagementRangePlan.CONSTRUCTOR_DESCRIPTOR,
                null,
                null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        constructor.instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD, TARGET, "weapon", WEAPON_DESCRIPTOR));
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        addRangeCall(constructor);
        constructor.instructions.add(new org.objectweb.asm.tree.LdcInsnNode(1.5f));
        constructor.instructions.add(new InsnNode(Opcodes.FMUL));
        constructor.instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD, TARGET, "targetSearchRange", "F"));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        constructor.accept(writer);

        MethodNode use = new MethodNode(Opcodes.ACC_PUBLIC, "use", "()F", null, null);
        for (int index = 0; index < useCalls; index++) {
            addRangeCall(use);
            if (index < 2) {
                use.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false));
                use.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                        "java/lang/Float", "floatValue", "()F", false));
            }
            if (index > 0) use.instructions.add(new InsnNode(Opcodes.FADD));
        }
        use.instructions.add(new InsnNode(Opcodes.FRETURN));
        use.accept(writer);

        MethodNode searchBox = new MethodNode(
                Opcodes.ACC_PUBLIC, "searchBox", "()Ljava/lang/Float;", null, null);
        searchBox.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        searchBox.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, TARGET, "targetSearchRange", "F"));
        searchBox.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false));
        searchBox.instructions.add(new InsnNode(Opcodes.ARETURN));
        searchBox.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void addRangeCall(MethodNode method) {
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, TARGET, "weapon", WEAPON_DESCRIPTOR));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, HANDLE, GETTER, GETTER_DESCRIPTOR, false));
    }

    private static Map<String, byte[]> fixtureTypes() {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put(WEAPON.replace('/', '.'), emptyType(WEAPON, true));
        classes.put("com.fs.starfarer.api.combat.CombatEntityAPI",
                emptyType("com/fs/starfarer/api/combat/CombatEntityAPI", true));
        classes.put("com.fs.starfarer.api.combat.ShipAPI",
                emptyType("com/fs/starfarer/api/combat/ShipAPI", true));
        classes.put("com.genir.aitweaks.core.shipai.autofire.ballistics.BallisticParams",
                emptyType(
                        "com/genir/aitweaks/core/shipai/autofire/ballistics/BallisticParams", false));
        classes.put("com.genir.aitweaks.core.shipai.global.TargetTracker",
                emptyType("com/genir/aitweaks/core/shipai/global/TargetTracker", false));
        classes.put(HANDLE.replace('/', '.'), handleFixture());
        return classes;
    }

    private static byte[] emptyType(String name, boolean anInterface) {
        ClassWriter writer = new ClassWriter(0);
        int access = Opcodes.ACC_PUBLIC | (anInterface
                ? Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT
                : 0);
        writer.visit(Opcodes.V17, access, name, null, "java/lang/Object", null);
        if (!anInterface) {
            MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            constructor.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
            constructor.instructions.add(new InsnNode(Opcodes.RETURN));
            constructor.maxStack = 1;
            constructor.maxLocals = 1;
            constructor.accept(writer);
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] handleFixture() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, HANDLE, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "current", "F", null, null)
                .visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "calls", "I", null, null)
                .visitEnd();
        MethodNode getter = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, GETTER, GETTER_DESCRIPTOR, null, null);
        getter.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, HANDLE, "calls", "I"));
        getter.instructions.add(new InsnNode(Opcodes.ICONST_1));
        getter.instructions.add(new InsnNode(Opcodes.IADD));
        getter.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, HANDLE, "calls", "I"));
        getter.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, HANDLE, "current", "F"));
        getter.instructions.add(new InsnNode(Opcodes.FRETURN));
        getter.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode parse(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static int calls(ClassNode owner, String targetOwner, String name) {
        int count = 0;
        for (MethodNode method : owner.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && targetOwner.equals(call.owner) && name.equals(call.name)) count++;
            }
        }
        return count;
    }

    private static final class ByteArrayLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        private ByteArrayLoader(Map<String, byte[]> classes) {
            super(AiTweaksEngagementRangePlanTest.class.getClassLoader());
            this.classes = Map.copyOf(classes);
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null && classes.containsKey(name)) {
                byte[] bytes = classes.get(name);
                loaded = defineClass(name, bytes, 0, bytes.length);
            }
            if (loaded == null) loaded = super.loadClass(name, false);
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }
}
