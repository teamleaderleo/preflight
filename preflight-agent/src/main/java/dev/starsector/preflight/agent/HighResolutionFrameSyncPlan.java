package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Replaces only BaseGameState's reviewed millisecond-truncating final frame wait. */
final class HighResolutionFrameSyncPlan {
    static final String TARGET_CLASS = "com/fs/starfarer/BaseGameState";
    static final String TRAVERSE_METHOD = "traverse";
    static final String TRAVERSE_DESCRIPTOR = "()Ljava/lang/String;";

    private static final String THREAD = "java/lang/Thread";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/HighResolutionFrameSyncRuntime";

    private HighResolutionFrameSyncPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!HighResolutionFrameSyncRuntime.enabled()
                || !TARGET_CLASS.equals(signature.internalName())
                || signature.majorVersion() != 61
                || !signature.hasMethod(TRAVERSE_METHOD, TRAVERSE_DESCRIPTOR)) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode traverse = unique(owner, TRAVERSE_METHOD, TRAVERSE_DESCRIPTOR);
        if (traverse == null || calls(traverse, RUNTIME, "sleepSeconds") != 0) {
            return null;
        }

        SleepBlock block = uniqueSleepBlock(traverse);
        if (block == null) {
            return null;
        }

        InsnList replacement = new InsnList();
        replacement.add(new VarInsnNode(Opcodes.FLOAD, block.secondsLocal));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "sleepSeconds", "(F)V", false));
        traverse.instructions.insertBefore(block.start, replacement);
        removeInclusive(traverse, block.start, block.end);

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        HighResolutionFrameSyncRuntime.installed();
        return writer.toByteArray();
    }

    /**
     * Matches exactly:
     * FLOAD seconds; LDC 1000f; FMUL; F2I; ISTORE millis; ILOAD millis; I2L;
     * Thread.sleep(J)V.
     */
    private static SleepBlock uniqueSleepBlock(MethodNode method) {
        SleepBlock found = null;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof VarInsnNode seconds)
                    || seconds.getOpcode() != Opcodes.FLOAD) {
                continue;
            }
            AbstractInsnNode cursor = nextCode(instruction);
            if (!(cursor instanceof LdcInsnNode scale)
                    || !(scale.cst instanceof Float value)
                    || Float.floatToIntBits(value) != Float.floatToIntBits(1000f)) {
                continue;
            }
            cursor = nextCode(cursor);
            if (cursor == null || cursor.getOpcode() != Opcodes.FMUL) continue;
            cursor = nextCode(cursor);
            if (cursor == null || cursor.getOpcode() != Opcodes.F2I) continue;
            cursor = nextCode(cursor);
            if (!(cursor instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ISTORE) continue;
            cursor = nextCode(cursor);
            if (!(cursor instanceof VarInsnNode load)
                    || load.getOpcode() != Opcodes.ILOAD || load.var != store.var) continue;
            cursor = nextCode(cursor);
            if (cursor == null || cursor.getOpcode() != Opcodes.I2L) continue;
            cursor = nextCode(cursor);
            if (!(cursor instanceof MethodInsnNode sleep)
                    || sleep.getOpcode() != Opcodes.INVOKESTATIC
                    || !THREAD.equals(sleep.owner)
                    || !"sleep".equals(sleep.name)
                    || !"(J)V".equals(sleep.desc)) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = new SleepBlock(instruction, sleep, seconds.var);
        }
        return found;
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction.getNext();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getNext();
        }
        return cursor;
    }

    private static void removeInclusive(MethodNode method, AbstractInsnNode first, AbstractInsnNode last) {
        AbstractInsnNode cursor = first;
        while (cursor != null) {
            AbstractInsnNode next = cursor.getNext();
            method.instructions.remove(cursor);
            if (cursor == last) {
                return;
            }
            cursor = next;
        }
    }

    private static MethodNode unique(ClassNode owner, String name, String descriptor) {
        MethodNode result = null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (result != null) return null;
                result = method;
            }
        }
        return result;
    }

    private static int calls(MethodNode method, String owner, String name) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) {
                result++;
            }
        }
        return result;
    }

    private record SleepBlock(AbstractInsnNode start, AbstractInsnNode end, int secondsLocal) {
    }
}
