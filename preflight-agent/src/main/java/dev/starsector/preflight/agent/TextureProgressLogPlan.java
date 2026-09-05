package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Removes only reviewed Windows upload progress formatting and successful cleanup messages. */
final class TextureProgressLogPlan {
    static final String LOADED = "Loaded %.2f MB of texture data so far";
    static final String CLEANED = "Cleaned buffer for texture %s (using reflection)";
    private static final String LOGGER = "org/apache/log4j/Logger";

    private TextureProgressLogPlan() { }

    static boolean apply(ClassSignature signature, ClassNode owner) {
        if (!TexturePreparedResourceLoaderPlan.WINDOWS_SHA256.equals(signature.sha256())
                || !TexturePreparedPixelPlan.TARGET_CLASS.equals(owner.name)) return false;
        List<Block> blocks = new ArrayList<>();
        int loaded = 0, cleaned = 0;
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof LdcInsnNode text)) continue;
                boolean cleanup = CLEANED.equals(text.cst);
                if (!cleanup && !LOADED.equals(text.cst)) continue;
                Block block = block(method, text, cleanup);
                if (block == null) return false;
                blocks.add(block);
                if (cleanup) cleaned++; else loaded++;
            }
        }
        if (loaded != 2 || cleaned != 1) return false;
        for (Block block : blocks) {
            // Keep labels, frames and exception boundaries in their original locations.
            for (AbstractInsnNode instruction : block.instructions()) {
                block.method().instructions.remove(instruction);
            }
        }
        return true;
    }

    private static Block block(MethodNode method, LdcInsnNode text, boolean cleanup) {
        AbstractInsnNode first = previous(text);
        if (!cleanup) first = previous(first);
        int[] expected = cleanup
                ? new int[] {Opcodes.GETSTATIC, Opcodes.LDC, Opcodes.ICONST_1, Opcodes.ANEWARRAY,
                    Opcodes.DUP, Opcodes.ICONST_0, Opcodes.ALOAD, Opcodes.AASTORE,
                    Opcodes.INVOKESTATIC, Opcodes.INVOKEVIRTUAL}
                : new int[] {Opcodes.ALOAD, Opcodes.GETFIELD, Opcodes.LDC, Opcodes.ICONST_1,
                    Opcodes.ANEWARRAY, Opcodes.DUP, Opcodes.ICONST_0, Opcodes.GETSTATIC,
                    Opcodes.L2F, Opcodes.LDC, Opcodes.FDIV, Opcodes.INVOKESTATIC,
                    Opcodes.AASTORE, Opcodes.INVOKESTATIC, Opcodes.INVOKEVIRTUAL};
        List<AbstractInsnNode> sequence = new ArrayList<>();
        AbstractInsnNode cursor = first;
        for (int opcode : expected) {
            if (cursor == null || cursor.getOpcode() != opcode) return null;
            sequence.add(cursor);
            cursor = next(cursor);
        }
        FieldInsnNode logger = (FieldInsnNode) sequence.get(cleanup ? 0 : 1);
        if (!logger.owner.equals(TexturePreparedPixelPlan.TARGET_CLASS)
                || !logger.desc.equals("L" + LOGGER + ";")) return null;
        TypeInsnNode array = (TypeInsnNode) sequence.get(cleanup ? 3 : 4);
        if (!array.desc.equals("java/lang/Object")) return null;
        if (cleanup) {
            if (((VarInsnNode) sequence.get(6)).var != 1) return null;
        } else {
            if (((VarInsnNode) sequence.get(0)).var != 0) return null;
            FieldInsnNode count = (FieldInsnNode) sequence.get(7);
            if (!count.owner.equals(TexturePreparedPixelPlan.TARGET_CLASS) || !count.desc.equals("J")
                    || !Float.valueOf(1048576F).equals(((LdcInsnNode) sequence.get(9)).cst)
                    || !call(sequence.get(11), "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;")) return null;
        }
        if (!call(sequence.get(sequence.size() - 2), "java/lang/String", "format",
                "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;")
                || !call(sequence.get(sequence.size() - 1), LOGGER, cleanup ? "info" : "debug",
                        "(Ljava/lang/Object;)V")) return null;
        return new Block(method, sequence);
    }

    private static boolean call(AbstractInsnNode instruction, String owner, String name, String descriptor) {
        return instruction instanceof MethodInsnNode call && call.owner.equals(owner)
                && call.name.equals(name) && call.desc.equals(descriptor);
    }

    private static AbstractInsnNode previous(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        do { instruction = instruction.getPrevious(); }
        while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }
    private static AbstractInsnNode next(AbstractInsnNode instruction) {
        do { instruction = instruction.getNext(); }
        while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }
    private record Block(MethodNode method, List<AbstractInsnNode> instructions) { }
}
