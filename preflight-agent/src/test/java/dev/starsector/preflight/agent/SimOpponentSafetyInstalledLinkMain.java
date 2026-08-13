package dev.starsector.preflight.agent;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        Map<String, byte[]> transformed = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(archive.toFile())) {
            byte[] safetyOriginal = read(jar, SimOpponentSafetyPlan.TARGET_CLASS);
            ClassSignature safetySignature = ClassSignature.parse(safetyOriginal);
            if (!SimOpponentSafetyPlan.ORIGINAL_SHA256.equals(safetySignature.sha256())) {
                throw new IllegalStateException("Installed safety target hash is not reviewed");
            }
            byte[] safety = SimOpponentSafetyPlan.transform(safetySignature, safetyOriginal);
            if (safety == null) throw new IllegalStateException("Installed safety transform declined");
            transformed.put(SimOpponentSafetyPlan.TARGET_CLASS.replace('/', '.'), safety);

            byte[] dialogOriginal = read(jar, SimOpponentDialogProbePlan.TARGET_CLASS);
            ClassSignature dialogSignature = ClassSignature.parse(dialogOriginal);
            if (!SimOpponentDialogProbePlan.ORIGINAL_SHA256.equals(dialogSignature.sha256())) {
                throw new IllegalStateException("Installed dialog target hash is not reviewed");
            }
            byte[] dialog = SimOpponentDialogProbePlan.transform(dialogSignature, dialogOriginal);
            if (dialog == null) throw new IllegalStateException("Installed dialog transform declined");
            transformed.put(SimOpponentDialogProbePlan.TARGET_CLASS.replace('/', '.'), dialog);
        }

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
            for (String expected : transformed.keySet()) {
                Class<?> linked = loader.loadClass(expected);
                if (!expected.equals(linked.getName())) {
                    throw new IllegalStateException("Linked the wrong installed class");
                }
            }
        }
        System.out.println("sim-opponent-installed-link-ok");
    }

    private static byte[] read(JarFile jar, String internalName) throws Exception {
        var entry = jar.getJarEntry(internalName + ".class");
        if (entry == null) throw new IllegalStateException("Installed target class is absent");
        try (var input = jar.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }

    private static final class InstalledLoader extends URLClassLoader {
        private final Map<String, byte[]> transformed;

        private InstalledLoader(URL[] classpath, Map<String, byte[]> transformed) {
            super(classpath, SimOpponentSafetyInstalledLinkMain.class.getClassLoader());
            this.transformed = Map.copyOf(transformed);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = transformed.get(name);
            if (bytes != null) {
                return defineClass(name, bytes, 0, bytes.length);
            }
            return super.findClass(name);
        }
    }
}
