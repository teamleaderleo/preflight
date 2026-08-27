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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Pre-sizes two bounded temporary lists in AI Tweaks' weapon-arc split pass. */
final class AiTweaksSplitArcsPlan {
    static final String PLAN_ID = "aitweaks-split-arcs-capacity-v1";
    static final String ENABLED_PROPERTY = "preflight.combat.aiTweaksArcCapacity";
    static final String TARGET_CLASS = "com/genir/aitweaks/core/shipai/WeaponGroup";
    static final String ORIGINAL_SHA256 =
            "788cbde04b454753673e4500ebdecc06735b56e175b05ff75de4f7847c076476";
    static final String METHOD = "splitArcs";
    static final String DESCRIPTOR = "(Ljava/util/List;)Ljava/util/List;";

    private static final String ARRAY_LIST = "java/util/ArrayList";
    private static final String LIST = "java/util/List";
    private static final String CONSTRUCTOR = "<init>";
    private static final String EMPTY_CONSTRUCTOR = "()V";
    private static final String CAPACITY_CONSTRUCTOR = "(I)V";

    private AiTweaksSplitArcsPlan() {
    }

    static boolean enabled() {
        return Boolean.getBoolean(ENABLED_PROPERTY);
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!enabled()
                || signature.majorVersion() != 61
                || !TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || !signature.hasMethod(METHOD, DESCRIPTOR)) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode method = unique(owner, METHOD, DESCRIPTOR);
        if (method == null) return null;

        List<MethodInsnNode> constructors = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && ARRAY_LIST.equals(call.owner)
                    && CONSTRUCTOR.equals(call.name)
                    && EMPTY_CONSTRUCTOR.equals(call.desc)) {
                AbstractInsnNode duplicate = previousCode(call);
                AbstractInsnNode allocation = previousCode(duplicate);
                if (duplicate == null || duplicate.getOpcode() != Opcodes.DUP
                        || !(allocation instanceof TypeInsnNode type)
                        || allocation.getOpcode() != Opcodes.NEW
                        || !ARRAY_LIST.equals(type.desc)) {
                    return null;
                }
                constructors.add(call);
            }
        }
        if (constructors.size() != 2) return null;

        for (int index = 0; index < constructors.size(); index++) {
            MethodInsnNode constructor = constructors.get(index);
            InsnList capacity = new InsnList();
            capacity.add(new VarInsnNode(Opcodes.ALOAD, 1));
            capacity.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE, LIST, "size", "()I", true));
            if (index == 0) {
                // The first list receives exactly the two angular limits of every input arc.
                capacity.add(new InsnNode(Opcodes.ICONST_2));
                capacity.add(new InsnNode(Opcodes.IMUL));
            }
            method.instructions.insertBefore(constructor, capacity);
            constructor.desc = CAPACITY_CONSTRUCTOR;
        }
        method.maxStack += 2;

        ClassWriter writer = new ClassWriter(0);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode unique(ClassNode owner, String name, String descriptor) {
        MethodNode result = null;
        for (MethodNode method : owner.methods) {
            if (!name.equals(method.name) || !descriptor.equals(method.desc)) continue;
            if (result != null) return null;
            result = method;
        }
        return result;
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        AbstractInsnNode previous = instruction == null ? null : instruction.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous;
    }
}
