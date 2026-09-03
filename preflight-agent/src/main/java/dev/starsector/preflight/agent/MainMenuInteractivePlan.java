package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Publishes the earliest reviewed usable title boundary and later overlay-removal telemetry. */
final class MainMenuInteractivePlan {
    static final String PLAN_ID = "vanilla-main-menu-interactive-state-and-control-v3";
    static final String TARGET_CLASS = "com/fs/starfarer/title/B";
    static final String ORIGINAL_SHA256 =
            "a07eb94f8229ac0bb42139cebc6450518e8fe036023bd7687fb1a76347079f22";
    static final String LINUX_TARGET_CLASS = "com/fs/starfarer/title/OoOO";
    static final String LINUX_ORIGINAL_SHA256 =
            "fcc26761e5ab5896bd100f0b99d02bb008bf07cd2565418daee7409c1d1dafc7";
    // Windows' obfuscator emits the title overlay as a 256-character class name.
    static final String WINDOWS_TARGET_CLASS =
            "com/fs/starfarer/title/Oo" + "O".repeat(254);
    static final String WINDOWS_ORIGINAL_SHA256 =
            "7a034024de849f2829ad5e41dbb0e58f5979a6e7a81e55527f6839055db3d4c6";
    static final String ADVANCE_METHOD = "advanceImpl";
    static final String ADVANCE_DESCRIPTOR = "(F)V";
    static final String SHOW_METHOD = "show";
    static final String SHOW_DESCRIPTOR = "()V";

    private static final String RUNTIME =
            "dev/starsector/preflight/agent/RuntimeSemanticState";
    private static final String CONTROL_RUNTIME =
            "dev/starsector/preflight/agent/InternalGameControlRuntime";
    private static final String REMOVE_DESCRIPTOR = "(Lcom/fs/starfarer/ui/c;)V";

    private MainMenuInteractivePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        boolean linuxTarget = LINUX_TARGET_CLASS.equals(signature.internalName());
        if (!RuntimeSemanticState.enabled()
                || !supportedTarget(signature)
                || signature.majorVersion() != 61
                || !signature.hasMethod(SHOW_METHOD, SHOW_DESCRIPTOR)
                || (!linuxTarget && !signature.hasMethod(ADVANCE_METHOD, ADVANCE_DESCRIPTOR))) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode show = unique(owner, SHOW_METHOD, SHOW_DESCRIPTOR);
        AbstractInsnNode showCompletion = uniqueReturn(show);
        if (showCompletion == null
                || callsMarker(show, "mainMenuInteractive") != 0
                || callsMarker(show, "mainMenuOverlayRemoved") != 0) {
            return null;
        }

        MethodNode advance = null;
        MethodInsnNode removal = null;
        AbstractInsnNode advanceCompletion = null;
        if (!linuxTarget) {
            advance = unique(owner, ADVANCE_METHOD, ADVANCE_DESCRIPTOR);
            removal = uniqueRemoval(advance);
            advanceCompletion = uniqueReturn(advance);
            if (removal == null
                    || advanceCompletion == null
                    || callsMarker(advance, "mainMenuInteractive") != 0
                    || callsMarker(advance, "mainMenuOverlayRemoved") != 0
                    || callsControl(advance) != 0) {
                return null;
            }
        }

        show.instructions.insertBefore(showCompletion, new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "mainMenuInteractive", "()V", false));
        if (!linuxTarget) {
            advance.instructions.insert(removal, new MethodInsnNode(
                    Opcodes.INVOKESTATIC, RUNTIME, "mainMenuOverlayRemoved", "()V", false));
            org.objectweb.asm.tree.InsnList control = new org.objectweb.asm.tree.InsnList();
            control.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
            control.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    CONTROL_RUNTIME,
                    "titleAdvance",
                    "(Ljava/lang/Object;)V",
                    false));
            advance.instructions.insertBefore(advanceCompletion, control);
        }
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

    private static boolean supportedTarget(ClassSignature signature) {
        return (TARGET_CLASS.equals(signature.internalName())
                        && ORIGINAL_SHA256.equals(signature.sha256()))
                || (LINUX_TARGET_CLASS.equals(signature.internalName())
                        && LINUX_ORIGINAL_SHA256.equals(signature.sha256()))
                || (WINDOWS_TARGET_CLASS.equals(signature.internalName())
                        && WINDOWS_ORIGINAL_SHA256.equals(signature.sha256()));
    }

    private static AbstractInsnNode uniqueReturn(MethodNode method) {
        if (method == null) return null;
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

    private static int callsMarker(MethodNode method, String name) {
        if (method == null) return 0;
        int result = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && RUNTIME.equals(call.owner)
                    && name.equals(call.name)
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
}
