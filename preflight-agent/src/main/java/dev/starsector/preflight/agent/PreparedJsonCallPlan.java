package dev.starsector.preflight.agent;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Isolates cache control flow so the game method's original stack-map hierarchy survives. */
final class PreparedJsonCallPlan {
    private PreparedJsonCallPlan() {
    }

    static boolean hasHelper(ClassNode owner, String name) {
        return owner.methods.stream().anyMatch(method -> name.equals(method.name));
    }

    static void replace(ClassNode owner, MethodInsnNode call, String runtime, String name) {
        MethodNode helper = new MethodNode(Opcodes.ASM9,
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name, call.desc, null, null);
        LabelNode hit = new LabelNode();
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        helper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, runtime,
                "cached", "(Ljava/lang/String;)Ljava/lang/Object;", false));
        helper.instructions.add(new InsnNode(Opcodes.DUP));
        helper.instructions.add(new JumpInsnNode(Opcodes.IFNONNULL, hit));
        helper.instructions.add(new InsnNode(Opcodes.POP));
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        helper.instructions.add(new MethodInsnNode(call.getOpcode(), call.owner, call.name, call.desc, call.itf));
        helper.instructions.add(new InsnNode(Opcodes.DUP));
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        helper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, runtime,
                "capture", "(Ljava/lang/Object;Ljava/lang/String;)V", false));
        helper.instructions.add(hit);
        helper.instructions.add(new FrameNode(Opcodes.F_NEW, 1, new Object[] {"java/lang/String"},
                1, new Object[] {"java/lang/Object"}));
        helper.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "org/json/JSONObject"));
        helper.instructions.add(new InsnNode(Opcodes.ARETURN));
        helper.maxLocals = 1;
        owner.methods.add(helper);
        call.owner = owner.name;
        call.name = name;
    }
}
