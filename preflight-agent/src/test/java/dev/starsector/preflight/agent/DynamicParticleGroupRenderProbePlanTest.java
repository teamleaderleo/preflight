package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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

class DynamicParticleGroupRenderProbePlanTest {
    private static final String TARGET = DynamicParticleGroupRenderProbePlan.TARGET_CLASS;
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/DynamicParticleGroupRenderProbeRuntime";
    private static final String GL11 = "org/lwjgl/opengl/GL11";

    @BeforeEach
    void enable() {
        DynamicParticleGroupRenderProbeRuntime.beginSessionForTest(true);
    }

    @AfterEach
    void reset() {
        DynamicParticleGroupRenderProbeRuntime.resetForTest();
    }

    @Test
    void wrapsEveryNormalReturnAndCapturesGlCallsiteFingerprint() throws Exception {
        byte[] original = fixture(TARGET, 2);
        byte[] transformed = DynamicParticleGroupRenderProbePlan.transform(
                ClassSignature.parse(original), original);
        assertNotNull(transformed);

        MethodNode render = method(transformed);
        assertEquals(1, calls(render, RUNTIME, "begin"));
        assertEquals(2, calls(render, RUNTIME, "end"));
        assertEquals(1, calls(render, GL11, "glBegin"));
        assertEquals(1, calls(render, GL11, "glEnd"));
        assertEquals(1, calls(render, GL11, "glVertex2f"));

        var telemetry = DynamicParticleGroupRenderProbeRuntime.telemetry();
        assertEquals(2, telemetry.get("returnSites"));
        assertEquals(1, telemetry.get("glBeginSites"));
        assertEquals(1, telemetry.get("glEndSites"));
        assertEquals(1, telemetry.get("vertexSites"));
    }

    @Test
    void dispatcherCarriesExactExternalParticleTarget() throws Exception {
        byte[] original = fixture(TARGET, 1);
        assertNotNull(FrameTimePlan.transform(ClassSignature.parse(original), original));
    }

    @Test
    void declinesDisabledWrongClassMissingRenderAndSecondApplication() throws Exception {
        byte[] original = fixture(TARGET, 1);
        DynamicParticleGroupRenderProbeRuntime.beginSessionForTest(false);
        assertNull(DynamicParticleGroupRenderProbePlan.transform(
                ClassSignature.parse(original), original));

        DynamicParticleGroupRenderProbeRuntime.beginSessionForTest(true);
        byte[] wrong = fixture("example/Other", 1);
        assertNull(DynamicParticleGroupRenderProbePlan.transform(
                ClassSignature.parse(wrong), wrong));

        byte[] missing = missingRenderFixture();
        assertNull(DynamicParticleGroupRenderProbePlan.transform(
                ClassSignature.parse(missing), missing));

        byte[] transformed = DynamicParticleGroupRenderProbePlan.transform(
                ClassSignature.parse(original), original);
        assertNotNull(transformed);
        assertNull(DynamicParticleGroupRenderProbePlan.transform(
                ClassSignature.parse(transformed), transformed));
    }

    private static byte[] fixture(String className, int returns) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        MethodVisitor render = writer.visitMethod(
                Opcodes.ACC_PUBLIC,
                DynamicParticleGroupRenderProbePlan.RENDER_METHOD,
                DynamicParticleGroupRenderProbePlan.RENDER_DESCRIPTOR,
                null,
                null);
        render.visitCode();
        render.visitInsn(Opcodes.ICONST_0);
        render.visitMethodInsn(Opcodes.INVOKESTATIC, GL11, "glBegin", "(I)V", false);
        render.visitInsn(Opcodes.FCONST_0);
        render.visitInsn(Opcodes.FCONST_0);
        render.visitMethodInsn(Opcodes.INVOKESTATIC, GL11, "glVertex2f", "(FF)V", false);
        render.visitMethodInsn(Opcodes.INVOKESTATIC, GL11, "glEnd", "()V", false);
        if (returns == 2) {
            var second = new org.objectweb.asm.Label();
            render.visitInsn(Opcodes.ICONST_0);
            render.visitJumpInsn(Opcodes.IFEQ, second);
            render.visitInsn(Opcodes.RETURN);
            render.visitLabel(second);
        }
        render.visitInsn(Opcodes.RETURN);
        render.visitMaxs(0, 0);
        render.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] missingRenderFixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static MethodNode method(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream()
                .filter(candidate -> DynamicParticleGroupRenderProbePlan.RENDER_METHOD.equals(candidate.name)
                        && DynamicParticleGroupRenderProbePlan.RENDER_DESCRIPTOR.equals(candidate.desc))
                .findFirst()
                .orElseThrow();
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)) {
                count++;
            }
        }
        return count;
    }
}
