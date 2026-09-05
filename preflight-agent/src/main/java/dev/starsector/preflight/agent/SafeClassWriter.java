package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassWriter;

/**
 * A {@link ClassWriter} that never loads an application class to answer a frame question.
 *
 * <p>{@code COMPUTE_FRAMES} makes ASM ask for the common supertype of two types, and the default
 * implementation answers by loading them. Inside a {@code ClassFileTransformer} that is a way to
 * deadlock or to fail on a class the loader cannot yet resolve — the agent is running while the
 * class being transformed is still being defined. With explicit opt-in, bootstrap types can be resolved without that
 * application-loader recursion. Their common ancestor must be preserved: merging InputStream
 * and BufferedInputStream to Object makes a subsequent InputStream call unverifiable.
 * Unknown application types still use Object; callers must not assume that this fallback proves
 * every transformed method verifiable.
 *
 * <p>Existing constructors retain the historical Object merge to preserve pinned replacement
 * hashes. Only prototype final-composition writers opt into bootstrap hierarchy resolution.
 */
final class SafeClassWriter extends ClassWriter {
    private final boolean bootstrapHierarchy;

    SafeClassWriter(int flags) {
        this(flags, false);
    }

    SafeClassWriter(int flags, boolean bootstrapHierarchy) {
        super(flags);
        this.bootstrapHierarchy = bootstrapHierarchy;
    }

    SafeClassWriter(org.objectweb.asm.ClassReader reader, int flags) {
        super(reader, flags);
        this.bootstrapHierarchy = false;
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        if (type1.equals(type2)) {
            return type1;
        }
        if (!bootstrapHierarchy || !type1.startsWith("java/") || !type2.startsWith("java/")) {
            return "java/lang/Object";
        }
        try {
            // Explicit null loader: never invoke the agent/game/application class loader.
            Class<?> first = Class.forName(type1.replace('/', '.'), false, null);
            Class<?> second = Class.forName(type2.replace('/', '.'), false, null);
            if (first.isAssignableFrom(second)) return type1;
            if (second.isAssignableFrom(first)) return type2;
            if (first.isInterface() || second.isInterface()) return "java/lang/Object";
            do {
                first = first.getSuperclass();
            } while (first != null && !first.isAssignableFrom(second));
            return first == null ? "java/lang/Object" : first.getName().replace('.', '/');
        } catch (ClassNotFoundException unavailable) {
            return "java/lang/Object";
        }
    }
}
