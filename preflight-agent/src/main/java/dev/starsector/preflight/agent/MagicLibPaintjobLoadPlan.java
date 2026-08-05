package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Skips MagicLib's exception-driven loads of optional paintjob JSON proven absent. */
final class MagicLibPaintjobLoadPlan {
    static final String TARGET_CLASS = MagicLibPaintjobNotificationPlan.TARGET_CLASS;
    static final String ORIGINAL_SHA256 = MagicLibPaintjobNotificationPlan.ORIGINAL_SHA256;
    static final String LOAD_METHOD = "loadPaintjobs";
    static final String LOAD_DESCRIPTOR = "()Lkotlin/Pair;";

    private static final String SETTINGS = "com/fs/starfarer/api/SettingsAPI";
    private static final String MOD_SPEC = "com/fs/starfarer/api/ModSpecAPI";
    private static final String JSON = "org/json/JSONObject";
    private static final String OPTIONAL = "preflight$optionalPaintjobJson";
    private static final String OPTIONAL_DESCRIPTOR = "(L" + SETTINGS
            + ";Ljava/lang/String;Ljava/lang/String;L" + MOD_SPEC + ";)L" + JSON + ";";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/MagicLibPaintjobLoadRuntime";

    private MagicLibPaintjobLoadPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] bytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(LOAD_METHOD, LOAD_DESCRIPTOR)) return null;

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode load = unique(owner, LOAD_METHOD, LOAD_DESCRIPTOR);
        if (load == null || (load.access & Opcodes.ACC_STATIC) == 0
                || unique(owner, OPTIONAL, OPTIONAL_DESCRIPTOR) != null) return null;

        MethodInsnNode jsonLoad = null;
        for (AbstractInsnNode instruction : load.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && SETTINGS.equals(call.owner)
                    && "loadJSON".equals(call.name)
                    && "(Ljava/lang/String;Ljava/lang/String;)Lorg/json/JSONObject;"
                            .equals(call.desc)) {
                if (jsonLoad != null) return null;
                jsonLoad = call;
            }
        }
        if (jsonLoad == null || !loadsReviewedModLocal(jsonLoad)) return null;

        // This exact Kotlin body keeps the outer-loop ModSpecAPI in local 3.
        load.instructions.insertBefore(jsonLoad, new VarInsnNode(Opcodes.ALOAD, 3));
        jsonLoad.setOpcode(Opcodes.INVOKESTATIC);
        jsonLoad.owner = TARGET_CLASS;
        jsonLoad.name = OPTIONAL;
        jsonLoad.desc = OPTIONAL_DESCRIPTOR;
        jsonLoad.itf = false;
        owner.methods.add(optionalHelper());

        ClassWriter writer = new SafeClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        MagicLibPaintjobLoadRuntime.installed();
        return writer.toByteArray();
    }

    private static boolean loadsReviewedModLocal(MethodInsnNode jsonLoad) {
        AbstractInsnNode getId = previousCode(jsonLoad);
        AbstractInsnNode mod = previousCode(getId);
        return getId instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKEINTERFACE
                && MOD_SPEC.equals(call.owner)
                && "getId".equals(call.name)
                && "()Ljava/lang/String;".equals(call.desc)
                && mod instanceof VarInsnNode local
                && local.getOpcode() == Opcodes.ALOAD && local.var == 3;
    }

    /** Keeps the original invokeinterface intact on every path not proven absent. */
    private static MethodNode optionalHelper() {
        MethodNode method = new MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                OPTIONAL, OPTIONAL_DESCRIPTOR, null, null);
        LabelNode vanilla = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "knownMissing",
                "(Ljava/lang/String;Ljava/lang/Object;)Z", false));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, vanilla));
        method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.instructions.add(vanilla);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, SETTINGS, "loadJSON",
                "(Ljava/lang/String;Ljava/lang/String;)L" + JSON + ";", true));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        return method;
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
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
}
