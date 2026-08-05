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
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class CampaignMarketFleetTimePlanTest {
    private static final String RUNTIME =
            CampaignMarketFleetTimeRuntime.class.getName().replace('.', '/');

    @BeforeEach
    void enable() {
        CampaignMarketFleetTimeRuntime.beginSession(true);
    }

    @AfterEach
    void reset() {
        CampaignMarketFleetTimeRuntime.reset();
    }

    @Test
    void marketPinsAllReviewedCallsAndDeclinesShapeDrift() throws Exception {
        byte[] original = marketFixture(false);
        byte[] transformed = CampaignMarketFleetTimePlan.transform(
                exact(original, CampaignMarketFleetTimePlan.MARKET_SHA256), original);
        assertNotNull(transformed);
        MethodNode method = method(transformed);
        assertEquals(8, calls(method, "enter"));
        assertEquals(3, calls(method, "enterClass"));
        assertEquals(16, calls(method, "exit"));
        assertEquals(6, calls(method, "exitClass"));
        assertNull(CampaignMarketFleetTimePlan.transform(
                ClassSignature.parse(transformed), transformed));

        byte[] changed = marketFixture(true);
        assertNull(CampaignMarketFleetTimePlan.transform(
                exact(changed, CampaignMarketFleetTimePlan.MARKET_SHA256), changed));
    }

    @Test
    void fleetPinsAllReviewedCallsAndDisabledRuntimeDeclines() throws Exception {
        byte[] original = fleetFixture();
        byte[] transformed = CampaignMarketFleetTimePlan.transform(
                exact(original, CampaignMarketFleetTimePlan.FLEET_SHA256), original);
        assertNotNull(transformed);
        MethodNode method = method(transformed);
        assertEquals(10, calls(method, "enter"));
        assertEquals(3, calls(method, "enterClass"));

        CampaignMarketFleetTimeRuntime.beginSession(false);
        assertNull(CampaignMarketFleetTimePlan.transform(
                exact(original, CampaignMarketFleetTimePlan.FLEET_SHA256), original));
    }

    private static byte[] marketFixture(boolean omitIndustry) {
        ClassWriter writer = owner(CampaignMarketFleetTimePlan.MARKET_CLASS);
        MethodVisitor method = advance(writer);
        for (int call = 0; call < 5; call++) emit(method,
                "com/fs/starfarer/api/combat/MutableStatWithTempMods", "advance", "(F)V");
        emit(method, "com/fs/starfarer/api/campaign/econ/MarketConditionPlugin", "advance", "(F)V");
        emit(method, "com/fs/starfarer/api/campaign/SubmarketPlugin", "advance", "(F)V");
        emit(method, "com/fs/starfarer/campaign/rules/Memory", "advance", "(F)V");
        emit(method, "com/fs/starfarer/rpg/Person", "advance", "(F)V");
        emit(method, "com/fs/starfarer/campaign/econ/CommodityOnMarket", "reapplyEventMod", "()V");
        if (!omitIndustry) emit(method,
                "com/fs/starfarer/api/campaign/econ/Industry", "advance", "(F)V");
        finish(writer, method);
        return writer.toByteArray();
    }

    private static byte[] fleetFixture() {
        ClassWriter writer = owner(CampaignMarketFleetTimePlan.FLEET_CLASS);
        MethodVisitor method = advance(writer);
        emit(method, "com/fs/starfarer/api/campaign/ai/CampaignFleetAIAPI", "advance", "(F)V");
        emit(method, "com/fs/starfarer/rpg/Person", "advance", "(F)V");
        emit(method, "com/fs/starfarer/rpg/Person", "advance", "(F)V");
        emit(method, "com/fs/starfarer/campaign/BaseCampaignEntity", "advance", "(F)V");
        emit(method, "com/fs/starfarer/api/combat/HullModFleetEffect", "advanceInCampaign",
                "(Lcom/fs/starfarer/api/campaign/CampaignFleetAPI;)V");
        emit(method, "com/fs/starfarer/campaign/fleet/MutableFleetStats", "advance", "(F)V");
        emit(method, "com/fs/starfarer/campaign/accidents/AccidentManager", "advance", "(F)V");
        emit(method, "com/fs/starfarer/campaign/fleet/LogisticsModule", "advance", "(F)V");
        emit(method, CampaignMarketFleetTimePlan.FLEET_CLASS, "updateCounts", "()V");
        emit(method, "com/fs/starfarer/campaign/fleet/SmoothMovementModule", "advance",
                "(Lcom/fs/starfarer/campaign/fleet/CampaignFleet;Lorg/lwjgl/util/vector/Vector2f;"
                        + "Lorg/lwjgl/util/vector/Vector2f;F)V");
        emit(method, "com/fs/starfarer/api/combat/HullModEffect", "advanceInCampaign",
                "(Lcom/fs/starfarer/api/fleet/FleetMemberAPI;F)V");
        emit(method, "com/fs/starfarer/campaign/BuffManager", "advance", "(F)V");
        emit(method, "com/fs/starfarer/campaign/fleet/CampaignFleetView", "advance", "(F)V");
        finish(writer, method);
        return writer.toByteArray();
    }

    private static ClassWriter owner(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        return writer;
    }

    private static MethodVisitor advance(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC,
                CampaignMarketFleetTimePlan.ADVANCE,
                CampaignMarketFleetTimePlan.DESCRIPTOR, null, null);
        method.visitCode();
        return method;
    }

    private static void emit(MethodVisitor method, String owner, String name, String descriptor) {
        method.visitInsn(Opcodes.ACONST_NULL);
        for (Type argument : Type.getArgumentTypes(descriptor)) {
            switch (argument.getSort()) {
                case Type.FLOAT -> method.visitInsn(Opcodes.FCONST_0);
                case Type.LONG -> method.visitInsn(Opcodes.LCONST_0);
                case Type.DOUBLE -> method.visitInsn(Opcodes.DCONST_0);
                case Type.OBJECT, Type.ARRAY -> method.visitInsn(Opcodes.ACONST_NULL);
                default -> method.visitInsn(Opcodes.ICONST_0);
            }
        }
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, name, descriptor, false);
    }

    private static void finish(ClassWriter writer, MethodVisitor method) {
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(8, 2);
        method.visitEnd();
        writer.visitEnd();
    }

    private static ClassSignature exact(byte[] bytes, String hash) throws Exception {
        ClassSignature parsed = ClassSignature.parse(bytes);
        return new ClassSignature(parsed.internalName(), hash, parsed.majorVersion(),
                parsed.access(), parsed.methods());
    }

    private static MethodNode method(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream()
                .filter(candidate -> CampaignMarketFleetTimePlan.ADVANCE.equals(candidate.name)
                        && CampaignMarketFleetTimePlan.DESCRIPTOR.equals(candidate.desc))
                .findFirst().orElseThrow();
    }

    private static int calls(MethodNode method, String name) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && RUNTIME.equals(call.owner) && name.equals(call.name)) result++;
        }
        return result;
    }
}
