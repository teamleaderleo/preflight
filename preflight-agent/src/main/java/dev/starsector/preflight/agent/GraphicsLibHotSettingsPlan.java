package dev.starsector.preflight.agent;

import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Caches three LunaLib-backed GraphicsLib constants until GraphicsLib applies new settings. */
final class GraphicsLibHotSettingsPlan {
    static final String TARGET_CLASS = "org/dark/shaders/util/GraphicsLibSettings";
    static final String ORIGINAL_SHA256 =
            "a4551b22589ea3dbb62c9a4ca36a9c8f541bc3ef14cdc8b6a2922a9922fd07e3";
    static final String LOAD_METHOD = "load";
    static final String LOAD_DESCRIPTOR = "()V";
    static final String APPLY_METHOD = "applyChanges";
    static final String APPLY_DESCRIPTOR = "()V";

    private static final String RUNTIME =
            "dev/starsector/preflight/agent/GraphicsLibHotSettingsRuntime";
    private static final String LUNA = "lunalib/lunaSettings/LunaSettings";
    private static final String GET_FLOAT_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Float;";
    private static final List<Setting> SETTINGS = List.of(
            new Setting("fighterBrightnessScale"),
            new Setting("weaponFlashHeight"),
            new Setting("weaponLightHeight"));

    private GraphicsLibHotSettingsPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(LOAD_METHOD, LOAD_DESCRIPTOR)
                || !signature.hasMethod(APPLY_METHOD, APPLY_DESCRIPTOR)
                || SETTINGS.stream().anyMatch(setting ->
                        !signature.hasMethod(setting.name(), "()F"))) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode load = unique(owner, LOAD_METHOD, LOAD_DESCRIPTOR);
        MethodNode apply = unique(owner, APPLY_METHOD, APPLY_DESCRIPTOR);
        if (load == null || apply == null
                || (load.access & Opcodes.ACC_STATIC) == 0
                || (apply.access & Opcodes.ACC_STATIC) == 0
                || SETTINGS.stream().anyMatch(setting -> !review(owner, setting))) {
            return null;
        }

        for (Setting setting : SETTINGS) {
            MethodNode original = unique(owner, setting.name(), "()F");
            original.name = setting.originalName();
            owner.fields.add(new FieldNode(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE
                            | Opcodes.ACC_SYNTHETIC,
                    setting.validField(),
                    "Z",
                    null,
                    null));
            owner.methods.add(wrapper(setting, original.access));
        }
        load.instructions.insert(invalidation());
        apply.instructions.insert(invalidation());

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        GraphicsLibHotSettingsRuntime.installed();
        return writer.toByteArray();
    }

    private static boolean review(ClassNode owner, Setting setting) {
        if (hasField(owner, setting.validField())
                || unique(owner, setting.originalName(), "()F") != null) {
            return false;
        }
        FieldNode field = uniqueField(owner, setting.name(), "F");
        MethodNode getter = unique(owner, setting.name(), "()F");
        if (field == null || getter == null
                || (field.access & Opcodes.ACC_STATIC) == 0
                || (getter.access & Opcodes.ACC_PUBLIC) == 0
                || (getter.access & Opcodes.ACC_STATIC) == 0) {
            return false;
        }
        return calls(getter, LUNA, "getFloat", GET_FLOAT_DESCRIPTOR) == 1
                && fieldAccesses(getter, Opcodes.PUTSTATIC, setting.name(), "F") == 1
                && fieldAccesses(getter, Opcodes.GETSTATIC, setting.name(), "F") == 1
                && returns(getter, Opcodes.FRETURN) == 2;
    }

    private static MethodNode wrapper(Setting setting, int access) {
        MethodNode method = new MethodNode(Opcodes.ASM9, access, setting.name(), "()F", null, null);
        LabelNode cached = new LabelNode();
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETSTATIC, TARGET_CLASS, setting.validField(), "Z"));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNE, cached));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, TARGET_CLASS, setting.originalName(), "()F", false));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, TARGET_CLASS, setting.name(), "F"));
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new FieldInsnNode(
                Opcodes.PUTSTATIC, TARGET_CLASS, setting.validField(), "Z"));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "miss", "()V", false));
        method.instructions.add(new InsnNode(Opcodes.FRETURN));
        method.instructions.add(cached);
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "hit", "()V", false));
        method.instructions.add(new FieldInsnNode(
                Opcodes.GETSTATIC, TARGET_CLASS, setting.name(), "F"));
        method.instructions.add(new InsnNode(Opcodes.FRETURN));
        return method;
    }

    private static InsnList invalidation() {
        InsnList instructions = new InsnList();
        for (Setting setting : SETTINGS) {
            instructions.add(new InsnNode(Opcodes.ICONST_0));
            instructions.add(new FieldInsnNode(
                    Opcodes.PUTSTATIC, TARGET_CLASS, setting.validField(), "Z"));
        }
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "invalidated", "()V", false));
        return instructions;
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

    private static FieldNode uniqueField(ClassNode owner, String name, String descriptor) {
        FieldNode result = null;
        for (FieldNode field : owner.fields) {
            if (name.equals(field.name) && descriptor.equals(field.desc)) {
                if (result != null) return null;
                result = field;
            }
        }
        return result;
    }

    private static boolean hasField(ClassNode owner, String name) {
        return owner.fields.stream().anyMatch(field -> name.equals(field.name));
    }

    private static int calls(MethodNode method, String owner, String name, String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                count++;
            }
        }
        return count;
    }

    private static int fieldAccesses(
            MethodNode method, int opcode, String name, String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && opcode == field.getOpcode() && TARGET_CLASS.equals(field.owner)
                    && name.equals(field.name) && descriptor.equals(field.desc)) {
                count++;
            }
        }
        return count;
    }

    private static int returns(MethodNode method, int opcode) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) count++;
        }
        return count;
    }

    private record Setting(String name) {
        String originalName() {
            return "preflight$original$" + name;
        }

        String validField() {
            return "preflight$" + name + "$valid";
        }
    }
}
