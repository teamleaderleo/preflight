package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Restricts AshLib's variant index to its exact repository-population callback. */
final class AshLibVariantLookupPlan {
    static final String REPOSITORY_CLASS =
            "ashlib/data/plugins/repositories/ShipRenderInfoRepo";
    static final String REPOSITORY_SHA256 =
            "5955d8f27dba81580e2648bbc0a7a16a9924bcd1734baf7937ab1d3417e6507f";
    static final String POPULATE = "populateRenderInfoRepo";
    static final String POPULATE_DESCRIPTOR = "()V";
    static final String LOOKUP_CLASS = "ashlib/data/plugins/misc/AshMisc";
    static final String LOOKUP_SHA256 =
            "e75a538ded25fdb81d70520f85c63174bd7e3cf60bbc50e92ef5caa6c0d7f046";
    static final String LOOKUP = "getVaraint";
    static final String LOOKUP_DESCRIPTOR =
            "(Lcom/fs/starfarer/api/combat/ShipHullSpecAPI;)Ljava/lang/String;";

    private static final String GLOBAL = "com/fs/starfarer/api/Global";
    private static final String SETTINGS = "com/fs/starfarer/api/SettingsAPI";
    private static final String HULL = "com/fs/starfarer/api/combat/ShipHullSpecAPI";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/AshLibVariantLookupRuntime";

    private AshLibVariantLookupPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (REPOSITORY_CLASS.equals(signature.internalName())) {
            return transformRepository(signature, originalBytes);
        }
        if (LOOKUP_CLASS.equals(signature.internalName())) {
            return transformLookup(signature, originalBytes);
        }
        return null;
    }

    private static byte[] transformRepository(ClassSignature signature, byte[] originalBytes) {
        if (!REPOSITORY_SHA256.equals(signature.sha256())
                || !signature.hasMethod(POPULATE, POPULATE_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = read(originalBytes);
        MethodNode populate = unique(owner, POPULATE, POPULATE_DESCRIPTOR);
        if (populate == null || (populate.access & Opcodes.ACC_STATIC) == 0
                || count(populate, Opcodes.RETURN) != 1 || hasRuntimeCall(populate)) {
            return null;
        }

        InsnList begin = new InsnList();
        begin.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, GLOBAL, "getSettings", "()L" + SETTINGS + ";", false));
        begin.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "begin", "(Ljava/lang/Object;)V", false));
        LabelNode protectedStart = new LabelNode();
        begin.add(protectedStart);
        populate.instructions.insert(begin);
        AbstractInsnNode onlyReturn = first(populate, Opcodes.RETURN);
        populate.instructions.insertBefore(onlyReturn, new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "end", "()V", false));

        // Do not leak the callback scope if AshLib or a mod-supplied getter aborts startup. The
        // original Throwable is rethrown unchanged after the ThreadLocal is cleared.
        int failureLocal = populate.maxLocals++;
        LabelNode protectedEnd = new LabelNode();
        LabelNode handler = new LabelNode();
        InsnList cleanup = new InsnList();
        cleanup.add(protectedEnd);
        cleanup.add(handler);
        cleanup.add(new VarInsnNode(Opcodes.ASTORE, failureLocal));
        cleanup.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "end", "()V", false));
        cleanup.add(new VarInsnNode(Opcodes.ALOAD, failureLocal));
        cleanup.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ATHROW));
        populate.instructions.add(cleanup);
        populate.tryCatchBlocks.add(new TryCatchBlockNode(
                protectedStart, protectedEnd, handler, null));

        ClassWriter writer = new SafeClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        AshLibVariantLookupRuntime.repositoryInstalled();
        return writer.toByteArray();
    }

    private static byte[] transformLookup(ClassSignature signature, byte[] originalBytes) {
        if (!LOOKUP_SHA256.equals(signature.sha256())
                || !signature.hasMethod(LOOKUP, LOOKUP_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = read(originalBytes);
        MethodNode lookup = unique(owner, LOOKUP, LOOKUP_DESCRIPTOR);
        if (lookup == null || (lookup.access & Opcodes.ACC_STATIC) == 0
                || hasRuntimeCall(lookup)) {
            return null;
        }
        LabelNode vanilla = new LabelNode();
        InsnList shortcut = new InsnList();
        shortcut.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "active", "()Z", false));
        shortcut.add(new JumpInsnNode(Opcodes.IFEQ, vanilla));
        shortcut.add(new VarInsnNode(Opcodes.ALOAD, 0));
        shortcut.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, HULL, "getHullId", "()Ljava/lang/String;", true));
        shortcut.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "lookup",
                "(Ljava/lang/String;)Ljava/lang/String;", false));
        shortcut.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ARETURN));
        shortcut.add(vanilla);
        lookup.instructions.insert(shortcut);

        ClassWriter writer = new SafeClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        AshLibVariantLookupRuntime.lookupInstalled();
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static MethodNode unique(ClassNode owner, String name, String descriptor) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (found != null) return null;
                found = method;
            }
        }
        return found;
    }

    private static boolean hasRuntimeCall(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) {
                return true;
            }
        }
        return false;
    }

    private static int count(MethodNode method, int opcode) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) count++;
        }
        return count;
    }

    private static AbstractInsnNode first(MethodNode method, int opcode) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) return instruction;
        }
        return null;
    }
}
