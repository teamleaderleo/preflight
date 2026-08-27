package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

class CombatListenerRangeSnapshotPlanTest {
    private static final String SHIP = "com/fs/starfarer/api/combat/ShipAPI";
    private static final String LIST = "java/util/List";
    private static final String ARRAY_LIST = "java/util/ArrayList";
    private static final String ITERATOR = "java/util/Iterator";
    private static final String SNAPSHOT_RUNTIME =
            "dev/starsector/preflight/agent/CombatListenerRangeSnapshotRuntime";

    @AfterEach
    void clearProperty() {
        System.clearProperty(CombatListenerRangeSnapshotPlan.ENABLED_PROPERTY);
        System.clearProperty(CombatListenerRangeSnapshotRuntime.ENABLED_PROPERTY);
        CombatListenerRangeSnapshotRuntime.beginSession();
    }

    @Test
    void remainsInertUnlessExplicitlyEnabled() {
        assertNull(CombatListenerRangeSnapshotPlan.transform(signature(), fixture(false)));
    }

    @Test
    void replacesEachListCopyAndIteratorWithOneMutationSafeArraySnapshot() {
        System.setProperty(CombatListenerRangeSnapshotPlan.ENABLED_PROPERTY, "true");
        byte[] transformed = CombatListenerRangeSnapshotPlan.transform(signature(), fixture(false));
        assertNotNull(transformed);

        ClassNode owner = parse(transformed);
        for (CombatListenerRangeSnapshotPlan.RangeMethod spec
                : CombatListenerRangeSnapshotPlan.METHODS) {
            MethodNode method = unique(owner, spec.name());
            assertEquals(0, allocations(method, ARRAY_LIST), spec.name());
            assertEquals(0, calls(method, ARRAY_LIST, "<init>", "(Ljava/util/Collection;)V"),
                    spec.name());
            assertEquals(0, calls(method, ARRAY_LIST, "iterator", "()Ljava/util/Iterator;"),
                    spec.name());
            assertEquals(0, calls(method, LIST, "toArray", "()[Ljava/lang/Object;"), spec.name());
            assertEquals(1, calls(method, SNAPSHOT_RUNTIME, "snapshot",
                    "(Ljava/util/List;)[Ljava/lang/Object;"), spec.name());
            assertEquals(1, opcodes(method, Opcodes.AALOAD), spec.name());
            assertEquals(1, calls(method, spec.listener(), spec.callback(),
                    "(Lcom/fs/starfarer/api/combat/ShipAPI;"
                            + "Lcom/fs/starfarer/api/combat/WeaponAPI;)F"), spec.name());
        }
    }

    @Test
    void reuseSubknobRoutesEachPrivateSnapshotThroughValidatedRuntime() {
        System.setProperty(CombatListenerRangeSnapshotRuntime.ENABLED_PROPERTY, "true");
        CombatListenerRangeSnapshotRuntime.beginSession();
        byte[] transformed = CombatListenerRangeSnapshotPlan.transform(signature(), fixture(false));
        assertNotNull(transformed);

        ClassNode owner = parse(transformed);
        for (CombatListenerRangeSnapshotPlan.RangeMethod spec
                : CombatListenerRangeSnapshotPlan.METHODS) {
            MethodNode method = unique(owner, spec.name());
            assertEquals(0, calls(method, LIST, "toArray", "()[Ljava/lang/Object;"), spec.name());
            assertEquals(1, calls(method, SNAPSHOT_RUNTIME, "snapshot",
                    "(Ljava/util/List;)[Ljava/lang/Object;"), spec.name());
            assertEquals(1, opcodes(method, Opcodes.AALOAD), spec.name());
        }
        assertEquals(true, CombatListenerRangeSnapshotRuntime.telemetry().get("enabled"));
        assertEquals(true, CombatListenerRangeSnapshotRuntime.telemetry().get("installed"));
    }

    @Test
    void reuseSubknobCannotCreateASecondTransformationCacheVariant() {
        System.setProperty(CombatListenerRangeSnapshotPlan.ENABLED_PROPERTY, "true");
        CombatListenerRangeSnapshotRuntime.beginSession();
        byte[] arrayOnly = CombatListenerRangeSnapshotPlan.transform(signature(), fixture(false));

        System.clearProperty(CombatListenerRangeSnapshotPlan.ENABLED_PROPERTY);
        System.setProperty(CombatListenerRangeSnapshotRuntime.ENABLED_PROPERTY, "true");
        CombatListenerRangeSnapshotRuntime.beginSession();
        byte[] reuse = CombatListenerRangeSnapshotPlan.transform(signature(), fixture(false));

        assertArrayEquals(arrayOnly, reuse);
    }

