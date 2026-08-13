package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Invalidates entity indexes when the reviewed base implementation changes an id. */
final class EntityIdMutationPlan {
    static final String TARGET_CLASS = "com/fs/starfarer/campaign/BaseCampaignEntity";
    static final String ORIGINAL_SHA256 =
            "7e4683903f4ad35219912dfd7aecb0db1e302957bb7d896e09620b1a8135b18b";
    static final String SET_ID_METHOD = "setId";
    static final String SET_ID_DESCRIPTOR = "(Ljava/lang/String;)V";

    private static final String RUNTIME = "dev/starsector/preflight/agent/EntityLookupRuntime";

    private EntityIdMutationPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(SET_ID_METHOD, SET_ID_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode method = uniqueMethod(owner, SET_ID_METHOD, SET_ID_DESCRIPTOR);
        if (!eligible(method)) {
            return null;
        }
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "entityIdChanging",
                "(Ljava/lang/Object;)V", false));
        method.instructions.insert(hook);

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        EntityLookupRuntime.idMutationInstalled();
        return writer.toByteArray();
    }

    private static boolean eligible(MethodNode method) {
        return method != null && (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT
                | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) == 0;
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
