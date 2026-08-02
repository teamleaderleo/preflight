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

class WeaponLoaderPhasePlanTest {
    private static final String RUNTIME = "dev/starsector/preflight/agent/StartupPhaseRuntime";
    private static final String LOADING_UTILS = "com/fs/starfarer/loading/LoadingUtils";
    private static final String SCRIPT_STORE = "com/fs/starfarer/loading/scripts/ScriptStore";
    private static final String REGISTRY = "com/fs/starfarer/loading/interface";

    @Test
    void timesEveryReviewedWeaponSubphase() throws Exception {
        byte[] original = fixture(3);
        byte[] rewritten = WeaponLoaderPhasePlan.transform(ClassSignature.parse(original), original);

        assertNotNull(rewritten);
        assertEquals(8, runtimeCalls(rewritten, "specSubphaseStart"));
        assertEquals(8, runtimeCalls(rewritten, "specSubphaseEnd"));
    }

    @Test
    void refusesAChangedWeaponLoaderShape() throws Exception {
        byte[] changed = fixture(2);
        assertNull(WeaponLoaderPhasePlan.transform(ClassSignature.parse(changed), changed));
    }

    @Test
    void refusesToWeaveTwice() throws Exception {
        byte[] original = fixture(3);
        byte[] once = WeaponLoaderPhasePlan.transform(ClassSignature.parse(original), original);
        assertNotNull(once);
        assertNull(WeaponLoaderPhasePlan.transform(ClassSignature.parse(once), once));
    }

    private static byte[] fixture(int scriptCalls) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, WeaponLoaderPhasePlan.TARGET_CLASS,
                null, "java/lang/Object", null);

        MethodVisitor loadAll = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                WeaponLoaderPhasePlan.LOAD_ALL_METHOD, WeaponLoaderPhasePlan.LOAD_ALL_DESCRIPTOR,
                null, null);
        loadAll.visitCode();
        for (int index = 0; index < 2; index++) {
            loadAll.visitLdcInsn(index == 0 ? "data/weapons" : "data/shipsystems/wpn");
            loadAll.visitLdcInsn("wpn");
            loadAll.visitMethodInsn(Opcodes.INVOKESTATIC, LOADING_UTILS, "super",
                    "(Ljava/lang/String;Ljava/lang/String;)Ljava/util/List;", false);
            loadAll.visitInsn(Opcodes.POP);
            loadAll.visitLdcInsn("example.wpn");
            loadAll.visitMethodInsn(Opcodes.INVOKESTATIC, WeaponLoaderPhasePlan.TARGET_CLASS,
                    WeaponLoaderPhasePlan.LOAD_ONE_METHOD,
                    WeaponLoaderPhasePlan.LOAD_ONE_DESCRIPTOR, false);
        }
        loadAll.visitInsn(Opcodes.RETURN);
        loadAll.visitMaxs(0, 0);
        loadAll.visitEnd();

        MethodVisitor loadOne = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                WeaponLoaderPhasePlan.LOAD_ONE_METHOD, WeaponLoaderPhasePlan.LOAD_ONE_DESCRIPTOR,
                null, null);
        loadOne.visitCode();
        loadOne.visitVarInsn(Opcodes.ALOAD, 0);
        loadOne.visitMethodInsn(Opcodes.INVOKESTATIC, LOADING_UTILS, "Ó00000",
                "(Ljava/lang/String;)Lorg/json/JSONObject;", false);
        loadOne.visitInsn(Opcodes.POP);
        for (int index = 0; index < scriptCalls; index++) {
            loadOne.visitLdcInsn("script." + index);
            loadOne.visitMethodInsn(Opcodes.INVOKESTATIC, SCRIPT_STORE, "Object",
                    "(Ljava/lang/String;)V", false);
        }
        for (int index = 0; index < 2; index++) {
            loadOne.visitLdcInsn("weapon." + index);
            loadOne.visitInsn(Opcodes.ACONST_NULL);
            loadOne.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "o00000",
                    "(Ljava/lang/String;Lcom/fs/starfarer/loading/specs/BaseWeaponSpec;)V", false);
        }
        loadOne.visitInsn(Opcodes.RETURN);
        loadOne.visitMaxs(0, 0);
        loadOne.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static int runtimeCalls(byte[] bytes, String name) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        int count = 0;
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null; instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call
                        && RUNTIME.equals(call.owner) && name.equals(call.name)) {
                    count++;
                }
            }
        }
        return count;
    }
}
