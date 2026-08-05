package dev.starsector.preflight.agent;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/** Attributes the dominant ship/fighter-variant loader without changing its control flow. */
final class VariantLoaderPhasePlan {
    static final String METHOD = "oO0000";
    static final String DESCRIPTOR = "()V";
    private static final String TARGET = SpecStorePhasePlan.TARGET_CLASS;
    private static final String RUNTIME = "dev/starsector/preflight/agent/StartupPhaseRuntime";
    private static final String LOADING_UTILS = "com/fs/starfarer/loading/LoadingUtils";
    private static final String VARIANT = "com/fs/starfarer/loading/specs/HullVariantSpec";
    private static final String LOADING_PACKAGE = "com/fs/starfarer/loading/";

    private VariantLoaderPhasePlan() {
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
        if (!TARGET.equals(signature.internalName()) || !signature.hasMethod(METHOD, DESCRIPTOR)) {
            return false;
        }
        MethodNode method = uniqueMethod(owner, METHOD, DESCRIPTOR);
        if (method == null || hasRuntimeCalls(method)) {
            return false;
        }

        List<MethodInsnNode> listing = calls(method, LOADING_UTILS, "super",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/util/List;");
        List<MethodInsnNode> directories = calls(method, LOADING_UTILS, "Ö00000",
                "(Ljava/lang/String;)Ljava/util/List;");
        List<MethodInsnNode> json = calls(method, LOADING_UTILS, "Ó00000",
                "(Ljava/lang/String;)Lorg/json/JSONObject;");
        List<MethodInsnNode> constructors = calls(method, VARIANT, "<init>",
                "(Lorg/json/JSONObject;)V");
        List<MethodInsnNode> registration = obfuscatedRegistryCalls(method, "super",
                "(Lcom/fs/starfarer/loading/specs/HullVariantSpec;Z)V");
        List<MethodInsnNode> postPass = obfuscatedRegistryCalls(
                method, "super", "()Ljava/util/List;");
        if (listing.size() != 2 || directories.size() != 1 || json.size() != 1
                || constructors.size() != 1 || registration.size() != 1 || postPass.size() != 1
                || !registration.get(0).owner.equals(postPass.get(0).owner)) {
            return false;
        }

        listing.forEach(call -> wrapCall(method, call, "variant-file-listing"));
        directories.forEach(call -> wrapCall(method, call, "variant-file-listing"));
        json.forEach(call -> wrapCall(method, call, "variant-json-merge-parse"));
        registration.forEach(call -> wrapCall(method, call, "variant-registry-insert"));

        MethodInsnNode constructor = constructors.get(0);
        TypeInsnNode allocation = previousNew(constructor, VARIANT);
        if (allocation == null) {
            return false;
        }
        method.instructions.insertBefore(allocation, start("variant-object-construction"));
        method.instructions.insert(constructor, end());

        MethodInsnNode postPassStart = postPass.get(0);
        AbstractInsnNode methodReturn = uniqueReturn(method);
        if (methodReturn == null || !comesBefore(postPassStart, methodReturn)) {
            return false;
        }
        method.instructions.insertBefore(postPassStart, start("variant-post-pass"));
        method.instructions.insertBefore(methodReturn, end());

        return true;
    }

    private static void wrapCall(MethodNode method, MethodInsnNode call, String label) {
        method.instructions.insertBefore(call, start(label));
        method.instructions.insert(call, end());
    }

    private static InsnList start(String label) {
        InsnList instructions = new InsnList();
        instructions.add(new LdcInsnNode(label));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "specSubphaseStart", "(Ljava/lang/String;)V", false));
        return instructions;
    }

    private static MethodInsnNode end() {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME,
                "specSubphaseEnd", "()V", false);
    }

    private static List<MethodInsnNode> calls(
            MethodNode method, String owner, String name, String descriptor) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call && owner.equals(call.owner)
                    && name.equals(call.name) && descriptor.equals(call.desc)) {
                result.add(call);
            }
        }
        return result;
    }

    private static List<MethodInsnNode> obfuscatedRegistryCalls(
            MethodNode method, String name, String descriptor) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.startsWith(LOADING_PACKAGE + "Oo")
                    && call.owner.length() > LOADING_PACKAGE.length() + 100
                    && name.equals(call.name) && descriptor.equals(call.desc)) {
                result.add(call);
            }
        }
        return result;
    }

    private static TypeInsnNode previousNew(MethodInsnNode constructor, String type) {
        for (AbstractInsnNode cursor = constructor.getPrevious(); cursor != null; cursor = cursor.getPrevious()) {
            if (cursor instanceof TypeInsnNode candidate && candidate.getOpcode() == Opcodes.NEW) {
                return type.equals(candidate.desc) ? candidate : null;
            }
        }
        return null;
    }

    private static AbstractInsnNode uniqueReturn(MethodNode method) {
        AbstractInsnNode found = null;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                if (found != null) {
                    return null;
                }
                found = instruction;
            }
        }
        return found;
    }

    private static boolean comesBefore(AbstractInsnNode first, AbstractInsnNode last) {
        for (AbstractInsnNode cursor = first; cursor != null; cursor = cursor.getNext()) {
            if (cursor == last) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRuntimeCalls(MethodNode method) {
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call && RUNTIME.equals(call.owner)) {
                return true;
            }
        }
        return false;
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
}
