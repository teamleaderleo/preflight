package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.GeneratedBytecodeCache;
import dev.starsector.preflight.core.GeneratedBytecodeContext;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Opt-in no-game fidelity test against Starsector's own Janino and commons-compiler archives. */
class JaninoInstalledAdapterIT {
    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void reset() {
        JaninoBytecodeCacheRuntime.beginSession();
    }

    @Test
    void installedJaninoCompilesStoresHitsAndDefinesEveryGeneratedClass() throws Exception {
        String configured = System.getProperty("preflight.janino.jar");
        Assumptions.assumeTrue(configured != null && !configured.isBlank(),
                "set -Dpreflight.janino.jar=/path/to/janino.jar");
        Path janino = Path.of(configured).toAbsolutePath().normalize();
        Path commons = janino.resolveSibling("commons-compiler.jar");
        Assumptions.assumeTrue(Files.isRegularFile(janino) && Files.isRegularFile(commons));

        byte[] original;
        try (URLClassLoader archive = new URLClassLoader(
                new URL[] {janino.toUri().toURL()}, null);
             InputStream input = archive.getResourceAsStream(
                     JaninoBytecodeCachePlan.TARGET_CLASS + ".class")) {
            assertNotNull(input);
            original = input.readAllBytes();
        }
        ClassSignature signature = ClassSignature.parse(original);
        AdapterTarget target = AdapterTargetRegistry.janinoBytecodeCacheTarget();
        assertEquals(target.sha256(), signature.sha256(),
                "the installed compiler must be the exact reviewed Janino class");
        byte[] transformed = JaninoBytecodeCachePlan.transform(signature, original);
        assertNotNull(transformed);

        Path sources = temporaryDirectory.resolve("sources");
        Path source = sources.resolve("scripts/Example.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source,
                "package scripts; public class Example {"
                        + " public static int value() { return 7; }"
                        + " public static class Nested { public int value() { return 8; } }"
                        + " }");
        GeneratedBytecodeContext context = new GeneratedBytecodeContext(
                "11".repeat(32), "12".repeat(32), "13".repeat(32), "14".repeat(32),
                "15".repeat(32), "16".repeat(32), "17".repeat(32));
        Path cache = temporaryDirectory.resolve("cache");
        JaninoBytecodeCacheRuntime.configure(cache, context.portableToken());

        try (InstalledLoader first = new InstalledLoader(janino, commons, transformed)) {
            Class<?> example = compile(first, sources);
            assertEquals(7, example.getMethod("value").invoke(null));
            assertEquals(1L, JaninoBytecodeCacheRuntime.telemetry().get("stored"));
        }
        try (InstalledLoader second = new InstalledLoader(janino, commons, transformed)) {
            Class<?> example = compile(second, sources);
            assertEquals(7, example.getMethod("value").invoke(null));
            Class<?> nested = example.getClassLoader().loadClass("scripts.Example$Nested");
            assertEquals(8, nested.getMethod("value").invoke(nested.getConstructor().newInstance()));
            assertEquals(1L, JaninoBytecodeCacheRuntime.telemetry().get("hits"));
        }

        Path bundle = GeneratedBytecodeCache.bundlePath(
                cache, context.keySha256(), "scripts.Example");
        assertTrue(Files.isRegularFile(bundle));
    }

    private static Class<?> compile(InstalledLoader classes, Path sources) throws Exception {
        Class<?> loaderType = classes.loadClass("org.codehaus.janino.JavaSourceClassLoader");
        Constructor<?> constructor = loaderType.getConstructor(
                ClassLoader.class, File[].class, String.class);
        Object compiler = constructor.newInstance(
                ClassLoader.getSystemClassLoader(), new File[] {sources.toFile()}, "UTF-8");
        loaderType.getMethod("setDebuggingInfo", boolean.class, boolean.class, boolean.class)
                .invoke(compiler, true, true, true);
        return (Class<?>) loaderType.getMethod("loadClass", String.class)
                .invoke(compiler, "scripts.Example");
    }

    private static final class InstalledLoader extends URLClassLoader {
        private final byte[] transformed;

        private InstalledLoader(Path janino, Path commons, byte[] transformed) throws Exception {
            super(new URL[] {janino.toUri().toURL(), commons.toUri().toURL()},
                    JaninoInstalledAdapterIT.class.getClassLoader());
            this.transformed = transformed.clone();
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("org.codehaus.")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException absent) {
                        loaded = super.loadClass(name, false);
                    }
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (name.equals("org.codehaus.janino.JavaSourceClassLoader")) {
                return defineClass(name, transformed, 0, transformed.length);
            }
            return super.findClass(name);
        }
    }
}
