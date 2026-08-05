package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class LoadingUtilsReaderPlanTest {
    @Test
    void replacesOnlyTheReviewedStaticReader() throws Exception {
        byte[] original = fixture(true);
        byte[] rewritten = LoadingUtilsReaderPlan.transform(
                ClassSignature.parse(original), original);
        assertNotNull(rewritten);
        Class<?> loaded = new ByteArrayLoader().define(rewritten);
        Object result = loaded.getMethod("super", InputStream.class).invoke(null,
                new ByteArrayInputStream("a\r\nb".getBytes(StandardCharsets.UTF_8)));
        assertEquals("a\nb", result);

        assertNull(LoadingUtilsReaderPlan.transform(ClassSignature.parse(rewritten), rewritten),
                "a rewritten reader no longer has the reviewed fixture body");
        assertNull(LoadingUtilsReaderPlan.transform(
                ClassSignature.parse(fixture(false)), fixture(false)));
    }

    private static byte[] fixture(boolean staticMethod) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, LoadingUtilsReaderPlan.TARGET_CLASS,
                null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC
                        | (staticMethod ? Opcodes.ACC_STATIC : 0),
                LoadingUtilsReaderPlan.METHOD, LoadingUtilsReaderPlan.DESCRIPTOR, null,
                new String[] {"java/io/IOException"});
        method.visitCode();
        method.visitLdcInsn("vanilla");
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, staticMethod ? 1 : 2);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class ByteArrayLoader extends ClassLoader {
        private ByteArrayLoader() {
            super(LoadingUtilsReaderPlanTest.class.getClassLoader());
        }

        private Class<?> define(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }
}
