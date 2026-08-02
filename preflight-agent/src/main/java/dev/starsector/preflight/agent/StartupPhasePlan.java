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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Marks the exact boundaries after Starsector has already drawn loading progress at 100%. */
final class StartupPhasePlan {
    static final String TARGET_CLASS = "com/fs/starfarer/loading/ResourceLoaderState";
    static final String INIT_METHOD = "init";
    static final String INIT_DESCRIPTOR = "(Ljava/util/Map;)V";
    private static final String RUNTIME = "dev/starsector/preflight/agent/StartupPhaseRuntime";

    private StartupPhasePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !signature.hasMethod(INIT_METHOD, INIT_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode init = uniqueMethod(owner, INIT_METHOD, INIT_DESCRIPTOR);
        if (init == null || calls(init, RUNTIME, "mark", "(Ljava/lang/String;)V").size() > 0) {
            return null;
        }

        MethodInsnNode shutdown = uniqueCall(init, "java/util/concurrent/ExecutorService",
                "shutdown", "()V");
        MethodInsnNode await = uniqueCall(init, "java/util/concurrent/ExecutorService",
                "awaitTermination", "(JLjava/util/concurrent/TimeUnit;)Z");
        MethodInsnNode graphicsFinalize = uniqueCall(init, "com/fs/graphics/L", "new", "()V");
        MethodInsnNode scripts = uniqueCall(init,
                "com/fs/starfarer/loading/scripts/ScriptStore", "Ô00000", "()V");
        MethodInsnNode enabledPlugins = uniqueCall(init,
                "com/fs/starfarer/launcher/ModManager", "getEnabledModPlugins", "()Ljava/util/List;");
        MethodInsnNode pluginCallback = uniqueCall(init,
                "com/fs/starfarer/api/ModPlugin", "onApplicationLoad", "()V");
        AbstractInsnNode onlyReturn = uniqueOpcode(init, Opcodes.RETURN);
        JumpInsnNode awaitRetry = nextOpcode(await) instanceof JumpInsnNode jump
                && jump.getOpcode() == Opcodes.IFEQ ? jump : null;
        JumpInsnNode pluginLoop = pluginCallback == null ? null : pluginLoopJump(pluginCallback);
        AbstractInsnNode modStart = previousOpcode(enabledPlugins);
        if (!(modStart instanceof MethodInsnNode getInstance)
                || !"com/fs/starfarer/launcher/ModManager".equals(getInstance.owner)
                || !"getInstance".equals(getInstance.name)
                || !"()Lcom/fs/starfarer/launcher/ModManager;".equals(getInstance.desc)
                || shutdown == null || awaitRetry == null || graphicsFinalize == null || scripts == null
                || pluginCallback == null || pluginLoop == null || onlyReturn == null) {
            return null;
        }

        init.instructions.insertBefore(init.instructions.getFirst(), mark("resource-init-enter"));
        init.instructions.insertBefore(shutdown, mark("progress-100"));
        init.instructions.insert(awaitRetry, mark("audio-workers-complete"));
        init.instructions.insert(graphicsFinalize, mark("graphics-finalize-complete"));
        init.instructions.insertBefore(scripts, mark("script-store-start"));
        init.instructions.insert(scripts, mark("script-store-complete"));
        init.instructions.insertBefore(modStart, mark("mod-callbacks-start"));

        InsnList pluginStart = new InsnList();
        pluginStart.add(new InsnNode(Opcodes.DUP));
        pluginStart.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "pluginStart", "(Ljava/lang/Object;)V", false));
        init.instructions.insertBefore(pluginCallback, pluginStart);
        init.instructions.insert(pluginCallback, new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "pluginEnd", "()V", false));
        init.instructions.insert(pluginLoop, mark("mod-callbacks-complete"));
        init.instructions.insertBefore(onlyReturn, mark("resource-init-complete"));

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        StartupPhaseRuntime.installed();
        return writer.toByteArray();
    }

    private static InsnList mark(String name) {
        InsnList instructions = new InsnList();
        instructions.add(new org.objectweb.asm.tree.LdcInsnNode(name));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "mark", "(Ljava/lang/String;)V", false));
        return instructions;
    }

    private static JumpInsnNode pluginLoopJump(MethodInsnNode pluginCallback) {
        for (AbstractInsnNode cursor = pluginCallback.getNext(); cursor != null; cursor = cursor.getNext()) {
            if (cursor instanceof MethodInsnNode call
                    && "java/util/Iterator".equals(call.owner)
                    && "hasNext".equals(call.name)
                    && "()Z".equals(call.desc)) {
                AbstractInsnNode next = nextOpcode(call);
                return next instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFNE
                        ? jump : null;
            }
        }
        return null;
    }

    private static MethodNode uniqueMethod(ClassNode owner, String name, String descriptor) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (found != null) return null;
                found = method;
            }
        }
        return found;
    }

    private static MethodInsnNode uniqueCall(MethodNode method, String owner, String name, String descriptor) {
        List<MethodInsnNode> matches = calls(method, owner, name, descriptor);
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static List<MethodInsnNode> calls(MethodNode method, String owner, String name, String descriptor) {
        List<MethodInsnNode> matches = new ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name) && descriptor.equals(call.desc)) {
                matches.add(call);
            }
        }
        return matches;
    }

    private static AbstractInsnNode uniqueOpcode(MethodNode method, int opcode) {
        AbstractInsnNode found = null;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() == opcode) {
                if (found != null) return null;
                found = instruction;
            }
        }
        return found;
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction == null ? null : instruction.getNext();
        while (cursor != null && cursor.getOpcode() < 0) cursor = cursor.getNext();
        return cursor;
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction == null ? null : instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) cursor = cursor.getPrevious();
        return cursor;
    }
}
