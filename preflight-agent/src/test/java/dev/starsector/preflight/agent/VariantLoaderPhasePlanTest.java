package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

class VariantLoaderPhasePlanTest {
    private static final String RUNTIME = "dev/starsector/preflight/agent/StartupPhaseRuntime";
    private static final String LOADING_UTILS = "com/fs/starfarer/loading/LoadingUtils";
    private static final String VARIANT = "com/fs/starfarer/loading/specs/HullVariantSpec";
    private static final String REGISTRY = "com/fs/starfarer/loading/Oo" + "O".repeat(120);

    @org.junit.jupiter.api.Test
    void timesEveryReviewedVariantSubphaseAndStartsConstructionBeforeNew() throws Exception {
        byte[] original = fixture(true);
        byte[] rewritten = VariantLoaderPhasePlan.transform(ClassSignature.parse(original), original);

        assertNotNull(rewritten);
        MethodNode method = method(rewritten);
        assertEquals(7, runtimeCalls(method, "specSubphaseStart"));
        assertEquals(7, runtimeCalls(method, "specSubphaseEnd"));

        TypeInsnNode allocation = firstNew(method);
        AbstractInsnNode prior = allocation.getPrevious();
        assertTrue(prior instanceof MethodInsnNode call
                && RUNTIME.equals(call.owner) && "specSubphaseStart".equals(call.name));
    }

    @org.junit.jupiter.api.Test
    void refusesAChangedVariantLoaderShape() throws Exception {
        byte[] changed = fixture(false);
        assertNull(VariantLoaderPhasePlan.transform(ClassSignature.parse(changed), changed));
    }

    @org.junit.jupiter.api.Test
    void refusesToWeaveTwice() throws Exception {
        byte[] original = fixture(true);
        byte[] once = VariantLoaderPhasePlan.transform(ClassSignature.parse(original), original);
        assertNotNull(once);
        assertNull(VariantLoaderPhasePlan.transform(ClassSignature.parse(once), once));
    }

    private static byte[] fixture(boolean includePostPass) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, SpecStorePhasePlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                VariantLoaderPhasePlan.METHOD, VariantLoaderPhasePlan.DESCRIPTOR, null, null);
        method.visitCode();

        method.visitLdcInsn("data/variants");
        method.visitLdcInsn("variant");
        method.visitMethodInsn(Opcodes.INVOKESTATIC, LOADING_UTILS, "super",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/util/List;", false);
        method.visitInsn(Opcodes.POP);
        method.visitLdcInsn("data/variants");
        method.visitMethodInsn(Opcodes.INVOKESTATIC, LOADING_UTILS, "Ö00000",
                "(Ljava/lang/String;)Ljava/util/List;", false);
        method.visitInsn(Opcodes.POP);
        method.visitLdcInsn("data/variants/extra");
        method.visitLdcInsn("variant");
        method.visitMethodInsn(Opcodes.INVOKESTATIC, LOADING_UTILS, "super",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/util/List;", false);
        method.visitInsn(Opcodes.POP);

        method.visitLdcInsn("data/variants/example.variant");
        method.visitMethodInsn(Opcodes.INVOKESTATIC, LOADING_UTILS, "Ó00000",
                "(Ljava/lang/String;)Lorg/json/JSONObject;", false);
        method.visitVarInsn(Opcodes.ASTORE, 0);
        method.visitTypeInsn(Opcodes.NEW, VARIANT);
        method.visitInsn(Opcodes.DUP);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, VARIANT, "<init>",
                "(Lorg/json/JSONObject;)V", false);
        method.visitVarInsn(Opcodes.ASTORE, 1);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "super",
                "(Lcom/fs/starfarer/loading/specs/HullVariantSpec;Z)V", false);
        if (includePostPass) {
            method.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "super",
                    "()Ljava/util/List;", false);
            method.visitInsn(Opcodes.POP);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static MethodNode method(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream()
                .filter(method -> VariantLoaderPhasePlan.METHOD.equals(method.name)
                        && VariantLoaderPhasePlan.DESCRIPTOR.equals(method.desc))
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

    private static TypeInsnNode firstNew(MethodNode method) {
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) {
                return type;
            }
        }
        throw new AssertionError("fixture has no NEW instruction");
    }
}
