package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class CombatRuntimeIntegrityPlanTest {
    private static final String INTEGRITY_RUNTIME =
            CombatRuntimeIntegrityRuntime.class.getName().replace('.', '/');
    private static final String FRAME_RUNTIME = FrameTimeRuntime.class.getName().replace('.', '/');
    private static final String WORKLOAD_RUNTIME = CombatWorkloadRuntime.class.getName().replace('.', '/');

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void reset() {
        CombatRuntimeIntegrityRuntime.beginSession();
        CombatWorkloadRuntime.reset();
        FrameTimeRuntime.reset();
        RuntimeSemanticState.reset();
        System.clearProperty(CombatWorkloadRuntime.ENABLE_PROPERTY);
        System.clearProperty(CombatWorkloadRuntime.OUTPUT_PROPERTY);
    }

    @Test
    void composesSemanticCombatStateWithoutFrameCollection() throws Exception {
        byte[] original = fixture();
        FrameTimeRuntime.beginSession(false);
        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));

        byte[] transformed = CombatRuntimeIntegrityPlan.transform(exactSignature(original), original);

        assertNotNull(transformed);
        assertEquals(1, calls(method(read(transformed)), INTEGRITY_RUNTIME, "observe"));
        assertEquals(1, calls(method(read(transformed)), FRAME_RUNTIME, "observeCombat"));
        assertEquals(0, calls(method(read(transformed)), WORKLOAD_RUNTIME, "begin"));
        assertEquals(0, calls(method(read(transformed)), WORKLOAD_RUNTIME, "end"));
    }

    @Test
    void alwaysObservesIntegrityAndComposesOptInFrameState() throws Exception {
        byte[] original = fixture();
        FrameTimeRuntime.beginSession(false);
        byte[] integrityOnly = CombatRuntimeIntegrityPlan.transform(exactSignature(original), original);
        assertNotNull(integrityOnly);
        assertEquals(1, calls(method(read(integrityOnly)), INTEGRITY_RUNTIME, "observe"));
        assertEquals(0, calls(method(read(integrityOnly)), FRAME_RUNTIME, "observeCombat"));
        assertEquals(0, calls(method(read(integrityOnly)), WORKLOAD_RUNTIME, "begin"));

        CombatRuntimeIntegrityRuntime.beginSession();
        FrameTimeRuntime.beginSession(true);
        byte[] withFrames = CombatRuntimeIntegrityPlan.transform(exactSignature(original), original);
        assertNotNull(withFrames);
        assertEquals(1, calls(method(read(withFrames)), INTEGRITY_RUNTIME, "observe"));
        assertEquals(1, calls(method(read(withFrames)), FRAME_RUNTIME, "observeCombat"));
    }

    @Test
    void composesOptInWorkloadSnapshotAndTimesEveryExit() throws Exception {
        byte[] original = fixture();
        System.setProperty(CombatWorkloadRuntime.ENABLE_PROPERTY, "true");

        byte[] transformed = CombatRuntimeIntegrityPlan.transform(exactSignature(original), original);

        assertNotNull(transformed);
        MethodNode advance = method(read(transformed));
        assertEquals(1, calls(advance, WORKLOAD_RUNTIME, "begin"));
        assertEquals(2, calls(advance, WORKLOAD_RUNTIME, "end"));
        Map<String, Object> telemetry = CombatWorkloadRuntime.telemetry();
        assertTrue((Boolean) telemetry.get("installed"));
    }

    @Test
    void declinesWrongIdentityAndSecondTransform() throws Exception {
        byte[] original = fixture();
        assertNull(CombatRuntimeIntegrityPlan.transform(ClassSignature.parse(original), original));
        byte[] transformed = CombatRuntimeIntegrityPlan.transform(exactSignature(original), original);
        assertNotNull(transformed);
        assertNull(CombatRuntimeIntegrityPlan.transform(
                ClassSignature.parse(transformed), transformed));
    }

    private static byte[] fixture() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                CombatRuntimeIntegrityPlan.TARGET_CLASS, null, "java/lang/Object", null);
        MethodVisitor advance = writer.visitMethod(Opcodes.ACC_PUBLIC,
                CombatRuntimeIntegrityPlan.ADVANCE_METHOD,
                CombatRuntimeIntegrityPlan.ADVANCE_DESCRIPTOR, null, null);
        advance.visitCode();
        Label throwPath = new Label();
        advance.visitVarInsn(Opcodes.FLOAD, 1);
        advance.visitInsn(Opcodes.FCONST_0);
        advance.visitInsn(Opcodes.FCMPL);
        advance.visitJumpInsn(Opcodes.IFEQ, throwPath);
        advance.visitInsn(Opcodes.RETURN);
        advance.visitLabel(throwPath);
        advance.visitTypeInsn(Opcodes.NEW, "java/lang/RuntimeException");
        advance.visitInsn(Opcodes.DUP);
        advance.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "()V", false);
        advance.visitInsn(Opcodes.ATHROW);
        advance.visitMaxs(0, 0);
        advance.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassSignature exactSignature(byte[] bytes) throws Exception {
        ClassSignature parsed = ClassSignature.parse(bytes);
        return new ClassSignature(parsed.internalName(),
                CombatRuntimeIntegrityPlan.ORIGINAL_SHA256, parsed.majorVersion(),
                parsed.access(), parsed.methods());
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static MethodNode method(ClassNode owner) {
        return owner.methods.stream()
                .filter(candidate -> CombatRuntimeIntegrityPlan.ADVANCE_METHOD.equals(candidate.name)
                        && CombatRuntimeIntegrityPlan.ADVANCE_DESCRIPTOR.equals(candidate.desc))
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
