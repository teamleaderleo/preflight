package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

class PreparedAudioPathPlanTest {
    private static final String RUNTIME =
            PreparedAudioRuntime.class.getName().replace('.', '/');

    @Test
    void passesTheLoaderFilenameBesideTheUntouchedInputStream() {
        byte[] transformed = PreparedAudioPathPlan.transform(fixture(false));
        assertNotNull(transformed);
        ClassNode owner = node(transformed);
        assertEquals(1, calls(owner, RUNTIME, "decodePath"));
        assertEquals(0, calls(owner, "sound/J", "o00000"));
        assertNull(PreparedAudioPathPlan.transform(transformed));
    }

    @Test
    void declinesChangedDecoderCallShape() {
        assertNull(PreparedAudioPathPlan.transform(fixture(true)));
    }

    private static byte[] fixture(boolean wrongInputLocal) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, PreparedAudioPathPlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC,
                PreparedAudioPathPlan.LOAD_METHOD,
                PreparedAudioPathPlan.LOAD_DESCRIPTOR,
                null,
                new String[] {"java/io/IOException"});
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, "sound/J");
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "sound/J", "<init>", "()V", false);
        method.visitVarInsn(Opcodes.ALOAD, wrongInputLocal ? 1 : 2);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "sound/J", "o00000",
                "(Ljava/io/InputStream;)Lsound/F;", false);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(2, 3);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static int calls(ClassNode owner, String callOwner, String name) {
        int count = 0;
        for (var method : owner.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && callOwner.equals(call.owner) && name.equals(call.name)) count++;
            }
        }
        return count;
    }
}
