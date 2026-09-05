package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JaninoUnitMemoInstalledIT {
    @TempDir Path directory;
    @AfterEach void reset() {
        System.clearProperty(JaninoUnitMemoRuntime.PROPERTY);
        JaninoUnitMemoRuntime.beginSession();
    }

    @Test void liveMemoPreservesEveryGeneratedByteAndDependencyDiscovery() throws Exception {
        String configured = System.getProperty("preflight.janino.jar");
        Assumptions.assumeTrue(configured != null);
        Path jar = Path.of(configured), commons = jar.resolveSibling("commons-compiler.jar");
        byte[] original;
        try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
            original = zip.getInputStream(zip.getEntry(JaninoBytecodeCachePlan.TARGET_CLASS + ".class")).readAllBytes();
        }
        byte[] transformed = JaninoUnitMemoPlan.transform(ClassSignature.parse(original), original);
        assertNotNull(transformed);
        assertEquals(callsExceptCompile(original), callsExceptCompile(transformed),
                "source discovery, output assembly and class definition must retain their calls");
        System.setProperty(JaninoUnitMemoRuntime.PROPERTY, "true");
        Path sources = directory.resolve("scripts"); Files.createDirectories(sources);
        Files.writeString(sources.resolve("A.java"), "package scripts; public class A { private int x=7; public class N { public int value(){return x;} } public static int base(){return 3;} }");
        Files.writeString(sources.resolve("B.java"), "package scripts; public class B extends A { public static int value(){ A a=new A(); return a.new N().value()+base(); } }");
        Files.writeString(sources.resolve("C.java"), "package scripts; public class C { public static int value(){ return D.value()+B.value(); } }");
        Files.writeString(sources.resolve("D.java"), "package scripts; public class D { public static int value(){return 5;} }");
        List<Map<String, byte[]>> baseline = generate(jar, commons, null);
        List<Map<String, byte[]>> memo = generate(jar, commons, transformed);
        assertEquals(baseline.size(), memo.size());
        for (int i=0; i<baseline.size(); i++) {
            assertEquals(baseline.get(i).keySet(), memo.get(i).keySet());
            for (String name : baseline.get(i).keySet()) {
                assertArrayEquals(baseline.get(i).get(name), memo.get(i).get(name), "request " + i + ": " + name);
            }
        }
        assertTrue((long) JaninoUnitMemoRuntime.report().get("hits") > 0);
        System.out.println("installed unit memo: " + JaninoUnitMemoRuntime.report());
    }

    private static List<String> callsExceptCompile(byte[] bytes) {
        var owner = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(bytes).accept(owner, 0);
        List<String> calls = new ArrayList<>();
        for (var method : owner.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                        && !call.owner.endsWith("JaninoUnitMemoRuntime")
                        && !(call.owner.equals(JaninoUnitMemoPlan.UNIT) && call.name.equals("compileUnit"))) {
                    calls.add(method.name + method.desc + ":" + call.owner + "." + call.name + call.desc);
                }
            }
        }
        return calls;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, byte[]>> generate(Path jar, Path commons, byte[] bytes) throws Exception {
        try (InstalledLoader classes = new InstalledLoader(jar, commons, bytes)) {
            Class<?> type = classes.loadClass("org.codehaus.janino.JavaSourceClassLoader");
            Object compiler = type.getConstructor(ClassLoader.class, File[].class, String.class)
                    .newInstance(getClass().getClassLoader(), new File[]{directory.toFile()}, "UTF-8");
            type.getMethod("setDebuggingInfo", boolean.class, boolean.class, boolean.class)
                    .invoke(compiler, true, true, true);
            Method generate = type.getDeclaredMethod("generateBytecodes", String.class);
            generate.setAccessible(true);
            List<Map<String, byte[]>> results = new ArrayList<>();
            for (String name : List.of("scripts.A", "scripts.B", "scripts.C", "scripts.D")) {
                results.add((Map<String, byte[]>) generate.invoke(compiler, name));
            }
            Class<?> c = (Class<?>) type.getMethod("loadClass", String.class).invoke(compiler, "scripts.C");
            assertEquals(15, c.getMethod("value").invoke(null));
            return results;
        }
    }

    private static final class InstalledLoader extends URLClassLoader {
        private final byte[] transformed;

        private InstalledLoader(Path janino, Path commons, byte[] transformed) throws Exception {
            super(new URL[] {janino.toUri().toURL(), commons.toUri().toURL()},
                    JaninoUnitMemoInstalledIT.class.getClassLoader());
            this.transformed = transformed == null ? null : transformed.clone();
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
            if (transformed != null && name.equals("org.codehaus.janino.JavaSourceClassLoader")) {
                return defineClass(name, transformed, 0, transformed.length);
            }
            return super.findClass(name);
        }
    }
}
