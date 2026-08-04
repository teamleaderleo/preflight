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

class CampaignLocationEconomyTimePlanTest {
    private static final String RUNTIME =
            CampaignLocationEconomyTimeRuntime.class.getName().replace('.', '/');

    @BeforeEach
    void enable() {
        CampaignLocationEconomyTimeRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        CampaignLocationEconomyTimeRuntime.reset();
    }

    @Test
    void locationTransformPinsThreeLoopCallsAndExceptionalExits() throws Exception {
        byte[] original = locationFixture(false);
        byte[] transformed = CampaignLocationEconomyTimePlan.transform(
                exact(original, CampaignLocationEconomyTimePlan.LOCATION_SHA256), original);
        assertNotNull(transformed);
        assertNull(CampaignLocationEconomyTimePlan.transform(
                ClassSignature.parse(transformed), transformed));
        MethodNode active = method(transformed, CampaignLocationEconomyTimePlan.LOCATION_ADVANCE);
        MethodNode paused = method(transformed, CampaignLocationEconomyTimePlan.LOCATION_PAUSED);
        assertEquals(1, calls(active, RUNTIME, "enterClass"));
        assertEquals(2, calls(active, RUNTIME, "exitClass"));
        assertEquals(2, calls(paused, RUNTIME, "enterClass"));
        assertEquals(4, calls(paused, RUNTIME, "exitClass"));
    }

    @Test
    void locationTransformDeclinesChangedShapeOrDisabledRuntime() throws Exception {
        byte[] changed = locationFixture(true);
        assertNull(CampaignLocationEconomyTimePlan.transform(
                exact(changed, CampaignLocationEconomyTimePlan.LOCATION_SHA256), changed));
        CampaignLocationEconomyTimeRuntime.beginSession(false);
        byte[] exact = locationFixture(false);
        assertNull(CampaignLocationEconomyTimePlan.transform(
                exact(exact, CampaignLocationEconomyTimePlan.LOCATION_SHA256), exact));
    }

    @Test
    void economyTransformPinsAllThreeReviewedCalls() throws Exception {
        byte[] original = economyFixture(false);
        byte[] transformed = CampaignLocationEconomyTimePlan.transform(
                exact(original, CampaignLocationEconomyTimePlan.ECONOMY_SHA256), original);
        assertNotNull(transformed);
        MethodNode method = method(transformed, CampaignLocationEconomyTimePlan.ECONOMY_ADVANCE);
        assertEquals(3, calls(method, RUNTIME, "enter"));
        assertEquals(6, calls(method, RUNTIME, "exit"));

        byte[] changed = economyFixture(true);
        assertNull(CampaignLocationEconomyTimePlan.transform(
                exact(changed, CampaignLocationEconomyTimePlan.ECONOMY_SHA256), changed));
    }

    private static byte[] locationFixture(boolean omitScript) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                CampaignLocationEconomyTimePlan.LOCATION_CLASS, null, "java/lang/Object", null);
        MethodVisitor active = writer.visitMethod(Opcodes.ACC_PUBLIC,
                CampaignLocationEconomyTimePlan.LOCATION_ADVANCE,
                CampaignLocationEconomyTimePlan.LOCATION_DESCRIPTOR, null, null);
        active.visitCode();
        emitFloat(active, "com/fs/starfarer/campaign/CampaignEntity", "advance", true);
        active.visitInsn(Opcodes.RETURN);
        active.visitMaxs(2, 3);
        active.visitEnd();

        MethodVisitor paused = writer.visitMethod(Opcodes.ACC_PUBLIC,
                CampaignLocationEconomyTimePlan.LOCATION_PAUSED,
                CampaignLocationEconomyTimePlan.LOCATION_DESCRIPTOR, null, null);
        paused.visitCode();
        emitFloat(paused, "com/fs/starfarer/campaign/CampaignEntity", "advanceEvenIfPaused", true);
        if (!omitScript) emitFloat(paused, "com/fs/starfarer/api/EveryFrameScript", "advance", true);
        paused.visitInsn(Opcodes.RETURN);
        paused.visitMaxs(2, 3);
        paused.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] economyFixture(boolean omitMarket) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                CampaignLocationEconomyTimePlan.ECONOMY_CLASS, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC,
                CampaignLocationEconomyTimePlan.ECONOMY_ADVANCE,
                CampaignLocationEconomyTimePlan.ECONOMY_DESCRIPTOR, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/campaign/econ/reach/ReachEconomy",
                "updateLocationMap", "()V", false);
        emitFloat(method, "com/fs/starfarer/campaign/econ/reach/ReachEconomyStepper",
                "nextFrame", false);
        if (!omitMarket) emitFloat(method, "com/fs/starfarer/api/campaign/econ/MarketAPI",
                "advance", true);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(2, 2);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void emitFloat(MethodVisitor method, String owner, String name, boolean itf) {
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.FCONST_0);
        method.visitMethodInsn(itf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL,
                owner, name, "(F)V", itf);
    }

    private static ClassSignature exact(byte[] bytes, String hash) throws Exception {
        ClassSignature parsed = ClassSignature.parse(bytes);
        return new ClassSignature(parsed.internalName(), hash, parsed.majorVersion(),
                parsed.access(), parsed.methods());
    }

    private static MethodNode method(byte[] bytes, String name) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream().filter(candidate -> name.equals(candidate.name))
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
