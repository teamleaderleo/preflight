package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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
import org.objectweb.asm.tree.VarInsnNode;

class RatAbyssFactionFlagPlanTest {
    private static final String JSON_OBJECT = "org/json/JSONObject";

    @BeforeEach
    void reset() {
        RatAbyssFactionFlagPlan.reset();
    }

    @Test
    void replacesTheReviewedThrowingLookupWithTheSameFalseDefaultLookup() {
        byte[] transformed = RatAbyssFactionFlagPlan.transform(exactSignature(), fixture(1));
        assertNotNull(transformed);
        ClassNode owner = read(transformed);
        assertEquals(0, calls(owner, "getBoolean"));
        assertEquals(1, calls(owner, "optBoolean"));
        assertEquals(1L, RatAbyssFactionFlagPlan.telemetry().get("installedTargets"));

        RatAbyssFactionFlagPlan.reset();
        AdapterInstallationEffects.replay(
                AdapterTargetRegistry.ratAbyssFactionFlagTarget(), exactSignature(), transformed);
        assertEquals(1L, RatAbyssFactionFlagPlan.telemetry().get("installedTargets"));
    }

    @Test
    void changedShapeWrongHashAndSecondRewriteFailClosed() {
        assertNull(RatAbyssFactionFlagPlan.transform(exactSignature(), fixture(2)));
        assertNull(RatAbyssFactionFlagPlan.transform(exactSignature(), fixtureWithLookupInHelper()));
        ClassSignature exact = exactSignature();
        ClassSignature wrongHash = new ClassSignature(
                exact.internalName(), "0".repeat(64), exact.majorVersion(),
                exact.access(), exact.methods());
        assertNull(RatAbyssFactionFlagPlan.transform(wrongHash, fixture(1)));

        byte[] transformed = RatAbyssFactionFlagPlan.transform(exact, fixture(1));
        assertNotNull(transformed);
        assertNull(RatAbyssFactionFlagPlan.transform(exact, transformed));
    }

    @Test
    void targetPinsTheReviewedRatArchiveAndModLoader() {
        AdapterTarget target = AdapterTargetRegistry.ratAbyssFactionFlagTarget();
        assertTrue(AdapterTransformationRegistry.hasPlan(target.planId()));
        String sourcePath = "/game/mods/Random Assortment of Things-3.3.1/jars/"
                + "RandomAssortmentofThings.jar";
        AdapterSourceIdentity source = new AdapterSourceIdentity(
                "file:" + sourcePath,
                sourcePath.toLowerCase(java.util.Locale.ROOT),
                "MOD",
                target.sourceSha256(),
                "",
                "java/net/URLClassLoader",
                null);
        assertTrue(target.match(exactSignature(), source).exact());
    }

    private static ClassSignature exactSignature() {
        return new ClassSignature(
                RatAbyssFactionFlagPlan.TARGET_CLASS,
                RatAbyssFactionFlagPlan.ORIGINAL_SHA256,
                61,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                List.of(new ClassSignature.Method(
                        RatAbyssFactionFlagPlan.ADVANCE_METHOD,
                        RatAbyssFactionFlagPlan.ADVANCE_DESCRIPTOR,
                        Opcodes.ACC_PUBLIC)));
    }

    private static byte[] fixture(int lookups) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        beginFixture(writer);
        MethodNode advance = new MethodNode(
                Opcodes.ACC_PUBLIC,
                RatAbyssFactionFlagPlan.ADVANCE_METHOD,
                RatAbyssFactionFlagPlan.ADVANCE_DESCRIPTOR,
                null,
                null);
        addLookups(advance, lookups);
        advance.instructions.add(new InsnNode(Opcodes.RETURN));
        advance.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] fixtureWithLookupInHelper() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        beginFixture(writer);
        MethodNode advance = new MethodNode(
                Opcodes.ACC_PUBLIC,
                RatAbyssFactionFlagPlan.ADVANCE_METHOD,
                RatAbyssFactionFlagPlan.ADVANCE_DESCRIPTOR,
                null,
                null);
        advance.instructions.add(new InsnNode(Opcodes.RETURN));
        advance.accept(writer);
        MethodNode helper = new MethodNode(Opcodes.ACC_PRIVATE, "helper", "()V", null, null);
        addLookups(helper, 1);
        helper.instructions.add(new InsnNode(Opcodes.RETURN));
        helper.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void beginFixture(ClassWriter writer) {
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                RatAbyssFactionFlagPlan.TARGET_CLASS,
                null,
                "java/lang/Object",
                null);
        writer.visitField(
                Opcodes.ACC_PRIVATE,
                "json",
                "Lorg/json/JSONObject;",
                null,
                null).visitEnd();
    }

    private static void addLookups(MethodNode method, int lookups) {
        for (int index = 0; index < lookups; index++) {
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            method.instructions.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    RatAbyssFactionFlagPlan.TARGET_CLASS,
                    "json",
                    "Lorg/json/JSONObject;"));
            method.instructions.add(new LdcInsnNode("rat_abyss_faction"));
            method.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    JSON_OBJECT,
                    "getBoolean",
                    "(Ljava/lang/String;)Z",
                    false));
            method.instructions.add(new InsnNode(Opcodes.POP));
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static int calls(ClassNode owner, String name) {
        int count = 0;
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && JSON_OBJECT.equals(call.owner)
                        && name.equals(call.name)) {
                    count++;
                }
            }
        }
        return count;
    }
}
