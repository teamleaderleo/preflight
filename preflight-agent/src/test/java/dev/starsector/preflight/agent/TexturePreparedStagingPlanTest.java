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

class TexturePreparedStagingPlanTest {
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/TexturePreparedStagingRuntime";

    @AfterEach
    void resetProperty() {
        System.clearProperty(TexturePreparedStagingRuntime.ENABLED_PROPERTY);
    }

    @Test
    void bracketsTheExactSpecStoreWindowAndRefusesASecondWeave() throws Exception {
        System.setProperty(TexturePreparedStagingRuntime.ENABLED_PROPERTY, "true");
        byte[] original = fixture(true);

        byte[] transformed = TexturePreparedStagingPlan.transform(
                ClassSignature.parse(original), original);

        assertNotNull(transformed);
        MethodNode init = init(transformed);
        assertEquals(1, calls(init, "start"));
        assertEquals(1, calls(init, "stop"));
        assertNull(TexturePreparedStagingPlan.transform(
                ClassSignature.parse(transformed), transformed));
    }

    @Test
    void remainsAbsentWhenDisabledOrTheSpecStoreShapeDrifts() throws Exception {
        byte[] original = fixture(true);
        assertNull(TexturePreparedStagingPlan.transform(
                ClassSignature.parse(original), original));

        System.setProperty(TexturePreparedStagingRuntime.ENABLED_PROPERTY, "true");
        byte[] drifted = fixture(false);
        assertNull(TexturePreparedStagingPlan.transform(
                ClassSignature.parse(drifted), drifted));
    }

    @Test
    void pinsTheWindowsTargetSeparatelyFromTheExistingResourcePlans() {
        AdapterTarget target = AdapterTargetRegistry.windowsTexturePreparedStagingTarget();
        assertEquals(TexturePreparedStagingRuntime.PLAN_ID, target.planId());
        assertEquals(FrameTimeStartupCompletionPlan.WINDOWS_ORIGINAL_SHA256, target.sha256());
        assertEquals("5dd222b9e266d2ac2d63b3dad4983eb05caaf5a247d7dfb82aaeba47ea774cc8",
                target.sourceSha256());
    }

    private static byte[] fixture(boolean includeSpecStore) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, TexturePreparedStagingPlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(
                Opcodes.ACC_PUBLIC,
                TexturePreparedStagingPlan.INIT_METHOD,
                TexturePreparedStagingPlan.INIT_DESCRIPTOR,
                null,
                new String[] {"java/lang/Exception"});
        init.visitCode();
        if (includeSpecStore) {
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "com/fs/starfarer/loading/SpecStore",
                    "differentOnEveryPlatform",
                    "(Lcom/fs/starfarer/loading/ResourceLoaderState;)V",
                    false);
        }
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static MethodNode init(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream()
                .filter(method -> TexturePreparedStagingPlan.INIT_METHOD.equals(method.name)
                        && TexturePreparedStagingPlan.INIT_DESCRIPTOR.equals(method.desc))
                .findFirst().orElseThrow();
    }

    private static int calls(MethodNode method, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && RUNTIME.equals(call.owner)
                    && name.equals(call.name)) {
                count++;
            }
        }
        return count;
    }
}
