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
