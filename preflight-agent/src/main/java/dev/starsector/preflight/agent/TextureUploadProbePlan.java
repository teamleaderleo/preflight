package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Times the reviewed stock GL texture uploads without replacing or deferring them. */
final class TextureUploadProbePlan {
    private static final String GL11 = "org/lwjgl/opengl/GL11";
    private static final String DESCRIPTOR = "(IIIIIIIILjava/nio/ByteBuffer;)V";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/TextureUploadProbeRuntime";
    private static final String PATH_UPLOAD_DESCRIPTOR =
            "(Lcom/fs/graphics/Object;Ljava/lang/String;IIIIIZ)Lcom/fs/graphics/Object;";

    private TextureUploadProbePlan() {
    }

    static int instrument(List<MethodNode> methods) {
        if (!TextureUploadProbeRuntime.enabled()) {
            return 0;
        }
        int instrumented = 0;
        for (MethodNode method : methods) {
            List<MethodInsnNode> uploads = new ArrayList<>();
            for (AbstractInsnNode instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && GL11.equals(call.owner)
                        && ("glTexImage2D".equals(call.name) || "glTexSubImage2D".equals(call.name))
                        && DESCRIPTOR.equals(call.desc)) {
                    uploads.add(call);
                }
            }
            for (MethodInsnNode upload : uploads) {
                instrument(method, upload);
                instrumented++;
            }
        }
        TextureUploadProbeRuntime.installed(instrumented);
        return instrumented;
    }

    private static void instrument(MethodNode method, MethodInsnNode upload) {
        int target = method.maxLocals++;
        int level = method.maxLocals++;
        int internalFormat = method.maxLocals++;
        int width = method.maxLocals++;
        int height = method.maxLocals++;
        int border = method.maxLocals++;
        int format = method.maxLocals++;
        int type = method.maxLocals++;
        int pixels = method.maxLocals++;
        int started = method.maxLocals;
        method.maxLocals += 2;
        int unpackAlignment = method.maxLocals++;

        InsnList before = new InsnList();
        before.add(new VarInsnNode(Opcodes.ASTORE, pixels));
        before.add(new VarInsnNode(Opcodes.ISTORE, type));
        before.add(new VarInsnNode(Opcodes.ISTORE, format));
        before.add(new VarInsnNode(Opcodes.ISTORE, border));
        before.add(new VarInsnNode(Opcodes.ISTORE, height));
        before.add(new VarInsnNode(Opcodes.ISTORE, width));
        before.add(new VarInsnNode(Opcodes.ISTORE, internalFormat));
        before.add(new VarInsnNode(Opcodes.ISTORE, level));
        before.add(new VarInsnNode(Opcodes.ISTORE, target));
        if (Boolean.getBoolean(TextureUploadProbeRuntime.CHECKPOINT_PROPERTY)) {
            // Query only potentially misaligned RGB rows, on the original GL owner thread.
            // This is diagnostic-only and never changes unpack state.
            org.objectweb.asm.tree.LabelNode aligned = new org.objectweb.asm.tree.LabelNode();
            before.add(new InsnNode(Opcodes.ICONST_M1));
            before.add(new VarInsnNode(Opcodes.ISTORE, unpackAlignment));
            before.add(new VarInsnNode(Opcodes.ILOAD, format));
            before.add(new LdcInsnNode(6407));
            before.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IF_ICMPNE, aligned));
            before.add(new VarInsnNode(Opcodes.ILOAD, "glTexSubImage2D".equals(upload.name) ? height : width));
            before.add(new InsnNode(Opcodes.ICONST_3));
            before.add(new InsnNode(Opcodes.IMUL));
            before.add(new InsnNode(Opcodes.ICONST_3));
            before.add(new InsnNode(Opcodes.IAND));
            before.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFEQ, aligned));
            before.add(new LdcInsnNode(3317));
            before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GL11, "glGetInteger", "(I)I", false));
            before.add(new VarInsnNode(Opcodes.ISTORE, unpackAlignment));
            before.add(aligned);
            before.add(new VarInsnNode(Opcodes.ILOAD, target));
            before.add(new VarInsnNode(Opcodes.ILOAD, level));
            before.add(new VarInsnNode(Opcodes.ILOAD, internalFormat));
            before.add(new VarInsnNode(Opcodes.ILOAD, width));
            before.add(new VarInsnNode(Opcodes.ILOAD, height));
            before.add(new VarInsnNode(Opcodes.ILOAD, border));
            before.add(new VarInsnNode(Opcodes.ILOAD, format));
            before.add(new VarInsnNode(Opcodes.ILOAD, type));
            before.add(new VarInsnNode(Opcodes.ALOAD, pixels));
            if (pathUpload(method)) before.add(new VarInsnNode(Opcodes.ALOAD, 2));
            else before.add(new LdcInsnNode("<buffered-image>"));
            before.add(new InsnNode("glTexSubImage2D".equals(upload.name) ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
            before.add(new VarInsnNode(Opcodes.ILOAD, unpackAlignment));
            before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "checkpoint",
                    "(IIIIIIIILjava/nio/ByteBuffer;Ljava/lang/String;ZI)V", false));
        }
        before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "begin", "()J", false));
        before.add(new VarInsnNode(Opcodes.LSTORE, started));
        before.add(new VarInsnNode(Opcodes.ILOAD, target));
        before.add(new VarInsnNode(Opcodes.ILOAD, level));
        before.add(new VarInsnNode(Opcodes.ILOAD, internalFormat));
        before.add(new VarInsnNode(Opcodes.ILOAD, width));
        before.add(new VarInsnNode(Opcodes.ILOAD, height));
        before.add(new VarInsnNode(Opcodes.ILOAD, border));
        before.add(new VarInsnNode(Opcodes.ILOAD, format));
        before.add(new VarInsnNode(Opcodes.ILOAD, type));
        before.add(new VarInsnNode(Opcodes.ALOAD, pixels));
        method.instructions.insertBefore(upload, before);

        InsnList after = new InsnList();
        after.add(new VarInsnNode(Opcodes.LLOAD, started));
        boolean subImage = "glTexSubImage2D".equals(upload.name);
        after.add(new VarInsnNode(Opcodes.ILOAD, subImage ? height : width));
        after.add(new VarInsnNode(Opcodes.ILOAD, subImage ? border : height));
        after.add(new VarInsnNode(Opcodes.ILOAD, format));
        after.add(new VarInsnNode(Opcodes.ILOAD, type));
        after.add(new VarInsnNode(Opcodes.ALOAD, pixels));
        if (pathUpload(method)) {
            after.add(new VarInsnNode(Opcodes.ALOAD, 2));
        } else {
            after.add(new LdcInsnNode("<buffered-image>"));
        }
        after.add(new InsnNode("glTexSubImage2D".equals(upload.name)
                ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        after.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                RUNTIME,
                "finish",
                "(JIIIILjava/nio/ByteBuffer;Ljava/lang/String;Z)V",
                false));
        method.instructions.insert(upload, after);
    }

    private static boolean pathUpload(MethodNode method) {
        return PATH_UPLOAD_DESCRIPTOR.equals(method.desc)
                || TexturePreparedResourceLoaderPlan.LOAD_DESCRIPTOR.equals(method.desc);
    }
}
