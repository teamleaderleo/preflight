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
    static final String SHIP_JSON_CLASS = "ashlib/data/plugins/models/ShipRenderInfo";
    static final String SHIP_JSON_SHA256 =
            "bb8d74bfb775f63ba79aa802c7e67158b5eea80c2d3057f9fd40350fd99e1aed";
    static final String SHIP_JSON_METHOD = "getShipJson";
    static final String SHIP_JSON_DESCRIPTOR =
            "(Ljava/lang/String;)Lorg/json/JSONObject;";
    private static final String ORIGINAL_SHIP_JSON = "preflight$original$getShipJson";

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
        if (SHIP_JSON_CLASS.equals(signature.internalName())) {
            return transformShipJson(signature, originalBytes);
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

    private static byte[] transformShipJson(ClassSignature signature, byte[] originalBytes) {
        if (!SHIP_JSON_SHA256.equals(signature.sha256())
                || !signature.hasMethod(SHIP_JSON_METHOD, SHIP_JSON_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = read(originalBytes);
        MethodNode original = unique(owner, SHIP_JSON_METHOD, SHIP_JSON_DESCRIPTOR);
        if (original == null || (original.access & Opcodes.ACC_STATIC) == 0
                || unique(owner, ORIGINAL_SHIP_JSON, SHIP_JSON_DESCRIPTOR) != null
                || hasRuntimeCall(original) || calls(owner, SHIP_JSON_METHOD, SHIP_JSON_DESCRIPTOR) != 4
                || mutatesJson(owner)) {
            return null;
        }
        int access = original.access;
        original.name = ORIGINAL_SHIP_JSON;
        original.access |= Opcodes.ACC_SYNTHETIC;

        MethodNode wrapper = new MethodNode(
                Opcodes.ASM9,
                access,
                SHIP_JSON_METHOD,
                SHIP_JSON_DESCRIPTOR,
                original.signature,
                original.exceptions == null ? null : original.exceptions.toArray(String[]::new));
        LabelNode load = new LabelNode();
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "cachedShipJson",
                "(Ljava/lang/String;)Ljava/lang/Object;", false));
        wrapper.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.DUP));
        wrapper.instructions.add(new JumpInsnNode(Opcodes.IFNULL, load));
        wrapper.instructions.add(new org.objectweb.asm.tree.TypeInsnNode(
                Opcodes.CHECKCAST, "org/json/JSONObject"));
        wrapper.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ARETURN));
        wrapper.instructions.add(load);
        wrapper.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.POP));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, SHIP_JSON_CLASS, ORIGINAL_SHIP_JSON,
                SHIP_JSON_DESCRIPTOR, false));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "rememberShipJson",
                "(Ljava/lang/String;Ljava/lang/Object;)V", false));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        wrapper.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ARETURN));
        owner.methods.add(wrapper);

        ClassWriter writer = new SafeClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        AshLibVariantLookupRuntime.shipJsonInstalled();
        return writer.toByteArray();
    }

    private static int calls(ClassNode owner, String name, String descriptor) {
        int count = 0;
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && SHIP_JSON_CLASS.equals(call.owner)
                        && name.equals(call.name) && descriptor.equals(call.desc)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean mutatesJson(ClassNode owner) {
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && "org/json/JSONObject".equals(call.owner)
                        && ("put".equals(call.name) || "remove".equals(call.name)
                        || "append".equals(call.name) || "accumulate".equals(call.name)
                        || "increment".equals(call.name))) {
                    return true;
                }
            }
        }
        return false;
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
