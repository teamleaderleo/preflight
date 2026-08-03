package dev.starsector.preflight.agent;

import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Caches GraphicsLib's fleet-manager accessor only within one insignia render invocation. */
final class GraphicsLibInsigniaManagerCachePlan {
    static final String TARGET_CLASS = "org/dark/graphics/plugins/InsigniaPlugin";
    static final String ORIGINAL_SHA256 =
            "6635b6661220635b578d7a049ca19fc7b0411101796f3132bd97591132994fb5";
    static final String RENDER_METHOD = "renderInUICoords";
    static final String RENDER_DESCRIPTOR =
            "(Lcom/fs/starfarer/api/combat/ViewportAPI;)V";

    private static final String ENGINE = "com/fs/starfarer/api/combat/CombatEngineAPI";
    private static final String MANAGER = "com/fs/starfarer/api/combat/CombatFleetManagerAPI";
    private static final String MANAGER_ACCESSOR = "getFleetManager";
    private static final String MANAGER_ACCESSOR_DESCRIPTOR = "(I)L" + MANAGER + ";";
    private static final String CACHE_FIELD = "preflight$managersThisRender";
    private static final String CACHE_DESCRIPTOR = "Ljava/util/Map;";
    private static final String HELPER = "preflight$getFleetManager";
    private static final String HELPER_DESCRIPTOR = "(L" + ENGINE + ";I)L" + MANAGER + ";";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/GraphicsLibInsigniaManagerCacheRuntime";

    private GraphicsLibInsigniaManagerCachePlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(RENDER_METHOD, RENDER_DESCRIPTOR)) {
            return null;
        }

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(originalBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        if (!TARGET_CLASS.equals(owner.name)
                || hasField(owner, CACHE_FIELD)
                || hasMethod(owner, HELPER, HELPER_DESCRIPTOR)) {
            return null;
        }
        MethodNode render = uniqueMethod(owner, RENDER_METHOD, RENDER_DESCRIPTOR);
        if (render == null || !rewriteAccessor(render, owner.name)) {
            return null;
        }

        owner.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE,
                CACHE_FIELD,
                CACHE_DESCRIPTOR,
                "Ljava/util/Map<Ljava/lang/Integer;L" + MANAGER + ";>;",
                null));
        render.instructions.insert(beginRender(owner.name));
        owner.methods.add(helper(owner.name));

        ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
    }

    private static boolean rewriteAccessor(MethodNode render, String owner) {
        List<MethodInsnNode> calls = stream(render).stream()
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> call.getOpcode() == Opcodes.INVOKEINTERFACE
                        && ENGINE.equals(call.owner)
                        && MANAGER_ACCESSOR.equals(call.name)
                        && MANAGER_ACCESSOR_DESCRIPTOR.equals(call.desc))
                .toList();
        if (calls.size() != 1) {
            return false;
        }
        MethodInsnNode call = calls.get(0);
        int engineLocal = render.maxLocals;
        int ownerLocal = engineLocal + 1;
        render.maxLocals += 2;
        InsnList replacement = new InsnList();
        replacement.add(new VarInsnNode(Opcodes.ISTORE, ownerLocal));
        replacement.add(new VarInsnNode(Opcodes.ASTORE, engineLocal));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, 0));
        replacement.add(new VarInsnNode(Opcodes.ALOAD, engineLocal));
        replacement.add(new VarInsnNode(Opcodes.ILOAD, ownerLocal));
        replacement.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, owner, HELPER, HELPER_DESCRIPTOR, false));
        render.instructions.insertBefore(call, replacement);
        render.instructions.remove(call);
        return true;
    }

    private static List<AbstractInsnNode> stream(MethodNode method) {
        java.util.ArrayList<AbstractInsnNode> instructions = new java.util.ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            instructions.add(instruction);
        }
        return instructions;
    }

    private static InsnList beginRender(String owner) {
        InsnList instructions = new InsnList();
        LabelNode clear = new LabelNode();
        LabelNode ready = new LabelNode();
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner, CACHE_FIELD, CACHE_DESCRIPTOR));
        instructions.add(new JumpInsnNode(Opcodes.IFNONNULL, clear));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashMap"));
        instructions.add(new InsnNode(Opcodes.DUP));
        instructions.add(new InsnNode(Opcodes.ICONST_4));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "(I)V", false));
        instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, CACHE_FIELD, CACHE_DESCRIPTOR));
        instructions.add(new JumpInsnNode(Opcodes.GOTO, ready));
        instructions.add(clear);
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner, CACHE_FIELD, CACHE_DESCRIPTOR));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, "java/util/Map", "clear", "()V", true));
        instructions.add(ready);
        return instructions;
    }

    private static MethodNode helper(String owner) {
        MethodNode method = new MethodNode(
                Opcodes.ASM9, Opcodes.ACC_PRIVATE, HELPER, HELPER_DESCRIPTOR, null, null);
        LabelNode miss = new LabelNode();
        InsnList instructions = method.instructions;

        instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
        instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner, CACHE_FIELD, CACHE_DESCRIPTOR));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Map",
                "containsKey",
                "(Ljava/lang/Object;)Z",
                true));
        instructions.add(new JumpInsnNode(Opcodes.IFEQ, miss));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "hit", "()V", false));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner, CACHE_FIELD, CACHE_DESCRIPTOR));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Map",
                "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                true));
        instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, MANAGER));
        instructions.add(new InsnNode(Opcodes.ARETURN));

        instructions.add(miss);
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                ENGINE,
                MANAGER_ACCESSOR,
                MANAGER_ACCESSOR_DESCRIPTOR,
                true));
        instructions.add(new VarInsnNode(Opcodes.ASTORE, 4));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner, CACHE_FIELD, CACHE_DESCRIPTOR));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        instructions.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Map",
                "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                true));
        instructions.add(new InsnNode(Opcodes.POP));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "miss", "()V", false));
        instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
        instructions.add(new InsnNode(Opcodes.ARETURN));
        return method;
    }

    private static MethodNode uniqueMethod(ClassNode owner, String name, String descriptor) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (found != null) {
                    return null;
                }
                found = method;
            }
        }
        return found;
    }

    private static boolean hasMethod(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .anyMatch(method -> name.equals(method.name) && descriptor.equals(method.desc));
    }

    private static boolean hasField(ClassNode owner, String name) {
        return owner.fields.stream().anyMatch(field -> name.equals(field.name));
    }
}
