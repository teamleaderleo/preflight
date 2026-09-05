package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SafeClassWriterTest {
    private final SafeClassWriter writer = new SafeClassWriter(0, true);

    @Test
    void legacyConstructorsAndExplicitOffPreserveHistoricalMerge() {
        var bytes = new org.objectweb.asm.ClassWriter(0);
        bytes.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC,
                "Fixture", null, "java/lang/Object", null);
        bytes.visitEnd();
        for (SafeClassWriter legacy : new SafeClassWriter[] {
                new SafeClassWriter(0), new SafeClassWriter(0, false),
                new SafeClassWriter(new org.objectweb.asm.ClassReader(bytes.toByteArray()), 0)}) {
            assertEquals("java/lang/Object", legacy.getCommonSuperClass(
                    "java/io/InputStream", "java/io/BufferedInputStream"));
            assertEquals("java/io/InputStream", legacy.getCommonSuperClass(
                    "java/io/InputStream", "java/io/InputStream"));
        }
    }

    @Test
    void preservesBootstrapInputStreamMergeInBothOrders() {
        assertEquals("java/io/InputStream", writer.getCommonSuperClass(
                "java/io/InputStream", "java/io/BufferedInputStream"));
        assertEquals("java/io/InputStream", writer.getCommonSuperClass(
                "java/io/BufferedInputStream", "java/io/InputStream"));
    }

    @Test
    void findsSharedBootstrapAncestorsAndAssignableInterfaces() {
        assertEquals("java/util/AbstractList", writer.getCommonSuperClass(
                "java/util/ArrayList", "java/util/LinkedList"));
        assertEquals("java/util/List", writer.getCommonSuperClass(
                "java/util/List", "java/util/ArrayList"));
        assertEquals("java/lang/Object", writer.getCommonSuperClass(
                "java/util/List", "java/util/Map"));
    }

    @Test
    void neverConsultsContextLoaderForBootstrapOrApplicationTypes() {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        AtomicInteger attempts = new AtomicInteger();
        thread.setContextClassLoader(new ClassLoader(null) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                attempts.incrementAndGet();
                throw new ClassNotFoundException(name);
            }
        });
        try {
            assertEquals("java/io/InputStream", writer.getCommonSuperClass(
                    "java/io/InputStream", "java/io/BufferedInputStream"));
            assertEquals("java/lang/Object", writer.getCommonSuperClass(
                    "com/fs/graphics/Object", "com/fs/graphics/TextureLoader"));
            assertEquals("java/lang/Object", writer.getCommonSuperClass(
                    "java/io/InputStream", "com/fs/graphics/Object"));
            assertEquals("com/fs/graphics/Object", writer.getCommonSuperClass(
                    "com/fs/graphics/Object", "com/fs/graphics/Object"));
            assertEquals(0, attempts.get());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void unknownBootstrapNamesDeclineToObject() {
        assertEquals("java/lang/Object", writer.getCommonSuperClass(
                "java/not/Installed", "java/io/InputStream"));
    }
}
