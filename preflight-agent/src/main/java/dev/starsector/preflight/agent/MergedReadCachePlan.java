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
 * Puts the general merged-read cache in front of {@code LoadingUtils}' two merged readers.
 *
 * <p>The rewrite is {@link MergedReadProbePlan}'s, with a cache behind it instead of a stopwatch:
 * rename the original, then give its name to a fresh method that pushes the arguments and an
 * {@code LDC} handle for the renamed original and calls the runtime. Vanilla keeps its own code,
 * frames and exception table, one rename away, and every delegating overload inside
 * {@code LoadingUtils} resolves the new entry by name -- which is what makes a cache installed here
 * cover the callers nobody enumerated.
 *
 * <p>This plan and the probe cannot both weave the same pair, and neither should have to know which
 * ran: each declines a class that already carries the other's renamed methods, so whichever the
 * dispatch reaches first is the one that installs. The runtime the cache calls reports the same
 * per-path timing the probe did, so choosing the cache does not cost the measurement.
 */
final class MergedReadCachePlan {
    static final String TARGET_CLASS = MergedReadProbePlan.TARGET_CLASS;
    static final String MERGED_METHOD = MergedReadProbePlan.MERGED_METHOD;
    static final String CSV_DESCRIPTOR = MergedReadProbePlan.CSV_DESCRIPTOR;
    static final String JSON_DESCRIPTOR = MergedReadProbePlan.JSON_DESCRIPTOR;
    static final String CSV_VANILLA_METHOD = "preflightUncachedMergedCsv";
    static final String JSON_VANILLA_METHOD = "preflightUncachedMergedJson";

    private static final String RUNTIME = "dev/starsector/preflight/agent/MergedReadCacheRuntime";
    private static final String CSV_RUNTIME_DESCRIPTOR =
            "(Ljava/lang/Object;Ljava/lang/String;ZZLjava/lang/invoke/MethodHandle;)Ljava/lang/Object;";
    private static final String JSON_RUNTIME_DESCRIPTOR =
            "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/invoke/MethodHandle;)Ljava/lang/Object;";
    private static final String[] THROWN = {"java/io/IOException", "org/json/JSONException"};

    private MergedReadCachePlan() {
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
                || !signature.hasMethod(MERGED_METHOD, CSV_DESCRIPTOR)
                || !signature.hasMethod(MERGED_METHOD, JSON_DESCRIPTOR)) {
            return false;
        }
        // A class file older than 51 cannot carry a MethodHandle constant, and raising its version to
        // make room would change how it is verified. Decline instead.
        if ((owner.version & 0xFFFF) < Opcodes.V1_7) {
            return false;
        }

        MethodNode csv = null;
        MethodNode json = null;
        for (MethodNode method : owner.methods) {
            if (CSV_VANILLA_METHOD.equals(method.name) || JSON_VANILLA_METHOD.equals(method.name)
                    || MergedReadProbePlan.CSV_VANILLA_METHOD.equals(method.name)
                    || MergedReadProbePlan.JSON_VANILLA_METHOD.equals(method.name)) {
                // Already rewritten, by this plan or by the probe.
                return false;
            }
            if (!MERGED_METHOD.equals(method.name)) {
                continue;
            }
            if (CSV_DESCRIPTOR.equals(method.desc)) {
                if (csv != null) {
                    return false;
                }
                csv = method;
            } else if (JSON_DESCRIPTOR.equals(method.desc)) {
                if (json != null) {
                    return false;
                }
                json = method;
            }
        }
        if (csv == null || json == null
                || (csv.access & Opcodes.ACC_STATIC) == 0 || (json.access & Opcodes.ACC_STATIC) == 0) {
            return false;
        }

        rename(csv, CSV_VANILLA_METHOD);
        rename(json, JSON_VANILLA_METHOD);
        owner.methods.add(csvEntry());
        owner.methods.add(jsonEntry());
        return true;
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
