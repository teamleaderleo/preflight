package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Puts {@link LoadJsonMemoRuntime} in front of the game's single-file JSON loader.
 *
 * <p>{@code LoadingUtils.ô00000(String)} is the method behind
 * {@code Global.getSettings().loadJSON(path)}, so every mod that reads game data arrives here.
 * Unlike the other memo plans in this package the target is the whole method rather than one call
 * inside it, which means the original has to survive somewhere the replacement can still reach it.
 *
 * <p>So the original is renamed rather than deleted, and a fresh method takes its name and does
 * nothing but hand the path and a {@code MethodHandle} for the renamed original to the runtime.
 * Vanilla stays exactly as it was compiled, one rename away, and the runtime falls back to it on
 * anything it cannot serve. The renamed method keeps its own code, frames and exception table
 * untouched, so nothing about how the loader itself behaves changes.
 */
final class LoadJsonMemoPlan {
    static final String TARGET_CLASS = "com/fs/starfarer/loading/LoadingUtils";
    /** {@code ô00000}, the single-file loader. Spelled by code point so the source stays plain. */
    static final String LOAD_METHOD = "ô" + "00000";
    static final String LOAD_DESCRIPTOR = "(Ljava/lang/String;)Lorg/json/JSONObject;";
    static final String VANILLA_METHOD = "preflightVanillaLoadJson";

    private static final String JSON_OBJECT = "org/json/JSONObject";
    private static final String RUNTIME = "dev/starsector/preflight/agent/LoadJsonMemoRuntime";
    private static final String RUNTIME_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/lang/invoke/MethodHandle;)Ljava/lang/Object;";

    private LoadJsonMemoPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        if (!apply(signature, owner)) {
            return null;
        }
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    static boolean apply(ClassSignature signature, ClassNode owner) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !signature.hasMethod(LOAD_METHOD, LOAD_DESCRIPTOR)) {
            return false;
        }
        // A class file older than 51 cannot carry a MethodHandle constant, and raising its version
        // to make room would change how it is verified. Decline instead.
        if ((owner.version & 0xFFFF) < Opcodes.V1_7) {
            return false;
        }

        MethodNode vanilla = null;
        for (MethodNode method : owner.methods) {
            if (VANILLA_METHOD.equals(method.name)) {
                // Already rewritten.
                return false;
            }
            if (LOAD_METHOD.equals(method.name) && LOAD_DESCRIPTOR.equals(method.desc)) {
                if (vanilla != null) {
                    return false;
                }
                vanilla = method;
            }
        }
        if (vanilla == null || (vanilla.access & Opcodes.ACC_STATIC) == 0) {
            return false;
        }

        vanilla.name = VANILLA_METHOD;
        vanilla.access = (vanilla.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED))
                | Opcodes.ACC_PUBLIC;

        MethodNode entry = new MethodNode(Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, LOAD_METHOD, LOAD_DESCRIPTOR, null,
                new String[] {"java/io/IOException", "org/json/JSONException"});
        entry.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        entry.instructions.add(new LdcInsnNode(new Handle(
                Opcodes.H_INVOKESTATIC, TARGET_CLASS, VANILLA_METHOD, LOAD_DESCRIPTOR, false)));
        entry.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "loadJson", RUNTIME_DESCRIPTOR, false));
        entry.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, JSON_OBJECT));
        entry.instructions.add(new InsnNode(Opcodes.ARETURN));
        entry.maxStack = 2;
        entry.maxLocals = 1;
        owner.methods.add(entry);
        return true;
    }
}
