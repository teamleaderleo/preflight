package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Times the exact top-level loader sequence hidden behind the initial 0% progress bar. */
final class SpecStorePhasePlan {
    static final String TARGET_CLASS = "com/fs/starfarer/loading/SpecStore";
    static final String INIT_METHOD = "ÓO0000";
    static final String INIT_DESCRIPTOR = "(Lcom/fs/starfarer/loading/ResourceLoaderState;)V";
    private static final String RUNTIME = "dev/starsector/preflight/agent/StartupPhaseRuntime";
    private static final int EXPECTED_TOP_LEVEL_LOADERS = 41;

    private SpecStorePhasePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        if (!apply(signature, owner)) {
            return null;
        }
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        StartupPhaseRuntime.installed();
        return writer.toByteArray();
    }

    static boolean apply(ClassSignature signature, ClassNode owner) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !signature.hasMethod(INIT_METHOD, INIT_DESCRIPTOR)) {
            return false;
        }
        MethodNode init = uniqueMethod(owner, INIT_METHOD, INIT_DESCRIPTOR);
        if (init == null || hasRuntimeCalls(init)) {
            return false;
        }

        MethodInsnNode first = uniqueCall(init, TARGET_CLASS, "new", INIT_DESCRIPTOR);
        MethodInsnNode last = uniqueCall(init, "com/fs/starfarer/campaign/rules/Rules",
                "super", INIT_DESCRIPTOR);
        if (first == null || last == null || !comesBefore(first, last)) {
            return false;
        }

        List<MethodInsnNode> loaders = staticVoidCalls(first, last);
        if (loaders.size() != EXPECTED_TOP_LEVEL_LOADERS
                || loaders.get(0) != first || loaders.get(loaders.size() - 1) != last) {
            return false;
        }

        for (int index = 0; index < loaders.size(); index++) {
            MethodInsnNode loader = loaders.get(index);
            init.instructions.insertBefore(loader, start(label(index + 1, loader)));
            init.instructions.insert(loader, new MethodInsnNode(
                    Opcodes.INVOKESTATIC, RUNTIME, "specLoaderEnd", "()V", false));
        }

        return true;
    }

    private static InsnList start(String label) {
        InsnList instructions = new InsnList();
        instructions.add(new LdcInsnNode(label));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "specLoaderStart", "(Ljava/lang/String;)V", false));
        return instructions;
    }

    private static String label(int ordinal, MethodInsnNode call) {
        int slash = call.owner.lastIndexOf('/');
        String owner = slash < 0 ? call.owner : call.owner.substring(slash + 1);
        if (owner.length() > 80) {
            owner = owner.substring(0, 77) + "...";
        }
        return ordinal + ":" + owner + "." + call.name + call.desc;
    }

    private static List<MethodInsnNode> staticVoidCalls(
            MethodInsnNode first, MethodInsnNode last) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode cursor = first; cursor != null; cursor = cursor.getNext()) {
            if (cursor instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && Type.VOID_TYPE.equals(Type.getReturnType(call.desc))) {
                calls.add(call);
            }
            if (cursor == last) {
                break;
            }
        }
        return calls;
    }

    private static boolean comesBefore(AbstractInsnNode first, AbstractInsnNode last) {
        for (AbstractInsnNode cursor = first; cursor != null; cursor = cursor.getNext()) {
            if (cursor == last) {
                return true;
            }
        }
        return false;
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

    private static MethodInsnNode uniqueCall(
            MethodNode method, String owner, String name, String descriptor) {
        MethodInsnNode found = null;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                if (found != null) {
                    return null;
                }
                found = call;
            }
        }
        return found;
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
