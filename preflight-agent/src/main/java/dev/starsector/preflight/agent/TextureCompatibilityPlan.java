package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Target-specific rewrite for the reviewed TextureLoader decoded-image seam. */
final class TextureCompatibilityPlan {
    static final String TARGET_CLASS = "com/fs/graphics/TextureLoader";
    static final String DECODE_METHOD = "Ô00000";
    static final String LINUX_DECODE_METHOD = "String";
    static final String DECODE_DESCRIPTOR = "(Ljava/lang/String;)Ljava/awt/image/BufferedImage;";
    private static final String PRELOADER = "com/fs/graphics/L";
    private static final String PRELOADER_METHOD = "class";
    private static final String RUNTIME = "dev/starsector/preflight/agent/TextureCompatibilityRuntime";

    private TextureCompatibilityPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || (!signature.hasMethod(DECODE_METHOD, DECODE_DESCRIPTOR)
                        && !signature.hasMethod(LINUX_DECODE_METHOD, DECODE_DESCRIPTOR))) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, 0);
        if (!TARGET_CLASS.equals(owner.name)) {
            return null;
        }

        MethodNode decode = null;
        for (MethodNode method : owner.methods) {
            if ((DECODE_METHOD.equals(method.name) || LINUX_DECODE_METHOD.equals(method.name))
                    && DECODE_DESCRIPTOR.equals(method.desc)) {
                if (decode != null) {
                    return null;
                }
                decode = method;
            }
        }
        if (decode == null
                || (decode.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                || (decode.access & Opcodes.ACC_STATIC) != 0
                || containsRuntimeCall(decode)) {
            return null;
        }

        AbstractInsnNode directDecode = directDecodeAfterPreloaderMiss(decode);
        if (directDecode == null) {
            return null;
        }
        LabelNode continueOriginal = new LabelNode();
        InsnList cacheLookup = new InsnList();
        cacheLookup.add(new VarInsnNode(Opcodes.ALOAD, 1));
        cacheLookup.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                RUNTIME,
                "load",
                DECODE_DESCRIPTOR,
                false));
        cacheLookup.add(new InsnNode(Opcodes.DUP));
        cacheLookup.add(new JumpInsnNode(Opcodes.IFNULL, continueOriginal));
        cacheLookup.add(new InsnNode(Opcodes.ARETURN));
        cacheLookup.add(continueOriginal);
        cacheLookup.add(new FrameNode(
                Opcodes.F_SAME1,
                0,
                null,
                1,
                new Object[] {"java/awt/image/BufferedImage"}));
        cacheLookup.add(new InsnNode(Opcodes.POP));
        decode.instructions.insertBefore(directDecode, cacheLookup);
        decode.maxStack = Math.max(decode.maxStack, 2);

        ClassWriter writer = new ClassWriter(0);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static boolean containsRuntimeCall(MethodNode method) {
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && RUNTIME.equals(call.owner)
                    && "load".equals(call.name)
                    && DECODE_DESCRIPTOR.equals(call.desc)) {
                return true;
            }
        }
        return false;
    }

    private static AbstractInsnNode directDecodeAfterPreloaderMiss(MethodNode method) {
        AbstractInsnNode insertion = null;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !PRELOADER.equals(call.owner)
                    || !PRELOADER_METHOD.equals(call.name)
                    || !DECODE_DESCRIPTOR.equals(call.desc)) {
                continue;
            }
            AbstractInsnNode store = nextOpcode(call);
            AbstractInsnNode loadForBranch = nextOpcode(store);
            AbstractInsnNode branch = nextOpcode(loadForBranch);
            AbstractInsnNode loadForReturn = nextOpcode(branch);
            AbstractInsnNode returnImage = nextOpcode(loadForReturn);
            if (!(store instanceof VarInsnNode stored)
                    || store.getOpcode() != Opcodes.ASTORE
                    || !(loadForBranch instanceof VarInsnNode branchLoad)
                    || loadForBranch.getOpcode() != Opcodes.ALOAD
                    || branchLoad.var != stored.var
                    || !(branch instanceof JumpInsnNode jump)
                    || branch.getOpcode() != Opcodes.IFNULL
                    || !(loadForReturn instanceof VarInsnNode returnLoad)
                    || loadForReturn.getOpcode() != Opcodes.ALOAD
                    || returnLoad.var != stored.var
                    || returnImage == null
                    || returnImage.getOpcode() != Opcodes.ARETURN) {
                return null;
            }
            AbstractInsnNode candidate = nextOpcode(jump.label);
            if (candidate == null || insertion != null) {
                return null;
            }
            insertion = candidate;
        }
        return insertion;
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        if (instruction == null) {
            return null;
        }
        AbstractInsnNode next = instruction.getNext();
        while (next instanceof LabelNode || next instanceof FrameNode || next instanceof LineNumberNode) {
            next = next.getNext();
        }
        return next;
    }
}
