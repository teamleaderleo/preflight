package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Supplies exact prepared pixels at Fast Rendering 0.8.4's post-DDS, pre-ImageIO seam. */
final class FastRenderingPreparedTexturePlan {
    static final String TARGET_CLASS = "com/genir/renderer/overrides/loading/TextureLoader";
    static final String TARGET_SHA256 =
            "a426f8a33473713b4e43293483dfe4596a517527b92be7e92dcc1701a64b6feb";
    static final String TARGET_METHOD = "loadTextureData";
    static final String TARGET_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/lang/String;)Lcom/genir/renderer/overrides/loading/TextureData;";
    static final String SOURCE_SHA256 =
            "dea3ea3d0fd7437d4a7945fee65f741d9b72d3fec565b9c4807aea479ce56144";

    private static final String DATA = "com/genir/renderer/overrides/loading/TextureData";
    private static final String DDS = "com/genir/renderer/overrides/loading/DDSCache";
    private static final String DDS_METHOD = "getTexture";
    private static final String IMAGE_IO = "javax/imageio/ImageIO";
    private static final String IMAGE_READ = "read";
    private static final String BUILDER = "com/genir/renderer/overrides/TextureBuilder";
    private static final String ANALYZE = "readAndAnalyzeImage";
    private static final String BUFFERED_INPUT = "java/io/BufferedInputStream";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/FastRenderingPreparedTextureRuntime";
    private static final String RUNTIME_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;";

    private FastRenderingPreparedTexturePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !signature.hasMethod(TARGET_METHOD, TARGET_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode method = uniqueMethod(owner, TARGET_METHOD, TARGET_DESCRIPTOR);
        TypeInsnNode decodeStart = uniqueNew(method, BUFFERED_INPUT);
        if (method == null
                || decodeStart == null
                || calls(method, DDS, DDS_METHOD) != 1
                || calls(method, IMAGE_IO, IMAGE_READ) != 1
                || calls(method, BUILDER, ANALYZE) != 1
                || calls(method, RUNTIME, "load") != 0) {
            return null;
        }

        LabelNode fallback = new LabelNode();
        InsnList shortcut = new InsnList();
        shortcut.add(new VarInsnNode(Opcodes.ALOAD, 0));
        shortcut.add(new VarInsnNode(Opcodes.ALOAD, 1));
        shortcut.add(new VarInsnNode(Opcodes.ALOAD, 2));
        shortcut.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "load", RUNTIME_DESCRIPTOR, false));
        shortcut.add(new InsnNode(Opcodes.DUP));
        shortcut.add(new JumpInsnNode(Opcodes.IFNULL, fallback));
        shortcut.add(new TypeInsnNode(Opcodes.CHECKCAST, DATA));
        shortcut.add(new InsnNode(Opcodes.ARETURN));
        shortcut.add(fallback);
        shortcut.add(new InsnNode(Opcodes.POP));
        method.instructions.insertBefore(decodeStart, shortcut);

        ClassWriter writer = new SafeClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        FastRenderingPreparedTextureRuntime.installed();
        return writer.toByteArray();
    }

    private static MethodNode uniqueMethod(ClassNode owner, String name, String descriptor) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (found != null) return null;
                found = method;
            }
        }
        return found;
    }

    private static TypeInsnNode uniqueNew(MethodNode method, String type) {
        if (method == null) return null;
        TypeInsnNode found = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode candidate
                    && candidate.getOpcode() == Opcodes.NEW && type.equals(candidate.desc)) {
                if (found != null) return null;
                found = candidate;
            }
        }
        return found;
    }

    private static int calls(MethodNode method, String owner, String name) {
        if (method == null) return 0;
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) {
                count++;
            }
        }
        return count;
    }
}
