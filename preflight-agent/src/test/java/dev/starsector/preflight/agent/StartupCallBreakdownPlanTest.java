package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

class StartupCallBreakdownPlanTest {
    private static final StartupCallBreakdownPlan.Probe PROBE =
            StartupCallBreakdownPlan.probes().get(0);

    @Test
    void wrapsReviewedCallSiteAndDeclinesUnknownIdentity() throws Exception {
        byte[] original = fixture();
        ClassSignature exact = exactSignature(original);
        byte[] transformed = StartupCallBreakdownPlan.transform(exact, original);
        assertNotNull(transformed);
        assertEquals(2, runtimeCalls(transformed));
        assertNull(StartupCallBreakdownPlan.transform(ClassSignature.parse(transformed), transformed));
        assertNull(StartupCallBreakdownPlan.transform(ClassSignature.parse(original), original));
    }

    @Test
    void wrapsNexerelinFactionLoadAndDefaultLookups() throws Exception {
        StartupCallBreakdownPlan.Probe nex = StartupCallBreakdownPlan.probes().stream()
                .filter(probe -> "exerelin/utilities/NexFactionConfig".equals(probe.className()))
                .findFirst()
                .orElseThrow();
        byte[] original = nexFixture();
        ClassSignature parsed = ClassSignature.parse(original);
        ClassSignature exact = new ClassSignature(parsed.internalName(), nex.sha256(),
                parsed.majorVersion(), parsed.access(), parsed.methods());

        byte[] transformed = StartupCallBreakdownPlan.transform(exact, original);

        assertNotNull(transformed);
        assertEquals(6, runtimeCalls(transformed));
    }

    private static byte[] fixture() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, PROBE.className(),
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "populateShip", "(Lcom/fs/starfarer/api/combat/ShipHullSpecAPI;)V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
                "ashlib/data/plugins/misc/AshMisc", "getVaraint",
                "(Lcom/fs/starfarer/api/combat/ShipHullSpecAPI;)Ljava/lang/String;", false);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] nexFixture() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                "exerelin/utilities/NexFactionConfig", null, "java/lang/Object", null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(Ljava/lang/String;)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "java/lang/Object", "<init>", "()V", false);
        constructor.visitLdcInsn("exerelin_fleets");
        constructor.visitLdcInsn("miningFleetName");
        constructor.visitMethodInsn(Opcodes.INVOKESTATIC,
                "exerelin/utilities/StringHelper", "getString",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
        constructor.visitInsn(Opcodes.POP);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "exerelin/utilities/NexFactionConfig", "loadFactionConfig", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor load = writer.visitMethod(Opcodes.ACC_PUBLIC,
                "loadFactionConfig", "()V", null, null);
        load.visitCode();
        load.visitInsn(Opcodes.ACONST_NULL);
        load.visitLdcInsn("data/config/exerelinFactionConfig/test.json");
        load.visitLdcInsn("nexerelin");
        load.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/SettingsAPI", "getMergedJSONForMod",
                "(Ljava/lang/String;Ljava/lang/String;)Lorg/json/JSONObject;", true);
        load.visitInsn(Opcodes.POP);
        load.visitInsn(Opcodes.RETURN);
        load.visitMaxs(0, 0);
        load.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassSignature exactSignature(byte[] bytes) throws Exception {
        ClassSignature parsed = ClassSignature.parse(bytes);
        return new ClassSignature(parsed.internalName(), PROBE.sha256(), parsed.majorVersion(),
                parsed.access(), parsed.methods());
    }

    private static int runtimeCalls(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, 0);
        int calls = 0;
        for (var method : owner.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode invoked
                        && "dev/starsector/preflight/agent/StartupPhaseRuntime".equals(invoked.owner)) {
                    calls++;
                }
            }
        }
        return calls;
    }
}
