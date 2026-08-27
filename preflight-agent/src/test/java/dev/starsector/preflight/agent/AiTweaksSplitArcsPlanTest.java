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
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

class AiTweaksSplitArcsPlanTest {
    private static final String ARRAY_LIST = "java/util/ArrayList";

    @AfterEach
    void clearProperty() {
        System.clearProperty(AiTweaksSplitArcsPlan.ENABLED_PROPERTY);
    }

    @Test
    void remainsInertUnlessExplicitlyEnabled() {
        assertNull(AiTweaksSplitArcsPlan.transform(signature(), fixture(2)));
    }

    @Test
    void preSizesBothBoundedTemporaryLists() throws Exception {
        System.setProperty(AiTweaksSplitArcsPlan.ENABLED_PROPERTY, "true");
        byte[] transformed = AiTweaksSplitArcsPlan.transform(signature(), fixture(2));
        assertNotNull(transformed);

        ClassNode owner = parse(transformed);
        MethodNode method = unique(owner);
        assertEquals(0, constructors(method, "()V"));
        assertEquals(2, constructors(method, "(I)V"));
        assertEquals(2, calls(method, "java/util/List", "size", "()I"));
        assertEquals(1, opcodes(method, Opcodes.IMUL));
        assertEquals(1, opcodes(method, Opcodes.ICONST_2));
    }

    @Test
    void changedShapeWrongHashAndSecondRewriteFailClosed() throws Exception {
        System.setProperty(AiTweaksSplitArcsPlan.ENABLED_PROPERTY, "true");
        ClassSignature exact = signature();
        assertNull(AiTweaksSplitArcsPlan.transform(new ClassSignature(
                exact.internalName(), "0".repeat(64), exact.majorVersion(), exact.access(),
                exact.methods()), fixture(2)));
        assertNull(AiTweaksSplitArcsPlan.transform(exact, fixture(1)));
        byte[] once = AiTweaksSplitArcsPlan.transform(exact, fixture(2));
        assertNotNull(once);
        assertNull(AiTweaksSplitArcsPlan.transform(exact, once));
    }

    @Test
    void targetPinsTheReviewedClassAndArchive() {
        AdapterTarget target = AdapterTargetRegistry.aiTweaksSplitArcsTarget();
        assertTrue(AdapterTransformationRegistry.hasPlan(target.planId()));
        assertEquals(AiTweaksSplitArcsPlan.ORIGINAL_SHA256, target.sha256());
        assertEquals("9f6179bcd2df2e3ce8cea2da79051c9f1be3c9b71712c6c28d7568b777ecf5b2",
                target.sourceSha256());
    }

    private static ClassSignature signature() {
        return new ClassSignature(
                AiTweaksSplitArcsPlan.TARGET_CLASS,
                AiTweaksSplitArcsPlan.ORIGINAL_SHA256,
                61,
                Opcodes.ACC_PUBLIC,
                List.of(new ClassSignature.Method(
                        AiTweaksSplitArcsPlan.METHOD,
                        AiTweaksSplitArcsPlan.DESCRIPTOR,
                        Opcodes.ACC_PRIVATE)));
    }

    private static byte[] fixture(int emptyConstructors) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                AiTweaksSplitArcsPlan.TARGET_CLASS, null, "java/lang/Object", null);
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE,
                AiTweaksSplitArcsPlan.METHOD,
                AiTweaksSplitArcsPlan.DESCRIPTOR,
                null,
                null);
        for (int index = 0; index < emptyConstructors; index++) {
            method.instructions.add(new TypeInsnNode(Opcodes.NEW, ARRAY_LIST));
            method.instructions.add(new InsnNode(Opcodes.DUP));
            method.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL, ARRAY_LIST, "<init>", "()V", false));
            if (index + 1 < emptyConstructors) method.instructions.add(new InsnNode(Opcodes.POP));
        }
        if (emptyConstructors == 0) method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
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

    private static MethodNode unique(ClassNode owner) {
        return owner.methods.stream()
                .filter(method -> AiTweaksSplitArcsPlan.METHOD.equals(method.name)
                        && AiTweaksSplitArcsPlan.DESCRIPTOR.equals(method.desc))
                .findFirst().orElseThrow();
    }

    private static int constructors(MethodNode method, String descriptor) {
        return calls(method, ARRAY_LIST, "<init>", descriptor);
    }

    private static int calls(MethodNode method, String owner, String name, String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
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
