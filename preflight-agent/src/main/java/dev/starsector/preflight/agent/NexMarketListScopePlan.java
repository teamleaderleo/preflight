package dev.starsector.preflight.agent;

import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Scopes core market-list reuse to Nexerelin's exact reviewed economy-info rebuild.
 *
 * <p>The two classes have independent exact source gates. Runtime activation additionally requires
 * both transformed halves, so a missing or drifted target retains original behavior.
 */
final class NexMarketListScopePlan {
    static final String NEX_CLASS = NexEconomyInfoTimePlan.TARGET_CLASS;
    static final String NEX_SHA256 = NexEconomyInfoTimePlan.ORIGINAL_SHA256;
    static final String NEX_SOURCE_SHA256 = NexEconomyInfoTimePlan.SOURCE_SHA256;
    static final String NEX_METHOD = NexEconomyInfoTimePlan.METHOD;
    static final String NEX_DESCRIPTOR = NexEconomyInfoTimePlan.DESCRIPTOR;

    static final String CORE_CLASS =
            "com/fs/starfarer/campaign/econ/reach/CommodityMarketData";
    static final String CORE_SHA256 =
            "463fd931f4d24fdf56e902400aef898af6d6d56a3d92a958ddf77f5eb91d9f96";
    static final String CORE_SOURCE_SHA256 =
            "a0f8fa3cf4f551eec188ff6dc4d3702ad38b760ff8a568e6c49675fe4665f149";
    static final String CORE_METHOD = "getMarkets";
    static final String CORE_DESCRIPTOR = "()Ljava/util/List;";

    private static final String NEX_ORIGINAL = "preflight$original$collectEconomicData";
    private static final String CORE_ORIGINAL = "preflight$original$getMarkets";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/NexMarketListScopeRuntime";

    private NexMarketListScopePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] currentBytes) {
        if (signature.majorVersion() != 61) return null;
        if (NEX_CLASS.equals(signature.internalName()) && NEX_SHA256.equals(signature.sha256())) {
            return transformNex(currentBytes);
        }
        if (CORE_CLASS.equals(signature.internalName()) && CORE_SHA256.equals(signature.sha256())) {
            return transformCore(currentBytes);
        }
        return null;
    }

    private static byte[] transformNex(byte[] currentBytes) {
        ClassNode owner = read(currentBytes);
        MethodNode original = unique(owner, NEX_METHOD, NEX_DESCRIPTOR);
        if (original == null || has(owner, NEX_ORIGINAL, NEX_DESCRIPTOR)) return null;

        int access = original.access;
        original.name = NEX_ORIGINAL;
        original.access = privateSynthetic(access);
        owner.methods.add(nexWrapper(access, original.signature, original.exceptions));

        byte[] transformed = write(owner);
        NexMarketListScopeRuntime.installedNex();
        return transformed;
    }

    private static byte[] transformCore(byte[] currentBytes) {
        ClassNode owner = read(currentBytes);
        MethodNode original = unique(owner, CORE_METHOD, CORE_DESCRIPTOR);
        if (original == null || has(owner, CORE_ORIGINAL, CORE_DESCRIPTOR)) return null;

        int access = original.access;
        original.name = CORE_ORIGINAL;
        original.access = privateSynthetic(access);
        owner.methods.add(coreWrapper(access, original.signature, original.exceptions));

        byte[] transformed = write(owner);
        NexMarketListScopeRuntime.installedCore();
        return transformed;
    }

    private static MethodNode nexWrapper(int access, String signature, List<String> exceptions) {
        MethodNode method = new MethodNode(
                Opcodes.ASM9,
                access,
                NEX_METHOD,
                NEX_DESCRIPTOR,
                signature,
                exceptions == null ? null : exceptions.toArray(String[]::new));
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode failure = new LabelNode();

        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "beginScope", "()V", false));
        method.instructions.add(start);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, NEX_CLASS, NEX_ORIGINAL, NEX_DESCRIPTOR, false));
        method.instructions.add(end);
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "endScope", "()V", false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));

        method.instructions.add(failure);
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 2));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "endScope", "()V", false));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, failure, null));
        return method;
    }

    private static MethodNode coreWrapper(int access, String signature, List<String> exceptions) {
        MethodNode method = new MethodNode(
                Opcodes.ASM9,
                access,
                CORE_METHOD,
                CORE_DESCRIPTOR,
                signature,
                exceptions == null ? null : exceptions.toArray(String[]::new));
        LabelNode original = new LabelNode();

        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "inScope", "()Z", false));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, original));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                RUNTIME,
                "reuse",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new JumpInsnNode(Opcodes.IFNULL, original));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/List"));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));

        method.instructions.add(original);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, CORE_CLASS, CORE_ORIGINAL, CORE_DESCRIPTOR, false));
        method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "inScope", "()Z", false));
        LabelNode returnOriginal = new LabelNode();
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, returnOriginal));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                RUNTIME,
                "observe",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                false));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/List"));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));

        method.instructions.add(returnOriginal);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        return method;
    }

    private static int privateSynthetic(int access) {
        return (access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
                | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static byte[] write(ClassNode owner) {
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode unique(ClassNode owner, String name, String descriptor) {
        MethodNode result = null;
        for (MethodNode method : owner.methods) {
            if (!name.equals(method.name) || !descriptor.equals(method.desc)) continue;
            if (result != null) return null;
            result = method;
        }
        return result;
    }

    private static boolean has(ClassNode owner, String name, String descriptor) {
        return unique(owner, name, descriptor) != null;
    }
}
