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
import org.objectweb.asm.tree.MethodInsnNode;

class AudioResourceFallbackPlanTest {
    private static final String RUNTIME =
            AudioResourceFallbackRuntime.class.getName().replace('.', '/');

    @AfterEach
    void reset() {
        AudioResourceFallbackRuntime.reset();
    }

    @Test
    void replacesOnlyTheThreeReviewedClassResourceLookups() throws Exception {
        byte[] original = fixture(false);
        byte[] transformed = AudioResourceFallbackPlan.transform(exactSignature(original), original);
        assertNotNull(transformed);

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        assertEquals(3, calls(owner, RUNTIME, "open"));
        assertEquals(0, calls(owner, "java/lang/Class", "getResourceAsStream"));
        assertEquals(true, AudioResourceFallbackRuntime.telemetry().get("installed"));
        assertNull(AudioResourceFallbackPlan.transform(ClassSignature.parse(transformed), transformed));
    }

    @Test
    void declinesWrongIdentityAndChangedCallShape() throws Exception {
        byte[] original = fixture(false);
        assertNull(AudioResourceFallbackPlan.transform(ClassSignature.parse(original), original));
        byte[] changed = fixture(true);
        assertNull(AudioResourceFallbackPlan.transform(exactSignature(changed), changed));
    }

    private static byte[] fixture(boolean omitLastLookup) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, AudioResourceFallbackPlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        for (int index = 0; index < AudioResourceFallbackPlan.METHODS.size(); index++) {
            String name = AudioResourceFallbackPlan.METHODS.get(index);
            MethodVisitor method = writer.visitMethod(
                    Opcodes.ACC_PUBLIC, name, AudioResourceFallbackPlan.STRING_DESCRIPTOR, null, null);
            method.visitCode();
            if (omitLastLookup && index == AudioResourceFallbackPlan.METHODS.size() - 1) {
                method.visitInsn(Opcodes.ACONST_NULL);
            } else {
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                        "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
                method.visitVarInsn(Opcodes.ALOAD, 1);
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class",
                        "getResourceAsStream", "(Ljava/lang/String;)Ljava/io/InputStream;", false);
                method.visitInsn(Opcodes.POP);
                method.visitInsn(Opcodes.ACONST_NULL);
            }
            method.visitInsn(Opcodes.ARETURN);
            method.visitMaxs(2, 2);
            method.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassSignature exactSignature(byte[] bytes) throws Exception {
        ClassSignature parsed = ClassSignature.parse(bytes);
        return new ClassSignature(parsed.internalName(), AudioResourceFallbackPlan.ORIGINAL_SHA256,
                parsed.majorVersion(), parsed.access(), parsed.methods());
    }

    private static int calls(ClassNode owner, String callOwner, String name) {
        int result = 0;
        for (var method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && callOwner.equals(call.owner) && name.equals(call.name)) result++;
            }
        }
        return result;
    }
}
