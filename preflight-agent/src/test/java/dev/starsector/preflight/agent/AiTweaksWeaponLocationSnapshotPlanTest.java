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
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

class AiTweaksWeaponLocationSnapshotPlanTest {
    private static final String RUNTIME =
            AiTweaksWeaponLocationSnapshotRuntime.class.getName().replace('.', '/');
    private static final String SELECT_TARGET =
            "com/genir/aitweaks/core/shipai/autofire/SelectTarget";
    private static final String SELECT_CONSTRUCTOR =
            "(Lcom/fs/starfarer/api/combat/WeaponAPI;"
                    + "Lcom/fs/starfarer/api/combat/CombatEntityAPI;"
                    + "Lcom/fs/starfarer/api/combat/ShipAPI;"
                    + "Lcom/genir/aitweaks/core/shipai/autofire/ballistics/BallisticParams;"
                    + "Lcom/genir/aitweaks/core/shipai/global/TargetTracker;"
                    + "Lkotlin/jvm/internal/DefaultConstructorMarker;)V";

    @AfterEach
    void clearProperty() {
        System.clearProperty(AiTweaksWeaponLocationSnapshotPlan.ENABLED_PROPERTY);
    }

    @Test
    void remainsInertUnlessExplicitlyEnabled() {
        assertNull(AiTweaksWeaponLocationSnapshotPlan.transform(
                signature(AiTweaksWeaponLocationSnapshotPlan.targets().get(0)), autofireFixture(true)));
    }

    @Test
    void bracketsSelectionAndWrapsOnlyTheExactLocationGetter() throws Exception {
        System.setProperty(AiTweaksWeaponLocationSnapshotPlan.ENABLED_PROPERTY, "true");
        var autofireTarget = AiTweaksWeaponLocationSnapshotPlan.targets().get(0);
        var handleTarget = AiTweaksWeaponLocationSnapshotPlan.targets().get(1);
        byte[] autofire = AiTweaksWeaponLocationSnapshotPlan.transform(
                signature(autofireTarget), autofireFixture(true));
        byte[] handle = AiTweaksWeaponLocationSnapshotPlan.transform(
                signature(handleTarget), weaponHandleFixture(true));
        assertNotNull(autofire);
        assertNotNull(handle);

        MethodNode update = unique(parse(autofire),
                AiTweaksWeaponLocationSnapshotPlan.AUTOFIRE_METHOD,
                AiTweaksWeaponLocationSnapshotPlan.AUTOFIRE_DESCRIPTOR);
        assertEquals(1, calls(update, RUNTIME, "begin", "(Ljava/lang/Object;)V"));
        assertEquals(2, calls(update, RUNTIME, "end", "()V"));
        assertEquals(1, update.tryCatchBlocks.size());
        assertEquals(1, calls(update, SELECT_TARGET, "target",
                "()Lcom/fs/starfarer/api/combat/CombatEntityAPI;"));

        MethodNode getter = unique(parse(handle),
                AiTweaksWeaponLocationSnapshotPlan.LOCATION_METHOD,
                AiTweaksWeaponLocationSnapshotPlan.LOCATION_DESCRIPTOR);
        assertEquals(1, calls(getter, RUNTIME, "cachedLocation",
                "(Ljava/lang/Object;)Ljava/lang/Object;"));
        assertEquals(1, calls(getter, RUNTIME, "rememberLocation",
                "(Ljava/lang/Object;Ljava/lang/Object;)V"));
        assertEquals(1, calls(getter, "com/fs/starfarer/api/combat/WeaponAPI", "getLocation",
                "()Lorg/lwjgl/util/vector/Vector2f;"));
    }

    @Test
    void changedShapeWrongHashAndSecondRewriteFailClosed() throws Exception {
        System.setProperty(AiTweaksWeaponLocationSnapshotPlan.ENABLED_PROPERTY, "true");
        var autofireTarget = AiTweaksWeaponLocationSnapshotPlan.targets().get(0);
        var handleTarget = AiTweaksWeaponLocationSnapshotPlan.targets().get(1);
        ClassSignature autofireSignature = signature(autofireTarget);
        assertNull(AiTweaksWeaponLocationSnapshotPlan.transform(new ClassSignature(
                autofireSignature.internalName(),
                "0".repeat(64),
                autofireSignature.majorVersion(),
                autofireSignature.access(),
                autofireSignature.methods()), autofireFixture(true)));
        assertNull(AiTweaksWeaponLocationSnapshotPlan.transform(
                autofireSignature, autofireFixture(false)));
        assertNull(AiTweaksWeaponLocationSnapshotPlan.transform(
                signature(handleTarget), weaponHandleFixture(false)));

        byte[] once = AiTweaksWeaponLocationSnapshotPlan.transform(
                autofireSignature, autofireFixture(true));
        assertNotNull(once);
        assertNull(AiTweaksWeaponLocationSnapshotPlan.transform(autofireSignature, once));
    }

