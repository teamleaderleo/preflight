package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Publishes the point where Starsector removes its title-screen "Preloading..." label. */
final class MainMenuInteractivePlan {
    static final String PLAN_ID = "vanilla-main-menu-interactive-state-and-control-v2";
    static final String TARGET_CLASS = "com/fs/starfarer/title/B";
    static final String ORIGINAL_SHA256 =
            "a07eb94f8229ac0bb42139cebc6450518e8fe036023bd7687fb1a76347079f22";
    static final String ADVANCE_METHOD = "advanceImpl";
    static final String ADVANCE_DESCRIPTOR = "(F)V";

    private static final String RUNTIME =
            "dev/starsector/preflight/agent/RuntimeSemanticState";
    private static final String CONTROL_RUNTIME =
            "dev/starsector/preflight/agent/InternalGameControlRuntime";
    private static final String REMOVE_DESCRIPTOR = "(Lcom/fs/starfarer/ui/c;)V";

    private MainMenuInteractivePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!RuntimeSemanticState.enabled()
                || !TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(ADVANCE_METHOD, ADVANCE_DESCRIPTOR)) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode advance = unique(owner, ADVANCE_METHOD, ADVANCE_DESCRIPTOR);
        MethodInsnNode removal = uniqueRemoval(advance);
        if (removal == null || callsMarker(advance) != 0 || callsControl(advance) != 0) return null;

        advance.instructions.insert(removal, new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "mainMenuInteractive", "()V", false));
        AbstractInsnNode onlyReturn = uniqueReturn(advance);
        if (onlyReturn == null) return null;
        org.objectweb.asm.tree.InsnList control = new org.objectweb.asm.tree.InsnList();
        control.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        control.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, CONTROL_RUNTIME, "titleAdvance", "(Ljava/lang/Object;)V", false));
        advance.instructions.insertBefore(onlyReturn, control);
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode unique(ClassNode owner, String name, String descriptor) {
        MethodNode result = null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (result != null) return null;
                result = method;
            }
        }
        return result;
    }

    private static MethodInsnNode uniqueRemoval(MethodNode method) {
        if (method == null) return null;
        MethodInsnNode result = null;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && "remove".equals(call.name)
                    && REMOVE_DESCRIPTOR.equals(call.desc)) {
                if (result != null) return null;
                result = call;
            }
        }
        return result;
    }

    private static int callsMarker(MethodNode method) {
        if (method == null) return 0;
        int result = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && RUNTIME.equals(call.owner)
                    && "mainMenuInteractive".equals(call.name)
                    && "()V".equals(call.desc)) {
                result++;
            }
        }
        return result;
    }

    private static int callsControl(MethodNode method) {
        if (method == null) return 0;
        int result = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && CONTROL_RUNTIME.equals(call.owner)
                    && "titleAdvance".equals(call.name)
                    && "(Ljava/lang/Object;)V".equals(call.desc)) result++;
        }
        return result;
    }

    private static AbstractInsnNode uniqueReturn(MethodNode method) {
        AbstractInsnNode result = null;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                if (result != null) return null;
                result = instruction;
            }
        }
        return result;
    }
}
