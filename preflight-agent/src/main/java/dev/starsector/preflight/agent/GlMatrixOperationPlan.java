package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Composed exact-method hooks for the reviewed LWJGL 2 matrix wrapper family. */
final class GlMatrixOperationPlan {
    private static final String GL11 = "org/lwjgl/opengl/GL11";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/GlMatrixOperationRuntime";

    private GlMatrixOperationPlan() {
    }

    static int instrument(ClassNode owner) {
        if (!GlMatrixOperationRuntime.planEnabled()) return 0;
        if (!GL11.equals(owner.name)) return 0;
        Map<String, Hook> hooks = hooks();
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
        GlMatrixOperationRuntime.installed(owner.name, selected.size());
        return selected.size();
    }

    private static Map<String, Hook> hooks() {
        Map<String, Hook> hooks = new LinkedHashMap<>();
        hooks.put("glMatrixMode(I)V", plain(GlMatrixOperationRuntime.MATRIX_MODE));
        hooks.put("glLoadIdentity()V", plain(GlMatrixOperationRuntime.LOAD_IDENTITY));
        hooks.put("glPushMatrix()V", plain(GlMatrixOperationRuntime.PUSH_MATRIX));
        hooks.put("glPopMatrix()V", plain(GlMatrixOperationRuntime.POP_MATRIX));
        hooks.put("glLoadMatrix(Ljava/nio/FloatBuffer;)V",
                plain(GlMatrixOperationRuntime.LOAD_MATRIX_FLOAT));
        hooks.put("glLoadMatrix(Ljava/nio/DoubleBuffer;)V",
                plain(GlMatrixOperationRuntime.LOAD_MATRIX_DOUBLE));
        hooks.put("glMultMatrix(Ljava/nio/FloatBuffer;)V",
                plain(GlMatrixOperationRuntime.MULT_MATRIX_FLOAT));
        hooks.put("glMultMatrix(Ljava/nio/DoubleBuffer;)V",
                plain(GlMatrixOperationRuntime.MULT_MATRIX_DOUBLE));
        hooks.put("glTranslatef(FFF)V", triple("recordTranslateF", "(FFF)V", Opcodes.FLOAD));
        hooks.put("glTranslated(DDD)V", triple("recordTranslateD", "(DDD)V", Opcodes.DLOAD));
        hooks.put("glRotatef(FFFF)V", first("recordRotateF", "(F)V", Opcodes.FLOAD));
        hooks.put("glRotated(DDDD)V", first("recordRotateD", "(D)V", Opcodes.DLOAD));
        hooks.put("glScalef(FFF)V", triple("recordScaleF", "(FFF)V", Opcodes.FLOAD));
        hooks.put("glScaled(DDD)V", triple("recordScaleD", "(DDD)V", Opcodes.DLOAD));
        hooks.put("glOrtho(DDDDDD)V", plain(GlMatrixOperationRuntime.ORTHO));
        hooks.put("glFrustum(DDDDDD)V", plain(GlMatrixOperationRuntime.FRUSTUM));
        return hooks;
    }

    private static Hook plain(int method) {
        InsnList instructions = new InsnList();
        instructions.add(new IntInsnNode(Opcodes.BIPUSH, method));
        invoke(instructions, "record", "(I)V");
        return new Hook(instructions);
    }

    private static Hook triple(String name, String descriptor, int opcode) {
        InsnList instructions = new InsnList();
        int width = opcode == Opcodes.DLOAD ? 2 : 1;
        instructions.add(new VarInsnNode(opcode, 0));
        instructions.add(new VarInsnNode(opcode, width));
        instructions.add(new VarInsnNode(opcode, width * 2));
        invoke(instructions, name, descriptor);
        return new Hook(instructions);
    }

    private static Hook first(String name, String descriptor, int opcode) {
        InsnList instructions = new InsnList();
        instructions.add(new VarInsnNode(opcode, 0));
        invoke(instructions, name, descriptor);
        return new Hook(instructions);
    }

    private static void invoke(InsnList instructions, String name, String descriptor) {
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, name, descriptor, false));
    }

    private record Hook(InsnList instructions) {
    }
}
