package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Preserves RAT's false fallback without constructing and catching JSON exceptions. */
final class RatAbyssFactionFlagPlan {
    static final String PLAN_ID = "rat-3.3.1-abyss-faction-optional-flag-v1";
    static final String TARGET_CLASS =
            "assortment_of_things/abyss/scripts/ForceNegAbyssalRep";
    static final String ORIGINAL_SHA256 =
            "0c84737fb3c365d195e10df213f08d8184b645e03d2d75a7447a2b6286aaee5f";
    static final String ADVANCE_METHOD = "advance";
    static final String ADVANCE_DESCRIPTOR = "(F)V";
    private static final String JSON_OBJECT = "org/json/JSONObject";
    private static final String BOOLEAN_DESCRIPTOR = "(Ljava/lang/String;)Z";
    private static final AtomicLong INSTALLED_TARGETS = new AtomicLong();

    private RatAbyssFactionFlagPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(ADVANCE_METHOD, ADVANCE_DESCRIPTOR)) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        MethodNode advance = unique(owner, ADVANCE_METHOD, ADVANCE_DESCRIPTOR);
        if (advance == null
                || calls(owner, "getBoolean") != 1
                || calls(advance, "getBoolean") != 1
                || calls(owner, "optBoolean") != 0) {
            return null;
        }

        for (AbstractInsnNode instruction : advance.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && JSON_OBJECT.equals(call.owner)
                    && "getBoolean".equals(call.name)
                    && BOOLEAN_DESCRIPTOR.equals(call.desc)) {
                advance.instructions.set(call, new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        JSON_OBJECT,
                        "optBoolean",
                        BOOLEAN_DESCRIPTOR,
                        false));
            }
        }

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        installed();
        return writer.toByteArray();
    }

    static void installed() {
        INSTALLED_TARGETS.incrementAndGet();
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("planId", PLAN_ID);
        values.put("installedTargets", INSTALLED_TARGETS.get());
        values.put("strategy", "replace-throwing-required-boolean-with-false-default-optional-boolean");
        return values;
    }

    static void reset() {
        INSTALLED_TARGETS.set(0L);
    }

    private static MethodNode unique(ClassNode owner, String name, String descriptor) {
        MethodNode result = null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (result != null) return null;
                result = method;
            }
        }
        return result;
    }

    private static int calls(ClassNode owner, String name) {
        int count = 0;
        for (MethodNode method : owner.methods) {
            count += calls(method, name);
        }
        return count;
    }

    private static int calls(MethodNode method, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && JSON_OBJECT.equals(call.owner)
                    && name.equals(call.name)
                    && BOOLEAN_DESCRIPTOR.equals(call.desc)) {
                count++;
            }
        }
        return count;
    }
}
