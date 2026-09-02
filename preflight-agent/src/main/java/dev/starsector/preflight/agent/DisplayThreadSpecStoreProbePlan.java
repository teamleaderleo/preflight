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
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Holds Display on one worker only across the exact Windows SpecStore CPU island. */
final class DisplayThreadSpecStoreProbePlan {
    static final String TARGET_CLASS = StartupPhasePlan.TARGET_CLASS;
    static final String ORIGINAL_SHA256 = FrameTimeStartupCompletionPlan.WINDOWS_ORIGINAL_SHA256;
    static final String INIT_METHOD = StartupPhasePlan.INIT_METHOD;
    static final String INIT_DESCRIPTOR = StartupPhasePlan.INIT_DESCRIPTOR;
    private static final String SPEC_STORE = "com/fs/starfarer/loading/SpecStore";
    private static final String SPEC_DESCRIPTOR =
            "(Lcom/fs/starfarer/loading/ResourceLoaderState;)V";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/DisplayThreadSpecStoreProbeRuntime";

    private DisplayThreadSpecStoreProbePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        if (!apply(signature, owner)) return null;
        ClassWriter writer = new SafeClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    static boolean apply(ClassSignature signature, ClassNode owner) {
        if (!requested()
                || !TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || !signature.hasMethod(INIT_METHOD, INIT_DESCRIPTOR)) return false;
        MethodNode init = unique(owner, INIT_METHOD, INIT_DESCRIPTOR);
        if (init == null || calls(init, RUNTIME).size() != 0) return false;
        List<MethodInsnNode> specStores = specStoreCalls(init);
        if (specStores.size() != 1) return false;
        MethodInsnNode specStore = specStores.get(0);

        int errorLocal = init.maxLocals;
        init.maxLocals++;
        LabelNode protectedStart = new LabelNode();
        LabelNode protectedEnd = new LabelNode();
        LabelNode failure = new LabelNode();
        LabelNode done = new LabelNode();

        InsnList before = new InsnList();
        before.add(call("beforeSpecStore"));
        before.add(protectedStart);
        init.instructions.insertBefore(specStore, before);

        InsnList after = new InsnList();
        after.add(protectedEnd);
        after.add(call("afterSpecStore"));
        after.add(new JumpInsnNode(Opcodes.GOTO, done));
        after.add(failure);
        after.add(new VarInsnNode(Opcodes.ASTORE, errorLocal));
        after.add(call("afterSpecStore"));
        after.add(new VarInsnNode(Opcodes.ALOAD, errorLocal));
        after.add(new InsnNode(Opcodes.ATHROW));
        after.add(done);
        init.instructions.insert(specStore, after);
        init.tryCatchBlocks.add(new TryCatchBlockNode(
                protectedStart, protectedEnd, failure, "java/lang/Throwable"));
        return true;
    }

    private static boolean requested() {
        return DisplayThreadSpecStoreProbeRuntime.requested();
    }

    private static MethodInsnNode call(String method) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, method, "()V", false);
    }

    private static List<MethodInsnNode> specStoreCalls(MethodNode method) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && SPEC_STORE.equals(call.owner)
                    && SPEC_DESCRIPTOR.equals(call.desc)) result.add(call);
        }
        return result;
    }

    private static List<MethodInsnNode> calls(MethodNode method, String owner) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && owner.equals(call.owner)) {
                result.add(call);
            }
        }
        return result;
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
}
