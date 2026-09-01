package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Composed exact-method hooks for selected legacy OpenGL state arguments. */
final class GlStateReissuePlan {
    private static final String GL11 = "org/lwjgl/opengl/GL11";
    private static final String GL13 = "org/lwjgl/opengl/GL13";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/GlStateReissueRuntime";

    private GlStateReissuePlan() {
    }

    static int instrument(ClassNode owner) {
        if (!GlStateReissueRuntime.planEnabled()) return 0;
        Map<String, Hook> hooks = hooks(owner.name);
        if (hooks.isEmpty()) return 0;
        Map<String, MethodNode> selected = new LinkedHashMap<>();
        for (MethodNode method : owner.methods) {
            String signature = method.name + method.desc;
            Hook hook = hooks.get(signature);
            if (hook == null) continue;
            if ((method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))
                    != (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)
                    || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                    || selected.put(signature, method) != null) {
                return -1;
            }
        }
        if (selected.size() != hooks.size()) return -1;
        for (Map.Entry<String, MethodNode> entry : selected.entrySet()) {
            entry.getValue().instructions.insert(hooks.get(entry.getKey()).instructions());
        }
        GlStateReissueRuntime.installed(owner.name, selected.size());
        return selected.size();
    }

    private static Map<String, Hook> hooks(String owner) {
        Map<String, Hook> hooks = new LinkedHashMap<>();
        if (GL11.equals(owner)) {
            hooks.put("glBindTexture(II)V", args("recordBindTexture", "(II)V", 0, 1));
            hooks.put("glEnable(I)V", capability(true));
            hooks.put("glDisable(I)V", capability(false));
            hooks.put("glBlendFunc(II)V", pair(GlStateReissueRuntime.BLEND_FUNC));
            hooks.put("glAlphaFunc(IF)V", alpha());
            hooks.put("glDepthFunc(I)V", single(GlStateReissueRuntime.DEPTH_FUNC));
            hooks.put("glDepthMask(Z)V", bool(GlStateReissueRuntime.DEPTH_MASK));
            hooks.put("glCullFace(I)V", single(GlStateReissueRuntime.CULL_FACE));
            hooks.put("glScissor(IIII)V", quad(GlStateReissueRuntime.SCISSOR));
            hooks.put("glViewport(IIII)V", quad(GlStateReissueRuntime.VIEWPORT));
            hooks.put("glMatrixMode(I)V", single(GlStateReissueRuntime.MATRIX_MODE));
            hooks.put("glCallList(I)V", invalidation(GlStateReissueRuntime.INVALIDATE_CALL_LIST));
            hooks.put("glCallLists(Ljava/nio/ByteBuffer;)V",
                    invalidation(GlStateReissueRuntime.INVALIDATE_CALL_LIST));
            hooks.put("glCallLists(Ljava/nio/IntBuffer;)V",
                    invalidation(GlStateReissueRuntime.INVALIDATE_CALL_LIST));
            hooks.put("glCallLists(Ljava/nio/ShortBuffer;)V",
                    invalidation(GlStateReissueRuntime.INVALIDATE_CALL_LIST));
            hooks.put("glPopAttrib()V",
                    invalidation(GlStateReissueRuntime.INVALIDATE_SERVER_ATTRIB_POP));
            hooks.put("glPopClientAttrib()V",
                    invalidation(GlStateReissueRuntime.INVALIDATE_CLIENT_ATTRIB_POP));
        } else if (GL13.equals(owner)) {
            hooks.put("glActiveTexture(I)V", args("recordActiveTexture", "(I)V", 0));
            hooks.put("glClientActiveTexture(I)V",
                    single(GlStateReissueRuntime.CLIENT_ACTIVE_TEXTURE));
        }
        return hooks;
    }

    private static Hook capability(boolean enabled) {
        InsnList instructions = new InsnList();
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        instructions.add(new InsnNode(enabled ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        invoke(instructions, "recordCapability", "(IZ)V");
        return new Hook(instructions);
    }

    private static Hook pair(int method) {
        InsnList instructions = method(method);
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        invoke(instructions, "recordPair", "(III)V");
        return new Hook(instructions);
    }

    private static Hook alpha() {
        InsnList instructions = new InsnList();
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        instructions.add(new VarInsnNode(Opcodes.FLOAD, 1));
        invoke(instructions, "recordAlpha", "(IF)V");
        return new Hook(instructions);
    }

    private static Hook single(int method) {
        InsnList instructions = method(method);
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        invoke(instructions, "recordSingle", "(II)V");
        return new Hook(instructions);
    }

    private static Hook bool(int method) {
        InsnList instructions = method(method);
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        invoke(instructions, "recordBoolean", "(IZ)V");
        return new Hook(instructions);
    }

    private static Hook quad(int method) {
        InsnList instructions = method(method);
        for (int local = 0; local < 4; local++) {
            instructions.add(new VarInsnNode(Opcodes.ILOAD, local));
        }
        invoke(instructions, "recordQuad", "(IIIII)V");
        return new Hook(instructions);
    }

    private static Hook invalidation(int reason) {
        InsnList instructions = method(reason);
        invoke(instructions, "recordInvalidation", "(I)V");
        return new Hook(instructions);
    }

    private static Hook args(String name, String descriptor, int... locals) {
        InsnList instructions = new InsnList();
        for (int local : locals) instructions.add(new VarInsnNode(Opcodes.ILOAD, local));
        invoke(instructions, name, descriptor);
        return new Hook(instructions);
    }

    private static InsnList method(int method) {
        InsnList instructions = new InsnList();
        instructions.add(new org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, method));
        return instructions;
    }

    private static void invoke(InsnList instructions, String name, String descriptor) {
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, name, descriptor, false));
    }

    private record Hook(InsnList instructions) {
    }
}
