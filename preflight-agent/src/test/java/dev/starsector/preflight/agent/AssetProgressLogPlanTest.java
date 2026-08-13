package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class AssetProgressLogPlanTest {
    private static final String LOGGER = "org/apache/log4j/Logger";

    @AfterEach
    void resetRuntimeGate() {
        System.clearProperty(AssetProgressLogRuntime.PROPERTY);
        AdapterPlanControl.configure(Set.of());
    }

    @Test
    void perPlanFilterOverridesTheRequestedSuppression() {
        System.setProperty(AssetProgressLogRuntime.PROPERTY, "off");
        AdapterPlanControl.configure(Set.of());
        assertTrue(AssetProgressLogRuntime.suppress());

        AdapterPlanControl.configure(Set.of(AssetProgressLogRuntime.PLAN_ID));
        assertFalse(AssetProgressLogRuntime.suppress());
    }

    @Test
    void removesAllFourWeaponProgressMessagesButKeepsOtherInfo() throws Exception {
        byte[] original = weaponFixture(false);
        byte[] rewritten = AssetProgressLogPlan.transform(ClassSignature.parse(original), original);

        assertNotNull(rewritten);
        assertEquals(0, progressConstants(rewritten));
        assertEquals(1, infoCalls(rewritten));
        assertEquals(1, constants(rewritten, "Weapon definitions loaded"));
        assertNull(AssetProgressLogPlan.transform(ClassSignature.parse(rewritten), rewritten));
    }

    @Test
    void refusesTheWholeClassWhenOneReviewedBlockDrifts() throws Exception {
        byte[] changed = weaponFixture(true);
        assertNull(AssetProgressLogPlan.transform(ClassSignature.parse(changed), changed));
        assertEquals(4, progressConstants(changed));
    }

    @Test
    void removesOnlyTheVariantProgressMessageAndKeepsSkippingDiagnostic() throws Exception {
        byte[] original = singleFixture(
                SpecStorePhasePlan.TARGET_CLASS,
                VariantLoaderPhasePlan.METHOD,
                "Loading variant [",
                "Skipping variant [example]");
        byte[] rewritten = AssetProgressLogPlan.transform(ClassSignature.parse(original), original);

        assertNotNull(rewritten);
        assertEquals(0, constants(rewritten, "Loading variant ["));
        assertEquals(1, constants(rewritten, "Skipping variant [example]"));
        assertEquals(1, infoCalls(rewritten));
    }

    @Test
    void removesTheHullProgressMessage() throws Exception {
        byte[] original = singleFixture(
                ShipHullLoaderPhasePlan.TARGET_CLASS,
                ShipHullLoaderPhasePlan.LOAD_ALL_METHOD,
                "Loading ship hull [",
                "Hull definitions loaded");
        byte[] rewritten = AssetProgressLogPlan.transform(ClassSignature.parse(original), original);

        assertNotNull(rewritten);
        assertEquals(0, constants(rewritten, "Loading ship hull ["));
        assertEquals(1, infoCalls(rewritten));
    }

    private static byte[] weaponFixture(boolean driftLastCall) {
        ClassWriter writer = writer(WeaponLoaderPhasePlan.TARGET_CLASS);
        MethodVisitor projectile = method(writer, "o00000");
        localPath(projectile);
        progress(projectile, "Loading projectile [", false);
        progress(projectile, "Loading ship system projectile [", false);
        projectile.visitInsn(Opcodes.RETURN);
        projectile.visitMaxs(0, 1);
        projectile.visitEnd();

        MethodVisitor weapon = method(writer, "new");
        localPath(weapon);
        progress(weapon, "Loading weapon [", false);
        progress(weapon, "Loading ship system-specific weapon [", driftLastCall);
        info(weapon, "Weapon definitions loaded");
        weapon.visitInsn(Opcodes.RETURN);
        weapon.visitMaxs(0, 1);
        weapon.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] singleFixture(
            String owner, String methodName, String prefix, String retainedMessage) {
        ClassWriter writer = writer(owner);
        MethodVisitor method = method(writer, methodName);
        localPath(method);
        progress(method, prefix, false);
        info(method, retainedMessage);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassWriter writer(String owner) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "LOG",
                "Lorg/apache/log4j/Logger;", null, null).visitEnd();
        return writer;
    }

    private static MethodVisitor method(ClassWriter writer, String name) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                name, "()V", null, null);
        method.visitCode();
        return method;
    }

    private static void progress(MethodVisitor method, String prefix, boolean drift) {
        method.visitFieldInsn(Opcodes.GETSTATIC, WeaponLoaderPhasePlan.TARGET_CLASS,
                "LOG", "Lorg/apache/log4j/Logger;");
        method.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        method.visitInsn(Opcodes.DUP);
        method.visitLdcInsn(prefix);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>",
                "(Ljava/lang/String;)V", false);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        method.visitLdcInsn("]");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                "()Ljava/lang/String;", false);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LOGGER, "info",
                drift ? "(Ljava/lang/String;)V" : "(Ljava/lang/Object;)V", false);
    }

    private static void localPath(MethodVisitor method) {
        method.visitLdcInsn("example.asset");
        method.visitVarInsn(Opcodes.ASTORE, 0);
    }

    private static void info(MethodVisitor method, String message) {
        method.visitFieldInsn(Opcodes.GETSTATIC, WeaponLoaderPhasePlan.TARGET_CLASS,
                "LOG", "Lorg/apache/log4j/Logger;");
        method.visitLdcInsn(message);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LOGGER, "info",
                "(Ljava/lang/Object;)V", false);
    }

    private static int progressConstants(byte[] bytes) {
        return List.of(
                        "Loading projectile [",
                        "Loading ship system projectile [",
                        "Loading weapon [",
                        "Loading ship system-specific weapon [")
                .stream().mapToInt(value -> constants(bytes, value)).sum();
    }

    private static int constants(byte[] bytes, String value) {
        ClassNode owner = node(bytes);
        int count = 0;
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null; instruction = instruction.getNext()) {
                if (instruction instanceof LdcInsnNode constant && value.equals(constant.cst)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int infoCalls(byte[] bytes) {
        ClassNode owner = node(bytes);
        int count = 0;
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null; instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call
                        && LOGGER.equals(call.owner) && "info".equals(call.name)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }
}
