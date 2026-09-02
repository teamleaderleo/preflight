package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Runs the Display-thread proof after the exact Windows caller has left Display.update(). */
final class DisplayUpdateCallerPlan {
    static final String TARGET_CLASS = "com/fs/graphics/F";
    static final String ORIGINAL_SHA256 =
            "d1353602d4b34e85701ed9ca2de4f00c37aa0982a7f8d2d2197781b24a19eafb";
    static final String METHOD = "o00000";
    static final String DESCRIPTOR = "([Ljava/lang/String;)V";
    private static final String DISPLAY = "org/lwjgl/opengl/Display";
    private static final String DISPLAY_UPDATE = "update";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/FrameTimeRuntime";

    private DisplayUpdateCallerPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!DisplayThreadTextureProbeRuntime.requested()
                || !TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || !signature.hasMethod(METHOD, DESCRIPTOR)) return null;
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode method = unique(owner);
        if (method == null || runtimeCalls(method) != 0) return null;
        MethodInsnNode update = uniqueUpdate(method);
        if (update == null) return null;
        method.instructions.insert(update, new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "postUpdate", "()V", false));
        ClassWriter writer = new SafeClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode unique(ClassNode owner) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (METHOD.equals(method.name) && DESCRIPTOR.equals(method.desc)) {
                if (found != null) return null;
                found = method;
            }
        }
        return found;
    }

    private static MethodInsnNode uniqueUpdate(MethodNode method) {
        MethodInsnNode found = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && DISPLAY.equals(call.owner) && DISPLAY_UPDATE.equals(call.name)
                    && "()V".equals(call.desc)) {
                if (found != null) return null;
                found = call;
            }
        }
        return found;
    }

    private static int runtimeCalls(MethodNode method) {
        int calls = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) calls++;
        }
        return calls;
    }
}
