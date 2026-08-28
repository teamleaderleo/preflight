package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Opt-in installed-core check for issue #1153's vanilla particle render probe. */
class DynamicParticleGroupRenderProbeInstalledAdapterIT {
    private static final String STOCK_COMMON_JAR_SHA256 =
            "10d89e113f6d1627cc7bc90b692e8a7f450fdd820c5a4ac5edaecd6710afe708";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/DynamicParticleGroupRenderProbeRuntime";

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void reset() {
        DynamicParticleGroupRenderProbeRuntime.resetForTest();
    }

    @Test
    void exactInstalledCoreTransformsWhileChangedClassAndArchiveFailClosed() throws Exception {
        String configured = System.getProperty("preflight.starsector.common.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(),
                "set -Dpreflight.starsector.common.jar=<fs.common_obf.jar>");
        Path installed = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(installed),
                "configured fs.common_obf.jar does not exist");
        assertEquals(STOCK_COMMON_JAR_SHA256, sha256(installed),
                "configured jar is not the reviewed Starsector 0.98a-RC8 common archive");

        Path exact = temporaryDirectory.resolve("exact/contents/resources/java/fs.common_obf.jar");
        Files.createDirectories(exact.getParent());
        Files.copy(installed, exact);
        byte[] original = classBytes(exact);
        ClassSignature originalSignature = ClassSignature.parse(original);
        assertEquals(DynamicParticleGroupRenderProbePlan.TARGET_CLASS, originalSignature.internalName());
        assertTrue(originalSignature.hasMethod(
                DynamicParticleGroupRenderProbePlan.RENDER_METHOD,
                DynamicParticleGroupRenderProbePlan.RENDER_DESCRIPTOR));

        AdapterTargetRegistry registry = AdapterTargetRegistry.empty()
                .withDynamicParticleGroupProbeTarget();
        DynamicParticleGroupRenderProbeRuntime.beginSessionForTest(true);

        byte[] transformed = transform(
                registry, exact, original, temporaryDirectory.resolve("exact-report.json"));
        assertNotNull(transformed, Files.readString(temporaryDirectory.resolve("exact-report.json")));
        MethodNode render = method(transformed);
        assertEquals(1, calls(render, RUNTIME, "begin"));
        assertTrue(calls(render, RUNTIME, "end") >= 1);

        Map<String, Object> telemetry = DynamicParticleGroupRenderProbeRuntime.telemetry();
        assertEquals(true, telemetry.get("installed"));
        assertTrue(((Integer) telemetry.get("returnSites")) >= 1);

        assertNull(transform(
                registry,
                exact,
                transformed,
                temporaryDirectory.resolve("changed-class-report.json")));

        Path changedArchive = temporaryDirectory.resolve(
                "changed/contents/resources/java/fs.common_obf.jar");
        Files.createDirectories(changedArchive.getParent());
        Files.copy(installed, changedArchive);
        Files.write(changedArchive, new byte[] {0}, StandardOpenOption.APPEND);
        assertNull(transform(
                registry,
                changedArchive,
                original,
                temporaryDirectory.resolve("changed-archive-report.json")));
    }

    private byte[] transform(
            AdapterTargetRegistry registry, Path archive, byte[] bytes, Path reportPath) throws Exception {
        AdapterReport report = new AdapterReport(
                AdapterMode.ENABLED, reportPath, null, List.of("com/fs/graphics/"));
        AdapterProbeTransformer transformer = new AdapterProbeTransformer(
                AdapterMode.ENABLED, registry, List.of("com/fs/graphics/"), report);
        CodeSource codeSource = new CodeSource(archive.toUri().toURL(), (Certificate[]) null);
        ProtectionDomain domain = new ProtectionDomain(codeSource, null);
        byte[] result = transformer.transform(
                ClassLoader.getSystemClassLoader(),
                DynamicParticleGroupRenderProbePlan.TARGET_CLASS,
                null,
                domain,
                bytes);
        report.write();
        return result;
    }

    private static byte[] classBytes(Path archive) throws Exception {
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(DynamicParticleGroupRenderProbePlan.TARGET_CLASS + ".class");
            assertNotNull(entry, "fs.common_obf.jar has no DynamicParticleGroup.class");
            try (InputStream input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static MethodNode method(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner.methods.stream()
                .filter(candidate -> DynamicParticleGroupRenderProbePlan.RENDER_METHOD.equals(candidate.name)
                        && DynamicParticleGroupRenderProbePlan.RENDER_DESCRIPTOR.equals(candidate.desc))
                .findFirst()
                .orElseThrow();
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)) {
                count++;
            }
        }
        return count;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) >= 0; ) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
