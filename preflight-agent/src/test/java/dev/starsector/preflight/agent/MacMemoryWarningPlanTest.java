package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

class MacMemoryWarningPlanTest {
    private static final String RUNTIME =
            MacMemoryWarningRuntime.class.getName().replace('.', '/');

    @AfterEach
    void reset() {
        MacMemoryWarningRuntime.reset();
    }

    @Test
    void replacesOnlyTheFinalSystemMemoryDecision() throws Exception {
        byte[] original = fixture(false);
        byte[] transformed = MacMemoryWarningPlan.transform(exactSignature(original), original);
        assertNotNull(transformed);

        MethodNode method = method(read(transformed));
        assertEquals(1, calls(method, RUNTIME, "shouldWarn"));
        assertEquals(1, constants(method, MacMemoryWarningPlan.WARNING));
        assertEquals(0, constants(method, MacMemoryWarningRuntime.WARNING_THRESHOLD_MIB));
        assertEquals(true, MacMemoryWarningRuntime.telemetry().get("installed"));
    }

    @Test
    void declinesWrongIdentityChangedShapeAndSecondTransform() throws Exception {
        byte[] original = fixture(false);
        assertNull(MacMemoryWarningPlan.transform(ClassSignature.parse(original), original));
        assertNull(MacMemoryWarningPlan.transform(exactSignature(fixture(true)), fixture(true)));

        byte[] transformed = MacMemoryWarningPlan.transform(exactSignature(original), original);
        assertNotNull(transformed);
        assertNull(MacMemoryWarningPlan.transform(ClassSignature.parse(transformed), transformed));
    }

    private static byte[] fixture(boolean changedThreshold) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MacMemoryWarningPlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                MacMemoryWarningPlan.METHOD, MacMemoryWarningPlan.DESCRIPTOR, null, null);
        method.visitCode();
        method.visitLdcInsn(24_576L);
        method.visitVarInsn(Opcodes.LSTORE, 9);
        method.visitLdcInsn(128L);
        method.visitVarInsn(Opcodes.LSTORE, 11);
        method.visitVarInsn(Opcodes.LLOAD, 11);
        method.visitLdcInsn(changedThreshold ? 999L : 1_000L);
        method.visitInsn(Opcodes.LCMP);
        var done = new org.objectweb.asm.Label();
        method.visitJumpInsn(Opcodes.IFGE, done);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitLdcInsn(MacMemoryWarningPlan.WARNING);
        method.visitInsn(Opcodes.POP2);
        method.visitLabel(done);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(2, 13);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassSignature exactSignature(byte[] bytes) throws Exception {
        ClassSignature parsed = ClassSignature.parse(bytes);
        return new ClassSignature(parsed.internalName(), MacMemoryWarningPlan.ORIGINAL_SHA256,
                parsed.majorVersion(), parsed.access(), parsed.methods());
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static MethodNode method(ClassNode owner) {
        return owner.methods.stream()
                .filter(candidate -> MacMemoryWarningPlan.METHOD.equals(candidate.name)
                        && MacMemoryWarningPlan.DESCRIPTOR.equals(candidate.desc))
                .findFirst().orElseThrow();
    }

    private static int calls(MethodNode method, String owner, String name) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner) && name.equals(call.name)) {
                result++;
            }
        }
        return result;
    }

    private static int constants(MethodNode method, Object expected) {
        int result = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof LdcInsnNode constant && expected.equals(constant.cst)) {
                result++;
            }
        }
        return result;
    }
}
