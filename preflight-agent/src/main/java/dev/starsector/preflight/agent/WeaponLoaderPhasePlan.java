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

/** Attributes the dominant weapon-definition loader without changing its control flow. */
final class WeaponLoaderPhasePlan {
    static final String TARGET_CLASS = "com/fs/starfarer/loading/WeaponSpecLoader";
    static final String LOAD_ALL_METHOD = "new";
    static final String LINUX_LOAD_ALL_METHOD = "Ò00000";
    static final String LOAD_ALL_DESCRIPTOR = "()V";
    static final String LOAD_ONE_METHOD = "new";
    static final String LINUX_LOAD_ONE_METHOD = "Ò00000";
    static final String LOAD_ONE_DESCRIPTOR = "(Ljava/lang/String;)V";
    private static final String RUNTIME = "dev/starsector/preflight/agent/StartupPhaseRuntime";
    private static final String LOADING_UTILS = "com/fs/starfarer/loading/LoadingUtils";
    private static final String SCRIPT_STORE = "com/fs/starfarer/loading/scripts/ScriptStore";
    private static final String REGISTRY = "com/fs/starfarer/loading/interface";
    private static final String LINUX_REGISTRY = "com/fs/starfarer/loading/o00O";
    private static final String WINDOWS_REGISTRY = "com/fs/starfarer/loading/Q";

    private WeaponLoaderPhasePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        if (!apply(signature, owner)) return null;
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    static boolean apply(ClassSignature signature, ClassNode owner) {
        String loadAllName = loadAllMethod(signature);
        String loadOneName = loadOneMethod(signature);
        if (!TARGET_CLASS.equals(signature.internalName())
                || loadAllName == null || loadOneName == null) {
            return false;
        }
        MethodNode loadAll = uniqueMethod(owner, loadAllName, LOAD_ALL_DESCRIPTOR);
        MethodNode loadOne = uniqueMethod(owner, loadOneName, LOAD_ONE_DESCRIPTOR);
        if (loadAll == null || loadOne == null || hasRuntimeCalls(loadAll) || hasRuntimeCalls(loadOne)) {
            return false;
        }

        List<MethodInsnNode> listings = calls(loadAll, LOADING_UTILS, "super",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/util/List;");
        List<MethodInsnNode> itemLoads = calls(loadAll, TARGET_CLASS, loadOneName, LOAD_ONE_DESCRIPTOR);
        List<MethodInsnNode> json = calls(loadOne, LOADING_UTILS, "Ó00000",
                "(Ljava/lang/String;)Lorg/json/JSONObject;");
        List<MethodInsnNode> scripts = platformCalls(
                loadOne,
                "(Ljava/lang/String;)V",
                new CallFamily(SCRIPT_STORE, "Object"),
                new CallFamily(SCRIPT_STORE, "Ó00000"));
        List<MethodInsnNode> registrations = platformCalls(
                loadOne,
                "(Ljava/lang/String;Lcom/fs/starfarer/loading/specs/BaseWeaponSpec;)V",
                new CallFamily(REGISTRY, "o00000"),
                new CallFamily(LINUX_REGISTRY, "super"),
                new CallFamily(WINDOWS_REGISTRY, "super"));
        if (listings.size() != 2 || itemLoads.size() != 2 || json.size() != 1
                || scripts.size() != 3 || registrations.size() != 2) {
            return false;
        }

        listings.forEach(call -> wrapCall(loadAll, call, "weapon-file-listing"));
        json.forEach(call -> wrapCall(loadOne, call, "weapon-json-merge-parse"));
        scripts.forEach(call -> wrapCall(loadOne, call, "weapon-script-registration"));
        registrations.forEach(call -> wrapCall(loadOne, call, "weapon-registry-insert"));

        return true;
    }

    static String loadAllMethod(ClassSignature signature) {
        return method(signature, LOAD_ALL_METHOD, LINUX_LOAD_ALL_METHOD, LOAD_ALL_DESCRIPTOR);
    }

    static String loadOneMethod(ClassSignature signature) {
        return method(signature, LOAD_ONE_METHOD, LINUX_LOAD_ONE_METHOD, LOAD_ONE_DESCRIPTOR);
    }

    private static String method(
            ClassSignature signature, String mac, String linux, String descriptor) {
        if (signature.hasMethod(mac, descriptor)) return mac;
        if (signature.hasMethod(linux, descriptor)) return linux;
        return null;
    }

    private static void wrapCall(MethodNode method, MethodInsnNode call, String label) {
        method.instructions.insertBefore(call, start(label));
        method.instructions.insert(call, new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "specSubphaseEnd", "()V", false));
    }

    private static InsnList start(String label) {
        InsnList instructions = new InsnList();
        instructions.add(new LdcInsnNode(label));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "specSubphaseStart", "(Ljava/lang/String;)V", false));
        return instructions;
    }

    private static List<MethodInsnNode> calls(
            MethodNode method, String owner, String name, String descriptor) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call && owner.equals(call.owner)
                    && name.equals(call.name) && descriptor.equals(call.desc)) {
                result.add(call);
            }
        }
        return result;
    }

    private static List<MethodInsnNode> platformCalls(
            MethodNode method, String descriptor, CallFamily... families) {
        List<MethodInsnNode> found = List.of();
        for (CallFamily family : families) {
            List<MethodInsnNode> candidates = calls(
                    method, family.owner(), family.name(), descriptor);
            if (!candidates.isEmpty()) {
                if (!found.isEmpty()) {
                    return List.of();
                }
                found = candidates;
            }
        }
        return found;
    }

    private record CallFamily(String owner, String name) {
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
