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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

class DetailedCombatResultsStateReusePlanTest {
    private static final String TARGET = DetailedCombatResultsStateReusePlan.TARGET_CLASS;
    private static final String HASH_MAP = "java/util/HashMap";
    private static final String ENGINE = "com/fs/starfarer/api/combat/CombatEngineAPI";
    private static final String MAP_DESCRIPTOR = "Ljava/util/HashMap;";

    @AfterEach
    void clearProperty() {
        System.clearProperty(DetailedCombatResultsStateReusePlan.ENABLED_PROPERTY);
        DetailedCombatResultsStateReuseRuntime.beginSession();
    }

    @Test
    void remainsInertUnlessExplicitlyEnabled() {
        assertNull(DetailedCombatResultsStateReusePlan.transform(signature(), fixture(false)));
    }

    @Test
    void replacesThreePerFrameMapAllocationsWithTwoNarrowReuseHelpers() {
        System.setProperty(DetailedCombatResultsStateReusePlan.ENABLED_PROPERTY, "true");
        DetailedCombatResultsStateReuseRuntime.beginSession();

        byte[] transformed = DetailedCombatResultsStateReusePlan.transform(
                signature(), fixture(false));
        assertNotNull(transformed);

        ClassNode owner = parse(transformed);
        MethodNode update = unique(owner, DetailedCombatResultsStateReusePlan.METHOD,
                DetailedCombatResultsStateReusePlan.DESCRIPTOR);
        assertEquals(0, allocations(update, HASH_MAP));
        assertEquals(1, calls(update, TARGET, "$preflight$refreshProjectileHistory",
                "(Lcom/fs/starfarer/api/combat/CombatEngineAPI;)V"));
        assertEquals(1, calls(update, TARGET, "$preflight$rotateShipMaps", "()V"));

        MethodNode history = unique(owner, "$preflight$refreshProjectileHistory",
                "(Lcom/fs/starfarer/api/combat/CombatEngineAPI;)V");
        assertEquals(1, calls(history, "java/util/Iterator", "remove", "()V"));
        assertEquals(1, calls(history,
                "dev/starsector/preflight/agent/DetailedCombatResultsStateReuseRuntime",
                "historyFrame", "(III)V"));

        MethodNode ships = unique(owner, "$preflight$rotateShipMaps", "()V");
        assertEquals(2, calls(ships, HASH_MAP, "clear", "()V"));
        assertEquals(1, calls(ships, HASH_MAP, "putAll", "(Ljava/util/Map;)V"));
        assertEquals(1, calls(ships,
                "dev/starsector/preflight/agent/DetailedCombatResultsStateReuseRuntime",
                "shipFrame", "(I)V"));
        assertEquals(true,
                DetailedCombatResultsStateReuseRuntime.telemetry().get("installed"));
    }

    @Test
    void changedShapeWrongHashAndSecondRewriteFailClosed() {
        System.setProperty(DetailedCombatResultsStateReusePlan.ENABLED_PROPERTY, "true");
        ClassSignature exact = signature();
        assertNull(DetailedCombatResultsStateReusePlan.transform(new ClassSignature(
                exact.internalName(), "0".repeat(64), exact.majorVersion(), exact.access(),
                exact.methods()), fixture(false)));
        assertNull(DetailedCombatResultsStateReusePlan.transform(exact, fixture(true)));
        byte[] once = DetailedCombatResultsStateReusePlan.transform(exact, fixture(false));
        assertNotNull(once);
        assertNull(DetailedCombatResultsStateReusePlan.transform(exact, once));
    }

    @Test
    void targetPinsReviewedClassAndArchive() {
        AdapterTarget target = AdapterTargetRegistry.detailedCombatResultsStateReuseTarget();
        assertTrue(AdapterTransformationRegistry.hasPlan(target.planId()));
        assertEquals(DetailedCombatResultsStateReusePlan.ORIGINAL_SHA256, target.sha256());
        assertEquals(DetailedCombatResultsStateReusePlan.SOURCE_SHA256, target.sourceSha256());
        assertEquals(
                DetailedCombatResultsStateReusePlan.SOURCE_FILE.toLowerCase(),
                target.sourceSuffix());
        assertEquals(DetailedCombatResultsStateReusePlan.LOADER, target.loaderClass());
    }

