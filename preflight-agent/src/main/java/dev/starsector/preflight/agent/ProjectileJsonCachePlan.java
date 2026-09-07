package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Uses prepared merged JSON while retaining the original LoadingUtils call as the miss path. */
final class ProjectileJsonCachePlan {
    private static final String TARGET = WeaponLoaderPhasePlan.TARGET_CLASS;
    private static final String RUNTIME = "dev/starsector/preflight/agent/ProjectileJsonCacheRuntime";
    private static final String LOADING_UTILS = "com/fs/starfarer/loading/LoadingUtils";
    private static final String HELPER = "preflight$projectileJson";
    private static final String JSON_DESCRIPTOR = "(Ljava/lang/String;)Lorg/json/JSONObject;";

    private ProjectileJsonCachePlan() {
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
        if (!TARGET.equals(signature.internalName())
                || !signature.hasMethod(
                        ProjectileLoaderPhasePlan.LOAD_ALL_METHOD,
                        ProjectileLoaderPhasePlan.LOAD_ALL_DESCRIPTOR)
                || !signature.hasMethod(
                        ProjectileLoaderPhasePlan.LOAD_ONE_METHOD,
                        ProjectileLoaderPhasePlan.LOAD_ONE_DESCRIPTOR)) {
            return false;
        }
        MethodNode loadAll = uniqueMethod(
                owner, ProjectileLoaderPhasePlan.LOAD_ALL_METHOD, ProjectileLoaderPhasePlan.LOAD_ALL_DESCRIPTOR);
        MethodNode loadOne = uniqueMethod(
                owner, ProjectileLoaderPhasePlan.LOAD_ONE_METHOD, ProjectileLoaderPhasePlan.LOAD_ONE_DESCRIPTOR);
        MethodInsnNode originalCall = uniqueJsonCall(loadOne);
        AbstractInsnNode loadAllReturn = uniqueReturn(loadAll);
        if (loadAll == null || loadOne == null || originalCall == null || loadAllReturn == null
                || hasRuntimeCalls(loadAll) || hasRuntimeCalls(loadOne)
                || PreparedJsonCallPlan.hasHelper(owner, HELPER)) {
            return false;
        }

        PreparedJsonCallPlan.replace(owner, originalCall, RUNTIME, HELPER);
        loadAll.instructions.insertBefore(loadAllReturn, new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "complete", "()V", false));
        return true;
    }

    private static MethodInsnNode uniqueJsonCall(MethodNode method) {
        if (method == null) {
            return null;
        }
        MethodInsnNode found = null;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && LOADING_UTILS.equals(call.owner)
                    && "Ó00000".equals(call.name)
                    && JSON_DESCRIPTOR.equals(call.desc)) {
                if (found != null) {
                    return null;
                }
                found = call;
            }
        }
        return found;
    }

    private static AbstractInsnNode uniqueReturn(MethodNode method) {
        if (method == null) {
            return null;
        }
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
