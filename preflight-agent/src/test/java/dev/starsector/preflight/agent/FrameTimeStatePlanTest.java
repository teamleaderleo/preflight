package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
    private static final String CONTROL_RUNTIME =
            InternalGameControlRuntime.class.getName().replace('.', '/');
    private static final String CAMPAIGN_ENGINE = "com/fs/starfarer/campaign/CampaignEngine";

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void enable() {
        FrameTimeRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        FrameTimeRuntime.reset();
        RuntimeSemanticState.reset();
    }

    @Test
    void marksCampaignForSemanticAutomationWithoutFrameCollection() throws Exception {
        FrameTimeRuntime.beginSession(false);
        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));

        assertObserver(FrameTimeStatePlan.CAMPAIGN_CLASS,
                FrameTimeStatePlan.CAMPAIGN_SHA256, "observeCampaign");
    }

    @Test
    void marksCampaignAtItsReviewedAdvanceSeam() throws Exception {
        assertObserver(FrameTimeStatePlan.CAMPAIGN_CLASS,
                FrameTimeStatePlan.CAMPAIGN_SHA256, "observeCampaign");
    }

    @Test
    void marksLinuxCampaignAtItsReviewedAdvanceSeam() throws Exception {
        assertObserver(FrameTimeStatePlan.CAMPAIGN_CLASS,
                FrameTimeStatePlan.LINUX_CAMPAIGN_SHA256, "observeCampaign");
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

    @Test
    void declinesWhenReviewedPauseSeamIsMissing() throws Exception {
        byte[] missingField = fixture(FrameTimeStatePlan.CAMPAIGN_CLASS, false, true);
        assertNull(FrameTimeStatePlan.transform(
                exactSignature(missingField, FrameTimeStatePlan.CAMPAIGN_SHA256), missingField));

        byte[] missingCall = fixture(FrameTimeStatePlan.CAMPAIGN_CLASS, true, false);
        assertNull(FrameTimeStatePlan.transform(
                exactSignature(missingCall, FrameTimeStatePlan.CAMPAIGN_SHA256), missingCall));
    }

    private static void assertObserver(String className, String hash, String observer)
            throws Exception {
        byte[] original = fixture(className);
        byte[] transformed = FrameTimeStatePlan.transform(exactSignature(original, hash), original);
        assertNotNull(transformed);
        ClassNode owner = read(transformed);
        assertEquals(1, calls(method(owner), RUNTIME, observer, "()V"));
        assertEquals(1, calls(method(owner), RUNTIME, "observeCampaignPaused", "(Z)V"));
        assertEquals(2, calls(method(owner), CAMPAIGN_ENGINE, "isPaused", "()Z"));
        MethodNode processInput = processInput(owner);
        assertEquals(1, calls(processInput, CONTROL_RUNTIME, "campaignInput",
                "(Ljava/lang/Object;Ljava/lang/Object;)V"));
        assertEquals(1, calls(processInput, CONTROL_RUNTIME, "campaignInputComplete",
                "(Ljava/lang/Object;)V"));
    }

    private static byte[] fixture(String className) {
        return fixture(className, true, true);
    }

    private static byte[] fixture(String className, boolean includeEngineField,
            boolean includePauseCall) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className,
                null, "java/lang/Object", null);
        if (includeEngineField) {
            writer.visitField(Opcodes.ACC_PRIVATE, FrameTimeStatePlan.ENGINE_FIELD,
                    FrameTimeStatePlan.ENGINE_DESCRIPTOR, null, null).visitEnd();
        }
        MethodVisitor advance = writer.visitMethod(Opcodes.ACC_PUBLIC,
                FrameTimeStatePlan.ADVANCE_METHOD,
                FrameTimeStatePlan.ADVANCE_DESCRIPTOR, null, null);
        advance.visitCode();
        if (includePauseCall) {
            advance.visitInsn(Opcodes.ACONST_NULL);
            advance.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CAMPAIGN_ENGINE,
                    "isPaused", "()Z", false);
            advance.visitInsn(Opcodes.POP);
        }
        advance.visitInsn(Opcodes.RETURN);
        advance.visitMaxs(0, 0);
        advance.visitEnd();
        MethodVisitor processInput = writer.visitMethod(Opcodes.ACC_PROTECTED,
                FrameTimeStatePlan.PROCESS_INPUT_METHOD,
                FrameTimeStatePlan.PROCESS_INPUT_DESCRIPTOR, null, null);
        processInput.visitCode();
        processInput.visitInsn(Opcodes.RETURN);
        processInput.visitMaxs(0, 0);
        processInput.visitEnd();
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

    private static MethodNode processInput(ClassNode owner) {
        return owner.methods.stream()
                .filter(candidate -> FrameTimeStatePlan.PROCESS_INPUT_METHOD.equals(candidate.name)
                        && FrameTimeStatePlan.PROCESS_INPUT_DESCRIPTOR.equals(candidate.desc))
                .findFirst().orElseThrow();
    }

    private static int calls(MethodNode method, String owner, String name, String descriptor) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && descriptor.equals(call.desc)) result++;
        }
        return result;
    }
}
