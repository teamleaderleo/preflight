package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Matches the reviewed Windows and Linux upload bodies; unknown buffers retain the original GL state. */
final class TextureUnpackAlignmentPlan {
    private static final String GL = "org/lwjgl/opengl/GL11";
    private static final String RUNTIME = "dev/starsector/preflight/agent/TexturePreparedPixelRuntime";
    static final String HELPER_PREFIX = "preflight$aligned$";
    static final String HELPER_DESCRIPTOR = "(IIIIIIIILjava/nio/ByteBuffer;Ljava/lang/String;)V";
    private static final String DESCRIPTOR = "(IIIIIIIILjava/nio/ByteBuffer;)V";

    private TextureUnpackAlignmentPlan() { }

    static List<MethodNode> apply(ClassSignature signature, List<MethodNode> methods) {
        if (!TexturePreparedResourceLoaderPlan.WINDOWS_SHA256.equals(signature.sha256())
                && !AdapterTargetRegistry.texturePreparedPixelTarget().sha256().equals(signature.sha256())
                && !AdapterTargetRegistry.linuxTexturePreparedPixelTarget().sha256().equals(signature.sha256())) {
            return List.of();
        }
        List<MethodNode> helpers = new ArrayList<>();
        for (MethodNode method : methods) {
            List<MethodInsnNode> calls = new ArrayList<>();
            for (AbstractInsnNode node : method.instructions) {
                if (node instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                        && GL.equals(call.owner) && DESCRIPTOR.equals(call.desc)
                        && (call.name.equals("glTexImage2D") || call.name.equals("glTexSubImage2D"))) calls.add(call);
            }
            for (MethodInsnNode call : calls) {
                String name = HELPER_PREFIX + call.name;
                if (helpers.stream().noneMatch(helper -> helper.name.equals(name))) {
                    MethodNode helper = new MethodNode(Opcodes.ASM9,
                            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                            name, HELPER_DESCRIPTOR, null, null);
                    helper.maxLocals = 10;
                    for (int i = 0; i < 9; i++) helper.instructions.add(
                            new VarInsnNode(i == 8 ? Opcodes.ALOAD : Opcodes.ILOAD, i));
                    MethodInsnNode nativeCall = new MethodInsnNode(Opcodes.INVOKESTATIC,
                            GL, call.name, DESCRIPTOR, false);
                    helper.instructions.add(nativeCall);
                    helper.instructions.add(new InsnNode(Opcodes.RETURN));
                    instrument(helper, nativeCall);
                    helpers.add(helper);
                }
                int path = TextureUploadProbePlan.pathLocal(method);
                method.instructions.insertBefore(call, path >= 0
                        ? new VarInsnNode(Opcodes.ALOAD, path)
                        : new InsnNode(Opcodes.ACONST_NULL));
                call.owner = signature.internalName();
                call.name = name;
                call.desc = HELPER_DESCRIPTOR;
            }
        }
        return helpers;
    }

    private static void instrument(MethodNode method, MethodInsnNode call) {
        int[] args = new int[9];
        for (int i = 0; i < args.length; i++) args[i] = method.maxLocals++;
        int previous = method.maxLocals++, restore = method.maxLocals++, error = method.maxLocals++;
        boolean sub = call.name.equals("glTexSubImage2D");
        int width = args[sub ? 4 : 3], height = args[sub ? 5 : 4];
        LabelNode invoke = new LabelNode(), start = new LabelNode(), end = new LabelNode();
        LabelNode handler = new LabelNode(), done = new LabelNode();
        InsnList before = new InsnList();
        for (int i = 8; i >= 0; i--) before.add(new VarInsnNode(i == 8 ? Opcodes.ASTORE : Opcodes.ISTORE, args[i]));
        before.add(new InsnNode(Opcodes.ICONST_M1));
        before.add(new VarInsnNode(Opcodes.ISTORE, restore));
        before.add(new VarInsnNode(Opcodes.ALOAD, args[8]));
        before.add(new VarInsnNode(Opcodes.ILOAD, width));
        before.add(new VarInsnNode(Opcodes.ILOAD, height));
        before.add(new VarInsnNode(Opcodes.ILOAD, args[6]));
        before.add(new VarInsnNode(Opcodes.ILOAD, args[7]));
        before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "requiresTightRgbUnpack",
                "(Ljava/nio/ByteBuffer;IIII)Z", false));
        before.add(new JumpInsnNode(Opcodes.IFEQ, invoke));
        before.add(new LdcInsnNode(3317));
        before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GL, "glGetInteger", "(I)I", false));
        before.add(new VarInsnNode(Opcodes.ISTORE, previous));
        before.add(new VarInsnNode(Opcodes.ILOAD, width));
        before.add(new VarInsnNode(Opcodes.ILOAD, previous));
        before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "rgbAlignmentNeedsOverride", "(II)Z", false));
        before.add(new JumpInsnNode(Opcodes.IFEQ, invoke));
        before.add(new VarInsnNode(Opcodes.ILOAD, previous));
        before.add(new VarInsnNode(Opcodes.ISTORE, restore));
        before.add(new LdcInsnNode(3317));
        before.add(new InsnNode(Opcodes.ICONST_1));
        before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GL, "glPixelStorei", "(II)V", false));
        before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "unpackAlignmentChanged", "()V", false));
        before.add(invoke);
        before.add(start);
        for (int i = 0; i < args.length; i++) before.add(new VarInsnNode(i == 8 ? Opcodes.ALOAD : Opcodes.ILOAD, args[i]));
        method.instructions.insertBefore(call, before);
        InsnList after = new InsnList();
        after.add(end);
        restore(after, restore);
        after.add(new JumpInsnNode(Opcodes.GOTO, done));
        after.add(handler);
        after.add(new VarInsnNode(Opcodes.ASTORE, error));
        restore(after, restore);
        after.add(new VarInsnNode(Opcodes.ALOAD, error));
        after.add(new InsnNode(Opcodes.ATHROW));
        after.add(done);
        method.instructions.insert(call, after);
        // Restore before the existing outer exceptional-release/loader handlers run.
        method.tryCatchBlocks.add(0, new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
    }

    private static void restore(InsnList code, int previous) {
        LabelNode unchanged = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ILOAD, previous));
        code.add(new JumpInsnNode(Opcodes.IFLE, unchanged));
        code.add(new LdcInsnNode(3317));
        code.add(new VarInsnNode(Opcodes.ILOAD, previous));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GL, "glPixelStorei", "(II)V", false));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "unpackAlignmentRestored", "()V", false));
        code.add(unchanged);
    }
}
