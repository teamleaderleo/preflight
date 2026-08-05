package dev.starsector.preflight.agent;

import java.util.HashSet;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Replaces MagicLib's per-frame linear scan of already-notified paintjob IDs. */
final class MagicLibPaintjobNotificationPlan {
    static final String TARGET_CLASS = "org/magiclib/paintjobs/MagicPaintjobManager";
    static final String ORIGINAL_SHA256 =
            "841f945d675920e0fad9ccf13c7fa3144b6437489b6ba11e121a3267e6b8993c";
    static final String ADVANCE_METHOD = "advance";
    static final String ADVANCE_DESCRIPTOR = "(F)V";

    private static final String FIELD = "completedPaintjobIdsThatUserHasBeenNotifiedFor";
    private static final String LIST_DESCRIPTOR = "Ljava/util/List;";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/MagicLibPaintjobNotificationRuntime";

    private MagicLibPaintjobNotificationPlan() {
    }

    static byte[] transform(ClassSignature signature, byte[] originalBytes) {
        if (!TARGET_CLASS.equals(signature.internalName())
                || !ORIGINAL_SHA256.equals(signature.sha256())
                || signature.majorVersion() != 61
                || !signature.hasMethod(ADVANCE_METHOD, ADVANCE_DESCRIPTOR)) {
            return null;
        }

        ClassReader reader = new ClassReader(originalBytes);
        ReviewVisitor review = new ReviewVisitor();
        reader.accept(review, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (!review.accepted()) {
            return null;
        }

        ClassWriter writer = new SafeClassWriter(reader, 0);
        RewriteVisitor rewrite = new RewriteVisitor(writer, review.rewriteMethods);
        reader.accept(rewrite, 0);
        if (!rewrite.accepted()) {
            return null;
        }
        MagicLibPaintjobNotificationRuntime.installed();
        return writer.toByteArray();
    }

    private static String methodKey(String name, String descriptor) {
        return name + descriptor;
    }

    private static boolean isTrackedField(String owner, String name, String descriptor) {
        return TARGET_CLASS.equals(owner) && FIELD.equals(name) && LIST_DESCRIPTOR.equals(descriptor);
    }

    /** Tracks the three-instruction lookback used by the reviewed tree implementation. */
    private abstract static class TrackingMethodVisitor extends MethodVisitor {
        private int instructionsSinceFieldRead = Integer.MAX_VALUE;

        TrackingMethodVisitor(MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        final boolean referencesTrackedField() {
            return instructionsSinceFieldRead < 3;
        }

        final void methodInstructionVisited() {
            ordinaryInstructionVisited();
        }

        private void ordinaryInstructionVisited() {
            if (instructionsSinceFieldRead < 3) {
                instructionsSinceFieldRead++;
            }
        }

        @Override
        public void visitInsn(int opcode) {
            super.visitInsn(opcode);
            ordinaryInstructionVisited();
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            super.visitIntInsn(opcode, operand);
            ordinaryInstructionVisited();
        }

        @Override
        public void visitVarInsn(int opcode, int variable) {
            super.visitVarInsn(opcode, variable);
            ordinaryInstructionVisited();
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            super.visitTypeInsn(opcode, type);
            ordinaryInstructionVisited();
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            super.visitFieldInsn(opcode, owner, name, descriptor);
            if (opcode == Opcodes.GETSTATIC && isTrackedField(owner, name, descriptor)) {
                instructionsSinceFieldRead = 0;
            } else {
                ordinaryInstructionVisited();
            }
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor,
                org.objectweb.asm.Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
            super.visitInvokeDynamicInsn(
                    name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
            ordinaryInstructionVisited();
        }

        @Override
        public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
            super.visitJumpInsn(opcode, label);
            ordinaryInstructionVisited();
        }

        @Override
        public void visitLdcInsn(Object value) {
            super.visitLdcInsn(value);
            ordinaryInstructionVisited();
        }

        @Override
        public void visitIincInsn(int variable, int increment) {
            super.visitIincInsn(variable, increment);
            ordinaryInstructionVisited();
        }

        @Override
        public void visitTableSwitchInsn(int minimum, int maximum,
                org.objectweb.asm.Label defaultLabel, org.objectweb.asm.Label... labels) {
            super.visitTableSwitchInsn(minimum, maximum, defaultLabel, labels);
            ordinaryInstructionVisited();
        }

        @Override
        public void visitLookupSwitchInsn(org.objectweb.asm.Label defaultLabel, int[] keys,
                org.objectweb.asm.Label[] labels) {
            super.visitLookupSwitchInsn(defaultLabel, keys, labels);
            ordinaryInstructionVisited();
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
            super.visitMultiANewArrayInsn(descriptor, dimensions);
            ordinaryInstructionVisited();
        }
    }

    private static final class ReviewVisitor extends ClassVisitor {
        private final Set<String> rewriteMethods = new HashSet<>();
        private int matchingFields;
        private int fieldReads;
        private int fieldWrites;
        private int containsCalls;
        private int clearCalls;
        private int addCalls;
        private boolean invalid;

        private ReviewVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                String signature, Object value) {
            if (FIELD.equals(name) && LIST_DESCRIPTOR.equals(descriptor)
                    && (access & Opcodes.ACC_STATIC) != 0
                    && (access & Opcodes.ACC_FINAL) != 0) {
                matchingFields++;
            }
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {
            String key = methodKey(name, descriptor);
            return new TrackingMethodVisitor(null) {
                @Override
                public void visitFieldInsn(
                        int opcode, String owner, String fieldName, String fieldDescriptor) {
                    if (isTrackedField(owner, fieldName, fieldDescriptor)) {
                        if (opcode == Opcodes.GETSTATIC) {
                            fieldReads++;
                        } else if (opcode == Opcodes.PUTSTATIC) {
                            fieldWrites++;
                        } else {
                            invalid = true;
                        }
                    }
                    super.visitFieldInsn(opcode, owner, fieldName, fieldDescriptor);
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String callName,
                        String callDescriptor, boolean isInterface) {
                    if ("java/util/List".equals(owner) && referencesTrackedField()) {
                        if ("contains".equals(callName)
                                && "(Ljava/lang/Object;)Z".equals(callDescriptor)
                                && ADVANCE_METHOD.equals(name)
                                && ADVANCE_DESCRIPTOR.equals(descriptor)) {
                            containsCalls++;
                        } else if ("clear".equals(callName) && "()V".equals(callDescriptor)) {
                            clearCalls++;
                        } else if ("add".equals(callName)
                                && "(Ljava/lang/Object;)Z".equals(callDescriptor)) {
                            addCalls++;
                        } else {
                            invalid = true;
                        }
                        rewriteMethods.add(key);
                    }
                    methodInstructionVisited();
                }
            };
        }

        private boolean accepted() {
            return !invalid && matchingFields == 1 && fieldReads == 4 && fieldWrites == 1
                    && containsCalls == 1 && clearCalls == 1 && addCalls == 2;
        }
    }

    private static final class RewriteVisitor extends ClassVisitor {
        private final Set<String> rewriteMethods;
        private int containsCalls;
        private int clearCalls;
        private int addCalls;

        private RewriteVisitor(ClassVisitor delegate, Set<String> rewriteMethods) {
            super(Opcodes.ASM9, delegate);
            this.rewriteMethods = rewriteMethods;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (!rewriteMethods.contains(methodKey(name, descriptor))) {
                return delegate;
            }
            return new TrackingMethodVisitor(delegate) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String callName,
                        String callDescriptor, boolean isInterface) {
                    if ("java/util/List".equals(owner) && referencesTrackedField()) {
                        if ("contains".equals(callName)
                                && "(Ljava/lang/Object;)Z".equals(callDescriptor)
                                && ADVANCE_METHOD.equals(name)
                                && ADVANCE_DESCRIPTOR.equals(descriptor)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "contains",
                                    "(Ljava/util/List;Ljava/lang/Object;)Z", false);
                            containsCalls++;
                            methodInstructionVisited();
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, callName, callDescriptor, isInterface);
                        if ("clear".equals(callName) && "()V".equals(callDescriptor)) {
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC, RUNTIME, "mutated", "()V", false);
                            clearCalls++;
                        } else if ("add".equals(callName)
                                && "(Ljava/lang/Object;)Z".equals(callDescriptor)) {
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC, RUNTIME, "mutated", "(Z)Z", false);
                            addCalls++;
                        }
                        methodInstructionVisited();
                        return;
                    }
                    super.visitMethodInsn(opcode, owner, callName, callDescriptor, isInterface);
                    methodInstructionVisited();
                }
            };
        }

        private boolean accepted() {
            return containsCalls == 1 && clearCalls == 1 && addCalls == 2;
        }
    }
}
