package dev.starsector.preflight.agent;

import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Fuses exact AI Tweaks multiply-then-add vector expressions into one fresh result. */
final class AiTweaksAffineVectorPlan {
    static final String PLAN_ID = "aitweaks-affine-vector-fusion-v2";
    static final String ENABLED_PROPERTY = "preflight.combat.aiTweaksAffineVectors";
    static final String SOURCE_SHA256 =
            "9f6179bcd2df2e3ce8cea2da79051c9f1be3c9b71712c6c28d7568b777ecf5b2";
    static final String SOURCE_FILE = "aitweaks-core.jar";
    static final String LOADER = "com/genir/aitweaks/launcher/loading/CoreLoader";
    static final String VECTOR = "org/lwjgl/util/vector/Vector2f";
    static final String TIMES_DESCRIPTOR = "(Lorg/lwjgl/util/vector/Vector2f;F)"
            + "Lorg/lwjgl/util/vector/Vector2f;";
    static final String PLUS_DESCRIPTOR = "(Lorg/lwjgl/util/vector/Vector2f;"
            + "Lorg/lwjgl/util/vector/Vector2f;)Lorg/lwjgl/util/vector/Vector2f;";
    static final String AFFINE_METHOD = "$preflight$affine";
    static final String AFFINE_DESCRIPTOR = "(Lorg/lwjgl/util/vector/Vector2f;"
            + "Lorg/lwjgl/util/vector/Vector2f;F)Lorg/lwjgl/util/vector/Vector2f;";
    static final List<Target> TARGETS = List.of(
            new Target(
                    "com/genir/aitweaks/core/shipai/autofire/ballistics/Projectile",
                    "50db98c94a6589e39bdc4d2e39bfb33161e8f50de1f54cabfbca7cca1c065379",
                    "targetMotion",
                    "(Lcom/genir/aitweaks/core/shipai/autofire/ballistics/BallisticTarget;"
                            + "Lcom/genir/aitweaks/core/shipai/autofire/ballistics/BallisticParams;)"
                            + "Lcom/genir/aitweaks/core/utils/types/LinearMotion;"),
            new Target(
                    "com/genir/aitweaks/core/shipai/autofire/ballistics/Beam",
                    "afd4434d05988ddeed96fc5821206399cf32067c6c7f1ac54e63c411fd3f8bc2",
                    "targetLocation",
                    "(Lcom/genir/aitweaks/core/shipai/autofire/ballistics/BallisticTarget;"
                            + "Lcom/genir/aitweaks/core/shipai/autofire/ballistics/BallisticParams;)"
                            + "Lorg/lwjgl/util/vector/Vector2f;"),
            new Target(
                    "com/genir/aitweaks/core/utils/types/LinearMotion",
                    "64889050bc99efaa693fe73e8fb6cf80af319907b77dbd9fdb03a433da67cecc",
                    "positionAfter",
                    "(F)Lorg/lwjgl/util/vector/Vector2f;"));
    private static final String PROJECTILE_MOTION_METHOD = "projectileMotionInTargetFoR";
    private static final String PROJECTILE_MOTION_DESCRIPTOR =
            "(Lcom/genir/aitweaks/core/utils/types/LinearMotion;"
                    + "Lcom/genir/aitweaks/core/shipai/autofire/ballistics/BallisticParams;)"
                    + "Lcom/genir/aitweaks/core/utils/types/LinearMotion;";

    private static final String VECTOR_EXTENSIONS =
            "com/genir/aitweaks/core/extensions/Vector2fKt";
    private static final String INTRINSICS = "kotlin/jvm/internal/Intrinsics";

    private AiTweaksAffineVectorPlan() {
    }

