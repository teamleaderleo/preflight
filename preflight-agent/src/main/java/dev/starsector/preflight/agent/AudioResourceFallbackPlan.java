package dev.starsector.preflight.agent;

import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Repairs three exact sound-store lookups that accidentally resolve paths relative to package sound. */
final class AudioResourceFallbackPlan {
    static final String TARGET_CLASS = "sound/ooOO";
    static final String ORIGINAL_SHA256 =
            "24a7e0107fd1c98168ba935b76f9141fdff6814eff43b819ae960a2a94a40147";
    static final String STRING_DESCRIPTOR = "(Ljava/lang/String;)Lsound/D;";
    static final List<String> METHODS = List.of("Ò00000", "Object", "o00000");

    private static final String CLASS = "java/lang/Class";
    private static final String GET_RESOURCE = "getResourceAsStream";
    private static final String GET_RESOURCE_DESCRIPTOR =
            "(Ljava/lang/String;)Ljava/io/InputStream;";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/AudioResourceFallbackRuntime";
    private static final String RUNTIME_DESCRIPTOR =
            "(Ljava/lang/Class;Ljava/lang/String;)Ljava/io/InputStream;";

    private AudioResourceFallbackPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || METHODS.stream().anyMatch(name -> !signature.hasMethod(name, STRING_DESCRIPTOR))) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        int replacements = 0;
        for (String name : METHODS) {
            MethodNode method = unique(owner, name, STRING_DESCRIPTOR);
            if (method == null || calls(method, RUNTIME, "open") != 0
                    || calls(method, CLASS, GET_RESOURCE) != 1) return null;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && CLASS.equals(call.owner)
                        && GET_RESOURCE.equals(call.name)
                        && GET_RESOURCE_DESCRIPTOR.equals(call.desc)) {
                    method.instructions.set(call, new MethodInsnNode(
                            Opcodes.INVOKESTATIC, RUNTIME, "open", RUNTIME_DESCRIPTOR, false));
                    replacements++;
                }
            }
        }
        if (replacements != METHODS.size()) return null;

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        AudioResourceFallbackRuntime.installed();
        return writer.toByteArray();
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

    private static int calls(MethodNode method, String owner, String name) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) result++;
        }
        return result;
    }
}
