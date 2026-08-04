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

class CampaignEngineTimePlanTest {
    private static final String RUNTIME =
            CampaignEngineTimeRuntime.class.getName().replace('.', '/');

    @BeforeEach
    void enable() {
        CampaignEngineTimeRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        CampaignEngineTimeRuntime.reset();
    }

    @Test
    void instrumentsOnlyTheReviewedManagerLocationAndScriptCalls() throws Exception {
        byte[] original = fixture(false);
        byte[] transformed = CampaignEngineTimePlan.transform(exactSignature(original), original);
        assertNotNull(transformed);
        assertNull(CampaignEngineTimePlan.transform(ClassSignature.parse(transformed), transformed));

        MethodNode method = advance(transformed);
        assertEquals(19, calls(method, RUNTIME, "enter"));
        assertEquals(38, calls(method, RUNTIME, "exit"));
        assertEquals(2, calls(method, RUNTIME, "enterScript"));
        assertEquals(4, calls(method, RUNTIME, "exitScript"));
        assertEquals(true, CampaignEngineTimeRuntime.telemetry().get("installed"));
    }

    @Test
    void declinesWrongIdentityDisabledRuntimeAndChangedCallCount() throws Exception {
        byte[] original = fixture(false);
        assertNull(CampaignEngineTimePlan.transform(ClassSignature.parse(original), original));

        CampaignEngineTimeRuntime.beginSession(false);
        assertNull(CampaignEngineTimePlan.transform(exactSignature(original), original));

        CampaignEngineTimeRuntime.beginSession(true);
        byte[] changed = fixture(true);
        assertNull(CampaignEngineTimePlan.transform(exactSignature(changed), changed));
    }

    private static byte[] fixture(boolean omitLast) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, CampaignEngineTimePlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC,
                CampaignEngineTimePlan.METHOD, CampaignEngineTimePlan.DESCRIPTOR, null, null);
        method.visitCode();
        var probes = CampaignEngineTimePlan.probes();
        for (int probeIndex = 0; probeIndex < probes.size(); probeIndex++) {
            CampaignEngineTimePlan.CallProbe probe = probes.get(probeIndex);
            int calls = probe.expectedCalls();
            if (omitLast && probeIndex == probes.size() - 1) calls--;
            for (int call = 0; call < calls; call++) emit(method, probe.owner(), probe.name(),
                    probe.descriptor(), false);
        }
        for (int call = 0; call < 2; call++) emit(method,
                "com/fs/starfarer/api/EveryFrameScript", "advance", "(F)V", true);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(3, 3);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void emit(
            MethodVisitor method, String owner, String name, String descriptor, boolean itf) {
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.FCONST_0);
        if (descriptor.startsWith("(FLcom/")) method.visitInsn(Opcodes.ACONST_NULL);
        method.visitMethodInsn(itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL,
                owner, name, descriptor, itf);
    }

    private static ClassSignature exactSignature(byte[] bytes) throws Exception {
        ClassSignature parsed = ClassSignature.parse(bytes);
        return new ClassSignature(parsed.internalName(), CampaignEngineTimePlan.ORIGINAL_SHA256,
                parsed.majorVersion(), parsed.access(), parsed.methods());
    }

    private static MethodNode advance(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream()
                .filter(method -> CampaignEngineTimePlan.METHOD.equals(method.name)
                        && CampaignEngineTimePlan.DESCRIPTOR.equals(method.desc))
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
