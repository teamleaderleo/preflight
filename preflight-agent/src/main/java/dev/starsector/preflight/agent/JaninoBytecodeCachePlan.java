package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Wraps Janino's complete-map compiler result without replacing its class-definition path. */
final class JaninoBytecodeCachePlan {
    static final String TARGET_CLASS = "org/codehaus/janino/JavaSourceClassLoader";
    static final String GENERATE_METHOD = "generateBytecodes";
    static final String GENERATE_DESCRIPTOR = "(Ljava/lang/String;)Ljava/util/Map;";
    static final String ORIGINAL_METHOD = "preflightGenerateBytecodesOriginal";

    private static final String RUNTIME =
            "dev/starsector/preflight/agent/JaninoBytecodeCacheRuntime";
    private static final String RUNTIME_DESCRIPTOR =
            "(Ljava/lang/Object;Ljava/lang/String;)Ljava/util/Map;";
    private static final String[] THROWN = {"java/lang/ClassNotFoundException"};

    private JaninoBytecodeCachePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !signature.hasMethod(GENERATE_METHOD, GENERATE_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode generate = null;
        for (MethodNode method : owner.methods) {
            if (ORIGINAL_METHOD.equals(method.name)) {
                return null;
            }
            if (GENERATE_METHOD.equals(method.name) && GENERATE_DESCRIPTOR.equals(method.desc)) {
                if (generate != null) {
                    return null;
                }
                generate = method;
            }
        }
        if (generate == null || (generate.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) {
            return null;
        }

        generate.name = ORIGINAL_METHOD;
        owner.methods.add(entry(generate.access));
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode entry(int originalAccess) {
        int access = originalAccess & ~(Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT);
        MethodNode entry = new MethodNode(
                Opcodes.ASM9, access, GENERATE_METHOD, GENERATE_DESCRIPTOR, null, THROWN);
        entry.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        entry.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        entry.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "generate", RUNTIME_DESCRIPTOR, false));
        entry.instructions.add(new InsnNode(Opcodes.ARETURN));
        entry.maxStack = 2;
        entry.maxLocals = 2;
        return entry;
    }
}