    @Test
    void registryPinsTwoReviewedClassesAndNeverTargetsSelectTarget() {
        List<AdapterTarget> targets =
                AdapterTargetRegistry.aiTweaksWeaponLocationSnapshotTargets();
        assertEquals(2, targets.size());
        assertTrue(AdapterTransformationRegistry.hasPlan(
                AiTweaksWeaponLocationSnapshotPlan.PLAN_ID));
        assertTrue(targets.stream().noneMatch(target -> SELECT_TARGET.equals(
                target.internalClassName())));
        for (int index = 0; index < targets.size(); index++) {
            var expected = AiTweaksWeaponLocationSnapshotPlan.targets().get(index);
            var actual = targets.get(index);
            assertEquals(expected.internalName(), actual.internalClassName());
            assertEquals(expected.sha256(), actual.sha256());
            assertEquals(AiTweaksWeaponLocationSnapshotPlan.SOURCE_SHA256,
                    actual.sourceSha256());
            assertEquals(1, actual.requiredMethods().size());
        }
    }

    private static ClassSignature signature(AiTweaksWeaponLocationSnapshotPlan.Target target) {
        return new ClassSignature(
                target.internalName(),
                target.sha256(),
                61,
                Opcodes.ACC_PUBLIC,
                List.of(new ClassSignature.Method(
                        target.method(), target.descriptor(), Opcodes.ACC_PRIVATE)));
    }

    private static byte[] autofireFixture(boolean exactShape) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                AiTweaksWeaponLocationSnapshotPlan.AUTOFIRE_CLASS,
                null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "weapon",
                "Lcom/fs/starfarer/api/combat/WeaponAPI;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE, "target",
                "Lcom/fs/starfarer/api/combat/CombatEntityAPI;", null, null).visitEnd();
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE,
                AiTweaksWeaponLocationSnapshotPlan.AUTOFIRE_METHOD,
                AiTweaksWeaponLocationSnapshotPlan.AUTOFIRE_DESCRIPTOR,
                null,
                null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new TypeInsnNode(Opcodes.NEW,
                exactShape ? SELECT_TARGET : "java/lang/Object"));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD,
                AiTweaksWeaponLocationSnapshotPlan.AUTOFIRE_CLASS,
                "weapon", "Lcom/fs/starfarer/api/combat/WeaponAPI;"));
        for (int index = 0; index < 5; index++) {
            method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        }
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                exactShape ? SELECT_TARGET : "java/lang/Object",
                "<init>", exactShape ? SELECT_CONSTRUCTOR : "()V", false));
        if (exactShape) {
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    SELECT_TARGET, "target",
                    "()Lcom/fs/starfarer/api/combat/CombatEntityAPI;", false));
            method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,
                    AiTweaksWeaponLocationSnapshotPlan.AUTOFIRE_CLASS,
                    "target", "Lcom/fs/starfarer/api/combat/CombatEntityAPI;"));
        } else {
            method.instructions.add(new InsnNode(Opcodes.POP));
        }
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] weaponHandleFixture(boolean exactShape) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                AiTweaksWeaponLocationSnapshotPlan.WEAPON_HANDLE_CLASS,
                null, "java/lang/Object", null);
        MethodNode method = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                AiTweaksWeaponLocationSnapshotPlan.LOCATION_METHOD,
                AiTweaksWeaponLocationSnapshotPlan.LOCATION_DESCRIPTOR,
                null,
                null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/combat/WeaponAPI", "getLocation",
                "()Lorg/lwjgl/util/vector/Vector2f;", true));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new LdcInsnNode(
                exactShape ? "getLocation(...)" : "changed"));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "kotlin/jvm/internal/Intrinsics", "checkNotNullExpressionValue",
                "(Ljava/lang/Object;Ljava/lang/String;)V", false));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
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
                    && owner.equals(call.owner) && name.equals(call.name)
                    && descriptor.equals(call.desc)) count++;
        }
        return count;
    }
}
