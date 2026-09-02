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

class DisplayThreadSpecStoreProbePlanTest {
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/DisplayThreadSpecStoreProbeRuntime";

    @AfterEach
    void clear() {
        System.clearProperty(DisplayThreadSpecStoreProbeRuntime.ENABLED_PROPERTY);
    }

    @Test
    void bracketsTheUniqueSpecStoreCallWithExceptionalRestoration() throws Exception {
        System.setProperty(DisplayThreadSpecStoreProbeRuntime.ENABLED_PROPERTY, "on");
        byte[] original = fixture(1);
        byte[] transformed = DisplayThreadSpecStoreProbePlan.transform(
                exactSignature(original), original);

        assertNotNull(transformed);
        MethodNode init = init(transformed);
        assertEquals(1, calls(init, RUNTIME, "beforeSpecStore"));
        assertEquals(2, calls(init, RUNTIME, "afterSpecStore"));
        assertEquals(1, init.tryCatchBlocks.size());
        assertNull(DisplayThreadSpecStoreProbePlan.transform(
                ClassSignature.parse(transformed), transformed));
        assertNull(DisplayThreadSpecStoreProbePlan.transform(
                exactSignature(fixture(2)), fixture(2)));
    }

    private static byte[] fixture(int calls) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                DisplayThreadSpecStoreProbePlan.TARGET_CLASS, null, "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC,
                DisplayThreadSpecStoreProbePlan.INIT_METHOD,
                DisplayThreadSpecStoreProbePlan.INIT_DESCRIPTOR, null, null);
        init.visitCode();
        for (int index = 0; index < calls; index++) {
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/fs/starfarer/loading/SpecStore", "o00000",
                    "(Lcom/fs/starfarer/loading/ResourceLoaderState;)V", false);
        }
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 2);
        init.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassSignature exactSignature(byte[] bytes) throws Exception {
        ClassSignature parsed = ClassSignature.parse(bytes);
        return new ClassSignature(parsed.internalName(),
                DisplayThreadSpecStoreProbePlan.ORIGINAL_SHA256,
                parsed.majorVersion(), parsed.access(), parsed.methods());
    }

    private static MethodNode init(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream()
                .filter(method -> DisplayThreadSpecStoreProbePlan.INIT_METHOD.equals(method.name)
                        && DisplayThreadSpecStoreProbePlan.INIT_DESCRIPTOR.equals(method.desc))
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
}
