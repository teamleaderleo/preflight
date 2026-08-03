package dev.starsector.preflight.agent;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;

/** Manual no-game linkage check intended for Starsector's own verification-disabled JVM. */
public final class SimOpponentSafetyInstalledLinkMain {
    private SimOpponentSafetyInstalledLinkMain() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Expected the installed starfarer_obf.jar path");
        }
        Path archive = Path.of(arguments[0]).toAbsolutePath().normalize();
        byte[] original;
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(SimOpponentSafetyPlan.TARGET_CLASS + ".class");
            if (entry == null) throw new IllegalStateException("Installed target class is absent");
            try (var input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        ClassSignature signature = ClassSignature.parse(original);
        if (!SimOpponentSafetyPlan.ORIGINAL_SHA256.equals(signature.sha256())) {
            throw new IllegalStateException("Installed target class hash is not reviewed");
        }
        byte[] transformed = SimOpponentSafetyPlan.transform(signature, original);
        if (transformed == null) throw new IllegalStateException("Installed transform declined");

        List<URL> classpath;
        try (var files = Files.list(archive.getParent())) {
            classpath = files.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .map(path -> {
                        try {
                            return path.toUri().toURL();
                        } catch (java.net.MalformedURLException error) {
                            throw new IllegalArgumentException(error);
                        }
                    })
                    .toList();
        }
        try (InstalledLoader loader = new InstalledLoader(
                classpath.toArray(URL[]::new), transformed)) {
            String expected = SimOpponentSafetyPlan.TARGET_CLASS.replace('/', '.');
            Class<?> linked = loader.loadClass(expected);
            if (!expected.equals(linked.getName())) {
                throw new IllegalStateException("Linked the wrong installed class");
            }
        }
        System.out.println("sim-opponent-installed-link-ok");
    }

    private static final class InstalledLoader extends URLClassLoader {
        private final byte[] transformed;

        private InstalledLoader(URL[] classpath, byte[] transformed) {
            super(classpath, SimOpponentSafetyInstalledLinkMain.class.getClassLoader());
            this.transformed = transformed.clone();
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (name.equals(SimOpponentSafetyPlan.TARGET_CLASS.replace('/', '.'))) {
                return defineClass(name, transformed, 0, transformed.length);
            }
            return super.findClass(name);
        }
    }
}
