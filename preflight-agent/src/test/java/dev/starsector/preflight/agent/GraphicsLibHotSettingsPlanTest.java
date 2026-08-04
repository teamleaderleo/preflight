package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
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
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class GraphicsLibHotSettingsPlanTest {
    private static final String RUNTIME =
            GraphicsLibHotSettingsRuntime.class.getName().replace('.', '/');
    private static final List<String> SETTINGS = List.of(
            "fighterBrightnessScale", "weaponFlashHeight", "weaponLightHeight");

    @BeforeEach
    void reset() {
        GraphicsLibHotSettingsRuntime.reset();
    }

    @Test
    void exactGettersCacheUntilGraphicsLibAppliesOrLoadsSettings() throws Exception {
        byte[] transformed = GraphicsLibHotSettingsPlan.transform(signature(), fixture(true));
        assertNotNull(transformed);
        ClassNode owner = read(transformed);
        for (String setting : SETTINGS) {
            MethodNode wrapper = method(owner, setting, "()F");
            assertEquals(0, calls(wrapper, "lunalib/lunaSettings/LunaSettings", "getFloat"));
            assertEquals(1, calls(wrapper, RUNTIME, "hit"));
            assertEquals(1, calls(wrapper, RUNTIME, "miss"));
            assertNotNull(method(owner, "preflight$original$" + setting, "()F"));
            var valid = owner.fields.stream()
                    .filter(field -> ("preflight$" + setting + "$valid").equals(field.name))
                    .findFirst().orElseThrow();
            assertTrue((valid.access & Opcodes.ACC_PRIVATE) != 0);
            assertTrue((valid.access & Opcodes.ACC_STATIC) != 0);
            assertTrue((valid.access & Opcodes.ACC_VOLATILE) != 0);
            assertTrue((valid.access & Opcodes.ACC_SYNTHETIC) != 0);
        }
        assertEquals(1, calls(method(owner, "load", "()V"), RUNTIME, "invalidated"));
        assertEquals(1, calls(method(owner, "applyChanges", "()V"), RUNTIME, "invalidated"));

        var loader = new ByteArrayLoader(Map.of(
                GraphicsLibHotSettingsPlan.TARGET_CLASS.replace('/', '.'), transformed,
                "lunalib.lunaSettings.LunaSettings", lunaFixture()));
        Class<?> luna = loader.loadClass("lunalib.lunaSettings.LunaSettings");
        Class<?> settings = loader.loadClass(
                GraphicsLibHotSettingsPlan.TARGET_CLASS.replace('/', '.'));
        Method fighter = settings.getMethod("fighterBrightnessScale");

        luna.getField("current").setFloat(null, 75f);
        assertEquals(0.75f, (float) fighter.invoke(null));
        luna.getField("current").setFloat(null, 50f);
        assertEquals(0.75f, (float) fighter.invoke(null));
        settings.getMethod("applyChanges").invoke(null);
        assertEquals(0.5f, (float) fighter.invoke(null));
        luna.getField("current").setFloat(null, 25f);
        settings.getMethod("load").invoke(null);
        assertEquals(0.25f, (float) fighter.invoke(null));

        assertEquals(1L, GraphicsLibHotSettingsRuntime.telemetry().get("hits"));
        assertEquals(3L, GraphicsLibHotSettingsRuntime.telemetry().get("misses"));
        assertEquals(2L, GraphicsLibHotSettingsRuntime.telemetry().get("invalidations"));
    }

    @Test
    void changedGetterWrongHashAndSecondRewriteFailClosed() {
        ClassSignature exact = signature();
        assertNull(GraphicsLibHotSettingsPlan.transform(new ClassSignature(
                exact.internalName(), "0".repeat(64), exact.majorVersion(), exact.access(),
                exact.methods()), fixture(true)));
        assertNull(GraphicsLibHotSettingsPlan.transform(exact, fixture(false)));
        byte[] once = GraphicsLibHotSettingsPlan.transform(exact, fixture(true));
        assertNotNull(once);
        assertNull(GraphicsLibHotSettingsPlan.transform(exact, once));
    }

    @Test
    void targetPinsTheReviewedModArchiveAndLoader() {
        AdapterTarget target = AdapterTargetRegistry.graphicsLibHotSettingsTarget();
        AdapterSourceIdentity source = new AdapterSourceIdentity(
                "file:/game/mods/GraphicsLib/jars/Graphics.jar",
                "/game/mods/graphicslib/jars/graphics.jar",
                "MOD",
                target.sourceSha256(),
                "",
                "java/net/URLClassLoader",
                null);
        assertTrue(target.match(signature(), source).exact());
    }

    private static ClassSignature signature() {
        return new ClassSignature(
                GraphicsLibHotSettingsPlan.TARGET_CLASS,
                GraphicsLibHotSettingsPlan.ORIGINAL_SHA256,
                61,
                Opcodes.ACC_PUBLIC,
                List.of(
                        new ClassSignature.Method("load", "()V", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC),
                        new ClassSignature.Method(
                                "applyChanges", "()V", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC),
                        new ClassSignature.Method(
                                "fighterBrightnessScale", "()F", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC),
                        new ClassSignature.Method(
                                "weaponFlashHeight", "()F", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC),
                        new ClassSignature.Method(
                                "weaponLightHeight", "()F", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)));
    }

    private static byte[] fixture(boolean reviewedGetters) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, GraphicsLibHotSettingsPlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "useLunaLib", "Z", null, true)
                .visitEnd();
        for (String setting : SETTINGS) {
            writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, setting, "F", null, null)
                    .visitEnd();
            getter(writer, setting, reviewedGetters || !"weaponLightHeight".equals(setting));
        }
        voidMethod(writer, "load");
        voidMethod(writer, "applyChanges");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void getter(ClassWriter writer, String setting, boolean reviewed) {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, setting, "()F", null, null);
        LabelNode fallback = new LabelNode();
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETSTATIC, GraphicsLibHotSettingsPlan.TARGET_CLASS, "useLunaLib", "Z"));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
        method.instructions.add(new LdcInsnNode("shaderLib"));
        method.instructions.add(new LdcInsnNode(setting));
        if (reviewed) {
            method.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "lunalib/lunaSettings/LunaSettings",
                    "getFloat",
                    "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Float;",
                    false));
            method.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false));
        } else {
            method.instructions.add(new InsnNode(Opcodes.POP2));
            method.instructions.add(new InsnNode(Opcodes.FCONST_0));
        }
        if ("fighterBrightnessScale".equals(setting)) {
            method.instructions.add(new LdcInsnNode(100f));
            method.instructions.add(new InsnNode(Opcodes.FDIV));
        }
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, GraphicsLibHotSettingsPlan.TARGET_CLASS, setting, "F"));
        method.instructions.add(new InsnNode(Opcodes.FRETURN));
        method.instructions.add(fallback);
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETSTATIC, GraphicsLibHotSettingsPlan.TARGET_CLASS, setting, "F"));
        method.instructions.add(new InsnNode(Opcodes.FRETURN));
        method.accept(writer);
    }

    private static void voidMethod(ClassWriter writer, String name) {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "()V", null, null);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.accept(writer);
    }

    private static byte[] lunaFixture() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "lunalib/lunaSettings/LunaSettings",
                null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "current", "F", null, null)
                .visitEnd();
        MethodNode getter = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "getFloat",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Float;",
                null,
                null);
        getter.instructions.add(new FieldInsnNode(
                Opcodes.GETSTATIC, "lunalib/lunaSettings/LunaSettings", "current", "F"));
        getter.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false));
        getter.instructions.add(new InsnNode(Opcodes.ARETURN));
        getter.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .filter(method -> name.equals(method.name) && descriptor.equals(method.desc))
                .findFirst().orElseThrow();
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) count++;
        }
        return count;
    }

    private static final class ByteArrayLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        private ByteArrayLoader(Map<String, byte[]> classes) {
            super(GraphicsLibHotSettingsPlanTest.class.getClassLoader());
            this.classes = Map.copyOf(classes);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) return super.findClass(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
