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
 * Times the two methods every merged data read in the game funnels through.
 *
 * <p>{@code LoadingUtils} has ten public read entry points, but only two of them do the merging:
 * {@code super(List, String, boolean, boolean)} builds a {@code JSONArray} from one CSV per enabled
 * root, and {@code super(String, Set)} builds a {@code JSONObject} by overlaying one JSON file per
 * enabled root. Every other CSV or merged-JSON overload delegates into one of those two, so wrapping
 * the pair counts each merged read exactly once no matter which overload the caller used.
 *
 * <p>Why this exists at all: the five caches that removed nine seconds from {@code SpecStore} each
 * pinned one loader and cached that loader's own merged reads. That answers "how expensive is the
 * hull loader" and cannot answer "how much merged reading is left anywhere", which is the question
 * that decides whether the next cache should be a sixth pinned loader or one general one. The seven
 * largest untouched spec loaders -- factions, ship systems, descriptions, hull mods, missions, sound
 * sets, and the two spreadsheet loaders -- all read through here, and so does every mod callback.
 *
 * <p>The rewrite is the {@link LoadJsonMemoPlan} shape: rename the original, give its name to a
 * fresh method that hands the arguments and a {@code MethodHandle} for the original to the runtime.
 * Vanilla stays exactly as compiled, one rename away, with its own code, frames, and exception table
 * untouched. Callers inside {@code LoadingUtils} resolve by name and so reach the new entry too,
 * which is what keeps the delegating overloads counted rather than counted twice.
 */
final class MergedReadProbePlan {
    static final String TARGET_CLASS = "com/fs/starfarer/loading/LoadingUtils";
    /** Both merged readers are named {@code super}; only the descriptor tells them apart. */
    static final String MERGED_METHOD = "super";
    static final String CSV_DESCRIPTOR = "(Ljava/util/List;Ljava/lang/String;ZZ)Lorg/json/JSONArray;";
    static final String JSON_DESCRIPTOR = "(Ljava/lang/String;Ljava/util/Set;)Lorg/json/JSONObject;";
    static final String CSV_VANILLA_METHOD = "preflightVanillaMergedCsv";
    static final String JSON_VANILLA_METHOD = "preflightVanillaMergedJson";

    private static final String RUNTIME = "dev/starsector/preflight/agent/StartupPhaseRuntime";
    private static final String CSV_RUNTIME_DESCRIPTOR =
            "(Ljava/lang/Object;Ljava/lang/String;ZZLjava/lang/invoke/MethodHandle;)Ljava/lang/Object;";
    private static final String JSON_RUNTIME_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/invoke/MethodHandle;)Ljava/lang/Object;";
    private static final String[] THROWN = {"java/io/IOException", "org/json/JSONException"};

    private MergedReadProbePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !signature.hasMethod(MERGED_METHOD, CSV_DESCRIPTOR)
                || !signature.hasMethod(MERGED_METHOD, JSON_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        // A class file older than 51 cannot carry a MethodHandle constant, and raising its version
        // to make room would change how it is verified. Decline instead.
        if ((owner.version & 0xFFFF) < Opcodes.V1_7) {
            return null;
        }

        MethodNode csv = null;
        MethodNode json = null;
        for (MethodNode method : owner.methods) {
            if (CSV_VANILLA_METHOD.equals(method.name) || JSON_VANILLA_METHOD.equals(method.name)) {
                // Already rewritten.
                return null;
            }
            if (!MERGED_METHOD.equals(method.name)) {
                continue;
            }
            if (CSV_DESCRIPTOR.equals(method.desc)) {
                if (csv != null) {
                    return null;
                }
                csv = method;
            } else if (JSON_DESCRIPTOR.equals(method.desc)) {
                if (json != null) {
                    return null;
                }
                json = method;
            }
        }
        if (csv == null || json == null
                || (csv.access & Opcodes.ACC_STATIC) == 0 || (json.access & Opcodes.ACC_STATIC) == 0) {
            return null;
        }

        rename(csv, CSV_VANILLA_METHOD);
        rename(json, JSON_VANILLA_METHOD);
        owner.methods.add(csvEntry());
        owner.methods.add(jsonEntry());

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static void rename(MethodNode method, String name) {
        method.name = name;
        method.access = (method.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED))
                | Opcodes.ACC_PUBLIC;
    }

    private static MethodNode csvEntry() {
        MethodNode entry = new MethodNode(Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, MERGED_METHOD, CSV_DESCRIPTOR, null, THROWN);
        entry.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        entry.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        entry.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        entry.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
        entry.instructions.add(new LdcInsnNode(new Handle(
                Opcodes.H_INVOKESTATIC, TARGET_CLASS, CSV_VANILLA_METHOD, CSV_DESCRIPTOR, false)));
        entry.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "mergedCsvRead", CSV_RUNTIME_DESCRIPTOR, false));
        entry.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "org/json/JSONArray"));
        entry.instructions.add(new InsnNode(Opcodes.ARETURN));
        entry.maxStack = 5;
        entry.maxLocals = 4;
        return entry;
    }

    private static MethodNode jsonEntry() {
        MethodNode entry = new MethodNode(Opcodes.ASM9,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, MERGED_METHOD, JSON_DESCRIPTOR, null, THROWN);
        entry.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        entry.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        entry.instructions.add(new LdcInsnNode(new Handle(
                Opcodes.H_INVOKESTATIC, TARGET_CLASS, JSON_VANILLA_METHOD, JSON_DESCRIPTOR, false)));
        entry.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, "mergedJsonRead", JSON_RUNTIME_DESCRIPTOR, false));
        entry.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "org/json/JSONObject"));
        entry.instructions.add(new InsnNode(Opcodes.ARETURN));
        entry.maxStack = 3;
        entry.maxLocals = 2;
        return entry;
    }
}
