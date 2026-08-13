package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Answers the game's resource resolver from memory, at both levels the walk spends time in.
 *
 * <p>The resolver is where a launch spends its filesystem time: it walks one root per enabled mod
 * looking for each path and stops at the first hit, so the cost is (lookups x mods) and it grows
 * for exactly the profiles that need it not to. Every root in that walk is tested with the same
 * one-instruction idiom -- build a {@code File}, ask {@code exists()} -- and replacing that single
 * call is enough to serve the first-match walk and the merged loader's collect-every-source walk
 * together, along with every caller of both, game and mod alike.
 *
 * <p>Two rewrites, because removing the syscalls left the walk. Every {@code File.exists()} in the
 * class is redirected, which serves the first-match walk and the merged loader's collect-every-
 * source walk together, along with every caller of both, game and mod alike. But answering a probe
 * from memory does not stop the walk from building a joined path and a {@code File} per root first,
 * and on the measured install that construction is 2.4 s against 0.2 s for the lookup it feeds. So
 * the per-root open is also replaced, one level up, where the root is still a root and the path is
 * still relative and neither has to be joined to answer.
 *
 * <p>The substitution is exactly stack-neutral: {@code invokevirtual File.exists()Z} consumes one
 * reference and produces one int, and so does {@code invokestatic exists(File)Z}. No locals, no
 * branches, no frame recomputation, and the method keeps the stack map vanilla wrote for it.
 *
 * <p>The per-root open cannot be a substitution -- it is the whole method -- so the original is
 * renamed rather than deleted and a fresh method takes its name, hands the runtime a
 * {@code MethodHandle} to the renamed original, and lets it decide. Vanilla stays exactly as it was
 * compiled, one rename away, and the runtime returns early only to say a file is not there.
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
    /** {@code Ô00000}, the per-root open both walks funnel through. */
    static final String OPEN_METHOD = "Ô" + "00000";
    static final String OPEN_DESCRIPTOR =
            "(Ljava/lang/String;Lcom/fs/util/C$Oo;)Ljava/io/InputStream;";
    static final String VANILLA_OPEN_METHOD = "preflightVanillaOpenFromRoot";
    static final int EXPECTED_CALL_SITES = 11;

    private static final String FILE_OWNER = "java/io/File";
    private static final String EXISTS_NAME = "exists";
    private static final String EXISTS_DESCRIPTOR = "()Z";
    private static final String RUNTIME = "dev/starsector/preflight/agent/ResourceProbeRuntime";
    private static final String RUNTIME_DESCRIPTOR = "(Ljava/io/File;)Z";
    private static final String OPEN_RUNTIME_DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/String;"
            + "Ljava/lang/Object;Ljava/lang/invoke/MethodHandle;)Ljava/io/InputStream;";

    private ResourceProbePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        if (!apply(signature, owner)) return null;
        return write(owner);
    }

    static boolean apply(ClassSignature signature, ClassNode owner) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !signature.hasMethod(RESOLVE_METHOD, RESOLVE_DESCRIPTOR)
                || !signature.hasMethod(OPEN_METHOD, OPEN_DESCRIPTOR)) {
            return false;
        }
        // A class file older than 51 cannot carry a MethodHandle constant, and raising its version
        // to make room would change how it is verified. Decline instead.
        if ((owner.version & 0xFFFF) < Opcodes.V1_7) {
            return false;
        }

        List<MethodInsnNode> sites = new ArrayList<>();
        MethodNode open = null;
        for (MethodNode method : owner.methods) {
            if (callsRuntime(method) || VANILLA_OPEN_METHOD.equals(method.name)) {
                // Already rewritten. Doing it twice would be harmless but it means something
                // upstream is confused about what it is holding, so decline.
                return false;
            }
            if (OPEN_METHOD.equals(method.name) && OPEN_DESCRIPTOR.equals(method.desc)) {
                if (open != null) {
                    return false;
                }
                open = method;
            }
            sites.addAll(existsCalls(method));
        }
        if (sites.size() != EXPECTED_CALL_SITES || open == null
                || (open.access & Opcodes.ACC_STATIC) != 0) {
            return false;
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
        owner.methods.add(wrap(open));

        return true;
    }

    static byte[] write(ClassNode owner) {
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Renames the per-root open and returns the method that takes its place.
     *
     * <p>The entry keeps {@code synchronized}: every caller reaches this from inside another
     * synchronized method on the same resolver, so it is already serialised, and dropping the
     * monitor here would be a change to something other than speed.
     */
    private static MethodNode wrap(MethodNode open) {
        open.name = VANILLA_OPEN_METHOD;
        open.access = (open.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED))
                | Opcodes.ACC_PUBLIC;

        MethodNode entry = new MethodNode(Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNCHRONIZED, OPEN_METHOD, OPEN_DESCRIPTOR, null,
                new String[] {"java/io/FileNotFoundException"});
        entry.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        entry.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        entry.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        entry.instructions.add(new LdcInsnNode(new Handle(
                Opcodes.H_INVOKEVIRTUAL, TARGET_CLASS, VANILLA_OPEN_METHOD, OPEN_DESCRIPTOR, false)));
        entry.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "open", OPEN_RUNTIME_DESCRIPTOR, false));
        entry.instructions.add(new InsnNode(Opcodes.ARETURN));
        entry.maxStack = 4;
        entry.maxLocals = 3;
        return entry;
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
