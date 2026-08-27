package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class AiTweaksAffineVectorPlanTest {
    private static final String EXTENSIONS = "com/genir/aitweaks/core/extensions/Vector2fKt";

    @AfterEach
    void clearProperty() {
        System.clearProperty(AiTweaksAffineVectorPlan.ENABLED_PROPERTY);
    }

    @Test
    void remainsInertUnlessExplicitlyEnabled() {
        var target = AiTweaksAffineVectorPlan.TARGETS.get(0);
        assertNull(AiTweaksAffineVectorPlan.transform(signature(target), fixture(target, true)));
    }

    @Test
    void fusesOneExactAffineExpressionInEveryReviewedTarget() throws Exception {
        System.setProperty(AiTweaksAffineVectorPlan.ENABLED_PROPERTY, "true");
        for (var target : AiTweaksAffineVectorPlan.TARGETS) {
            byte[] transformed = AiTweaksAffineVectorPlan.transform(
                    signature(target), fixture(target, true));
            assertNotNull(transformed, target.internalName());

            ClassNode owner = parse(transformed);
            MethodNode targetMethod = unique(owner, target.method(), target.descriptor());
            assertEquals(0, calls(targetMethod, EXTENSIONS, "times",
                    AiTweaksAffineVectorPlan.TIMES_DESCRIPTOR));
            assertEquals(0, calls(targetMethod, EXTENSIONS, "plus",
                    AiTweaksAffineVectorPlan.PLUS_DESCRIPTOR));
            assertEquals(1, calls(targetMethod, owner.name,
                    AiTweaksAffineVectorPlan.AFFINE_METHOD,
                    AiTweaksAffineVectorPlan.AFFINE_DESCRIPTOR));

            MethodNode helper = unique(
                    owner,
                    AiTweaksAffineVectorPlan.AFFINE_METHOD,
                    AiTweaksAffineVectorPlan.AFFINE_DESCRIPTOR);
            assertEquals(2, opcodes(helper, Opcodes.FMUL));
            assertEquals(2, opcodes(helper, Opcodes.FADD));
            assertEquals(1, calls(helper, AiTweaksAffineVectorPlan.VECTOR, "<init>", "(FF)V"));
        }
    }

    @Test
    void changedShapeWrongHashAndSecondRewriteFailClosed() throws Exception {
        System.setProperty(AiTweaksAffineVectorPlan.ENABLED_PROPERTY, "true");
        var target = AiTweaksAffineVectorPlan.TARGETS.get(0);
        byte[] original = fixture(target, true);
        ClassSignature exact = signature(target);
        assertNull(AiTweaksAffineVectorPlan.transform(new ClassSignature(
                exact.internalName(),
                "0".repeat(64),
                exact.majorVersion(),
                exact.access(),
                exact.methods()), original));
        assertNull(AiTweaksAffineVectorPlan.transform(exact, fixture(target, false)));
        byte[] once = AiTweaksAffineVectorPlan.transform(exact, original);
        assertNotNull(once);
        assertNull(AiTweaksAffineVectorPlan.transform(exact, once));
    }

    @Test
    void registryPinsAllThreeReviewedClassesAndOneArchive() {
        List<AdapterTarget> targets = AdapterTargetRegistry.aiTweaksAffineVectorTargets();
        assertEquals(3, targets.size());
        for (int index = 0; index < targets.size(); index++) {
            var expected = AiTweaksAffineVectorPlan.TARGETS.get(index);
            var actual = targets.get(index);
            assertTrue(AdapterTransformationRegistry.hasPlan(actual.planId()));
            assertEquals(expected.internalName(), actual.internalClassName());
            assertEquals(expected.sha256(), actual.sha256());
            assertEquals(AiTweaksAffineVectorPlan.SOURCE_SHA256, actual.sourceSha256());
        }
    }

    private static ClassSignature signature(AiTweaksAffineVectorPlan.Target target) {
        return new ClassSignature(
                target.internalName(),
                target.sha256(),
                61,
                Opcodes.ACC_PUBLIC,
                List.of(new ClassSignature.Method(
                        target.method(), target.descriptor(), Opcodes.ACC_PRIVATE)));
    }

    private static byte[] fixture(AiTweaksAffineVectorPlan.Target target, boolean exactPair) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, target.internalName(), null,
                "java/lang/Object", null);
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE,
                target.method(),
                target.descriptor(),
                null,
                null);
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new InsnNode(Opcodes.FCONST_1));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                EXTENSIONS,
                "times",
                AiTweaksAffineVectorPlan.TIMES_DESCRIPTOR,
                false));
        if (!exactPair) method.instructions.add(new InsnNode(Opcodes.POP));
        if (!exactPair) method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                EXTENSIONS,
                "plus",
                AiTweaksAffineVectorPlan.PLUS_DESCRIPTOR,
                false));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode parse(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static MethodNode unique(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .filter(method -> name.equals(method.name) && descriptor.equals(method.desc))
                .findFirst().orElseThrow();
    }

    private static int calls(MethodNode method, String owner, String name, String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && descriptor.equals(call.desc)) count++;
        }
        return count;
    }

    private static int opcodes(MethodNode method, int opcode) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) count++;
        }
        return count;
    }
}
