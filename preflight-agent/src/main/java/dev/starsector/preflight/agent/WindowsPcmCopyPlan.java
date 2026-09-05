package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Adds an optional bulk-copy branch around the exact Windows byte-at-a-time PCM loop. */
final class WindowsPcmCopyPlan {
    static final String TARGET = "sound/O0oO";
    static final String SHA = "4b28c09ee5004a353ea2f0d61611eb4c7e0504abfc7b1f5328d6a7123f7f72b7";
    static final String DESCRIPTOR = "(Ljava/io/InputStream;)Lsound/G;";
    private static final String RUNTIME = "dev/starsector/preflight/agent/WindowsPcmCopyRuntime";
    private WindowsPcmCopyPlan() { }
    static byte[] transform(ClassSignature signature, byte[] bytes) {
        if (!TARGET.equals(signature.internalName()) || !SHA.equals(dev.starsector.preflight.core.Hashes.sha256(bytes))) return null;
        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        MethodNode decode = node.methods.stream().filter(m -> m.name.equals("super") && m.desc.equals(DESCRIPTOR)).findFirst().orElse(null);
        if (decode == null) return null;
        AbstractInsnNode store = null, closeReceiver = null;
        for (AbstractInsnNode instruction : decode.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals(RUNTIME)) return null;
                if (call.owner.equals("sound/F") && call.name.equals("<init>") && call.desc.equals("(Ljava/io/InputStream;Z)V")) {
                    if (store != null || !(call.getNext() instanceof VarInsnNode variable)
                            || variable.getOpcode() != Opcodes.ASTORE || variable.var != 3) return null;
                    store = variable;
                }
                if (call.owner.equals("sound/F") && call.name.equals("close") && call.desc.equals("()V")) {
                    if (closeReceiver != null || !(call.getPrevious() instanceof VarInsnNode variable)
                            || variable.getOpcode() != Opcodes.ALOAD || variable.var != 3) return null;
                    closeReceiver = variable;
                }
            }
        }
        if (store == null || closeReceiver == null) return null;
        LabelNode done = new LabelNode();
        decode.instructions.insertBefore(closeReceiver, done);
        InsnList branch = new InsnList();
        branch.add(new VarInsnNode(Opcodes.ALOAD, 3));
        branch.add(new VarInsnNode(Opcodes.ALOAD, 2));
        branch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "copy",
                "(Ljava/io/InputStream;Ljava/io/ByteArrayOutputStream;)Z", false));
        branch.add(new JumpInsnNode(Opcodes.IFNE, done));
        decode.instructions.insert(store, branch);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
