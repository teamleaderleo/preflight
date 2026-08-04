package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.AbstractList;
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

class MagicLibPaintjobNotificationPlanTest {
    private static final String TARGET = MagicLibPaintjobNotificationPlan.TARGET_CLASS;
    private static final String RUNTIME =
            MagicLibPaintjobNotificationRuntime.class.getName().replace('.', '/');
    private static final String FIELD = "completedPaintjobIdsThatUserHasBeenNotifiedFor";

    @AfterEach
    void reset() {
        MagicLibPaintjobNotificationRuntime.reset();
    }

    @Test
    void replacesOneContainsAndInvalidatesAfterEveryReviewedMutation() {
        byte[] transformed = MagicLibPaintjobNotificationPlan.transform(signature(), fixture(true));
        assertNotNull(transformed);
        ClassNode owner = read(transformed);
        assertEquals(1, calls(owner, RUNTIME, "contains"));
        assertEquals(1, calls(owner, RUNTIME, "mutated", "()V"));
        assertEquals(2, calls(owner, RUNTIME, "mutated", "(Z)Z"));
        assertEquals(0, calls(method(owner, "advance"), "java/util/List", "contains"));
    }

    @Test
    void sameSizeReplacementAndIncrementalAddRemainCorrect() throws Exception {
        DynamicLoader loader = new DynamicLoader(getClass().getClassLoader());
        Class<?> target = loader.define(TARGET.replace('/', '.'),
                MagicLibPaintjobNotificationPlan.transform(signature(), fixture(true)));
        Method replace = target.getMethod("replace", String.class);
        Method add = target.getMethod("add", String.class);
        Method advance = target.getMethod("advance", float.class);
        Method last = target.getMethod("last");

        replace.invoke(null, "alpha");
        advance.invoke(null, 0f);
        assertTrue((Boolean) last.invoke(null));

        // The list still has size one. Mutation hooks, rather than a size heuristic, refresh it.
        replace.invoke(null, "beta");
        advance.invoke(null, 0f);
        assertFalse((Boolean) last.invoke(null));

        add.invoke(null, "alpha");
        advance.invoke(null, 0f);
        assertTrue((Boolean) last.invoke(null));
        assertEquals(3L, MagicLibPaintjobNotificationRuntime.telemetry().get("rebuilds"));
        assertEquals(5L, MagicLibPaintjobNotificationRuntime.telemetry().get("mutations"));
        assertEquals(0L, MagicLibPaintjobNotificationRuntime.telemetry().get("delegated"));
    }

    @Test
    void wrongHashWrongBodyAndSecondRewriteFailClosed() {
        byte[] original = fixture(true);
        ClassSignature exact = signature();
        assertNull(MagicLibPaintjobNotificationPlan.transform(new ClassSignature(
                exact.internalName(), "0".repeat(64), exact.majorVersion(), exact.access(),
                exact.methods()), original));
        assertNull(MagicLibPaintjobNotificationPlan.transform(exact, fixture(false)));
        byte[] once = MagicLibPaintjobNotificationPlan.transform(exact, original);
        assertNotNull(once);
        assertNull(MagicLibPaintjobNotificationPlan.transform(exact, once));
    }

    @Test
    void snapshotFailureDelegatesToTheOriginalListContains() {
        List<Object> values = new AbstractList<>() {
            @Override
            public Object get(int index) {
                throw new IllegalStateException("snapshot disabled");
            }

            @Override
            public int size() {
                return 1;
            }

            @Override
            public boolean contains(Object value) {
                return "alpha".equals(value);
            }
        };
        assertTrue(MagicLibPaintjobNotificationRuntime.contains(values, "alpha"));
        assertEquals(1L, MagicLibPaintjobNotificationRuntime.telemetry().get("delegated"));
        assertEquals(1L, MagicLibPaintjobNotificationRuntime.telemetry().get("failures"));
    }

