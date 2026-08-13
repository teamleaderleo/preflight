package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Initializes Logistics Notifications' fuel snapshot before its paused load alarm can read it. */
final class LogisticsNotificationsFuelPlan {
    static final String TARGET_CLASS = "logNot/scripts/SupplyDataTracking";
    static final String ORIGINAL_SHA256 =
            "357a9ef8ddb5de3c99417c5df2ed431d897cea07e091e38cbf961c4cbd1df7d2";
    static final String CONSTRUCTOR = "<init>";
    static final String CONSTRUCTOR_DESCRIPTOR = "()V";

    private static final String FUEL_FIELD = "_fuelLYRemaining";
    private static final String FUEL_DESCRIPTOR = "F";
    private static final String UPDATE_FUEL = "updateFuelLY";
    private static final String UPDATE_FUEL_DESCRIPTOR = "()V";

    private LogisticsNotificationsFuelPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(CONSTRUCTOR, CONSTRUCTOR_DESCRIPTOR)) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode constructor = unique(owner, CONSTRUCTOR, CONSTRUCTOR_DESCRIPTOR);
        MethodNode updateFuel = unique(owner, UPDATE_FUEL, UPDATE_FUEL_DESCRIPTOR);
        FieldNode fuel = owner.fields.stream()
                .filter(field -> FUEL_FIELD.equals(field.name)
                        && FUEL_DESCRIPTOR.equals(field.desc)
                        && (field.access & Opcodes.ACC_STATIC) == 0)
                .findFirst().orElse(null);
        AbstractInsnNode returnInstruction = singleReturn(constructor);
        if (constructor == null || updateFuel == null || fuel == null || returnInstruction == null
                || calls(constructor, UPDATE_FUEL, UPDATE_FUEL_DESCRIPTOR) != 0) {
            return null;
        }

        InsnList initialize = new InsnList();
        initialize.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initialize.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, TARGET_CLASS, UPDATE_FUEL, UPDATE_FUEL_DESCRIPTOR, false));
        constructor.instructions.insertBefore(returnInstruction, initialize);

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        LogisticsNotificationsFuelRuntime.installed();
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

    private static AbstractInsnNode singleReturn(MethodNode method) {
        if (method == null) return null;
        AbstractInsnNode result = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() != Opcodes.RETURN) continue;
            if (result != null) return null;
            result = instruction;
        }
        return result;
    }

    private static int calls(MethodNode method, String name, String descriptor) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && TARGET_CLASS.equals(call.owner)
                    && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                result++;
            }
        }
        return result;
    }
}
