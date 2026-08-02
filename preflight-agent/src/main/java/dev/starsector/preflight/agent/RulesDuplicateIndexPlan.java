package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Replaces vanilla's per-trigger linear duplicate scan with an equivalent temporary set. */
final class RulesDuplicateIndexPlan {
    private static final String TARGET = RulesLoaderPhasePlan.TARGET_CLASS;
    private static final String RUNTIME = "dev/starsector/preflight/agent/RulesDuplicateIndexRuntime";
    private static final String RULE = "com/fs/starfarer/campaign/rules/ooOO";

    private RulesDuplicateIndexPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET.equals(signature.internalName())
                || !signature.hasMethod(RulesLoaderPhasePlan.LOAD_METHOD, RulesLoaderPhasePlan.LOAD_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode load = uniqueMethod(
                owner, RulesLoaderPhasePlan.LOAD_METHOD, RulesLoaderPhasePlan.LOAD_DESCRIPTOR);
        if (load == null || hasRuntimeCalls(load)) {
            return null;
        }

        List<MethodInsnNode> triggerLists = calls(load, Opcodes.INVOKESTATIC,
                TARGET, "super", "(Ljava/lang/String;)Ljava/util/List;");
        if (triggerLists.size() != 2) {
            return null;
        }
        MethodInsnNode scanLookup = triggerLists.get(0);
        MethodInsnNode registrationLookup = triggerLists.get(1);
        AbstractInsnNode scanStart = previousLoad(scanLookup, 8);
        AbstractInsnNode registrationStart = previousLoad(registrationLookup, 8);
        if (!(scanStart instanceof VarInsnNode scanTrigger)
                || scanTrigger.getOpcode() != Opcodes.ALOAD || scanTrigger.var != 8
                || !(registrationStart instanceof VarInsnNode registrationTrigger)
                || registrationTrigger.getOpcode() != Opcodes.ALOAD || registrationTrigger.var != 8
                || countBetween(scanStart, registrationStart,
                        Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;") != 1
                || countBetween(scanStart, registrationStart,
                        Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z") != 1
                || countBetween(scanStart, registrationStart,
                        Opcodes.INVOKEVIRTUAL, RULE, "getId", "()Ljava/lang/String;") != 3
                || countBetween(scanStart, registrationStart,
                        Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z") != 1
                || countBetween(scanStart, registrationStart,
                        Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z") != 0) {
            return null;
        }

        InsnList replacement = new InsnList();
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 8));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 7));
        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "check", "(Ljava/lang/String;Ljava/lang/String;)V", false));
        load.instructions.insertBefore(scanStart, replacement);
        removeRange(load, scanStart, registrationStart);
        load.instructions.insertBefore(load.instructions.getFirst(), new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "begin", "()V", false));
        AbstractInsnNode onlyReturn = uniqueReturn(load);
        if (onlyReturn == null) {
            return null;
        }
        load.instructions.insertBefore(onlyReturn, new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "complete", "()V", false));

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static void removeRange(MethodNode method, AbstractInsnNode first, AbstractInsnNode exclusiveEnd) {
        AbstractInsnNode current = first;
        while (current != exclusiveEnd) {
            AbstractInsnNode next = current.getNext();
            method.instructions.remove(current);
            current = next;
        }
    }

    private static int countBetween(
            AbstractInsnNode first,
            AbstractInsnNode exclusiveEnd,
            int opcode,
            String owner,
            String name,
            String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction = first; instruction != exclusiveEnd;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call && call.getOpcode() == opcode
                    && owner.equals(call.owner) && name.equals(call.name) && descriptor.equals(call.desc)) {
                count++;
            }
        }
        return count;
    }

    private static AbstractInsnNode previousLoad(AbstractInsnNode instruction, int local) {
        AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
        while (current != null) {
            if (current instanceof VarInsnNode variable
                    && variable.getOpcode() == Opcodes.ALOAD && variable.var == local) {
                return variable;
            }
            current = current.getPrevious();
        }
        return null;
    }

    private static List<MethodInsnNode> calls(
            MethodNode method, int opcode, String owner, String name, String descriptor) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call && call.getOpcode() == opcode
                    && owner.equals(call.owner) && name.equals(call.name) && descriptor.equals(call.desc)) {
                result.add(call);
            }
        }
        return result;
    }

    private static AbstractInsnNode uniqueReturn(MethodNode method) {
        AbstractInsnNode found = null;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                if (found != null) {
                    return null;
                }
                found = instruction;
            }
        }
        return found;
    }

    private static boolean hasRuntimeCalls(MethodNode method) {
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) {
                return true;
            }
        }
        return false;
    }

    private static MethodNode uniqueMethod(ClassNode owner, String name, String descriptor) {
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
