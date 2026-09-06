package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.*;

import dev.starsector.preflight.core.*;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class LinuxPreparedAudioInstalledTest {
    @TempDir Path cache;

    private static java.net.URLClassLoader loader(URL[] urls, byte[] bytes) throws Exception {
        byte[] owner;
        try (JarFile jar = new JarFile(Path.of(urls[0].toURI()).toFile())) {
            owner = jar.getInputStream(jar.getJarEntry("sound/Object.class")).readAllBytes();
        }
        byte[] fenced = LinuxAudioBufferFencePlan.transform(ClassSignature.parse(owner), owner);
        assertNotNull(fenced);
        return WindowsPcmCopyInstalledTest.loader(urls, bytes, "sound.J", java.util.Map.of("sound.Object", fenced));
    }

    private static WindowsPcmCopyInstalledTest.Pcm decode(ClassLoader loader, java.io.InputStream input)
            throws Exception {
        return WindowsPcmCopyInstalledTest.decode(loader, input, "sound.J");
    }


    @AfterEach void reset() {
        PreparedAudioRuntime.enable(false);
        PreparedAudioRuntime.configure(null, null);
        PreparedAudioRuntime.reset();
    }

    @Test @Timeout(60)
    void installedLinuxCacheReturnsExactPcmAndRetainsOriginalFallback() throws Exception {
        String configured = System.getProperty("preflight.linux.audio.fixtures", "");
        Assumptions.assumeFalse(configured.isBlank(), "Supply private installed Linux audio fixtures");
        Path root = Path.of(configured);
        byte[] original;
        try (JarFile jar = new JarFile(root.resolve("fs.sound_obf.jar").toFile())) {
            original = jar.getInputStream(jar.getJarEntry("sound/J.class")).readAllBytes();
        }
        var target = AdapterTargetRegistry.linuxPreparedAudioTarget();
        var source = new AdapterSourceIdentity("file:///starsector/fs.sound_obf.jar",
                "/starsector/fs.sound_obf.jar", "STARSECTOR_CORE",
                Hashes.sha256(Files.readAllBytes(root.resolve("fs.sound_obf.jar"))), "",
                "jdk/internal/loader/ClassLoaders$AppClassLoader", "app");
        assertTrue(target.match(ClassSignature.parse(original), source).exact());
        assertFalse(target.match(ClassSignature.parse(original), AdapterSourceIdentity.unknown()).exact());
        byte[] fastBytes = PreparedAudioPlan.transformLinux(ClassSignature.parse(original), original);
        assertNotNull(fastBytes);
        assertNull(PreparedAudioPlan.transformLinux(ClassSignature.parse(fastBytes), fastBytes));
        var node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(fastBytes).accept(node, 0);
        for (var method : node.methods) new org.objectweb.asm.tree.analysis.Analyzer<>(
                new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(node.name, method);
        URL[] urls = new URL[5];
        int index = 0;
        for (String name : List.of("fs.sound_obf.jar", "jogg-0.0.7.jar", "jorbis-0.0.15.jar", "log4j-1.2.9.jar", "lwjgl.jar")) {
            urls[index++] = root.resolve(name).toUri().toURL();
        }
        String identity = Hashes.sha256("installed-test-policy".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        PreparedAudioRuntime.reset();
        PreparedAudioRuntime.enable(true);
        PreparedAudioRuntime.configure(cache, identity);
        try (var stock = loader(urls, original); var fast = loader(urls, fastBytes)) {
            for (String name : List.of("small.ogg", "medium.ogg", "large.ogg")) {
                byte[] encoded = Files.readAllBytes(root.resolve(name));
                var expected = decode(stock, new ByteArrayInputStream(encoded));
                assertEquals(0, expected.bytes().length % (expected.channels() * 2), name);
                PreparedAudio audio = new PreparedAudio(Hashes.sha256(encoded), identity,
                        PreparedAudio.Policy.FULLY_DECODED_EFFECT, PreparedAudio.PcmEncoding.PCM_SIGNED,
                        16, PreparedAudio.ByteOrder.LITTLE_ENDIAN, expected.rate(), expected.channels(),
                        expected.bytes().length / (expected.channels() * 2L), expected.bytes());
                Path blob = PreparedAudioCache.blobPath(cache, Hashes.sha256(encoded), identity,
                        PreparedAudio.Policy.FULLY_DECODED_EFFECT);
                Files.createDirectories(blob.getParent());
                Files.write(blob, PreparedAudioIO.toBytes(audio));
                long hits = (long) PreparedAudioRuntime.report().get("servedFromCache");
                try (var unfenced = WindowsPcmCopyInstalledTest.loader(urls, fastBytes, "sound.J")) {
                    assertArrayEquals(expected.bytes(), decode(unfenced, new ByteArrayInputStream(encoded)).bytes());
                    assertEquals(hits, PreparedAudioRuntime.report().get("servedFromCache"),
                            "cache admission must decline without the upload lifetime guard");
                }
                var actual = decode(fast, new ByteArrayInputStream(encoded));
                assertEquals(hits + 1, PreparedAudioRuntime.report().get("servedFromCache"));
                assertArrayEquals(expected.bytes(), actual.bytes());
                assertEquals(expected.channels(), actual.channels());
                assertEquals(expected.rate(), actual.rate());
                assertEquals(expected.position(), actual.position());
                Files.write(blob, new byte[] {1, 2, 3});
                assertArrayEquals(expected.bytes(), decode(fast, new ByteArrayInputStream(encoded)).bytes());
                Files.delete(blob);
                assertArrayEquals(expected.bytes(), decode(fast, new ByteArrayInputStream(encoded)).bytes());
            }
            Throwable before = assertThrows(InvocationTargetException.class, () -> decode(stock, null)).getCause();
            Throwable after = assertThrows(InvocationTargetException.class, () -> decode(fast, null)).getCause();
            assertEquals(before.getClass(), after.getClass());
            assertEquals(before.getMessage(), after.getMessage());
            byte[] encoded = Files.readAllBytes(root.resolve("medium.ogg"));
            // Unknown stream types must reach the original without the cache invoking readAllBytes.
            java.io.InputStream custom = new ByteArrayInputStream(encoded) {
                @Override public byte[] readAllBytes() { throw new AssertionError("Custom stream intercepted"); }
            };
            assertArrayEquals(decode(stock, new ByteArrayInputStream(encoded)).bytes(), decode(fast, custom).bytes());
            PreparedAudioRuntime.enable(false);
            assertArrayEquals(decode(stock, new ByteArrayInputStream(encoded)).bytes(),
                    decode(fast, new ByteArrayInputStream(encoded)).bytes());
        }
    }
}