    private static ClassSignature signature() {
        return new ClassSignature(
                TARGET,
                DetailedCombatResultsStateReusePlan.ORIGINAL_SHA256,
                60,
                Opcodes.ACC_PUBLIC,
                List.of(new ClassSignature.Method(
                        DetailedCombatResultsStateReusePlan.METHOD,
                        DetailedCombatResultsStateReusePlan.DESCRIPTOR,
                        Opcodes.ACC_PUBLIC)));
    }

    private static byte[] fixture(boolean omitKilledCopy) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        field(writer, "historicalProjectilesToAge");
        field(writer, "aliveShipsLastFrameById");
        field(writer, "aliveShipsThisFrameById");
        field(writer, "killedShipsThisFrameById");
        writer.visitField(Opcodes.ACC_PRIVATE, "currentAge", "D", null, null).visitEnd();

        MethodNode update = new MethodNode(
                Opcodes.ACC_PUBLIC,
                DetailedCombatResultsStateReusePlan.METHOD,
                DetailedCombatResultsStateReusePlan.DESCRIPTOR,
                null,
                null);
        update.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        update.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, ENGINE, "getProjectiles", "()Ljava/util/List;", true));
        update.instructions.add(new InsnNode(Opcodes.POP));

        update.instructions.add(new TypeInsnNode(Opcodes.NEW, HASH_MAP));
        update.instructions.add(new InsnNode(Opcodes.DUP));
        update.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        update.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, TARGET, "historicalProjectilesToAge", MAP_DESCRIPTOR));
        update.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, HASH_MAP, "size", "()I", false));
        update.instructions.add(new InsnNode(Opcodes.ICONST_2));
        update.instructions.add(new InsnNode(Opcodes.IMUL));
        update.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, HASH_MAP, "<init>", "(I)V", false));
        update.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        update.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        update.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, ENGINE, "getProjectiles", "()Ljava/util/List;", true));
        update.instructions.add(new InsnNode(Opcodes.POP));
        update.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        update.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        update.instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD, TARGET, "historicalProjectilesToAge", MAP_DESCRIPTOR));

        update.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        update.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        update.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, TARGET, "aliveShipsThisFrameById", MAP_DESCRIPTOR));
        update.instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD, TARGET, "aliveShipsLastFrameById", MAP_DESCRIPTOR));
        update.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (!omitKilledCopy) {
            update.instructions.add(new TypeInsnNode(Opcodes.NEW, HASH_MAP));
            update.instructions.add(new InsnNode(Opcodes.DUP));
        }
        update.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        update.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, TARGET, "aliveShipsLastFrameById", MAP_DESCRIPTOR));
        if (!omitKilledCopy) {
            update.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL, HASH_MAP, "<init>", "(Ljava/util/Map;)V", false));
        }
        update.instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD, TARGET, "killedShipsThisFrameById", MAP_DESCRIPTOR));
        update.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        update.instructions.add(new TypeInsnNode(Opcodes.NEW, HASH_MAP));
        update.instructions.add(new InsnNode(Opcodes.DUP));
        update.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        update.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, TARGET, "aliveShipsLastFrameById", MAP_DESCRIPTOR));
        update.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, HASH_MAP, "size", "()I", false));
        update.instructions.add(new InsnNode(Opcodes.ICONST_2));
        update.instructions.add(new InsnNode(Opcodes.IMUL));
        update.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, HASH_MAP, "<init>", "(I)V", false));
        update.instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD, TARGET, "aliveShipsThisFrameById", MAP_DESCRIPTOR));
        update.instructions.add(new InsnNode(Opcodes.RETURN));
        update.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void field(ClassWriter writer, String name) {
        writer.visitField(Opcodes.ACC_PRIVATE, name, MAP_DESCRIPTOR, null, null).visitEnd();
    }

    private static ClassNode parse(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static MethodNode unique(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .filter(method -> name.equals(method.name) && descriptor.equals(method.desc))
                .findFirst().orElseThrow();
    }

    private static int calls(MethodNode method, String owner, String name, String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)
                    && descriptor.equals(call.desc)) count++;
        }
        return count;
    }

    private static int allocations(MethodNode method, String type) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode allocation
                    && instruction.getOpcode() == Opcodes.NEW
                    && type.equals(allocation.desc)) count++;
        }
        return count;
    }
}
