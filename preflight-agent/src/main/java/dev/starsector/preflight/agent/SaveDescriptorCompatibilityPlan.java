package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
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
import org.objectweb.asm.tree.VarInsnNode;

/** Memoises only the exact boolean produced by vanilla's full save-descriptor parse. */
final class SaveDescriptorCompatibilityPlan {
    static final String TARGET_CLASS = "com/fs/starfarer/campaign/save/CampaignGameManager";
    static final String ORIGINAL_SHA256 =
            "f7be5d08dee2f3db4ad7e996a90b508d2cde6a4d3f214ed48f17cb46146c6532";
    static final String METHOD = "Ö00000";
    static final String DESCRIPTOR = "()Z";
    private static final String FILE = "java/io/File";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/SaveDescriptorCompatibilityRuntime";

    private SaveDescriptorCompatibilityPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(METHOD, DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode method = unique(owner, METHOD, DESCRIPTOR);
        if (method == null || calls(method, RUNTIME) != 0) return null;

        VarInsnNode directoryStore = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof VarInsnNode store
                    && store.getOpcode() == Opcodes.ASTORE && store.var == 1
                    && previousCode(store) instanceof MethodInsnNode call
                    && FILE.equals(call.owner) && "<init>".equals(call.name)) {
                if (directoryStore != null) return null;
                directoryStore = store;
            }
        }
        List<AbstractInsnNode> returns = new ArrayList<>();
        AbstractInsnNode parsedReturn = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() != Opcodes.IRETURN) continue;
            returns.add(instruction);
            if (previousCode(instruction) instanceof MethodInsnNode call
                    && "java/lang/String".equals(call.owner)
                    && "equals".equals(call.name)
                    && "(Ljava/lang/Object;)Z".equals(call.desc)) {
                parsedReturn = instruction;
            }
        }
        if (directoryStore == null || returns.size() != 4 || parsedReturn == null) return null;
        AbstractInsnNode exhaustedReturn = returns.get(returns.size() - 1);

        LabelNode miss = new LabelNode();
        InsnList lookup = new InsnList();
        lookup.add(new VarInsnNode(Opcodes.ALOAD, 1));
        lookup.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "lookup",
                "(Ljava/io/File;)Ljava/lang/Boolean;", false));
        lookup.add(new InsnNode(Opcodes.DUP));
        lookup.add(new JumpInsnNode(Opcodes.IFNULL, miss));
        lookup.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue",
                "()Z", false));
        lookup.add(new InsnNode(Opcodes.IRETURN));
        lookup.add(miss);
        lookup.add(new InsnNode(Opcodes.POP));
        method.instructions.insert(directoryStore, lookup);

        recordBefore(method, parsedReturn);
        recordBefore(method, exhaustedReturn);

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        SaveDescriptorCompatibilityRuntime.installed();
        return writer.toByteArray();
    }

    private static void recordBefore(MethodNode method, AbstractInsnNode returnInstruction) {
        InsnList record = new InsnList();
        record.add(new VarInsnNode(Opcodes.ALOAD, 1));
        record.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "record",
                "(ZLjava/io/File;)Z", false));
        method.instructions.insertBefore(returnInstruction, record);
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

    private static int calls(MethodNode method, String owner) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && owner.equals(call.owner)) count++;
        }
        return count;
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }
}
