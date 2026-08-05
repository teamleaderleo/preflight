package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class FactionLoaderPhasePlanTest {
    private static final String RUNTIME = "dev/starsector/preflight/agent/StartupPhaseRuntime";

    @Test
    void attributesEveryReviewedRepeatedOperation() throws Exception {
        byte[] original = fixture(18);
        byte[] rewritten = FactionLoaderPhasePlan.transform(
                ClassSignature.parse(original), original);

        assertNotNull(rewritten);
        MethodNode load = load(rewritten);
        assertEquals(41, runtimeCalls(load, "specSubphaseStart"));
        assertEquals(41, runtimeCalls(load, "specSubphaseEnd"));
    }

    @Test
    void refusesAChangedFactionLoaderShape() throws Exception {
        byte[] changed = fixture(17);
        assertNull(FactionLoaderPhasePlan.transform(ClassSignature.parse(changed), changed));
    }

    @Test
    void refusesToWeaveTwice() throws Exception {
        byte[] original = fixture(18);
        byte[] once = FactionLoaderPhasePlan.transform(ClassSignature.parse(original), original);
        assertNotNull(once);
        assertNull(FactionLoaderPhasePlan.transform(ClassSignature.parse(once), once));
    }

    private static byte[] fixture(int nameCalls) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, FactionLoaderPhasePlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                FactionLoaderPhasePlan.LOAD_METHOD, FactionLoaderPhasePlan.LOAD_DESCRIPTOR,
                null, null);
        method.visitCode();

        for (int index = 0; index < 5; index++) {
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "com/fs/starfarer/loading/LoadingUtils",
                    "Ó00000", "(Ljava/lang/String;)Lorg/json/JSONObject;", false);
            method.visitInsn(Opcodes.POP);
        }
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "com/fs/starfarer/loading/LoadingUtils",
                "Ò00000", "(Ljava/lang/String;Ljava/lang/String;Z)Lorg/json/JSONArray;", false);
        method.visitInsn(Opcodes.POP);
        for (int index = 0; index < 8; index++) {
            for (int argument = 0; argument < 3; argument++) {
                method.visitInsn(Opcodes.ACONST_NULL);
            }
            method.visitInsn(Opcodes.ICONST_0);
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, FactionLoaderPhasePlan.TARGET_CLASS,
                    "o00000", "(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;Z"
                            + "Lcom/fs/starfarer/loading/SpecStore$Oo;)V", false);
        }
        for (int index = 0; index < 3; index++) {
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, FactionLoaderPhasePlan.TARGET_CLASS,
                    "o00000", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;", false);
            method.visitInsn(Opcodes.POP);
        }
        for (int index = 0; index < nameCalls; index++) {
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "org/json/JSONObject", "getNames",
                    "(Lorg/json/JSONObject;)[Ljava/lang/String;", false);
            method.visitInsn(Opcodes.POP);
        }
        for (int index = 0; index < 6; index++) {
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitInsn(Opcodes.ACONST_NULL);
            method.visitInsn(Opcodes.ICONST_0);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "com/fs/starfarer/loading/ResourceLoaderState", "queueResource",
                    "(Lcom/fs/starfarer/loading/ResourceLoaderState$o;Ljava/lang/String;I)V", false);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static MethodNode load(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream()
                .filter(method -> FactionLoaderPhasePlan.LOAD_METHOD.equals(method.name)
                        && FactionLoaderPhasePlan.LOAD_DESCRIPTOR.equals(method.desc))
                .findFirst().orElseThrow();
    }

    private static int runtimeCalls(MethodNode method, String name) {
        int count = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && RUNTIME.equals(call.owner) && name.equals(call.name)) {
                count++;
            }
        }
        return count;
    }
}
