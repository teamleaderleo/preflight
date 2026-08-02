package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import java.util.function.IntUnaryOperator;
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

class ShipHullLoaderPhasePlanTest {
    private static final String RUNTIME = "dev/starsector/preflight/agent/StartupPhaseRuntime";

    @Test
    void wrapsOnlyTheReviewedHullLoaderBoundaries() throws Exception {
        byte[] original = fixture(value -> value);
        byte[] rewritten = ShipHullLoaderPhasePlan.transform(ClassSignature.parse(original), original);

        assertNotNull(rewritten);
        assertEquals(Map.of(
                "hull-file-listing", 1,
                "hull-json-merge-parse", 1,
                "hull-spec-lookup", 3,
                "hull-registry-insert", 1), labels(rewritten));
        assertEquals(6, calls(rewritten, RUNTIME, "specSubphaseStart"));
        assertEquals(6, calls(rewritten, RUNTIME, "specSubphaseEnd"));
    }

    @Test
    void refusesChangedCallCounts() throws Exception {
        byte[] changed = fixture(value -> value + 1);
        assertNull(ShipHullLoaderPhasePlan.transform(ClassSignature.parse(changed), changed));
    }

    @Test
    void refusesDoubleWeaving() throws Exception {
        byte[] original = fixture(value -> value);
        byte[] once = ShipHullLoaderPhasePlan.transform(ClassSignature.parse(original), original);
        assertNotNull(once);
        assertNull(ShipHullLoaderPhasePlan.transform(ClassSignature.parse(once), once));
    }

    private static byte[] fixture(IntUnaryOperator counts) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, ShipHullLoaderPhasePlan.TARGET_CLASS,
                null, "java/lang/Object", null);

        MethodVisitor loadAll = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                ShipHullLoaderPhasePlan.LOAD_ALL_METHOD,
                ShipHullLoaderPhasePlan.LOAD_ALL_DESCRIPTOR, null, null);
        loadAll.visitCode();
        repeat(loadAll, counts.applyAsInt(1), () -> {
            loadAll.visitLdcInsn("data/hulls");
            loadAll.visitLdcInsn("ship");
            loadAll.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/fs/starfarer/loading/LoadingUtils", "super",
                    "(Ljava/lang/String;Ljava/lang/String;)Ljava/util/List;", false);
            loadAll.visitInsn(Opcodes.POP);
        });
        loadAll.visitLdcInsn("data/hulls/example.ship");
        loadAll.visitMethodInsn(Opcodes.INVOKESTATIC, ShipHullLoaderPhasePlan.TARGET_CLASS,
                ShipHullLoaderPhasePlan.LOAD_ONE_METHOD,
                ShipHullLoaderPhasePlan.LOAD_ONE_DESCRIPTOR, false);
        loadAll.visitInsn(Opcodes.RETURN);
        loadAll.visitMaxs(0, 0);
        loadAll.visitEnd();

        MethodVisitor loadOne = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                ShipHullLoaderPhasePlan.LOAD_ONE_METHOD,
                ShipHullLoaderPhasePlan.LOAD_ONE_DESCRIPTOR, null, null);
        loadOne.visitCode();
        loadOne.visitVarInsn(Opcodes.ALOAD, 0);
        loadOne.visitMethodInsn(Opcodes.INVOKESTATIC,
                "com/fs/starfarer/loading/LoadingUtils", "Ó00000",
                "(Ljava/lang/String;)Lorg/json/JSONObject;", false);
        loadOne.visitInsn(Opcodes.POP);
        repeat(loadOne, 3, () -> {
            loadOne.visitLdcInsn(org.objectweb.asm.Type.getObjectType("java/lang/Object"));
            loadOne.visitLdcInsn("id");
            loadOne.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/fs/starfarer/loading/SpecStore", "o00000",
                    "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;", false);
            loadOne.visitInsn(Opcodes.POP);
        });
        loadOne.visitLdcInsn("example");
        loadOne.visitInsn(Opcodes.ACONST_NULL);
        loadOne.visitMethodInsn(Opcodes.INVOKESTATIC,
                "com/fs/starfarer/loading/M", "o00000",
                "(Ljava/lang/String;Lcom/fs/starfarer/loading/specs/g;)V", false);
        loadOne.visitInsn(Opcodes.RETURN);
        loadOne.visitMaxs(0, 0);
        loadOne.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void repeat(MethodVisitor visitor, int count, Runnable body) {
        for (int index = 0; index < count; index++) {
            body.run();
        }
    }

    private static Map<String, Integer> labels(byte[] bytes) {
        Map<String, Integer> result = new java.util.TreeMap<>();
        ClassNode owner = node(bytes);
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null; instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)
                        && "specSubphaseStart".equals(call.name)
                        && instruction.getPrevious() instanceof LdcInsnNode label) {
                    result.merge(String.valueOf(label.cst), 1, Integer::sum);
                }
            }
        }
        return result;
    }

    private static int calls(byte[] bytes, String ownerName, String methodName) {
        int count = 0;
        for (MethodNode method : node(bytes).methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null; instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call
                        && ownerName.equals(call.owner) && methodName.equals(call.name)) {
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
