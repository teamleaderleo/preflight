package dev.starsector.preflight.agent;

import java.io.IOException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Reuses GraphicsLib's immutable texture-data key Strings during a game session. */
final class GraphicsLibTextureKeyPlan {
    static final String INPUT_SHA256 =
            "f2f4c45d9d19f1dbc51821779ee2efca1817c96ac680d67e442cdb5180ef15ff";

    private static final String TEXTURE_DATA = "org/dark/shaders/util/TextureData";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/GraphicsLibTextureKeyRuntime";
    private static final String METHOD = "getTextureDataKey";
    private static final String ORIGINAL = "preflight$original$getTextureDataKey";
    private static final String DESCRIPTOR =
            "(Ljava/lang/String;Lorg/dark/shaders/util/TextureData$ObjectType;I)"
                    + "Ljava/lang/String;";

    private GraphicsLibTextureKeyPlan() {
    }

    static byte[] transform(byte[] input) throws IOException {
        ClassSignature signature = ClassSignature.parse(input);
        if (!TEXTURE_DATA.equals(signature.internalName())
                || !INPUT_SHA256.equals(signature.sha256())) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(input).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode target = exactMethod(owner, METHOD, DESCRIPTOR);
        if (target == null
                || exactMethod(owner, ORIGINAL, DESCRIPTOR) != null
                || (target.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC))
                        != (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) {
            return null;
        }
        target.name = ORIGINAL;
        target.access |= Opcodes.ACC_SYNTHETIC;
        owner.methods.add(wrapper(target.access & ~Opcodes.ACC_SYNTHETIC));
        ClassWriter writer = new SafeClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        GraphicsLibTextureKeyRuntime.installed();
        return writer.toByteArray();
    }

    private static MethodNode wrapper(int access) {
        MethodNode method = new MethodNode(Opcodes.ASM9, access, METHOD, DESCRIPTOR, null, null);
        LabelNode miss = new LabelNode();
        InsnList body = method.instructions;
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new VarInsnNode(Opcodes.ALOAD, 1));
        body.add(new VarInsnNode(Opcodes.ILOAD, 2));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "lookup",
                "(Ljava/lang/String;Ljava/lang/Object;I)Ljava/lang/String;", false));
        body.add(new InsnNode(Opcodes.DUP));
        body.add(new JumpInsnNode(Opcodes.IFNULL, miss));
        body.add(new InsnNode(Opcodes.ARETURN));
        body.add(miss);
        body.add(new InsnNode(Opcodes.POP));
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new VarInsnNode(Opcodes.ALOAD, 1));
        body.add(new VarInsnNode(Opcodes.ILOAD, 2));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TEXTURE_DATA, ORIGINAL,
                DESCRIPTOR, false));
        body.add(new VarInsnNode(Opcodes.ASTORE, 3));
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new VarInsnNode(Opcodes.ALOAD, 1));
        body.add(new VarInsnNode(Opcodes.ILOAD, 2));
        body.add(new VarInsnNode(Opcodes.ALOAD, 3));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "record",
                "(Ljava/lang/String;Ljava/lang/Object;ILjava/lang/String;)Ljava/lang/String;",
                false));
        body.add(new InsnNode(Opcodes.ARETURN));
        return method;
    }

    private static MethodNode exactMethod(ClassNode owner, String name, String descriptor) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (found != null) {
                    return null;
                }
                found = method;
            }
        }
        return found;
    }
}