    @Test
    void changedShapeWrongHashAndSecondRewriteFailClosed() {
        System.setProperty(CombatListenerRangeSnapshotPlan.ENABLED_PROPERTY, "true");
        ClassSignature exact = signature();
        assertNull(CombatListenerRangeSnapshotPlan.transform(new ClassSignature(
                exact.internalName(), "0".repeat(64), exact.majorVersion(), exact.access(),
                exact.methods()), fixture(false)));
        assertNull(CombatListenerRangeSnapshotPlan.transform(exact, fixture(true)));
        byte[] once = CombatListenerRangeSnapshotPlan.transform(exact, fixture(false));
        assertNotNull(once);
        assertNull(CombatListenerRangeSnapshotPlan.transform(exact, once));
    }

    @Test
    void targetPinsTheReviewedClassAndArchive() {
        AdapterTarget target = AdapterTargetRegistry.combatListenerRangeSnapshotTarget();
        assertTrue(AdapterTransformationRegistry.hasPlan(target.planId()));
        assertEquals(CombatListenerRangeSnapshotPlan.ORIGINAL_SHA256, target.sha256());
        assertEquals("6ac6c78c6116946d487376426340d019938f986ceae1391ae1fa599e890e3185",
                target.sourceSha256());
        assertEquals(6, target.requiredMethods().size());
    }

    private static ClassSignature signature() {
        return new ClassSignature(
                CombatListenerRangeSnapshotPlan.TARGET_CLASS,
                CombatListenerRangeSnapshotPlan.ORIGINAL_SHA256,
                61,
                Opcodes.ACC_PUBLIC,
                CombatListenerRangeSnapshotPlan.METHODS.stream()
                        .map(spec -> new ClassSignature.Method(
                                spec.name(),
                                CombatListenerRangeSnapshotPlan.DESCRIPTOR,
                                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))
                        .toList());
    }

    private static byte[] fixture(boolean damageFirstMethod) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                CombatListenerRangeSnapshotPlan.TARGET_CLASS, null, "java/lang/Object", null);
        for (int index = 0; index < CombatListenerRangeSnapshotPlan.METHODS.size(); index++) {
            var spec = CombatListenerRangeSnapshotPlan.METHODS.get(index);
            originalMethod(spec, damageFirstMethod && index == 0).accept(writer);
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static MethodNode originalMethod(
            CombatListenerRangeSnapshotPlan.RangeMethod spec, boolean omitCopy) {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                spec.name(),
                CombatListenerRangeSnapshotPlan.DESCRIPTOR,
                null,
                null);
        LabelNode loop = new LabelNode();
        LabelNode end = new LabelNode();
        method.instructions.add(new InsnNode(spec.multiply() ? Opcodes.FCONST_1 : Opcodes.FCONST_0));
        method.instructions.add(new VarInsnNode(Opcodes.FSTORE, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNULL, end));
        if (!omitCopy) {
            method.instructions.add(new TypeInsnNode(Opcodes.NEW, ARRAY_LIST));
            method.instructions.add(new InsnNode(Opcodes.DUP));
        }
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new LdcInsnNode(Type.getObjectType(spec.listener())));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                SHIP,
                "getListeners",
                "(Ljava/lang/Class;)Ljava/util/List;",
                true));
        if (!omitCopy) {
            method.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    ARRAY_LIST,
                    "<init>",
                    "(Ljava/util/Collection;)V",
                    false));
        }
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                ARRAY_LIST,
                "iterator",
                "()Ljava/util/Iterator;",
                false));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 4));
        method.instructions.add(loop);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, ITERATOR, "hasNext", "()Z", true));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, end));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, ITERATOR, "next", "()Ljava/lang/Object;", true));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, spec.listener()));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        method.instructions.add(new VarInsnNode(Opcodes.FLOAD, 2));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                spec.listener(),
                spec.callback(),
                "(Lcom/fs/starfarer/api/combat/ShipAPI;"
                        + "Lcom/fs/starfarer/api/combat/WeaponAPI;)F",
                true));
        method.instructions.add(new InsnNode(spec.multiply() ? Opcodes.FMUL : Opcodes.FADD));
        method.instructions.add(new VarInsnNode(Opcodes.FSTORE, 2));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, loop));
        method.instructions.add(end);
        method.instructions.add(new VarInsnNode(Opcodes.FLOAD, 2));
        method.instructions.add(new InsnNode(Opcodes.FRETURN));
        return method;
    }

    private static ClassNode parse(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static MethodNode unique(ClassNode owner, String name) {
        return owner.methods.stream()
                .filter(method -> name.equals(method.name)
                        && CombatListenerRangeSnapshotPlan.DESCRIPTOR.equals(method.desc))
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

    private static int opcodes(MethodNode method, int opcode) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) count++;
        }
        return count;
    }
}
