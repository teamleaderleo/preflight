package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/** Attributes the campaign-rules loader without changing its control flow. */
final class RulesLoaderPhasePlan {
    static final String TARGET_CLASS = "com/fs/starfarer/campaign/rules/Rules";
    static final String LOAD_METHOD = "super";
    static final String LOAD_DESCRIPTOR = "(Lcom/fs/starfarer/loading/ResourceLoaderState;)V";
    private static final String RUNTIME = "dev/starsector/preflight/agent/StartupPhaseRuntime";

    private RulesLoaderPhasePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !signature.hasMethod(LOAD_METHOD, LOAD_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode load = uniqueMethod(owner, LOAD_METHOD, LOAD_DESCRIPTOR);
        if (load == null || hasRuntimeCalls(load)) {
            return null;
        }

        List<MethodInsnNode> csv = calls(load, Opcodes.INVOKESTATIC,
                "com/fs/starfarer/loading/LoadingUtils", "super",
                "(Ljava/util/List;Ljava/lang/String;ZZ)Lorg/json/JSONArray;");
        List<MethodInsnNode> ruleObjects = calls(load, Opcodes.INVOKESPECIAL,
                "com/fs/starfarer/campaign/rules/ooOO", "<init>",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        List<MethodInsnNode> expressions = calls(load, Opcodes.INVOKESPECIAL,
                "com/fs/starfarer/campaign/rules/super", "<init>",
                "(Ljava/lang/String;)V");
        List<MethodInsnNode> options = calls(load, Opcodes.INVOKESPECIAL,
                "com/fs/starfarer/api/campaign/rules/Option", "<init>", "()V");
        List<MethodInsnNode> scripts = calls(load, Opcodes.INVOKESTATIC,
                "com/fs/starfarer/loading/scripts/ScriptStore", "Object", "(Ljava/lang/String;)V");
        List<MethodInsnNode> triggerMap = calls(load, Opcodes.INVOKESTATIC,
                TARGET_CLASS, "super", "(Ljava/lang/String;)Ljava/util/List;");
        List<MethodInsnNode> replacements = calls(load, Opcodes.INVOKEVIRTUAL,
                "java/lang/String", "replaceAll",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
        List<MethodInsnNode> splits = calls(load, Opcodes.INVOKEVIRTUAL,
                "java/lang/String", "split", "(Ljava/lang/String;)[Ljava/lang/String;");
        if (csv.size() != 1 || ruleObjects.size() != 1 || expressions.size() != 2
                || options.size() != 1 || scripts.size() != 1 || triggerMap.size() != 2
                || replacements.size() != 5 || splits.size() != 5
                || ruleObjects.stream().anyMatch(call -> allocationFor(call) == null)
                || expressions.stream().anyMatch(call -> allocationFor(call) == null)
                || options.stream().anyMatch(call -> allocationFor(call) == null)) {
            return null;
        }

        csv.forEach(call -> wrapCall(load, call, "rules-csv-merge-parse"));
        expressions.forEach(call -> wrapConstructor(load, call, "rules-expression-parse"));
        options.forEach(call -> wrapConstructor(load, call, "rules-option-construction"));
        scripts.forEach(call -> wrapCall(load, call, "rules-script-registration"));
        replacements.forEach(call -> wrapCall(load, call, "rules-string-regex"));
        splits.forEach(call -> wrapCall(load, call, "rules-string-regex"));
        load.instructions.insertBefore(triggerMap.get(0), start("rules-duplicate-scan"));
        load.instructions.insertBefore(triggerMap.get(1), end());
        wrapCall(load, triggerMap.get(1), "rules-trigger-registration");

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static void wrapCall(MethodNode method, MethodInsnNode call, String label) {
        method.instructions.insertBefore(call, start(label));
        method.instructions.insert(call, new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "specSubphaseEnd", "()V", false));
    }

    private static void wrapConstructor(MethodNode method, MethodInsnNode call, String label) {
        TypeInsnNode allocation = allocationFor(call);
        method.instructions.insertBefore(allocation, start(label));
        method.instructions.insert(call, new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "specSubphaseEnd", "()V", false));
    }

    private static TypeInsnNode allocationFor(MethodInsnNode call) {
        for (AbstractInsnNode instruction = call.getPrevious(); instruction != null;
                instruction = instruction.getPrevious()) {
            if (instruction instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.NEW && call.owner.equals(type.desc)) {
                return type;
            }
        }
        return null;
    }

    private static InsnList start(String label) {
        InsnList instructions = new InsnList();
        instructions.add(new LdcInsnNode(label));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "specSubphaseStart", "(Ljava/lang/String;)V", false));
        return instructions;
    }

    private static MethodInsnNode end() {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "specSubphaseEnd", "()V", false);
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
