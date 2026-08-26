package dev.starsector.preflight.agent;

import java.util.ArrayList;
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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Reuses up to eight non-escaping temporary vectors in Starsector's contrail render loop. */
final class ContrailRenderScratchPlan {
    static final String TARGET_CLASS = "com/fs/starfarer/campaign/fleet/ContrailEngineV2";
    static final String ORIGINAL_SHA256 =
            "deddd2b2f437cc71882c96b7f7442101155f93952cbf07a4223d44381f5d3647";
    static final String RENDER_METHOD = "render";
    static final String RENDER_DESCRIPTOR = "(F)V";
    static final int SCRATCH_COUNT = 8;

    private static final String VECTOR = "org/lwjgl/util/vector/Vector2f";
    private static final String VECTOR_DESCRIPTOR = "L" + VECTOR + ";";
    private static final String READABLE_VECTOR_DESCRIPTOR =
            "Lorg/lwjgl/util/vector/ReadableVector2f;";
    private static final String CONSTRUCTOR = "<init>";
    private static final String COPY_CONSTRUCTOR = "(" + READABLE_VECTOR_DESCRIPTOR + ")V";
    private static final String EMPTY_CONSTRUCTOR = "()V";
    private static final String SET_DESCRIPTOR =
            "(" + READABLE_VECTOR_DESCRIPTOR + ")L" + VECTOR + ";";
    private static final String VECTOR_OPERATION_DESCRIPTOR =
            "(" + VECTOR_DESCRIPTOR + VECTOR_DESCRIPTOR + VECTOR_DESCRIPTOR + ")"
                    + VECTOR_DESCRIPTOR;
    static final String FIELD_PREFIX = "preflight$contrailScratch";
    static final String ENSURE_METHOD = "preflight$ensureContrailScratch";

    private ContrailRenderScratchPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (signature.majorVersion() != 61
                || !TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || !signature.hasMethod(RENDER_METHOD, RENDER_DESCRIPTOR)) {
            return null;
        }

        ClassNode owner = read(originalBytes);
        MethodNode render = unique(owner, RENDER_METHOD, RENDER_DESCRIPTOR);
        if (render == null || unique(owner, ENSURE_METHOD, EMPTY_CONSTRUCTOR) != null
                || owner.fields.stream().anyMatch(field -> field.name.startsWith(FIELD_PREFIX))) {
            return null;
        }

        List<ConstructorSite> sites = constructorSites(render);
        if (sites == null) return null;

