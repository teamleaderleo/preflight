package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Captures Windows TextureLoader and counts later requests served by its own overlap cache. */
final class TextureSpecStoreOverlapPlan {
    static final String TARGET_CLASS = TexturePreparedPixelPlan.TARGET_CLASS;
    static final String ORIGINAL_SHA256 =
            "7d89b44c9401a122529450d17407dbfc8d52e13a9f7eb941dc93125eb5fc153b";
    static final String LOAD_METHOD = "o00000";
    static final String LOAD_DESCRIPTOR =
            "(Ljava/lang/String;)Lcom/fs/graphics/Object;";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/DisplayThreadSpecStoreProbeRuntime";

    private TextureSpecStoreOverlapPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!DisplayThreadSpecStoreProbeRuntime.candidateRequested()
                || !TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || !signature.hasMethod("<init>", "()V")
                || !signature.hasMethod(LOAD_METHOD, LOAD_DESCRIPTOR)) return null;
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode constructor = unique(owner, "<init>", "()V");
        MethodNode load = unique(owner, LOAD_METHOD, LOAD_DESCRIPTOR);
        if (constructor == null || load == null
                || calls(constructor, RUNTIME) != 0 || calls(load, RUNTIME) != 0) return null;
        AbstractInsnNode constructorReturn = uniqueReturn(constructor);
        if (constructorReturn == null) return null;
        InsnList capture = new InsnList();
        capture.add(new VarInsnNode(Opcodes.ALOAD, 0));
        capture.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "captureTextureLoader", "(Ljava/lang/Object;)V", false));
        constructor.instructions.insertBefore(constructorReturn, capture);
        InsnList observe = new InsnList();
        observe.add(new VarInsnNode(Opcodes.ALOAD, 1));
        observe.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "observeTextureRequest", "(Ljava/lang/String;)V", false));
        load.instructions.insert(observe);
        ClassWriter writer = new SafeClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode unique(ClassNode owner, String name, String descriptor) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (found != null) return null;
                found = method;
            }
        }
        return found;
    }

    private static AbstractInsnNode uniqueReturn(MethodNode method) {
        AbstractInsnNode found = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                if (found != null) return null;
                found = instruction;
            }
        }
        return found;
    }

    private static int calls(MethodNode method, String owner) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && owner.equals(call.owner)) result++;
        }
        return result;
    }
}
