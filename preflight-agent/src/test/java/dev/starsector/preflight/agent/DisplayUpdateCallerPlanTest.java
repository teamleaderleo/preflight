package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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

class DisplayUpdateCallerPlanTest {
    @AfterEach
    void clear() {
        System.clearProperty(DisplayThreadTextureProbeRuntime.ENABLED_PROPERTY);
    }

    @Test
    void installsImmediatelyAfterTheExactDisplayCallAndRejectsDrift() throws Exception {
        System.setProperty(DisplayThreadTextureProbeRuntime.ENABLED_PROPERTY, "on");
        byte[] original = fixture(1);
        byte[] transformed = DisplayUpdateCallerPlan.transform(exactSignature(original), original);
        assertNotNull(transformed);
        MethodNode method = method(read(transformed));
        int update = callIndex(method, "org/lwjgl/opengl/Display", "update");
        int hook = callIndex(method,
                FrameTimeRuntime.class.getName().replace('.', '/'), "postUpdate");
        assertEquals(update + 1, hook);
        assertNull(DisplayUpdateCallerPlan.transform(ClassSignature.parse(transformed), transformed));

        byte[] drift = fixture(2);
        assertNull(DisplayUpdateCallerPlan.transform(exactSignature(drift), drift));
    }

    @Test
    void composesWithTheEarlierResourceLoaderRewrite() throws Exception {
        System.setProperty(DisplayThreadTextureProbeRuntime.ENABLED_PROPERTY, "on");
        byte[] original = resourceLoaderFixture();
        byte[] transformed = AdapterTransformationRegistry.transform(
                AdapterTargetRegistry.windowsResourcePriorityTarget(),
                exactSignature(original), original);

        assertNotNull(transformed);
        MethodNode method = method(read(transformed));
        int update = callIndex(method, "org/lwjgl/opengl/Display", "update");
        int hook = callIndex(method,
                FrameTimeRuntime.class.getName().replace('.', '/'), "postUpdate");
        assertEquals(update + 1, hook);
    }

    private static byte[] fixture(int updates) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC, DisplayUpdateCallerPlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                DisplayUpdateCallerPlan.METHOD, DisplayUpdateCallerPlan.DESCRIPTOR, null, null);
        method.visitCode();
        for (int index = 0; index < updates; index++) {
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/Display", "update",
                    "()V", false);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] resourceLoaderFixture() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES
                | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, DisplayUpdateCallerPlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "resources", "Ljava/util/List;", null, null)
                .visitEnd();
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC,
                ResourcePriorityPlan.INIT_METHOD, ResourcePriorityPlan.INIT_DESCRIPTOR, null, null);
        init.visitCode();
        init.visitMethodInsn(Opcodes.INVOKESTATIC, TexturePreparedPrefetchPlan.TARGET_CLASS,
                "o00000", "()V", false);
        init.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        init.visitInsn(Opcodes.DUP);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "java/util/ArrayList", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ASTORE, 2);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitFieldInsn(Opcodes.GETFIELD, DisplayUpdateCallerPlan.TARGET_CLASS,
                "resources", "Ljava/util/List;");
        init.visitVarInsn(Opcodes.ALOAD, 2);
        init.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "removeAll",
                "(Ljava/util/Collection;)Z", true);
        init.visitInsn(Opcodes.POP);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitFieldInsn(Opcodes.GETFIELD, DisplayUpdateCallerPlan.TARGET_CLASS,
                "resources", "Ljava/util/List;");
        init.visitInsn(Opcodes.ICONST_0);
        init.visitVarInsn(Opcodes.ALOAD, 2);
        init.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "addAll",
                "(ILjava/util/Collection;)Z", true);
        init.visitInsn(Opcodes.POP);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor render = writer.visitMethod(Opcodes.ACC_PRIVATE,
                DisplayUpdateCallerPlan.METHOD, DisplayUpdateCallerPlan.DESCRIPTOR, null, null);
        render.visitCode();
        render.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/Display", "update",
                "()V", false);
        render.visitInsn(Opcodes.RETURN);
        render.visitMaxs(0, 0);
        render.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassSignature exactSignature(byte[] bytes) throws Exception {
        ClassSignature parsed = ClassSignature.parse(bytes);
        return new ClassSignature(parsed.internalName(), DisplayUpdateCallerPlan.ORIGINAL_SHA256,
                parsed.majorVersion(), parsed.access(), parsed.methods());
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static MethodNode method(ClassNode owner) {
        return owner.methods.stream()
                .filter(method -> DisplayUpdateCallerPlan.METHOD.equals(method.name)
                        && DisplayUpdateCallerPlan.DESCRIPTOR.equals(method.desc))
                .findFirst().orElseThrow();
    }

    private static int callIndex(MethodNode method, String owner, String name) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) return index;
            index++;
        }
        return -1;
    }
}
