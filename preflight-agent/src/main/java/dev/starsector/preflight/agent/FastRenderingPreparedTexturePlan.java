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
    static final String PORT_CLASS = "com/genir/renderer/overrides/loading/textures/TextureLoader";
    static final String PORT_SHA256 =
            "dee92a93ce9eda6d3facb84e044b28ac09addc55f9fed81e7ce2f6a90a7cb3e0";
    static final String PORT_DATA = "com/genir/renderer/overrides/loading/textures/TextureData";
    static final String PORT_DESCRIPTOR = "(Ljava/lang/String;Ljava/lang/String;)L" + PORT_DATA + ";";

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
        return transform(signature, originalBytes, false);
    }

    static byte[] transformPort(ClassSignature signature, byte[] originalBytes) {
        return transform(signature, originalBytes, true);
    }

    private static byte[] transform(ClassSignature signature, byte[] originalBytes, boolean port) {
        String descriptor = port ? PORT_DESCRIPTOR : TARGET_DESCRIPTOR;
        String data = port ? PORT_DATA : DATA;
        String runtimeMethod = port ? "loadPort" : "load";
        if (!(port ? PORT_CLASS : TARGET_CLASS).equals(signature.internalName())
                || !signature.hasMethod(TARGET_METHOD, descriptor)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode method = uniqueMethod(owner, TARGET_METHOD, descriptor);
        TypeInsnNode decodeStart = uniqueNew(method, BUFFERED_INPUT);
        if (method == null
                || decodeStart == null
                || calls(method, port ? "com/genir/renderer/overrides/loading/textures/DDSIntegration" : DDS, DDS_METHOD) != 1
                || calls(method, IMAGE_IO, IMAGE_READ) != 1
                || calls(method, port ? "com/genir/renderer/overrides/loading/textures/TextureBuilder" : BUILDER, ANALYZE) != 1
                || calls(method, RUNTIME, runtimeMethod) != 0
                || (port && !hasPortPolicyLocal(method))) {
            return null;
        }

        LabelNode fallback = new LabelNode();
        LabelNode decode = new LabelNode();
        InsnList shortcut = new InsnList();
        if (port) {
            // The reviewed class stores Blacklist.doNotModify in local 3. Preserve its
            // power-of-two policy by keeping the entire original decoder for those images.
            shortcut.add(new VarInsnNode(Opcodes.ILOAD, 3));
            shortcut.add(new JumpInsnNode(Opcodes.IFNE, decode));
        }
        shortcut.add(new VarInsnNode(Opcodes.ALOAD, 0));
        shortcut.add(new VarInsnNode(Opcodes.ALOAD, 1));
        shortcut.add(new VarInsnNode(Opcodes.ALOAD, 2));
        shortcut.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, runtimeMethod, RUNTIME_DESCRIPTOR, false));
        shortcut.add(new InsnNode(Opcodes.DUP));
        shortcut.add(new JumpInsnNode(Opcodes.IFNULL, fallback));
        shortcut.add(new TypeInsnNode(Opcodes.CHECKCAST, data));
        shortcut.add(new InsnNode(Opcodes.ARETURN));
        shortcut.add(fallback);
        shortcut.add(new InsnNode(Opcodes.POP));
        shortcut.add(decode);
        method.instructions.insertBefore(decodeStart, shortcut);

        ClassWriter writer = new SafeClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        FastRenderingPreparedTextureRuntime.installed();
        return writer.toByteArray();
    }

    private static boolean hasPortPolicyLocal(MethodNode method) {
        int matches = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("com/genir/renderer/overrides/loading/textures/Blacklist")
                    && call.name.equals("doNotModify")
                    && call.desc.equals("(Ljava/lang/String;)Z")) {
                AbstractInsnNode next = call.getNext();
                while (next != null && next.getOpcode() < 0) next = next.getNext();
                if (!(next instanceof VarInsnNode store)
                        || store.getOpcode() != Opcodes.ISTORE || store.var != 3) return false;
                matches++;
            }
        }
        return matches == 1;
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
