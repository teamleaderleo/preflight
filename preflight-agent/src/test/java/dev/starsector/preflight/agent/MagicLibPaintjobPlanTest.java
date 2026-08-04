package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class MagicLibPaintjobPlanTest {
    private static final String MANAGER = "org/magiclib/paintjobs/MagicPaintjobManager";
    private static final String RUNTIME = MagicLibPaintjobRuntime.class.getName().replace('.', '/');

    @AfterEach
    void reset() {
        MagicLibPaintjobRuntime.reset();
    }

    @Test
    void wrapsTheExactAllocationAndLinearScanWithAPreservedFallback() {
        byte[] original = extensionFixture();
        byte[] transformed = MagicLibPaintjobPlan.transform(signature(), original);
        assertNotNull(transformed);

        ClassNode owner = read(transformed);
        MethodNode wrapper = method(owner, MagicLibPaintjobPlan.LOOKUP_METHOD);
        MethodNode fallback = method(owner, "preflight$original$isUnlocked");
        assertNotNull(wrapper);
        assertNotNull(fallback);
        assertEquals(1, calls(wrapper, RUNTIME, "lookup"));
        assertEquals(1, calls(wrapper, MagicLibPaintjobPlan.TARGET_CLASS,
                "preflight$original$isUnlocked"));
        assertEquals(1, calls(fallback, MANAGER, "getUnlockedPaintjobIds"));
        assertEquals(1, calls(fallback, "java/util/List", "contains"));
    }

    @Test
    void wrongHashWrongBodyAndSecondRewriteFailClosed() {
        byte[] original = extensionFixture();
        ClassSignature exact = signature();
        assertNull(MagicLibPaintjobPlan.transform(new ClassSignature(
                exact.internalName(), "0".repeat(64), exact.majorVersion(), exact.access(),
                exact.methods()), original));
        assertNull(MagicLibPaintjobPlan.transform(exact, extensionFixtureWithoutContains()));
        byte[] once = MagicLibPaintjobPlan.transform(exact, original);
        assertNotNull(once);
        assertNull(MagicLibPaintjobPlan.transform(exact, once));
    }

    @Test
    void transformedLookupReadsTheAuthoritativeSetWithoutCopyingTheList() throws Exception {
        DynamicLoader loader = new DynamicLoader(getClass().getClassLoader());
        Class<?> manager = loader.define(MANAGER.replace('/', '.'), managerFixture(true));
        Class<?> spec = loader.define(MagicLibPaintjobPlan.SPEC.replace('/', '.'), specFixture());
        Class<?> extension = loader.define(MagicLibPaintjobPlan.TARGET_CLASS.replace('/', '.'),
                MagicLibPaintjobPlan.transform(signature(), extensionFixture()));

        Method unlock = manager.getMethod("unlock", String.class);
        unlock.invoke(null, "alpha");
        Object alpha = spec.getConstructor(String.class).newInstance("alpha");
        Object beta = spec.getConstructor(String.class).newInstance("beta");
        Method isUnlocked = extension.getMethod(MagicLibPaintjobPlan.LOOKUP_METHOD, spec);
        assertTrue((Boolean) isUnlocked.invoke(null, alpha));
        assertFalse((Boolean) isUnlocked.invoke(null, beta));
        assertEquals(1L, MagicLibPaintjobRuntime.telemetry().get("unlocked"));
        assertEquals(1L, MagicLibPaintjobRuntime.telemetry().get("locked"));
        assertEquals(0L, MagicLibPaintjobRuntime.telemetry().get("delegated"));
    }

    @Test
    void reflectionDriftDelegatesToThePreservedMagicLibMethod() throws Exception {
        DynamicLoader loader = new DynamicLoader(getClass().getClassLoader());
        Class<?> manager = loader.define(MANAGER.replace('/', '.'), managerFixture(false));
        Class<?> spec = loader.define(MagicLibPaintjobPlan.SPEC.replace('/', '.'), specFixture());
        Class<?> extension = loader.define(MagicLibPaintjobPlan.TARGET_CLASS.replace('/', '.'),
                MagicLibPaintjobPlan.transform(signature(), extensionFixture()));

        manager.getMethod("unlock", String.class).invoke(null, "alpha");
        Object alpha = spec.getConstructor(String.class).newInstance("alpha");
        assertTrue((Boolean) extension.getMethod(MagicLibPaintjobPlan.LOOKUP_METHOD, spec)
                .invoke(null, alpha));
        assertEquals(1L, MagicLibPaintjobRuntime.telemetry().get("delegated"));
        assertEquals(1L, MagicLibPaintjobRuntime.telemetry().get("failures"));
    }

    private static byte[] extensionFixture() {
        return extensionFixture(true);
    }

    private static byte[] extensionFixtureWithoutContains() {
        return extensionFixture(false);
    }

    private static byte[] extensionFixture(boolean contains) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                MagicLibPaintjobPlan.TARGET_CLASS, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                MagicLibPaintjobPlan.LOOKUP_METHOD, MagicLibPaintjobPlan.LOOKUP_DESCRIPTOR,
                null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, MANAGER, "getUnlockedPaintjobIds",
                "()Ljava/util/List;", false);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MagicLibPaintjobPlan.SPEC, "getId",
                "()Ljava/lang/String;", false);
        if (contains) {
            method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "contains",
                    "(Ljava/lang/Object;)Z", true);
        } else {
            method.visitInsn(Opcodes.POP2);
            method.visitInsn(Opcodes.ICONST_0);
        }
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(2, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] specFixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                MagicLibPaintjobPlan.SPEC, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "id", "Ljava/lang/String;",
                null, null).visitEnd();
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(Ljava/lang/String;)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V",
                false);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitVarInsn(Opcodes.ALOAD, 1);
        constructor.visitFieldInsn(Opcodes.PUTFIELD, MagicLibPaintjobPlan.SPEC, "id",
                "Ljava/lang/String;");
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(2, 2);
        constructor.visitEnd();
        MethodVisitor getId = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "getId",
                "()Ljava/lang/String;", null, null);
        getId.visitCode();
        getId.visitVarInsn(Opcodes.ALOAD, 0);
        getId.visitFieldInsn(Opcodes.GETFIELD, MagicLibPaintjobPlan.SPEC, "id",
                "Ljava/lang/String;");
        getId.visitInsn(Opcodes.ARETURN);
        getId.visitMaxs(1, 1);
        getId.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A Set for the fast path, or a List to force the runtime's preserved-original fallback. */
    private static byte[] managerFixture(boolean setField) {
        String fieldDescriptor = setField ? "Ljava/util/Set;" : "Ljava/util/List;";
        String implementation = setField ? "java/util/LinkedHashSet" : "java/util/ArrayList";
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, MANAGER, null,
                "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "unlockedPaintjobsInner", fieldDescriptor, null, null).visitEnd();
        MethodVisitor initialize = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V",
                null, null);
        initialize.visitCode();
        initialize.visitTypeInsn(Opcodes.NEW, implementation);
        initialize.visitInsn(Opcodes.DUP);
        initialize.visitMethodInsn(Opcodes.INVOKESPECIAL, implementation, "<init>", "()V", false);
        initialize.visitFieldInsn(Opcodes.PUTSTATIC, MANAGER, "unlockedPaintjobsInner",
                fieldDescriptor);
        initialize.visitInsn(Opcodes.RETURN);
        initialize.visitMaxs(2, 0);
        initialize.visitEnd();
        MethodVisitor unlock = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "unlock",
                "(Ljava/lang/String;)V", null, null);
        unlock.visitCode();
        unlock.visitFieldInsn(Opcodes.GETSTATIC, MANAGER, "unlockedPaintjobsInner", fieldDescriptor);
        unlock.visitVarInsn(Opcodes.ALOAD, 0);
        unlock.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                setField ? "java/util/Set" : "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        unlock.visitInsn(Opcodes.POP);
        unlock.visitInsn(Opcodes.RETURN);
        unlock.visitMaxs(2, 1);
        unlock.visitEnd();
        MethodVisitor getter = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "getUnlockedPaintjobIds", "()Ljava/util/List;", null, null);
        getter.visitCode();
        getter.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        getter.visitInsn(Opcodes.DUP);
        getter.visitFieldInsn(Opcodes.GETSTATIC, MANAGER, "unlockedPaintjobsInner", fieldDescriptor);
        getter.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>",
                "(Ljava/util/Collection;)V", false);
        getter.visitInsn(Opcodes.ARETURN);
        getter.visitMaxs(3, 0);
        getter.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassSignature signature() {
        return new ClassSignature(MagicLibPaintjobPlan.TARGET_CLASS,
                MagicLibPaintjobPlan.ORIGINAL_SHA256, 61, Opcodes.ACC_PUBLIC,
                List.of(new ClassSignature.Method(MagicLibPaintjobPlan.LOOKUP_METHOD,
                        MagicLibPaintjobPlan.LOOKUP_DESCRIPTOR,
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)));
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode result = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(result, ClassReader.EXPAND_FRAMES);
        return result;
    }

    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(method -> name.equals(method.name)).findFirst()
                .orElse(null);
    }

    private static int calls(MethodNode method, String owner, String name) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) {
                result++;
            }
        }
        return result;
    }

    private static final class DynamicLoader extends ClassLoader {
        private DynamicLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