    private static byte[] fixture(boolean includeContains) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                TARGET, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                FIELD, "Ljava/util/List;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "last", "Z", null, null)
                .visitEnd();

        MethodVisitor initialize = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V",
                null, null);
        initialize.visitCode();
        initialize.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        initialize.visitInsn(Opcodes.DUP);
        initialize.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>",
                "()V", false);
        initialize.visitFieldInsn(Opcodes.PUTSTATIC, TARGET, FIELD, "Ljava/util/List;");
        initialize.visitInsn(Opcodes.RETURN);
        initialize.visitMaxs(0, 0);
        initialize.visitEnd();

        MethodVisitor replace = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "replace", "(Ljava/lang/String;)V", null, null);
        replace.visitCode();
        replace.visitFieldInsn(Opcodes.GETSTATIC, TARGET, FIELD, "Ljava/util/List;");
        replace.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "clear", "()V", true);
        replace.visitFieldInsn(Opcodes.GETSTATIC, TARGET, FIELD, "Ljava/util/List;");
        replace.visitVarInsn(Opcodes.ALOAD, 0);
        replace.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add",
                "(Ljava/lang/Object;)Z", true);
        replace.visitInsn(Opcodes.POP);
        replace.visitInsn(Opcodes.RETURN);
        replace.visitMaxs(0, 0);
        replace.visitEnd();

        MethodVisitor add = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "add", "(Ljava/lang/String;)V", null, null);
        add.visitCode();
        add.visitFieldInsn(Opcodes.GETSTATIC, TARGET, FIELD, "Ljava/util/List;");
        add.visitVarInsn(Opcodes.ALOAD, 0);
        add.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "add",
                "(Ljava/lang/Object;)Z", true);
        add.visitInsn(Opcodes.POP);
        add.visitInsn(Opcodes.RETURN);
        add.visitMaxs(0, 0);
        add.visitEnd();

        MethodVisitor advance = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "advance", "(F)V", null, null);
        advance.visitCode();
        if (includeContains) {
            advance.visitFieldInsn(Opcodes.GETSTATIC, TARGET, FIELD, "Ljava/util/List;");
            advance.visitLdcInsn("alpha");
            advance.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "contains",
                    "(Ljava/lang/Object;)Z", true);
        } else {
            advance.visitInsn(Opcodes.ICONST_0);
        }
        advance.visitFieldInsn(Opcodes.PUTSTATIC, TARGET, "last", "Z");
        advance.visitInsn(Opcodes.RETURN);
        advance.visitMaxs(0, 0);
        advance.visitEnd();

        MethodVisitor last = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "last", "()Z", null, null);
        last.visitCode();
        last.visitFieldInsn(Opcodes.GETSTATIC, TARGET, "last", "Z");
        last.visitInsn(Opcodes.IRETURN);
        last.visitMaxs(0, 0);
        last.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassSignature signature() {
        return new ClassSignature(TARGET, MagicLibPaintjobNotificationPlan.ORIGINAL_SHA256,
                61, Opcodes.ACC_PUBLIC,
                List.of(new ClassSignature.Method("advance", "(F)V",
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)));
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode result = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(result, ClassReader.EXPAND_FRAMES);
        return result;
    }

    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(candidate -> name.equals(candidate.name))
                .findFirst().orElse(null);
    }

    private static int calls(ClassNode owner, String callOwner, String name) {
        return owner.methods.stream().mapToInt(method -> calls(method, callOwner, name)).sum();
    }

    private static int calls(ClassNode owner, String callOwner, String name, String descriptor) {
        return owner.methods.stream()
                .mapToInt(method -> calls(method, callOwner, name, descriptor)).sum();
    }

    private static int calls(MethodNode method, String callOwner, String name) {
        return calls(method, callOwner, name, null);
    }

    private static int calls(MethodNode method, String callOwner, String name, String descriptor) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && callOwner.equals(call.owner) && name.equals(call.name)
                    && (descriptor == null || descriptor.equals(call.desc))) {
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
