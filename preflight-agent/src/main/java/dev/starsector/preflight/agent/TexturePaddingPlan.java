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

/**
 * Wraps the installed loader's extracted power-of-two fold so it can return the dimension it was
 * given.
 *
 * <p>The shipped method is Slick's {@code get2Fold} — seed at two, double while short — and it sizes
 * the {@code glTexImage2D} allocation. Bypassing it is what turns the 1.86 GiB of padding on the
 * reviewed profile into memory that is never allocated. {@link TexturePaddingRuntime} decides
 * whether that happens; this class only makes the decision reachable.
 *
 * <p>Nothing is patched. The original method is renamed and kept verbatim, and the wrapper installed
 * under the original name delegates to it whenever the runtime declines, so the shipped behaviour
 * remains one branch away at all times.
 */
final class TexturePaddingPlan {
    static final String TARGET_CLASS = "com/fs/graphics/TextureLoader";
    static final String FOLD_METHOD = "o00000";
    static final String FOLD_DESCRIPTOR = "(I)I";

    private static final String ORIGINAL_FOLD = "preflight$original$foldDimension";
    private static final String RUNTIME = "dev/starsector/preflight/agent/TexturePaddingRuntime";

    /**
     * A fold is a handful of instructions. The real one is sixteen; the bound exists so that a
     * method which merely happens to share the name and descriptor cannot be mistaken for it.
     */
    private static final int MAX_FOLD_INSTRUCTIONS = 32;

    private TexturePaddingPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !signature.hasMethod(FOLD_METHOD, FOLD_DESCRIPTOR)) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        if (!TARGET_CLASS.equals(owner.name) || hasMethod(owner, ORIGINAL_FOLD)) {
            return null;
        }

        MethodNode fold = uniqueMethod(owner);
        if (fold == null || !eligible(fold) || !pureIntegerFold(fold)) {
            return null;
        }

        int access = fold.access;
        fold.name = ORIGINAL_FOLD;
        owner.methods.add(wrapper(owner.name, access));

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        // The gate stays shut until this line runs. Everything above can decline -- a class that
        // is not the loader, a fold that is not a pure integer fold, a class already rewritten --
        // and each of those leaves the allocation padded, which makes an unpadded buffer fatal.
        TexturePaddingRuntime.foldBypassInstalled();
        return writer.toByteArray();
    }

    /**
     * {@code unpadded() ? dimension : original(dimension)}.
     *
     * <p>The runtime call comes first so that the ordinary path is a property read and a branch. It
     * runs once per axis per texture, which on this profile is tens of thousands of times.
     */
    private static MethodNode wrapper(String owner, int access) {
        MethodNode wrapper = new MethodNode(Opcodes.ASM9, access, FOLD_METHOD, FOLD_DESCRIPTOR, null, null);
        LabelNode delegate = new LabelNode();

        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "unpadded", "()Z", false));
        wrapper.instructions.add(new JumpInsnNode(Opcodes.IFEQ, delegate));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        wrapper.instructions.add(new InsnNode(Opcodes.IRETURN));

        wrapper.instructions.add(delegate);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        wrapper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        wrapper.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, owner, ORIGINAL_FOLD, FOLD_DESCRIPTOR, false));
        wrapper.instructions.add(new InsnNode(Opcodes.IRETURN));
        return wrapper;
    }

    /**
     * Establishes that the method is a small pure integer computation: a doubling loop with a
     * comparison, touching no field, calling nothing, allocating nothing.
     *
     * <p><b>What this proves and what it does not.</b> It proves that replacing the method's result
     * cannot disturb anything except the value — there is no side effect to lose. It does <em>not</em>
     * prove the method computes {@code get2Fold} specifically; that identity is established by the
     * fixture's independent model and by {@code GpuTextureFootprintTest}. The distinction matters
     * because a method that folded differently would still be safe to bypass, while one that
     * mutated state would not be, and it is the second property that has to be checked here.
     */
    private static boolean pureIntegerFold(MethodNode fold) {
        int instructions = 0;
        boolean doubles = false;
        boolean compares = false;
        for (AbstractInsnNode node = fold.instructions.getFirst(); node != null; node = node.getNext()) {
            int opcode = node.getOpcode();
            if (opcode < 0) {
                continue;
            }
            if (++instructions > MAX_FOLD_INSTRUCTIONS) {
                return false;
            }
            switch (opcode) {
                case Opcodes.ILOAD, Opcodes.ISTORE, Opcodes.IRETURN, Opcodes.GOTO,
                        Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2, Opcodes.ICONST_3,
                        Opcodes.ICONST_4, Opcodes.ICONST_5, Opcodes.BIPUSH, Opcodes.SIPUSH,
                        Opcodes.DUP, Opcodes.POP, Opcodes.IADD, Opcodes.ISUB -> { }
                case Opcodes.IMUL, Opcodes.ISHL -> doubles = true;
                case Opcodes.IF_ICMPLT, Opcodes.IF_ICMPLE, Opcodes.IF_ICMPGT, Opcodes.IF_ICMPGE,
                        Opcodes.IFLT, Opcodes.IFLE, Opcodes.IFGT, Opcodes.IFGE -> compares = true;
                default -> {
                    return false;
                }
            }
        }
        return doubles && compares;
    }

    /** Refuses a synthetic, abstract, native or bridge method, and anything with a body to unwind. */
    private static boolean eligible(MethodNode fold) {
        int forbidden = Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNTHETIC
                | Opcodes.ACC_BRIDGE | Opcodes.ACC_STATIC;
        return (fold.access & forbidden) == 0
                && fold.instructions.size() > 0
                && (fold.tryCatchBlocks == null || fold.tryCatchBlocks.isEmpty());
    }

    /** Null unless exactly one method carries the name and descriptor, so an overload cannot slip in. */
    private static MethodNode uniqueMethod(ClassNode owner) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (FOLD_METHOD.equals(method.name) && FOLD_DESCRIPTOR.equals(method.desc)) {
                if (found != null) {
                    return null;
                }
                found = method;
            }
        }
        return found;
    }

    private static boolean hasMethod(ClassNode owner, String name) {
        return owner.methods.stream().anyMatch(method -> name.equals(method.name));
    }
}
