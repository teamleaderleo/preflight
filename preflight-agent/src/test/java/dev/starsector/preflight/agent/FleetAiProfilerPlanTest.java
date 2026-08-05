package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class FleetAiProfilerPlanTest {
    private static final String ABILITY = "test/Ability";

    @BeforeEach
    void enable() {
        FleetAiProfilerRuntime.beginSession();
    }

    @AfterEach
    void reset() {
        System.clearProperty(FleetAiProfilerRuntime.DISABLED_PROPERTY);
        FleetAiProfilerRuntime.beginSession();
    }

    @Test
    void constantLabelWhenProfilerIsOffAndExactLabelWhenOn() throws Exception {
        byte[] fleet = fleetFixture();
        byte[] profiler = profilerFixture();
        byte[] transformedFleet = FleetAiProfilerPlan.transform(
                exact(fleet, FleetAiProfilerPlan.FLEET_AI_SHA256), fleet);
        byte[] transformedProfiler = FleetAiProfilerPlan.transform(
                exact(profiler, FleetAiProfilerPlan.PROFILER_SHA256), profiler);
        assertNotNull(transformedFleet);
        assertNotNull(transformedProfiler);
        assertEquals(1, calls(method(transformedFleet, FleetAiProfilerPlan.ADVANCE,
                FleetAiProfilerPlan.ADVANCE_DESCRIPTOR),
                FleetAiProfilerRuntime.class.getName().replace('.', '/'), "abilityLabelNeeded"));
        assertEquals(1, calls(method(transformedProfiler, FleetAiProfilerPlan.PROFILER_TOGGLE,
                FleetAiProfilerPlan.PROFILER_TOGGLE_DESCRIPTOR),
                FleetAiProfilerRuntime.class.getName().replace('.', '/'), "profilerState"));
        assertNull(FleetAiProfilerPlan.transform(
                ClassSignature.parse(transformedFleet), transformedFleet));

        ByteArrayLoader loader = new ByteArrayLoader(Map.of(
                FleetAiProfilerPlan.FLEET_AI_CLASS.replace('/', '.'), transformedFleet,
                FleetAiProfilerPlan.PROFILER_CLASS.replace('/', '.'), transformedProfiler,
                ABILITY.replace('/', '.'), abilityFixture()));
        Class<?> abilityType = loader.loadClass(ABILITY.replace('/', '.'));
        Object ability = Proxy.newProxyInstance(loader, new Class<?>[] {abilityType},
                (proxy, method, arguments) -> "ship");
        Class<?> fleetType = loader.loadClass(FleetAiProfilerPlan.FLEET_AI_CLASS.replace('/', '.'));
        Object instance = fleetType.getConstructor(abilityType).newInstance(ability);
        fleetType.getMethod(FleetAiProfilerPlan.ADVANCE, float.class).invoke(instance, 1f);
        Class<?> profilerType = loader.loadClass(FleetAiProfilerPlan.PROFILER_CLASS.replace('/', '.'));
        assertEquals("Ability", profilerType.getField("last").get(null));

        profilerType.getMethod(FleetAiProfilerPlan.PROFILER_TOGGLE, boolean.class)
                .invoke(null, true);
        fleetType.getMethod(FleetAiProfilerPlan.ADVANCE, float.class).invoke(instance, 1f);
        assertEquals("Ability [ship]", profilerType.getField("last").get(null));
        profilerType.getMethod(FleetAiProfilerPlan.PROFILER_TOGGLE, boolean.class)
                .invoke(null, false);
        fleetType.getMethod(FleetAiProfilerPlan.ADVANCE, float.class).invoke(instance, 1f);
        assertEquals("Ability", profilerType.getField("last").get(null));
        assertEquals(2L, FleetAiProfilerRuntime.telemetry().get("labelsAvoided"));
        assertEquals(1L, FleetAiProfilerRuntime.telemetry().get("labelsDelegated"));
    }

    @Test
    void partialInstallationAndKillSwitchRetainVanillaLabel() throws Exception {
        assertTrue(FleetAiProfilerRuntime.abilityLabelNeeded());
        byte[] fleet = fleetFixture();
        System.setProperty(FleetAiProfilerRuntime.DISABLED_PROPERTY, "true");
        FleetAiProfilerRuntime.beginSession();
        assertNull(FleetAiProfilerPlan.transform(
                exact(fleet, FleetAiProfilerPlan.FLEET_AI_SHA256), fleet));
    }

    private static byte[] fleetFixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                FleetAiProfilerPlan.FLEET_AI_CLASS, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "ability", "L" + ABILITY + ";", null, null).visitEnd();
        MethodVisitor constructor = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "<init>", "(L" + ABILITY + ";)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ALOAD, 1);
        constructor.visitFieldInsn(Opcodes.PUTFIELD,
                FleetAiProfilerPlan.FLEET_AI_CLASS, "ability", "L" + ABILITY + ";");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(2, 2);
        constructor.visitEnd();

        MethodVisitor advance = writer.visitMethod(Opcodes.ACC_PUBLIC,
                FleetAiProfilerPlan.ADVANCE, FleetAiProfilerPlan.ADVANCE_DESCRIPTOR, null, null);
        advance.visitCode();
        advance.visitVarInsn(Opcodes.ALOAD, 0);
        advance.visitFieldInsn(Opcodes.GETFIELD,
                FleetAiProfilerPlan.FLEET_AI_CLASS, "ability", "L" + ABILITY + ";");
        advance.visitVarInsn(Opcodes.ASTORE, 5);
        advance.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        advance.visitInsn(Opcodes.DUP);
        advance.visitLdcInsn("Ability [");
        advance.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
        advance.visitVarInsn(Opcodes.ALOAD, 5);
        advance.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                ABILITY, "getId", "()Ljava/lang/String;", true);
        advance.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        advance.visitLdcInsn("]");
        advance.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        advance.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                "()Ljava/lang/String;", false);
        advance.visitMethodInsn(Opcodes.INVOKESTATIC,
                FleetAiProfilerPlan.PROFILER_CLASS, "new", "(Ljava/lang/String;)V", false);
        advance.visitInsn(Opcodes.RETURN);
        advance.visitMaxs(3, 6);
        advance.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] profilerFixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                FleetAiProfilerPlan.PROFILER_CLASS, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "enabled", "Z", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "last", "Ljava/lang/String;", null, null).visitEnd();
        MethodVisitor toggle = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                FleetAiProfilerPlan.PROFILER_TOGGLE,
                FleetAiProfilerPlan.PROFILER_TOGGLE_DESCRIPTOR, null, null);
        toggle.visitCode();
        toggle.visitVarInsn(Opcodes.ILOAD, 0);
        toggle.visitFieldInsn(Opcodes.PUTSTATIC,
                FleetAiProfilerPlan.PROFILER_CLASS, "enabled", "Z");
        toggle.visitInsn(Opcodes.RETURN);
        toggle.visitMaxs(1, 1);
        toggle.visitEnd();
        MethodVisitor begin = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "new", "(Ljava/lang/String;)V", null, null);
        begin.visitCode();
        begin.visitVarInsn(Opcodes.ALOAD, 0);
        begin.visitFieldInsn(Opcodes.PUTSTATIC,
                FleetAiProfilerPlan.PROFILER_CLASS, "last", "Ljava/lang/String;");
        begin.visitInsn(Opcodes.RETURN);
        begin.visitMaxs(1, 1);
        begin.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] abilityFixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                ABILITY, null, "java/lang/Object", null);
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "getId", "()Ljava/lang/String;", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassSignature exact(byte[] bytes, String hash) throws Exception {
        ClassSignature parsed = ClassSignature.parse(bytes);
        return new ClassSignature(parsed.internalName(), hash, parsed.majorVersion(),
                parsed.access(), parsed.methods());
    }

    private static MethodNode method(byte[] bytes, String name, String descriptor) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream()
                .filter(candidate -> name.equals(candidate.name) && descriptor.equals(candidate.desc))
                .findFirst().orElseThrow();
    }

    private static int calls(MethodNode method, String owner, String name) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) result++;
        }
        return result;
    }

    private static final class ByteArrayLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        private ByteArrayLoader(Map<String, byte[]> classes) {
            super(FleetAiProfilerPlanTest.class.getClassLoader());
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
