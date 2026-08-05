package dev.starsector.preflight.agent;

import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Deduplicates the matching URL reads performed by LunaLib and Nexerelin's version checkers. */
final class VersionCheckResponseDedupPlan {
    static final String LUNA_CLASS = "lunalib/backend/ui/versionchecker/VersionChecker";
    static final String LUNA_SHA256 =
            "4015b4f59076d61b0188f69366e2805cbac8292f18911c21f29864719ef17ebc";
    static final String NEX_CLASS = "exerelin/utilities/versionchecker/VersionChecker";
    static final String NEX_SHA256 =
            "156ecf1c1295e67ce51d8a72461747f9b8ac30b4c4c5268082a6893dfe3cfa92";
    static final String REMOTE_METHOD = "getRemoteVersionFile";
    static final String REMOTE_DESCRIPTOR = "(Ljava/lang/String;)Ljava/lang/Object;";
    static final String LATEST_METHOD = "getLatestSSVersion";
    static final String LATEST_DESCRIPTOR = "()Ljava/lang/String;";

    private static final String URL = "java/net/URL";
    private static final String OPEN = "openStream";
    private static final String OPEN_DESCRIPTOR = "()Ljava/io/InputStream;";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/VersionCheckResponseDedupRuntime";
    private static final String RUNTIME_DESCRIPTOR = "(Ljava/net/URL;)Ljava/io/InputStream;";
    private static final Map<String, String> REVIEWED = Map.of(
            LUNA_CLASS, LUNA_SHA256,
            NEX_CLASS, NEX_SHA256);

    private VersionCheckResponseDedupPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!REVIEWED.getOrDefault(signature.internalName(), "").equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(REMOTE_METHOD, REMOTE_DESCRIPTOR)
                || !signature.hasMethod(LATEST_METHOD, LATEST_DESCRIPTOR)) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode remote = unique(owner, REMOTE_METHOD, REMOTE_DESCRIPTOR);
        MethodNode latest = unique(owner, LATEST_METHOD, LATEST_DESCRIPTOR);
        if (remote == null || latest == null
                || calls(remote, URL, OPEN, OPEN_DESCRIPTOR) != 1
                || calls(latest, URL, OPEN, OPEN_DESCRIPTOR) != 1
                || calls(owner, URL, OPEN, OPEN_DESCRIPTOR) != 2
                || calls(owner, RUNTIME, OPEN, RUNTIME_DESCRIPTOR) != 0) {
            return null;
        }

        replace(remote);
        replace(latest);
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        VersionCheckResponseDedupRuntime.installed();
        return writer.toByteArray();
    }

    private static void replace(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && URL.equals(call.owner) && OPEN.equals(call.name)
                    && OPEN_DESCRIPTOR.equals(call.desc)) {
                method.instructions.set(call, new MethodInsnNode(
                        Opcodes.INVOKESTATIC, RUNTIME, OPEN, RUNTIME_DESCRIPTOR, false));
            }
        }
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

    private static int calls(
            ClassNode owner, String callOwner, String name, String descriptor) {
        return owner.methods.stream()
                .mapToInt(method -> calls(method, callOwner, name, descriptor))
                .sum();
    }

    private static int calls(
            MethodNode method, String callOwner, String name, String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && callOwner.equals(call.owner) && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                count++;
            }
        }
        return count;
    }
}
