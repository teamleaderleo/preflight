package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
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
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Exact-target GL11 client-state cache for the six glIsEnabled capabilities Fast Rendering tracks.
 * Unknown state keeps LWJGL's native getter intact.
 */
final class GlIsEnabledStateCachePlan {
    static final String TARGET_CLASS = "org/lwjgl/opengl/GL11";
    static final String IS_ENABLED = "glIsEnabled";
    static final String IS_ENABLED_DESCRIPTOR = "(I)Z";

    private static final String RUNTIME =
            "dev/starsector/preflight/agent/GlIsEnabledStateCacheRuntime";
    private static final String GL_CONTEXT = "org/lwjgl/opengl/GLContext";
    private static final String GET_CAPABILITIES = "getCapabilities";
    private static final String GET_CAPABILITIES_DESCRIPTOR =
            "()Lorg/lwjgl/opengl/ContextCapabilities;";

    private GlIsEnabledStateCachePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!GlIsEnabledStateCacheRuntime.enabled()
                || !TARGET_CLASS.equals(signature.internalName())
                || !signature.hasMethod(IS_ENABLED, IS_ENABLED_DESCRIPTOR)
                || !signature.hasMethod("glEnable", "(I)V")
                || !signature.hasMethod("glDisable", "(I)V")
                || !signature.hasMethod("glPushAttrib", "(I)V")
                || !signature.hasMethod("glPopAttrib", "()V")
                || !signature.hasMethod("glNewList", "(II)V")
                || !signature.hasMethod("glEndList", "()V")
                || !signature.hasMethod("glCallList", "(I)V")) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        if (calls(owner, RUNTIME) != 0) return null;

        MethodNode isEnabled = unique(owner, IS_ENABLED, IS_ENABLED_DESCRIPTOR);
        MethodNode enable = unique(owner, "glEnable", "(I)V");
        MethodNode disable = unique(owner, "glDisable", "(I)V");
        MethodNode pushAttrib = unique(owner, "glPushAttrib", "(I)V");
        MethodNode popAttrib = unique(owner, "glPopAttrib", "()V");
        MethodNode newList = unique(owner, "glNewList", "(II)V");
        MethodNode endList = unique(owner, "glEndList", "()V");
        MethodNode callList = unique(owner, "glCallList", "(I)V");
        if (isEnabled == null || enable == null || disable == null || pushAttrib == null
                || popAttrib == null || newList == null || endList == null || callList == null
                || opcodeCount(isEnabled, Opcodes.IRETURN) < 1
                || opcodeCount(enable, Opcodes.RETURN) < 1
                || opcodeCount(disable, Opcodes.RETURN) < 1
                || opcodeCount(pushAttrib, Opcodes.RETURN) < 1
                || opcodeCount(popAttrib, Opcodes.RETURN) < 1
                || opcodeCount(newList, Opcodes.RETURN) < 1
                || opcodeCount(endList, Opcodes.RETURN) < 1
                || opcodeCount(callList, Opcodes.RETURN) < 1) {
            return null;
        }

        instrumentGetter(isEnabled);
        instrumentIntSetter(enable, "enable");
        instrumentIntSetter(disable, "disable");
        instrumentIntSetter(pushAttrib, "pushAttrib");
        instrumentNoArgSetter(popAttrib, "popAttrib");
        instrumentNoArgSetter(newList, "beginList");
        instrumentNoArgSetter(endList, "endList");
        instrumentNoArgSetter(callList, "callList");
        for (MethodNode method : owner.methods) {
            if ("glCallLists".equals(method.name) && method.desc.endsWith(")V")) {
                instrumentNoArgSetter(method, "callList");
            }
        }

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        GlIsEnabledStateCacheRuntime.installed();
        return writer.toByteArray();
    }

    private static void instrumentGetter(MethodNode method) {
        List<AbstractInsnNode> returns = returns(method, Opcodes.IRETURN);
        int tokenLocal = method.maxLocals++;
        int cachedLocal = method.maxLocals++;
        int resultLocal = method.maxLocals++;

        LabelNode fallback = new LabelNode();
        InsnList entry = new InsnList();
        entry.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                GL_CONTEXT,
                GET_CAPABILITIES,
                GET_CAPABILITIES_DESCRIPTOR,
                false));
        entry.add(new VarInsnNode(Opcodes.ASTORE, tokenLocal));
        entry.add(new VarInsnNode(Opcodes.ILOAD, 0));
        entry.add(new VarInsnNode(Opcodes.ALOAD, tokenLocal));
        entry.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                RUNTIME,
                "cached",
                "(ILjava/lang/Object;)I",
                false));
        entry.add(new VarInsnNode(Opcodes.ISTORE, cachedLocal));
        entry.add(new VarInsnNode(Opcodes.ILOAD, cachedLocal));
        entry.add(new JumpInsnNode(Opcodes.IFLT, fallback));
        entry.add(new VarInsnNode(Opcodes.ILOAD, cachedLocal));
        entry.add(new org.objectweb.asm.tree.InsnNode(Opcodes.IRETURN));
        entry.add(fallback);
        method.instructions.insert(entry);

        for (AbstractInsnNode returnInsn : returns) {
            InsnList exit = new InsnList();
            exit.add(new VarInsnNode(Opcodes.ISTORE, resultLocal));
            exit.add(new VarInsnNode(Opcodes.ILOAD, 0));
            exit.add(new VarInsnNode(Opcodes.ILOAD, resultLocal));
            exit.add(new VarInsnNode(Opcodes.ALOAD, tokenLocal));
            exit.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    RUNTIME,
                    "observedQuery",
                    "(IZLjava/lang/Object;)V",
                    false));
            exit.add(new VarInsnNode(Opcodes.ILOAD, resultLocal));
            method.instructions.insertBefore(returnInsn, exit);
        }
    }

    private static void instrumentIntSetter(MethodNode method, String runtimeMethod) {
        instrumentBeforeReturns(method, () -> {
            InsnList exit = new InsnList();
            exit.add(new VarInsnNode(Opcodes.ILOAD, 0));
            exit.add(capabilities());
            exit.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    RUNTIME,
                    runtimeMethod,
                    "(ILjava/lang/Object;)V",
                    false));
            return exit;
        });
    }

    private static void instrumentNoArgSetter(MethodNode method, String runtimeMethod) {
        instrumentBeforeReturns(method, () -> {
            InsnList exit = new InsnList();
            exit.add(capabilities());
            exit.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    RUNTIME,
                    runtimeMethod,
                    "(Ljava/lang/Object;)V",
                    false));
            return exit;
        });
    }

    private static MethodInsnNode capabilities() {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                GL_CONTEXT,
                GET_CAPABILITIES,
                GET_CAPABILITIES_DESCRIPTOR,
                false);
    }

    private static void instrumentBeforeReturns(MethodNode method, InsnFactory factory) {
        for (AbstractInsnNode returnInsn : returns(method, Opcodes.RETURN)) {
            method.instructions.insertBefore(returnInsn, factory.create());
        }
    }

    private static List<AbstractInsnNode> returns(MethodNode method, int opcode) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) result.add(instruction);
        }
        return result;
    }

    private static int opcodeCount(MethodNode method, int opcode) {
        return returns(method, opcode).size();
    }

    private static int calls(ClassNode owner, String targetOwner) {
        int count = 0;
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && targetOwner.equals(call.owner)) {
                    count++;
                }
            }
        }
        return count;
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

    @FunctionalInterface
    private interface InsnFactory {
        InsnList create();
    }
}
