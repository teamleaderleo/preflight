package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class WindowsPcmCopyInstalledTest {
    @BeforeEach @AfterEach void reset() {
        System.clearProperty(WindowsPcmCopyRuntime.PROPERTY);
        WindowsPcmCopyRuntime.beginSession();
    }

    @Test @Timeout(60)
    void exactInstalledDecoderPreservesEveryPcmByteMetadataAndNullFailure() throws Exception {
        String configured = System.getProperty("preflight.windows.audio.fixtures", "");
        Assumptions.assumeFalse(configured.isBlank(), "Supply private installed sound JAR and Ogg fixtures");
        Path root = Path.of(configured);
        byte[] original;
        try (JarFile jar = new JarFile(root.resolve("fs.sound_obf.jar").toFile())) {
            original = jar.getInputStream(jar.getJarEntry("sound/O0oO.class")).readAllBytes();
        }
        byte[] transformed = WindowsPcmCopyPlan.transform(ClassSignature.parse(original), original);
        assertNotNull(transformed);
        assertNull(WindowsPcmCopyPlan.transform(ClassSignature.parse(transformed), transformed));
        var node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(transformed).accept(node, 0);
        for (var method : node.methods) {
            new org.objectweb.asm.tree.analysis.Analyzer<>(
                    new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(node.name, method);
        }
        URL[] urls = new URL[5];
        int i = 0;
        for (String file : List.of("fs.sound_obf.jar", "jogg-0.0.7.jar", "jorbis-0.0.15.jar", "log4j-1.2.9.jar", "lwjgl.jar")) {
            urls[i++] = root.resolve(file).toUri().toURL();
        }
        try (URLClassLoader stock = loader(urls, original); URLClassLoader fast = loader(urls, transformed)) {
            System.setProperty(WindowsPcmCopyRuntime.PROPERTY, "true");
            for (String file : List.of("small.ogg", "medium.ogg", "large.ogg")) {
                byte[] encoded = Files.readAllBytes(root.resolve(file));
                Pcm expected = decode(stock, new ByteArrayInputStream(encoded));
                Pcm actual = decode(fast, new ByteArrayInputStream(encoded));
                assertEquals(expected.channels, actual.channels, file);
                assertEquals(expected.rate, actual.rate, file);
                assertEquals(expected.position, actual.position, file);
                assertArrayEquals(expected.bytes, actual.bytes, file);
            }
            assertEquals(3L, WindowsPcmCopyRuntime.report().get("completed"));
            assertEquals(0L, WindowsPcmCopyRuntime.report().get("declined"));
            assertTrue((long) WindowsPcmCopyRuntime.report().get("bulkBytes") > 0);
            System.setProperty(WindowsPcmCopyRuntime.PROPERTY, "false");
            byte[] encoded = Files.readAllBytes(root.resolve("medium.ogg"));
            assertArrayEquals(decode(stock, new ByteArrayInputStream(encoded)).bytes,
                    decode(fast, new ByteArrayInputStream(encoded)).bytes);
            assertEquals(3L, WindowsPcmCopyRuntime.report().get("completed"));
            Throwable before = assertThrows(InvocationTargetException.class, () -> decode(stock, null)).getCause();
            Throwable after = assertThrows(InvocationTargetException.class, () -> decode(fast, null)).getCause();
            assertEquals(before.getClass(), after.getClass());
            assertEquals(before.getMessage(), after.getMessage());
        }
    }

    static URLClassLoader loader(URL[] urls, byte[] decoder) {
        return loader(urls, decoder, "sound.O0oO");
    }
    static URLClassLoader loader(URL[] urls, byte[] decoder, String decoderName) {
        return new URLClassLoader(urls, WindowsPcmCopyInstalledTest.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("sound.") && !name.startsWith("com.jcraft.")
                        && !name.startsWith("org.apache.log4j.")) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null) type = findClass(name);
                    if (resolve) resolveClass(type);
                    return type;
                }
            }
            @Override public URL getResource(String name) {
                return name.startsWith("sound/") ? findResource(name) : super.getResource(name);
            }
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.equals(decoderName)) return defineClass(name, decoder, 0, decoder.length);
                return super.findClass(name);
            }
        };
    }
    static Pcm decode(ClassLoader loader, InputStream input) throws Exception {
        return decode(loader, input, "sound.O0oO");
    }
    static Pcm decode(ClassLoader loader, InputStream input, String decoderName) throws Exception {
        Class<?> type = Class.forName(decoderName, true, loader);
        Object result = type.getMethod("super", InputStream.class).invoke(type.getConstructor().newInstance(), input);
        Class<?> shape = result.getClass();
        ByteBuffer buffer = (ByteBuffer) shape.getField("Object").get(result);
        assertTrue(buffer.isDirect());
        int position = buffer.position();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        return new Pcm(bytes, shape.getField("o00000").getInt(result), shape.getField("Ò00000").getInt(result), position);
    }
    record Pcm(byte[] bytes, int channels, int rate, int position) { }
}