        addScratchFields(owner);
        owner.methods.add(ensureMethod());
        InsnList ensure = new InsnList();
        ensure.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ensure.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, TARGET_CLASS, ENSURE_METHOD, EMPTY_CONSTRUCTOR, false));
        render.instructions.insert(ensure);

        for (int index = 0; index < sites.size(); index++) {
            rewrite(render, sites.get(index), field(index));
        }
        ContrailRenderScratchRuntime.installed();
        return write(owner);
    }

    private static List<ConstructorSite> constructorSites(MethodNode render) {
        List<MethodInsnNode> constructors = new ArrayList<>();
        for (AbstractInsnNode instruction : render.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && VECTOR.equals(call.owner)
                    && CONSTRUCTOR.equals(call.name)
                    && (COPY_CONSTRUCTOR.equals(call.desc) || EMPTY_CONSTRUCTOR.equals(call.desc))) {
                constructors.add(call);
            }
        }
        if (constructors.size() != SCRATCH_COUNT) return null;

        String[] descriptors = {
            COPY_CONSTRUCTOR, EMPTY_CONSTRUCTOR, COPY_CONSTRUCTOR, EMPTY_CONSTRUCTOR,
            COPY_CONSTRUCTOR, EMPTY_CONSTRUCTOR, EMPTY_CONSTRUCTOR, EMPTY_CONSTRUCTOR
        };
        String[] consumers = {null, "add", null, "add", null, "add", "sub", "sub"};
        List<ConstructorSite> sites = new ArrayList<>(SCRATCH_COUNT);
        for (int index = 0; index < SCRATCH_COUNT; index++) {
            MethodInsnNode constructor = constructors.get(index);
            if (!descriptors[index].equals(constructor.desc)) return null;
            AbstractInsnNode duplicate;
            if (COPY_CONSTRUCTOR.equals(constructor.desc)) {
                AbstractInsnNode sourceField = previousCode(constructor);
                AbstractInsnNode sourceLoad = previousCode(sourceField);
                duplicate = previousCode(sourceLoad);
                if (!(sourceField instanceof FieldInsnNode field)
                        || field.getOpcode() != Opcodes.GETFIELD
                        || !VECTOR_DESCRIPTOR.equals(field.desc)
                        || !(sourceLoad instanceof VarInsnNode load)
                        || load.getOpcode() != Opcodes.ALOAD) {
                    return null;
                }
            } else {
                duplicate = previousCode(constructor);
                AbstractInsnNode consumer = nextCode(constructor);
                if (!(consumer instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKESTATIC
                        || !VECTOR.equals(call.owner)
                        || !consumers[index].equals(call.name)
                        || !VECTOR_OPERATION_DESCRIPTOR.equals(call.desc)) {
                    return null;
                }
            }
            AbstractInsnNode allocation = previousCode(duplicate);
            if (!(duplicate instanceof InsnNode) || duplicate.getOpcode() != Opcodes.DUP
                    || !(allocation instanceof TypeInsnNode type)
                    || allocation.getOpcode() != Opcodes.NEW
                    || !VECTOR.equals(type.desc)) {
                return null;
            }
            sites.add(new ConstructorSite(allocation, duplicate, constructor));
        }
        return sites;
    }

    private static void rewrite(MethodNode render, ConstructorSite site, String field) {
        InsnList scratch = new InsnList();
        scratch.add(new VarInsnNode(Opcodes.ALOAD, 0));
        scratch.add(new FieldInsnNode(
                Opcodes.GETFIELD, TARGET_CLASS, field, VECTOR_DESCRIPTOR));
        render.instructions.insertBefore(site.allocation, scratch);
        render.instructions.remove(site.allocation);
        render.instructions.remove(site.duplicate);
        if (COPY_CONSTRUCTOR.equals(site.constructor.desc)) {
            render.instructions.set(site.constructor, new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, VECTOR, "set", SET_DESCRIPTOR, false));
        } else {
            render.instructions.remove(site.constructor);
        }
    }

    private static void addScratchFields(ClassNode owner) {
        int access = Opcodes.ACC_PRIVATE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC;
        for (int index = 0; index < SCRATCH_COUNT; index++) {
            owner.fields.add(new FieldNode(access, field(index), VECTOR_DESCRIPTOR, null, null));
        }
    }

    private static MethodNode ensureMethod() {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                ENSURE_METHOD, EMPTY_CONSTRUCTOR, null, null);
        InsnList code = method.instructions;
        org.objectweb.asm.tree.LabelNode initialize = new org.objectweb.asm.tree.LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, TARGET_CLASS, field(SCRATCH_COUNT - 1), VECTOR_DESCRIPTOR));
        code.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFNULL, initialize));
        code.add(new InsnNode(Opcodes.RETURN));
        code.add(initialize);
        for (int index = 0; index < SCRATCH_COUNT; index++) {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new TypeInsnNode(Opcodes.NEW, VECTOR));
            code.add(new InsnNode(Opcodes.DUP));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL, VECTOR, CONSTRUCTOR, EMPTY_CONSTRUCTOR, false));
            code.add(new FieldInsnNode(
                    Opcodes.PUTFIELD, TARGET_CLASS, field(index), VECTOR_DESCRIPTOR));
        }
        code.add(new InsnNode(Opcodes.RETURN));
        return method;
    }

    private static String field(int index) {
        return FIELD_PREFIX + index;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static byte[] write(ClassNode owner) {
        ClassWriter writer =
                new SafeClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        return writer.toByteArray();
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

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction == null ? null : instruction.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction.getNext();
        while (current != null && current.getOpcode() < 0) current = current.getNext();
        return current;
    }

    private record ConstructorSite(
            AbstractInsnNode allocation,
            AbstractInsnNode duplicate,
            MethodInsnNode constructor) {
    }
}
