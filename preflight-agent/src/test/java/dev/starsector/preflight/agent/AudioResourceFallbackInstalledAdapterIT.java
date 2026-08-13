package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Exact installed sound-store transform check; it never opens OpenAL or starts the game. */
class AudioResourceFallbackInstalledAdapterIT {
    @AfterEach
    void reset() {
        AudioResourceFallbackRuntime.reset();
    }

    @Test
    void installedStoreReplacesAllThreeRelativeLookups() throws Exception {
        String configured = System.getProperty("preflight.starsector.sound.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.sound.jar=<fs.sound_obf.jar>");
        Path archive = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(archive));

        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(AudioResourceFallbackPlan.TARGET_CLASS + ".class");
            assertNotNull(entry);
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        assertEquals(AudioResourceFallbackPlan.ORIGINAL_SHA256, signature.sha256());
        byte[] transformed = AudioResourceFallbackPlan.transform(signature, original);
        assertNotNull(transformed);
        assertNull(AudioResourceFallbackPlan.transform(ClassSignature.parse(transformed), transformed));

        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(transformed).accept(owner, ClassReader.EXPAND_FRAMES);
        String runtime = AudioResourceFallbackRuntime.class.getName().replace('.', '/');
        assertEquals(3, calls(owner, runtime, "open"));
        assertEquals(0, calls(owner, "java/lang/Class", "getResourceAsStream"));

        Path gameClasses = archive.getParent();
        URL[] classpath = {
                archive.toUri().toURL(),
                gameClasses.toUri().toURL(),
                gameClasses.resolve("log4j-1.2.9.jar").toUri().toURL(),
                gameClasses.resolve("lwjgl.jar").toUri().toURL()
        };
        try (URLClassLoader loader = transformedLoader(classpath, transformed)) {
            Class<?> storeClass = Class.forName("sound.ooOO", true, loader);
            var constructor = storeClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object store = constructor.newInstance();
            var load = storeClass.getDeclaredMethod("Object", String.class);
            load.setAccessible(true);
            Object sound = load.invoke(store, "sounds/sfx_interface/ui_button_mouseover.ogg");
            assertNotNull(sound);
        }

        var telemetry = AudioResourceFallbackRuntime.telemetry();
        assertEquals(1L, telemetry.get("lookups"));
        assertEquals(0L, telemetry.get("originalHits"));
        assertEquals(1L, telemetry.get("fallbackAttempts"));
        assertEquals(1L, telemetry.get("fallbackHits"));
        assertEquals(0L, telemetry.get("fallbackMisses"));
    }

    private static URLClassLoader transformedLoader(URL[] urls, byte[] transformed) {
        return new URLClassLoader(
                urls, AudioResourceFallbackInstalledAdapterIT.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null && "sound.ooOO".equals(name)) {
                        loaded = defineClass(name, transformed, 0, transformed.length);
                    }
                    if (loaded == null && (name.startsWith("sound.")
                            || name.startsWith("org.lwjgl.")
                            || name.startsWith("org.apache.log4j."))) {
                        loaded = findClass(name);
                    }
                    if (loaded == null) loaded = super.loadClass(name, false);
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
        };
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
