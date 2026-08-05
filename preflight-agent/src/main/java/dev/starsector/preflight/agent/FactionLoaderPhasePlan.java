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

/** Attributes the largest still-unbroken SpecStore loader without changing its control flow. */
final class FactionLoaderPhasePlan {
    static final String TARGET_CLASS = SpecStorePhasePlan.TARGET_CLASS;
    static final String LOAD_METHOD = "oo0000";
    static final String LOAD_DESCRIPTOR = SpecStorePhasePlan.INIT_DESCRIPTOR;
    private static final String RUNTIME = "dev/starsector/preflight/agent/StartupPhaseRuntime";
    private static final String LOADING_UTILS = "com/fs/starfarer/loading/LoadingUtils";
    private static final String JSON_OBJECT = "org/json/JSONObject";
    private static final String RESOURCE_STATE = "com/fs/starfarer/loading/ResourceLoaderState";

    private FactionLoaderPhasePlan() {
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

        List<MethodInsnNode> json = calls(load, LOADING_UTILS, "Ó00000",
                "(Ljava/lang/String;)Lorg/json/JSONObject;");
        List<MethodInsnNode> csv = calls(load, LOADING_UTILS, "Ò00000",
                "(Ljava/lang/String;Ljava/lang/String;Z)Lorg/json/JSONArray;");
        List<MethodInsnNode> priorityTables = calls(load, TARGET_CLASS, "o00000",
                "(Lorg/json/JSONObject;Ljava/lang/String;Ljava/lang/String;Z"
                        + "Lcom/fs/starfarer/loading/SpecStore$Oo;)V");
        List<MethodInsnNode> specLookups = calls(load, TARGET_CLASS, "o00000",
                "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;");
        List<MethodInsnNode> names = calls(load, JSON_OBJECT, "getNames",
                "(Lorg/json/JSONObject;)[Ljava/lang/String;");
        List<MethodInsnNode> queues = calls(load, RESOURCE_STATE, "queueResource",
                "(Lcom/fs/starfarer/loading/ResourceLoaderState$o;Ljava/lang/String;I)V");
        if (json.size() != 5 || csv.size() != 1 || priorityTables.size() != 8
                || specLookups.size() != 3 || names.size() != 18 || queues.size() != 6) {
            return null;
        }

        json.forEach(call -> wrapCall(load, call, "faction-json-read"));
        csv.forEach(call -> wrapCall(load, call, "faction-csv-read"));
        priorityTables.forEach(call -> wrapCall(load, call, "faction-priority-table"));
        specLookups.forEach(call -> wrapCall(load, call, "faction-spec-lookup"));
        names.forEach(call -> wrapCall(load, call, "faction-json-name-snapshot"));
        queues.forEach(call -> wrapCall(load, call, "faction-resource-queue"));

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static void wrapCall(MethodNode method, MethodInsnNode call, String label) {
        method.instructions.insertBefore(call, start(label));
        method.instructions.insert(call, new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "specSubphaseEnd", "()V", false));
    }

    private static InsnList start(String label) {
        InsnList instructions = new InsnList();
        instructions.add(new LdcInsnNode(label));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "specSubphaseStart", "(Ljava/lang/String;)V", false));
        return instructions;
    }

    static List<MethodInsnNode> calls(
            MethodNode method, String owner, String name, String descriptor) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
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
