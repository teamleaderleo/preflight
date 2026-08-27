package dev.starsector.preflight.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Exact LWJGL wrapper guards for one opt-in identity-matrix experiment. */
final class GlMatrixIdentityElisionPlan {
    static final String SOURCE_FILE = GlCommandCountPlan.SOURCE_FILE;
    static final String SOURCE_SHA256 = GlCommandCountPlan.SOURCE_SHA256;
    static final String LOADER = GlCommandCountPlan.LOADER;
    static final String LOADER_NAME = GlCommandCountPlan.LOADER_NAME;
    static final String TARGET_CLASS = "org/lwjgl/opengl/GL11";
    static final String TARGET_SHA256 =
            "875ff80814db1f6c16dd118fb27df7a7dc97adb4876dde023afd0e4ca0f18ce4";
    static final int EXPECTED_METHODS = 8;
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/GlMatrixIdentityElisionRuntime";

    private GlMatrixIdentityElisionPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!GlMatrixIdentityElisionRuntime.planEnabled()
                || !TARGET_CLASS.equals(signature.internalName())
                || !TARGET_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 49
                || !signature.hasMethod("glBegin", "(I)V")) {
            return null;
        }
        Map<String, Hook> hooks = hooks();
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        Map<String, MethodNode> selected = new LinkedHashMap<>();
        for (MethodNode method : owner.methods) {
            String key = method.name + method.desc;
            if (!hooks.containsKey(key)) continue;
            if ((method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))
                    != (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)
                    || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                    || selected.put(key, method) != null
                    || callsRuntime(method)) {
                return null;
            }
        }
        if (selected.size() != hooks.size()) return null;
        for (Map.Entry<String, MethodNode> entry : selected.entrySet()) {
            Hook hook = hooks.get(entry.getKey());
            if (hook.guardMethod() != null) {
                if (!instrumentGuard(entry.getValue(), hook)) return null;
            } else if (!instrumentCompletion(entry.getValue(), hook.completionMethod())) {
                return null;
            }
        }
        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        GlMatrixIdentityElisionRuntime.installed(owner.name, selected.size());
        return writer.toByteArray();
    }

    private static Map<String, Hook> hooks() {
        Map<String, Hook> hooks = new LinkedHashMap<>();
        hooks.put("glBegin(I)V", completion("beginPrimitive"));
        hooks.put("glEnd()V", completion("endPrimitive"));
        hooks.put("glTranslatef(FFF)V", guard("shouldSkipTranslateF", "(FFF)Z", Opcodes.FLOAD, 3));
        hooks.put("glTranslated(DDD)V", guard("shouldSkipTranslateD", "(DDD)Z", Opcodes.DLOAD, 3));
        hooks.put("glRotatef(FFFF)V", guard("shouldSkipRotateF", "(FFFF)Z", Opcodes.FLOAD, 4));
        hooks.put("glRotated(DDDD)V", guard("shouldSkipRotateD", "(DDDD)Z", Opcodes.DLOAD, 4));
        hooks.put("glScalef(FFF)V", guard("shouldSkipScaleF", "(FFF)Z", Opcodes.FLOAD, 3));
        hooks.put("glScaled(DDD)V", guard("shouldSkipScaleD", "(DDD)Z", Opcodes.DLOAD, 3));
        return hooks;
    }

    private static boolean instrumentGuard(MethodNode method, Hook hook) {
        LabelNode original = new LabelNode();
        InsnList guard = new InsnList();
        int local = 0;
        int width = hook.loadOpcode() == Opcodes.DLOAD ? 2 : 1;
        for (int index = 0; index < hook.argumentCount(); index++) {
            guard.add(new VarInsnNode(hook.loadOpcode(), local));
            local += width;
        }
        guard.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, hook.guardMethod(), hook.descriptor(), false));
        guard.add(new JumpInsnNode(Opcodes.IFEQ, original));
        guard.add(new InsnNode(Opcodes.RETURN));
        guard.add(original);
        method.instructions.insert(guard);
        return true;
    }

    private static boolean instrumentCompletion(MethodNode method, String runtimeMethod) {
        AbstractInsnNode onlyReturn = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() != Opcodes.RETURN) continue;
            if (onlyReturn != null) return false;
            onlyReturn = instruction;
        }
        if (onlyReturn == null) return false;
        InsnList completed = new InsnList();
        completed.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME, runtimeMethod, "()V", false));
        method.instructions.insertBefore(onlyReturn, completed);
        return true;
    }

    private static boolean callsRuntime(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) return true;
        }
        return false;
    }

    private static Hook guard(String method, String descriptor, int loadOpcode, int arguments) {
        return new Hook(method, descriptor, loadOpcode, arguments, null);
    }

    private static Hook completion(String method) {
        return new Hook(null, null, 0, 0, method);
    }

    private record Hook(
            String guardMethod,
            String descriptor,
            int loadOpcode,
            int argumentCount,
            String completionMethod) {
    }
}
