package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
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

class CombatRuntimeIntegrityPlanTest {
    private static final String INTEGRITY_RUNTIME =
            CombatRuntimeIntegrityRuntime.class.getName().replace('.', '/');
    private static final String FRAME_RUNTIME = FrameTimeRuntime.class.getName().replace('.', '/');
    private static final String CONTROL_RUNTIME =
            InternalGameControlRuntime.class.getName().replace('.', '/');

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void reset() {
        System.clearProperty("preflight.desktopSmoke");
        CombatRuntimeIntegrityRuntime.beginSession();
        FrameTimeRuntime.reset();
        InternalGameControlRuntime.reset();
        RuntimeSemanticState.reset();
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
    }

    @Test
    void alwaysObservesIntegrityAndComposesOptInFrameState() throws Exception {
        byte[] original = fixture();
        FrameTimeRuntime.beginSession(false);
        byte[] integrityOnly = CombatRuntimeIntegrityPlan.transform(exactSignature(original), original);
        assertNotNull(integrityOnly);
        assertEquals(1, calls(method(read(integrityOnly)), INTEGRITY_RUNTIME, "observe"));
        assertEquals(0, calls(method(read(integrityOnly)), FRAME_RUNTIME, "observeCombat"));

        CombatRuntimeIntegrityRuntime.beginSession();
        FrameTimeRuntime.beginSession(true);
        byte[] withFrames = CombatRuntimeIntegrityPlan.transform(exactSignature(original), original);
        assertNotNull(withFrames);
        assertEquals(1, calls(method(read(withFrames)), INTEGRITY_RUNTIME, "observe"));
        assertEquals(1, calls(method(read(withFrames)), FRAME_RUNTIME, "observeCombat"));
    }

    @Test
    void composesClosedCombatControlOnlyForDesktopSmoke() throws Exception {
        byte[] original = fixture();
        System.setProperty("preflight.desktopSmoke", "true");
        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));
        InternalGameControlRuntime.beginSession(temporaryDirectory.resolve("adapter.json"));

        byte[] transformed = CombatRuntimeIntegrityPlan.transform(exactSignature(original), original);

        assertNotNull(transformed);
        assertEquals(1, calls(method(read(transformed)), CONTROL_RUNTIME, "combatAdvance"));
    }

    @Test
    void insertsClosedControlImmediatelyAfterTheRealCombatInputBatchIsGenerated() throws Exception {
        byte[] original = combatStateFixture();
        assertNull(CombatRuntimeIntegrityPlan.transform(
                combatStateSignature(original), original));

        System.setProperty("preflight.desktopSmoke", "true");
        RuntimeSemanticState.beginSession(temporaryDirectory.resolve("runtime-state.json"));
        InternalGameControlRuntime.beginSession(temporaryDirectory.resolve("adapter.json"));

        byte[] transformed = CombatRuntimeIntegrityPlan.transform(
                combatStateSignature(original), original);

        assertNotNull(transformed);
        MethodNode traverse = read(transformed).methods.stream()
                .filter(candidate -> CombatRuntimeIntegrityPlan.TRAVERSE_METHOD.equals(candidate.name)
                        && CombatRuntimeIntegrityPlan.TRAVERSE_DESCRIPTOR.equals(candidate.desc))
                .findFirst().orElseThrow();
        assertEquals(1, calls(traverse, CONTROL_RUNTIME, "combatInput"));
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
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                CombatRuntimeIntegrityPlan.TARGET_CLASS, null, "java/lang/Object", null);
        MethodVisitor advance = writer.visitMethod(Opcodes.ACC_PUBLIC,
                CombatRuntimeIntegrityPlan.ADVANCE_METHOD,
                CombatRuntimeIntegrityPlan.ADVANCE_DESCRIPTOR, null, null);
        advance.visitCode();
        advance.visitInsn(Opcodes.RETURN);
        advance.visitMaxs(0, 3);
        advance.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] combatStateFixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                CombatRuntimeIntegrityPlan.COMBAT_STATE_CLASS, null, "java/lang/Object", null);
        MethodVisitor traverse = writer.visitMethod(Opcodes.ACC_PUBLIC,
                CombatRuntimeIntegrityPlan.TRAVERSE_METHOD,
                CombatRuntimeIntegrityPlan.TRAVERSE_DESCRIPTOR, null, null);
        traverse.visitCode();
        traverse.visitMethodInsn(Opcodes.INVOKESTATIC,
                "com/fs/starfarer/util/super/A", "Object",
                "()Lcom/fs/starfarer/util/super/B;", false);
        traverse.visitVarInsn(Opcodes.ASTORE, 1);
        traverse.visitInsn(Opcodes.ACONST_NULL);
        traverse.visitInsn(Opcodes.ARETURN);
        traverse.visitMaxs(1, 2);
        traverse.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassSignature exactSignature(byte[] bytes) throws Exception {
        ClassSignature parsed = ClassSignature.parse(bytes);
        return new ClassSignature(parsed.internalName(),
                CombatRuntimeIntegrityPlan.ORIGINAL_SHA256, parsed.majorVersion(),
                parsed.access(), parsed.methods());
    }

    private static ClassSignature combatStateSignature(byte[] bytes) throws Exception {
        ClassSignature parsed = ClassSignature.parse(bytes);
        return new ClassSignature(parsed.internalName(),
                CombatRuntimeIntegrityPlan.COMBAT_STATE_SHA256, parsed.majorVersion(),
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
