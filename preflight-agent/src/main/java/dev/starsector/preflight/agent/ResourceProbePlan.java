package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Routes every {@code File.exists()} inside the game's resource resolver through
 * {@link ResourceProbeRuntime}.
 *
 * <p>The resolver is where a launch spends its filesystem time: it walks one root per enabled mod
 * looking for each path and stops at the first hit, so the cost is (lookups x mods) and it grows
 * for exactly the profiles that need it not to. Every root in that walk is tested with the same
 * one-instruction idiom -- build a {@code File}, ask {@code exists()} -- and replacing that single
 * call is enough to serve the first-match walk and the merged loader's collect-every-source walk
 * together, along with every caller of both, game and mod alike.
 *
 * <p>The substitution is exactly stack-neutral: {@code invokevirtual File.exists()Z} consumes one
 * reference and produces one int, and so does {@code invokestatic exists(File)Z}. No locals, no
 * branches, no frame recomputation, and the method keeps the stack map vanilla wrote for it.
 *
 * <p>The rewrite declines rather than adapts if the class does not look like the reviewed one. The
 * expected call count is pinned for the same reason the class digest is: a build with a different
 * number of probe sites is a build this was not reviewed against.
 */
final class ResourceProbePlan {
    static final String TARGET_CLASS = "com/fs/util/C";
    /** The first-match walk; named here so the target can require it before installing. */
    static final String RESOLVE_METHOD = "Object";
    static final String RESOLVE_DESCRIPTOR = "(Ljava/lang/String;Z)Ljava/io/InputStream;";
    static final int EXPECTED_CALL_SITES = 11;

    private static final String FILE_OWNER = "java/io/File";
    private static final String EXISTS_NAME = "exists";
    private static final String EXISTS_DESCRIPTOR = "()Z";
    private static final String RUNTIME = "dev/starsector/preflight/agent/ResourceProbeRuntime";
    private static final String RUNTIME_DESCRIPTOR = "(Ljava/io/File;)Z";

    private ResourceProbePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !signature.hasMethod(RESOLVE_METHOD, RESOLVE_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);

        List<MethodInsnNode> sites = new ArrayList<>();
        for (MethodNode method : owner.methods) {
            if (callsRuntime(method)) {
                // Already rewritten. Doing it twice would be harmless but it means something
                // upstream is confused about what it is holding, so decline.
                return null;
            }
            sites.addAll(existsCalls(method));
        }
        if (sites.size() != EXPECTED_CALL_SITES) {
            return null;
        }
        for (MethodInsnNode site : sites) {
            for (MethodNode method : owner.methods) {
                if (method.instructions.contains(site)) {
                    method.instructions.set(site, new MethodInsnNode(
                            Opcodes.INVOKESTATIC, RUNTIME, EXISTS_NAME, RUNTIME_DESCRIPTOR, false));
                    break;
                }
            }
        }

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static List<MethodInsnNode> existsCalls(MethodNode method) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && FILE_OWNER.equals(call.owner)
                    && EXISTS_NAME.equals(call.name)
                    && EXISTS_DESCRIPTOR.equals(call.desc)) {
                result.add(call);
            }
        }
        return result;
    }

    private static boolean callsRuntime(MethodNode method) {
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) {
                return true;
            }
        }
        return false;
    }
}
