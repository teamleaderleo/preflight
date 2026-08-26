package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

class MnemonicSensorsEntityFilterPlanTest {
    private static final String COLLECTIONS = "kotlin/collections/CollectionsKt";
    private static final String ENTITY = "com/fs/starfarer/api/campaign/SectorEntityToken";

    @BeforeEach
    void reset() {
        MnemonicSensorsEntityFilterPlan.reset();
    }

    @Test
    void removesOnlyTheFirstSnapshotAndGuardsTheExistingPredicateLoop() {
        byte[] transformed = MnemonicSensorsEntityFilterPlan.transform(exactSignature(), fixture(true, 1));
        assertNotNull(transformed);
        MethodNode method = method(read(transformed));
        assertEquals(0, calls(method, COLLECTIONS, "filterNotNull"));
        assertEquals(1, calls(method, "java/util/Collection", "add"));

        JumpInsnNode nullGuard = jumps(method, Opcodes.IFNULL).get(0);
        List<JumpInsnNode> falseBranches = jumps(method, Opcodes.IFEQ);
        assertEquals(2, falseBranches.size());
        assertTrue(falseBranches.stream().anyMatch(branch -> branch.label == nullGuard.label),
                "null elements must continue through the same loop edge as predicate misses");
        assertEquals(1L, MnemonicSensorsEntityFilterPlan.telemetry().get("installedTargets"));
    }

    @Test
    void changedShapeWrongHashAndSecondRewriteFailClosed() throws Exception {
        assertNull(MnemonicSensorsEntityFilterPlan.transform(exactSignature(), fixture(false, 1)));
        assertNull(MnemonicSensorsEntityFilterPlan.transform(exactSignature(), fixture(true, 2)));

        ClassSignature exact = exactSignature();
        ClassSignature wrongHash = new ClassSignature(
                exact.internalName(), "0".repeat(64), exact.majorVersion(),
                exact.access(), exact.methods());
        assertNull(MnemonicSensorsEntityFilterPlan.transform(wrongHash, fixture(true, 1)));

        byte[] transformed = MnemonicSensorsEntityFilterPlan.transform(exact, fixture(true, 1));
        assertNotNull(transformed);
        assertNull(MnemonicSensorsEntityFilterPlan.transform(
                ClassSignature.parse(transformed), transformed));
    }

    @Test
    void targetPinsTheReviewedMnemonicArchiveAndModLoader() {
        AdapterTarget target = AdapterTargetRegistry.mnemonicSensorsEntityFilterTarget();
        assertTrue(AdapterTransformationRegistry.hasPlan(target.planId()));
        String sourcePath = "/game/mods/MnemonicUtils-0.5.1/jars/MnemonicUtils.jar";
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
                MnemonicSensorsEntityFilterPlan.TARGET_CLASS,
                MnemonicSensorsEntityFilterPlan.ORIGINAL_SHA256,
                61,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                List.of(new ClassSignature.Method(
                        MnemonicSensorsEntityFilterPlan.METHOD,
                        MnemonicSensorsEntityFilterPlan.DESCRIPTOR,
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)));
    }

    private static byte[] fixture(boolean predicateBranch, int filterCalls) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                MnemonicSensorsEntityFilterPlan.TARGET_CLASS,
                null,
                "java/lang/Object",
                null);
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                MnemonicSensorsEntityFilterPlan.METHOD,
                MnemonicSensorsEntityFilterPlan.DESCRIPTOR,
                null,
                null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/campaign/LocationAPI",
                "getAllEntities",
                "()Ljava/util/List;",
                true));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Iterable"));
        for (int index = 0; index < filterCalls; index++) {
            method.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    COLLECTIONS,
                    "filterNotNull",
                    "(Ljava/lang/Iterable;)Ljava/util/List;",
                    false));
            if (index + 1 < filterCalls) {
                method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Iterable"));
            }
        }
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Iterable"));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/Collection"));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, "java/lang/Iterable", "iterator",
                "()Ljava/util/Iterator;", true));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 4));
        LabelNode loop = new LabelNode();
        LabelNode end = new LabelNode();
        method.instructions.add(loop);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, end));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next",
                "()Ljava/lang/Object;", true));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 5));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, ENTITY));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 6));
        if (predicateBranch) {
            method.instructions.add(new InsnNode(Opcodes.ICONST_1));
            method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, loop));
        }
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 5));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Collection",
                "add",
                "(Ljava/lang/Object;)Z",
                true));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, loop));
        method.instructions.add(end);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static MethodNode method(ClassNode owner) {
        return owner.methods.stream()
                .filter(candidate -> MnemonicSensorsEntityFilterPlan.METHOD.equals(candidate.name)
                        && MnemonicSensorsEntityFilterPlan.DESCRIPTOR.equals(candidate.desc))
                .findFirst().orElseThrow();
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) count++;
        }
        return count;
    }

    private static List<JumpInsnNode> jumps(MethodNode method, int opcode) {
        return java.util.Arrays.stream(method.instructions.toArray())
                .filter(instruction -> instruction instanceof JumpInsnNode
                        && instruction.getOpcode() == opcode)
                .map(instruction -> (JumpInsnNode) instruction)
                .toList();
    }
}
