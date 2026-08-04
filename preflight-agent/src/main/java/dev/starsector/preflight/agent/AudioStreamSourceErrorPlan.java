package dev.starsector.preflight.agent;

import java.util.ArrayList;
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
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Makes the streaming-source constructor check the error from {@code alGenSources}, not before it. */
final class AudioStreamSourceErrorPlan {
    static final String TARGET_CLASS = "sound/oo0O";
    static final String ORIGINAL_SHA256 =
            "a4f2d2fdc944c94d2e942fb91558b713d0be6a49ea2c3c75dac41a46c380a510";
    static final String CONSTRUCTOR = "<init>";
    static final String CONSTRUCTOR_DESCRIPTOR = "(Ljava/util/List;Ljava/lang/String;)V";

    private static final String AL10 = "org/lwjgl/openal/AL10";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/AudioStreamSourceErrorRuntime";
    private static final String TRANSITIONS =
            "dev/starsector/preflight/agent/AudioMusicTransitionRuntime";
    private static final String ID_FIELD = "Object";
    private static final String SOURCE_FIELD = "o00000";
    private static final String FADE_SCALE_FIELD = "Õ00000";
    private static final String FADE_OUT_FIELD = "ô00000";

    private AudioStreamSourceErrorPlan() {
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
        MethodNode fadeIn = unique(owner, "class", "(I)V");
        MethodNode fadeOut = unique(owner, "Ô00000", "(I)V");
        MethodNode fadePause = unique(owner, "new", "(I)V");
        MethodNode fadeStop = unique(owner, "Ó00000", "(I)V");
        MethodNode cleanup = unique(owner, "ö00000", "()V");
        if (constructor == null || fadeIn == null || fadeOut == null || fadePause == null
                || fadeStop == null || cleanup == null
                || field(owner, ID_FIELD, "Ljava/lang/String;") == null
                || field(owner, SOURCE_FIELD, "I") == null
                || field(owner, FADE_SCALE_FIELD, "F") == null
                || field(owner, FADE_OUT_FIELD, "I") == null) return null;

        List<MethodInsnNode> errorCalls = calls(constructor, AL10, "alGetError", "()I");
        List<MethodInsnNode> generationCalls = calls(
                constructor, AL10, "alGenSources", "(Ljava/nio/IntBuffer;)V");
        if (errorCalls.size() != 1 || generationCalls.size() != 1) return null;
        MethodInsnNode oldError = errorCalls.get(0);
        MethodInsnNode generate = generationCalls.get(0);
        AbstractInsnNode storeNode = nextCode(oldError);
        if (!(storeNode instanceof VarInsnNode store)
                || store.getOpcode() != Opcodes.ISTORE
                || indexOf(constructor, oldError) >= indexOf(constructor, generate)
                || varAccesses(constructor, Opcodes.ILOAD, store.var) < 2) {
            return null;
        }

        InsnList clearTelemetry = new InsnList();
        clearTelemetry.add(new InsnNode(Opcodes.DUP));
        clearTelemetry.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "beforeGeneration", "(I)V", false));
        constructor.instructions.insert(oldError, clearTelemetry);
        constructor.instructions.set(store, new InsnNode(Opcodes.POP));

        InsnList actualError = new InsnList();
        actualError.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, AL10, "alGetError", "()I", false));
        actualError.add(new InsnNode(Opcodes.DUP));
        actualError.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "afterGeneration", "(I)V", false));
        actualError.add(new VarInsnNode(Opcodes.ISTORE, store.var));
        constructor.instructions.insert(generate, actualError);

        List<AbstractInsnNode> returns = instructions(constructor, Opcodes.RETURN);
        if (returns.size() != 1) return null;
        InsnList created = new InsnList();
        created.add(new VarInsnNode(Opcodes.ALOAD, 0));
        created.add(new FieldInsnNode(
                Opcodes.GETFIELD, TARGET_CLASS, ID_FIELD, "Ljava/lang/String;"));
        created.add(new VarInsnNode(Opcodes.ALOAD, 0));
        created.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET_CLASS, SOURCE_FIELD, "I"));
        created.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, TRANSITIONS, "created", "(Ljava/lang/String;I)V", false));
        constructor.instructions.insertBefore(returns.get(0), created);
        probeFade(fadeIn, "fade-in");
        probeFade(fadeOut, "fade-out");
        probeFade(fadePause, "fade-pause");
        probeFade(fadeStop, "fade-stop");
        InsnList cleaned = new InsnList();
        cleaned.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleaned.add(new FieldInsnNode(
                Opcodes.GETFIELD, TARGET_CLASS, ID_FIELD, "Ljava/lang/String;"));
        cleaned.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleaned.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET_CLASS, FADE_SCALE_FIELD, "F"));
        cleaned.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleaned.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET_CLASS, FADE_OUT_FIELD, "I"));
        cleaned.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleaned.add(new FieldInsnNode(Opcodes.GETFIELD, TARGET_CLASS, SOURCE_FIELD, "I"));
        cleaned.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                TRANSITIONS,
                "cleanup",
                "(Ljava/lang/String;FII)V",
                false));
        cleanup.instructions.insert(cleaned);

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        AudioStreamSourceErrorRuntime.installed();
        AudioMusicTransitionRuntime.installed();
        return writer.toByteArray();
    }

    private static void probeFade(MethodNode method, String kind) {
        InsnList probe = new InsnList();
        probe.add(new LdcInsnNode(kind));
        probe.add(new VarInsnNode(Opcodes.ALOAD, 0));
        probe.add(new FieldInsnNode(
                Opcodes.GETFIELD, TARGET_CLASS, ID_FIELD, "Ljava/lang/String;"));
        probe.add(new VarInsnNode(Opcodes.ILOAD, 1));
        probe.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                TRANSITIONS,
                "fade",
                "(Ljava/lang/String;Ljava/lang/String;I)V",
                false));
        method.instructions.insert(probe);
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

    private static List<MethodInsnNode> calls(
            MethodNode method, String owner, String name, String descriptor) {
        List<MethodInsnNode> found = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                found.add(call);
            }
        }
        return found;
    }

    private static FieldNode field(ClassNode owner, String name, String descriptor) {
        return owner.fields.stream()
                .filter(candidate -> name.equals(candidate.name) && descriptor.equals(candidate.desc))
                .findFirst().orElse(null);
    }

    private static List<AbstractInsnNode> instructions(MethodNode method, int opcode) {
        List<AbstractInsnNode> found = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == opcode) found.add(instruction);
        }
        return found;
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction.getNext();
        while (current != null && current.getOpcode() < 0) current = current.getNext();
        return current;
    }

    private static int indexOf(MethodNode method, AbstractInsnNode wanted) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction == wanted) return index;
            index++;
        }
        return -1;
    }

    private static int varAccesses(MethodNode method, int opcode, int variable) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof VarInsnNode access
                    && access.getOpcode() == opcode && access.var == variable) count++;
        }
        return count;
    }
}
