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

class FrameTimeStatePlanTest {
    private static final String RUNTIME = FrameTimeRuntime.class.getName().replace('.', '/');

    @BeforeEach
    void enable() {
        FrameTimeRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        FrameTimeRuntime.reset();
    }

    @Test
    void marksCampaignAndCombatAtTheirReviewedAdvanceSeams() throws Exception {
        assertObserver(FrameTimeStatePlan.CAMPAIGN_CLASS,
                FrameTimeStatePlan.CAMPAIGN_SHA256, "observeCampaign");
        assertObserver(FrameTimeStatePlan.COMBAT_CLASS,
                FrameTimeStatePlan.COMBAT_SHA256, "observeCombat");
    }

    @Test
    void declinesDisabledWrongIdentityAndSecondTransform() throws Exception {
        byte[] original = fixture(FrameTimeStatePlan.CAMPAIGN_CLASS);
        FrameTimeRuntime.beginSession(false);
        assertNull(FrameTimeStatePlan.transform(
                exactSignature(original, FrameTimeStatePlan.CAMPAIGN_SHA256), original));

        FrameTimeRuntime.beginSession(true);
        assertNull(FrameTimeStatePlan.transform(ClassSignature.parse(original), original));
        byte[] transformed = FrameTimeStatePlan.transform(
                exactSignature(original, FrameTimeStatePlan.CAMPAIGN_SHA256), original);
        assertNotNull(transformed);
        assertNull(FrameTimeStatePlan.transform(ClassSignature.parse(transformed), transformed));
    }

    private static void assertObserver(String className, String hash, String observer)
            throws Exception {
        byte[] original = fixture(className);
        byte[] transformed = FrameTimeStatePlan.transform(exactSignature(original, hash), original);
        assertNotNull(transformed);
        ClassNode owner = read(transformed);
        assertEquals(1, calls(method(owner), RUNTIME, observer));
    }

    private static byte[] fixture(String className) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className,
                null, "java/lang/Object", null);
        MethodVisitor advance = writer.visitMethod(Opcodes.ACC_PUBLIC,
                FrameTimeStatePlan.ADVANCE_METHOD,
                FrameTimeStatePlan.ADVANCE_DESCRIPTOR, null, null);
        advance.visitCode();
        advance.visitInsn(Opcodes.RETURN);
        advance.visitMaxs(0, 3);
        advance.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassSignature exactSignature(byte[] bytes, String hash) throws Exception {
        ClassSignature parsed = ClassSignature.parse(bytes);
        return new ClassSignature(parsed.internalName(), hash, parsed.majorVersion(),
                parsed.access(), parsed.methods());
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static MethodNode method(ClassNode owner) {
        return owner.methods.stream()
                .filter(candidate -> FrameTimeStatePlan.ADVANCE_METHOD.equals(candidate.name)
                        && FrameTimeStatePlan.ADVANCE_DESCRIPTOR.equals(candidate.desc))
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
