package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Opt-in exact-archive transform check; it does not load or launch Starsector. */
class GraphicsLibInsigniaInstalledAdapterIT {
    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void reset() {
        GraphicsLibInsigniaManagerCacheRuntime.beginSession();
    }

    @Test
    void exactStockRendererTransformsWhileChangedClassAndArchiveFailClosed() throws Exception {
        String configured = System.getProperty("preflight.graphicslib.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.graphicslib.jar=<Graphics.jar> for the installed-archive check");
        Path installed = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(installed), "configured Graphics.jar does not exist");

        Path exact = temporaryDirectory.resolve("exact/mods/GraphicsLib/jars/Graphics.jar");
        Files.createDirectories(exact.getParent());
        Files.copy(installed, exact);
        byte[] original = classBytes(exact);
        assertEquals(
                GraphicsLibInsigniaManagerCachePlan.ORIGINAL_SHA256,
                ClassSignature.parse(original).sha256(),
                "configured jar is not reviewed stock 1.12.1");

        GraphicsLibInsigniaManagerCacheRuntime.configure(true);
        assertTrue(GraphicsLibInsigniaManagerCacheRuntime.ready());
        byte[] transformed = transform(exact, original, temporaryDirectory.resolve("exact-report.json"));
        assertNotNull(transformed, Files.readString(temporaryDirectory.resolve("exact-report.json")));
        assertTrue(hasMethod(transformed, "preflight$getFleetManager"));
        ClassNode node = read(transformed);
        MethodNode render = method(node, GraphicsLibInsigniaManagerCachePlan.RENDER_METHOD);
        MethodNode helper = method(node, "preflight$getFleetManager");
        assertEquals(1, calls(render, node.name, "preflight$getFleetManager"));
        assertEquals(0, calls(
                render, "com/fs/starfarer/api/combat/CombatEngineAPI", "getFleetManager"));
        assertEquals(1, calls(
                helper, "com/fs/starfarer/api/combat/CombatEngineAPI", "getFleetManager"));

        assertNull(transform(exact, transformed, temporaryDirectory.resolve("changed-class-report.json")));

        Path changedArchive = temporaryDirectory.resolve("changed/mods/GraphicsLib/jars/Graphics.jar");
        Files.createDirectories(changedArchive.getParent());
        Files.copy(installed, changedArchive);
        Files.write(changedArchive, new byte[] {0}, StandardOpenOption.APPEND);
        assertNull(transform(
                changedArchive, original, temporaryDirectory.resolve("changed-archive-report.json")));
    }

    private byte[] transform(Path archive, byte[] bytes, Path reportPath) throws Exception {
        AdapterTargetRegistry registry = AdapterTargetRegistry.empty()
                .withGraphicsLibInsigniaManagerCacheTarget();
        AdapterReport report = new AdapterReport(
                AdapterMode.ENABLED, reportPath, null, List.of("com/fs/"));
        AdapterProbeTransformer transformer = new AdapterProbeTransformer(
                AdapterMode.ENABLED, registry, List.of("com/fs/"), report);
        CodeSource codeSource = new CodeSource(archive.toUri().toURL(), (Certificate[]) null);
        ProtectionDomain domain = new ProtectionDomain(codeSource, null);
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {archive.toUri().toURL()}, null)) {
            byte[] result = transformer.transform(
                    loader,
                    GraphicsLibInsigniaManagerCachePlan.TARGET_CLASS,
                    null,
                    domain,
                    bytes);
            report.write();
            return result;
        }
    }

    private static byte[] classBytes(Path archive) throws Exception {
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(GraphicsLibInsigniaManagerCachePlan.TARGET_CLASS + ".class");
            assertNotNull(entry, "Graphics.jar has no InsigniaPlugin.class");
            try (var input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static boolean hasMethod(byte[] bytes, String name) {
        ClassNode node = read(bytes);
        return node.methods.stream().anyMatch(method -> name.equals(method.name));
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG);
        return node;
    }

    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(method -> name.equals(method.name)).findFirst().orElseThrow();
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)) {
                count++;
            }
        }
        return count;
    }
}
