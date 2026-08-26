package dev.starsector.preflight.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Skips only MagicLib advance's private notification scan between exact mutations. */
final class MagicLibPaintjobSnapshotPlan {
    static final String TARGET_CLASS = MagicLibPaintjobNotificationPlan.TARGET_CLASS;
    static final String ORIGINAL_SHA256 = MagicLibPaintjobNotificationPlan.ORIGINAL_SHA256;
    static final String ADVANCE_METHOD = MagicLibPaintjobNotificationPlan.ADVANCE_METHOD;
    static final String ADVANCE_DESCRIPTOR = MagicLibPaintjobNotificationPlan.ADVANCE_DESCRIPTOR;

    private static final String FIELD = "paintjobsInner";
    private static final String UNLOCKED_FIELD = "unlockedPaintjobsInner";
    private static final String NOTIFIED_FIELD =
            "completedPaintjobIdsThatUserHasBeenNotifiedFor";
    private static final String LIST_DESCRIPTOR = "Ljava/util/List;";
    private static final String SET_DESCRIPTOR = "Ljava/util/Set;";
    private static final String DEFAULT_METHOD = "getPaintjobs$default";
    private static final String DEFAULT_DESCRIPTOR = "(ZILjava/lang/Object;)Ljava/util/Set;";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/MagicLibPaintjobSnapshotRuntime";
    private static final String SNAPSHOT_DESCRIPTOR =
            "(ZILjava/lang/Object;Ljava/lang/Class;)Ljava/util/Set;";

    private MagicLibPaintjobSnapshotPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] currentBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(ADVANCE_METHOD, ADVANCE_DESCRIPTOR)) {
            return null;
        }
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(currentBytes).accept(owner, ClassReader.EXPAND_FRAMES);
        if (!reviewedFields(owner) || calls(owner, RUNTIME, "snapshot") != 0) return null;

        MethodNode advance = unique(owner, ADVANCE_METHOD, ADVANCE_DESCRIPTOR);
        MethodInsnNode snapshotCall = null;
        int catalogClear = 0;
        int catalogAddAll = 0;
        int catalogRemoveAll = 0;
        int catalogAdd = 0;
        int unlockedAdd = 0;
        int unlockedAddAll = 0;
        int unlockedRemove = 0;
        int notifiedClear = 0;
        int notifiedAdd = 0;
        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                if (method == advance
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && TARGET_CLASS.equals(call.owner)
                        && DEFAULT_METHOD.equals(call.name)
                        && DEFAULT_DESCRIPTOR.equals(call.desc)) {
                    if (snapshotCall != null) return null;
                    snapshotCall = call;
                }
                String field = trackedFieldBefore(call);
                if (field == null) continue;
                if (FIELD.equals(field) && call.getOpcode() == Opcodes.INVOKEINTERFACE
                        && "java/util/List".equals(call.owner)
                        && "clear".equals(call.name) && "()V".equals(call.desc)) {
                    catalogClear++;
                } else if (FIELD.equals(field) && call.getOpcode() == Opcodes.INVOKEINTERFACE
                        && "java/util/List".equals(call.owner)
                        && "addAll".equals(call.name)
                        && "(Ljava/util/Collection;)Z".equals(call.desc)) {
                    catalogAddAll++;
                } else if (FIELD.equals(field) && call.getOpcode() == Opcodes.INVOKESTATIC
                        && "kotlin/collections/CollectionsKt".equals(call.owner)
                        && "removeAll".equals(call.name)
                        && "(Ljava/util/List;Lkotlin/jvm/functions/Function1;)Z".equals(call.desc)) {
                    catalogRemoveAll++;
                } else if (FIELD.equals(field) && call.getOpcode() == Opcodes.INVOKEINTERFACE
                        && "java/util/List".equals(call.owner)
                        && "add".equals(call.name)
                        && "(Ljava/lang/Object;)Z".equals(call.desc)) {
                    catalogAdd++;
                } else if (UNLOCKED_FIELD.equals(field)
                        && call.getOpcode() == Opcodes.INVOKEINTERFACE
                        && "java/util/Set".equals(call.owner)
                        && "add".equals(call.name)
                        && "(Ljava/lang/Object;)Z".equals(call.desc)) {
                    unlockedAdd++;
                } else if (UNLOCKED_FIELD.equals(field)
                        && call.getOpcode() == Opcodes.INVOKEINTERFACE
                        && "java/util/Set".equals(call.owner)
                        && "addAll".equals(call.name)
                        && "(Ljava/util/Collection;)Z".equals(call.desc)) {
                    unlockedAddAll++;
                } else if (UNLOCKED_FIELD.equals(field)
                        && call.getOpcode() == Opcodes.INVOKEINTERFACE
                        && "java/util/Set".equals(call.owner)
                        && "remove".equals(call.name)
                        && "(Ljava/lang/Object;)Z".equals(call.desc)) {
                    unlockedRemove++;
                } else if (NOTIFIED_FIELD.equals(field)
                        && call.getOpcode() == Opcodes.INVOKEINTERFACE
                        && "java/util/List".equals(call.owner)
                        && "clear".equals(call.name) && "()V".equals(call.desc)) {
                    notifiedClear++;
                } else if (NOTIFIED_FIELD.equals(field)
                        && call.getOpcode() == Opcodes.INVOKEINTERFACE
                        && "java/util/List".equals(call.owner)
                        && "add".equals(call.name)
                        && "(Ljava/lang/Object;)Z".equals(call.desc)) {
                    notifiedAdd++;
                }
            }
        }
        if (snapshotCall == null
                || catalogClear != 1 || catalogAddAll != 1
                || catalogRemoveAll != 1 || catalogAdd != 1
                || unlockedAdd != 2 || unlockedAddAll != 1 || unlockedRemove != 1
                || notifiedClear != 1 || notifiedAdd != 2) {
            return null;
        }

        for (MethodNode method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || !reviewedMutation(trackedFieldBefore(call), call)) continue;
                if ("clear".equals(call.name) && "()V".equals(call.desc)) {
                    method.instructions.insert(call, new MethodInsnNode(
                            Opcodes.INVOKESTATIC, RUNTIME, "mutated", "()V", false));
                } else {
                    method.instructions.insert(call, new MethodInsnNode(
                            Opcodes.INVOKESTATIC, RUNTIME, "mutated", "(Z)Z", false));
                }
            }
        }
        InsnList managerClass = new InsnList();
        managerClass.add(new LdcInsnNode(Type.getObjectType(TARGET_CLASS)));
        advance.instructions.insertBefore(snapshotCall, managerClass);
        snapshotCall.owner = RUNTIME;
        snapshotCall.name = "snapshot";
        snapshotCall.desc = SNAPSHOT_DESCRIPTOR;
        snapshotCall.itf = false;

        ClassWriter writer = new SafeClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        owner.accept(writer);
        MagicLibPaintjobSnapshotRuntime.installed();
        return writer.toByteArray();
    }

    private static boolean reviewedFields(ClassNode owner) {
        long catalogFields = owner.fields.stream().filter(field -> FIELD.equals(field.name)
                && LIST_DESCRIPTOR.equals(field.desc)
                && (field.access & Opcodes.ACC_PRIVATE) != 0
                && (field.access & Opcodes.ACC_STATIC) != 0
                && (field.access & Opcodes.ACC_FINAL) != 0).count();
        long unlockedFields = owner.fields.stream().filter(field -> UNLOCKED_FIELD.equals(field.name)
                && SET_DESCRIPTOR.equals(field.desc)
                && (field.access & Opcodes.ACC_PRIVATE) != 0
                && (field.access & Opcodes.ACC_STATIC) != 0
                && (field.access & Opcodes.ACC_FINAL) != 0).count();
        long notifiedFields = owner.fields.stream().filter(field -> NOTIFIED_FIELD.equals(field.name)
                && LIST_DESCRIPTOR.equals(field.desc)
                && (field.access & Opcodes.ACC_PRIVATE) != 0
                && (field.access & Opcodes.ACC_STATIC) != 0
                && (field.access & Opcodes.ACC_FINAL) != 0).count();
        long writes = owner.methods.stream().flatMap(method -> java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(
                                method.instructions.iterator(), 0), false))
                .filter(instruction -> instruction instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.PUTSTATIC
                        && TARGET_CLASS.equals(field.owner)
                        && (FIELD.equals(field.name) || UNLOCKED_FIELD.equals(field.name)
                                || NOTIFIED_FIELD.equals(field.name))).count();
        return catalogFields == 1 && unlockedFields == 1 && notifiedFields == 1 && writes == 3;
    }

    private static String trackedFieldBefore(MethodInsnNode call) {
        int distance = 0;
        for (AbstractInsnNode cursor = call.getPrevious(); cursor != null && distance < 16;
                cursor = cursor.getPrevious()) {
            if (cursor.getOpcode() < 0) continue;
            distance++;
            if (cursor instanceof FieldInsnNode field) {
                if (field.getOpcode() == Opcodes.GETSTATIC && TARGET_CLASS.equals(field.owner)
                        && (FIELD.equals(field.name) || UNLOCKED_FIELD.equals(field.name)
                                || NOTIFIED_FIELD.equals(field.name))) {
                    return field.name;
                }
                // Do not walk through a different collection receiver and accidentally associate
                // its mutation with an older tracked field. Scalar JSON keys and object singletons
                // may legitimately appear inside the reviewed unlocked-set conversion chain.
                if (field.getOpcode() == Opcodes.GETSTATIC && TARGET_CLASS.equals(field.owner)
                        && (LIST_DESCRIPTOR.equals(field.desc) || SET_DESCRIPTOR.equals(field.desc))) {
                    return null;
                }
            }
        }
        return null;
    }

    private static boolean reviewedMutation(String field, MethodInsnNode call) {
        if (field == null) return false;
        if (FIELD.equals(field)) {
            return ("java/util/List".equals(call.owner)
                            && ("clear".equals(call.name) || "addAll".equals(call.name)
                                    || "add".equals(call.name)))
                    || ("kotlin/collections/CollectionsKt".equals(call.owner)
                            && "removeAll".equals(call.name));
        }
        if (UNLOCKED_FIELD.equals(field)) {
            return "java/util/Set".equals(call.owner)
                    && ("add".equals(call.name) || "addAll".equals(call.name)
                            || "remove".equals(call.name));
        }
        return NOTIFIED_FIELD.equals(field) && "java/util/List".equals(call.owner)
                && ("clear".equals(call.name) || "add".equals(call.name));
    }

    private static long calls(ClassNode owner, String callOwner, String name) {
        return owner.methods.stream().flatMap(method -> java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(
                                method.instructions.iterator(), 0), false))
                .filter(instruction -> instruction instanceof MethodInsnNode call
                        && callOwner.equals(call.owner) && name.equals(call.name)).count();
    }

    private static MethodNode unique(ClassNode owner, String name, String descriptor) {
        MethodNode found = null;
        for (MethodNode method : owner.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                if (found != null) return null;
                found = method;
            }
        }
        return found;
    }
}