    static boolean enabled() {
        return Boolean.getBoolean(ENABLED_PROPERTY);
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        Target target = target(signature.internalName());
        if (!enabled()
                || target == null
                || signature.majorVersion() != 61
                || !target.sha256().equals(signature.sha256())
                || !signature.hasMethod(target.method(), target.descriptor())) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        if (unique(owner, AFFINE_METHOD, AFFINE_DESCRIPTOR) != null) return null;
        List<MethodTarget> methods = methods(target);
        List<AffinePair> pairs = new java.util.ArrayList<>();
        for (MethodTarget methodTarget : methods) {
            MethodNode method = unique(owner, methodTarget.name(), methodTarget.descriptor());
            if (method == null) return null;
            List<AffinePair> methodPairs = affinePairs(method);
            if (methodPairs.size() != methodTarget.pairs()
                    || staticCalls(method, "plus", PLUS_DESCRIPTOR)
                    != methodTarget.pairs() + methodTarget.unpairedPlusCalls()) {
                return null;
            }
            pairs.addAll(methodPairs);
        }
        for (AffinePair pair : pairs) {
            pair.method().instructions.set(pair.times(), new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    owner.name,
                    AFFINE_METHOD,
                    AFFINE_DESCRIPTOR,
                    false));
            pair.method().instructions.remove(pair.plus());
        }
        owner.methods.add(affineMethod());

        ClassWriter writer = new ClassWriter(0);
        owner.accept(writer);
        return writer.toByteArray();
    }

    static Target target(String internalName) {
        for (Target target : TARGETS) {
            if (target.internalName().equals(internalName)) return target;
        }
        return null;
    }

    static List<MethodTarget> methods(Target target) {
        MethodTarget primary = new MethodTarget(target.method(), target.descriptor(), 1, 0);
        if (target.internalName().endsWith("/Projectile")
                || target.internalName().endsWith("/Beam")) {
            return List.of(primary, new MethodTarget(
                    PROJECTILE_MOTION_METHOD,
                    PROJECTILE_MOTION_DESCRIPTOR,
                    2,
                    target.internalName().endsWith("/Projectile") ? 1 : 0));
        }
        return List.of(primary);
    }

    private static List<AffinePair> affinePairs(MethodNode method) {
        List<AffinePair> pairs = new java.util.ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !VECTOR_EXTENSIONS.equals(call.owner)
                    || !"times".equals(call.name)
                    || !TIMES_DESCRIPTOR.equals(call.desc)) {
                continue;
            }
            AbstractInsnNode next = nextCode(call);
            if (!(next instanceof MethodInsnNode nextCall)
                    || nextCall.getOpcode() != Opcodes.INVOKESTATIC
                    || !VECTOR_EXTENSIONS.equals(nextCall.owner)
                    || !"plus".equals(nextCall.name)
                    || !PLUS_DESCRIPTOR.equals(nextCall.desc)) {
                return List.of();
            }
            pairs.add(new AffinePair(method, call, nextCall));
        }
        return pairs;
    }

    private static int staticCalls(MethodNode method, String name, String descriptor) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && VECTOR_EXTENSIONS.equals(call.owner)
                    && name.equals(call.name)
                    && descriptor.equals(call.desc)) {
                count++;
            }
        }
        return count;
    }

    private static MethodNode affineMethod() {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                AFFINE_METHOD,
                AFFINE_DESCRIPTOR,
                null,
                null);
        // Match the Kotlin extension validation order: scaled vector first, then the base vector.
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new LdcInsnNode("<this>"));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                INTRINSICS,
                "checkNotNullParameter",
                "(Ljava/lang/Object;Ljava/lang/String;)V",
                false));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new LdcInsnNode("<this>"));
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                INTRINSICS,
                "checkNotNullParameter",
                "(Ljava/lang/Object;Ljava/lang/String;)V",
                false));
        method.instructions.add(new TypeInsnNode(Opcodes.NEW, VECTOR));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        component(method, "x");
        component(method, "y");
        method.instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, VECTOR, "<init>", "(FF)V", false));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 6;
        method.maxLocals = 3;
        return method;
    }

    private static void component(MethodNode method, String field) {
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, VECTOR, field, "F"));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, VECTOR, field, "F"));
        method.instructions.add(new VarInsnNode(Opcodes.FLOAD, 2));
        method.instructions.add(new InsnNode(Opcodes.FMUL));
        method.instructions.add(new InsnNode(Opcodes.FADD));
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

    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        AbstractInsnNode next = instruction == null ? null : instruction.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        return next;
    }

    record Target(String internalName, String sha256, String method, String descriptor) {
    }

    record MethodTarget(String name, String descriptor, int pairs, int unpairedPlusCalls) {
    }

    private record AffinePair(MethodNode method, MethodInsnNode times, MethodInsnNode plus) {
    }
}
