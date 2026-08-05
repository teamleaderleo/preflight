package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassWriter;

/**
 * A {@link ClassWriter} that never loads a class to answer a frame question.
 *
 * <p>{@code COMPUTE_FRAMES} makes ASM ask for the common supertype of two types, and the default
 * implementation answers by loading them. Inside a {@code ClassFileTransformer} that is a way to
 * deadlock or to fail on a class the loader cannot yet resolve — the agent is running while the
 * class being transformed is still being defined. Answering {@code java/lang/Object} is always
 * verifiable, at the cost of a slightly wider frame than strictly necessary.
 *
 * <p>Shared by every plan rather than nested in one of them, so that a transformation added later
 * cannot quietly get the default behaviour instead.
 */
final class SafeClassWriter extends ClassWriter {
    SafeClassWriter(int flags) {
        super(flags);
    }

    SafeClassWriter(org.objectweb.asm.ClassReader reader, int flags) {
        super(reader, flags);
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        return type1.equals(type2) ? type1 : "java/lang/Object";
    }
}
