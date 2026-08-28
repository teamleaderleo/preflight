package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Exact-target aggregate timer for vanilla DynamicParticleGroup.render(float,float). */
final class DynamicParticleGroupRenderProbePlan {
    static final String TARGET_CLASS = "com/fs/graphics/particle/DynamicParticleGroup";
    static final String RENDER_METHOD = "render";
    static final String RENDER_DESCRIPTOR = "(FF)V";

    private static final String RUNTIME =
            "dev/starsector/preflight/agent/DynamicParticleGroupRenderProbeRuntime";
    private static final String GL11 = "org/lwjgl/opengl/GL11";

    private DynamicParticleGroupRenderProbePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!DynamicParticleGroupRenderProbeRuntime.enabled()
                || !TARGET_CLASS.equals(signature.internalName())
                || !signature.hasMethod(RENDER_METHOD, RENDER_DESCRIPTOR)) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode render = unique(owner, RENDER_METHOD, RENDER_DESCRIPTOR);
        if (render == null || calls(render, RUNTIME, "begin") != 0 || calls(render, RUNTIME, "end") != 0) {
            return null;
        }

        int returns = opcodeCount(render, Opcodes.RETURN);
        if (returns < 1) {
            return null;
        }

        int glBegin = calls(render, GL11, "glBegin");
        int glEnd = calls(render, GL11, "glEnd");
        int vertices = callsPrefix(render, GL11, "glVertex");
        int texCoords = callsPrefix(render, GL11, "glTexCoord");
        int colors = callsPrefix(render, GL11, "glColor");
        int bindTextures = calls(render, GL11, "glBindTexture");
        int blendFuncs = callsPrefix(render, GL11, "glBlendFunc");

        int startLocal = render.maxLocals;
        render.maxLocals += 2;
        InsnList entry = new InsnList();
        entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "begin", "()J", false));
        entry.add(new VarInsnNode(Opcodes.LSTORE, startLocal));
        render.instructions.insert(entry);

        for (AbstractInsnNode instruction = render.instructions.getFirst(); instruction != null; ) {
            AbstractInsnNode next = instruction.getNext();
            if (instruction.getOpcode() == Opcodes.RETURN) {
                InsnList exit = new InsnList();
                exit.add(new VarInsnNode(Opcodes.LLOAD, startLocal));
                exit.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "end", "(J)V", false));
                render.instructions.insertBefore(instruction, exit);
            }
            instruction = next;
        }

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        DynamicParticleGroupRenderProbeRuntime.installed(
                returns, glBegin, glEnd, vertices, texCoords, colors, bindTextures, blendFuncs);
        return writer.toByteArray();
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

    private static int opcodeCount(MethodNode method, int opcode) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) count++;
        }
        return count;
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)) {
                count++;
            }
        }
        return count;
    }

    private static int callsPrefix(MethodNode method, String owner, String namePrefix) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && call.name.startsWith(namePrefix)) {
                count++;
            }
        }
        return count;
    }
}
