package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Replaces MagicLib's catalog construction with a profile-bound fresh-object replay. */
final class MagicLibPaintjobCachePlan {
    static final String TARGET_CLASS = "org/magiclib/paintjobs/MagicPaintjobManager";
    static final String ORIGINAL_SHA256 =
            "841f945d675920e0fad9ccf13c7fa3144b6437489b6ba11e121a3267e6b8993c";
    static final String LOAD_METHOD = "loadPaintjobs";
    static final String LOAD_DESCRIPTOR = "()Lkotlin/Pair;";

    private static final String RUNTIME =
            "dev/starsector/preflight/agent/MagicLibPaintjobCacheRuntime";

    private MagicLibPaintjobCachePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] currentBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(LOAD_METHOD, LOAD_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(currentBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        if (unique(owner, MagicLibPaintjobCacheRuntime.ORIGINAL_METHOD, LOAD_DESCRIPTOR) != null) {
            return null;
        }
        MethodNode original = unique(owner, LOAD_METHOD, LOAD_DESCRIPTOR);
        if (!reviewedBody(original)) {
            return null;
        }
        int access = original.access;
        original.name = MagicLibPaintjobCacheRuntime.ORIGINAL_METHOD;
        original.access |= Opcodes.ACC_SYNTHETIC;
        owner.methods.add(wrapper(access, original));

        ClassWriter writer = new SafeClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        MagicLibPaintjobCacheRuntime.installed();
        return writer.toByteArray();
    }

    private static MethodNode wrapper(int access, MethodNode original) {
        MethodNode method = new MethodNode(
                Opcodes.ASM9,
                access,
                LOAD_METHOD,
                LOAD_DESCRIPTOR,
                original.signature,
                original.exceptions == null ? null : original.exceptions.toArray(String[]::new));
        LabelNode load = new LabelNode();
        method.instructions.add(new LdcInsnNode(Type.getObjectType(TARGET_CLASS)));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                RUNTIME,
                "replay",
                "(Ljava/lang/Class;)Ljava/lang/Object;",
                false));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNULL, load));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "kotlin/Pair"));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.instructions.add(load);
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                TARGET_CLASS,
                MagicLibPaintjobCacheRuntime.ORIGINAL_METHOD,
                LOAD_DESCRIPTOR,
                false));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new LdcInsnNode(Type.getObjectType(TARGET_CLASS)));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                RUNTIME,
                "capture",
                "(Ljava/lang/Object;Ljava/lang/Class;)V",
                false));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        return method;
    }

    /** Pins the catalog loop after the disjoint optional-file shortcut has been composed. */
    private static boolean reviewedBody(MethodNode method) {
        if (method == null || (method.access & Opcodes.ACC_STATIC) == 0) {
            return false;
        }
        int returns = 0;
        int enabledMods = 0;
        int weaponLoads = 0;
        int pairs = 0;
        int mapPuts = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == Opcodes.ARETURN) {
                returns++;
            }
            if (instruction instanceof MethodInsnNode call) {
                if ("com/fs/starfarer/api/ModManagerAPI".equals(call.owner)
                        && "getEnabledModsCopy".equals(call.name)
                        && "()Ljava/util/List;".equals(call.desc)) {
                    enabledMods++;
                } else if (TARGET_CLASS.equals(call.owner)
                        && "loadWeaponPaintjobs".equals(call.name)) {
                    weaponLoads++;
                } else if ("kotlin/Pair".equals(call.owner)
                        && "<init>".equals(call.name)
                        && "(Ljava/lang/Object;Ljava/lang/Object;)V".equals(call.desc)) {
                    pairs++;
                } else if ("java/util/Map".equals(call.owner)
                        && "put".equals(call.name)
                        && "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;".equals(call.desc)) {
                    mapPuts++;
                }
            }
        }
        return returns == 1 && enabledMods == 1 && weaponLoads == 1 && pairs == 1 && mapPuts >= 2;
    }

    private static MethodNode unique(ClassNode owner, String name, String descriptor) {
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
