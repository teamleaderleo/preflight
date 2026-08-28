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

/** Opt-in exact-archive check for issue #1153's guarded GL11 state cache. */
class GlIsEnabledStateCacheInstalledAdapterIT {
    private static final String STOCK_LWJGL_JAR_SHA256 =
            "527d509f60132e5b2653c7fc0f8cf299d6f698f4a8013342bef47705dc57ed3f";
    private static final String RUNTIME =
            "dev/starsector/preflight/agent/GlIsEnabledStateCacheRuntime";

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void reset() {
        GlIsEnabledStateCacheRuntime.resetForTest();
    }

    @Test
    void exactLwjglArchiveTransformsWhileChangedClassAndArchiveFailClosed() throws Exception {
        String configured = System.getProperty("preflight.lwjgl.jar", "").trim();
        Assumptions.assumeTrue(!configured.isEmpty(), "set -Dpreflight.lwjgl.jar=<lwjgl.jar>");
        Path installed = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(installed), "configured lwjgl.jar does not exist");
        assertEquals(STOCK_LWJGL_JAR_SHA256, sha256(installed),
                "configured jar is not Starsector's reviewed LWJGL archive");

        // AdapterSourceIdentity classifies the real game archive as STARSECTOR_CORE because its
        // normalized path contains the Starsector installation name. Keep that real source signal
        // in the fixture instead of weakening the production target to OTHER.
        Path exact = temporaryDirectory.resolve(
                "Starsector/contents/resources/java/lwjgl.jar");
        Files.createDirectories(exact.getParent());
        Files.copy(installed, exact);
        byte[] original = classBytes(exact);
        ClassSignature originalSignature = ClassSignature.parse(original);
        assertEquals(GlIsEnabledStateCachePlan.TARGET_CLASS, originalSignature.internalName());
        assertEquals(49, originalSignature.majorVersion());
        assertTrue(originalSignature.hasMethod(
                GlIsEnabledStateCachePlan.IS_ENABLED,
                GlIsEnabledStateCachePlan.IS_ENABLED_DESCRIPTOR));

        AdapterTargetRegistry registry = AdapterTargetRegistry.load(
                exactTargetFile(originalSignature.sha256()));
        GlIsEnabledStateCacheRuntime.beginSessionForTest(true);

        byte[] transformed = transform(
                registry, exact, original, temporaryDirectory.resolve("exact-report.json"));
        assertNotNull(transformed, Files.readString(temporaryDirectory.resolve("exact-report.json")));
        ClassNode owner = read(transformed);
        assertEquals(1, calls(method(owner, "glIsEnabled", "(I)Z"), RUNTIME, "cached"));
        assertEquals(1, calls(method(owner, "glIsEnabled", "(I)Z"), RUNTIME, "observedQuery"));
        assertEquals(1, calls(method(owner, "glEnable", "(I)V"), RUNTIME, "enable"));
        assertEquals(1, calls(method(owner, "glDisable", "(I)V"), RUNTIME, "disable"));
        assertEquals(1, calls(method(owner, "glPushAttrib", "(I)V"), RUNTIME, "pushAttrib"));
        assertEquals(1, calls(method(owner, "glPopAttrib", "()V"), RUNTIME, "popAttrib"));
        assertEquals(true, GlIsEnabledStateCacheRuntime.telemetry().get("installed"));

        assertNull(transform(
                registry,
                exact,
                transformed,
                temporaryDirectory.resolve("changed-class-report.json")));

        Path changedArchive = temporaryDirectory.resolve(
                "Starsector-changed/contents/resources/java/lwjgl.jar");
        Files.createDirectories(changedArchive.getParent());
        Files.copy(installed, changedArchive);
        Files.write(changedArchive, new byte[] {0}, StandardOpenOption.APPEND);
        assertNull(transform(
                registry,
                changedArchive,
                original,
                temporaryDirectory.resolve("changed-archive-report.json")));
    }

    private Path exactTargetFile(String classSha256) throws Exception {
        Path file = temporaryDirectory.resolve("gl11-targets.txt");
        Files.writeString(file, String.join(System.lineSeparator(),
                "target lwjgl-2-gl11-is-enabled-cache",
                "class " + GlIsEnabledStateCachePlan.TARGET_CLASS,
                "sha256 " + classSha256,
                "plan " + FrameTimeRuntime.PLAN_ID,
                "source-kind STARSECTOR_CORE",
                "source-suffix contents/resources/java/lwjgl.jar",
                "source-sha256 " + STOCK_LWJGL_JAR_SHA256,
                "loader-class jdk/internal/loader/ClassLoaders$AppClassLoader",
                "loader-name app",
                "method glIsEnabled (I)Z",
                "method glEnable (I)V",
                "method glDisable (I)V",
                "method glPushAttrib (I)V",
                "method glPopAttrib ()V",
                "method glNewList (II)V",
                "method glEndList ()V",
                "method glCallList (I)V",
                "end",
                ""));
        return file;
    }

    private byte[] transform(
            AdapterTargetRegistry registry, Path archive, byte[] bytes, Path reportPath) throws Exception {
        AdapterReport report = new AdapterReport(
                AdapterMode.ENABLED, reportPath, null, List.of("org/lwjgl/"));
        AdapterProbeTransformer transformer = new AdapterProbeTransformer(
                AdapterMode.ENABLED, registry, List.of("org/lwjgl/"), report);
        CodeSource codeSource = new CodeSource(archive.toUri().toURL(), (Certificate[]) null);
        ProtectionDomain domain = new ProtectionDomain(codeSource, null);
        byte[] result = transformer.transform(
                ClassLoader.getSystemClassLoader(),
                GlIsEnabledStateCachePlan.TARGET_CLASS,
                null,
                domain,
                bytes);
        report.write();
        return result;
    }

    private static byte[] classBytes(Path archive) throws Exception {
        try (JarFile jar = new JarFile(archive.toFile())) {
            var entry = jar.getJarEntry(GlIsEnabledStateCachePlan.TARGET_CLASS + ".class");
            assertNotNull(entry, "lwjgl.jar has no GL11.class");
            try (InputStream input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode owner = new ClassNode(Opcodes.ASM9);
        new ClassReader(bytes).accept(owner, ClassReader.EXPAND_FRAMES);
        return owner;
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner.methods.stream()
                .filter(candidate -> name.equals(candidate.name) && descriptor.equals(candidate.desc))
                .findFirst()
                .orElseThrow();
    }

    private static int calls(MethodNode method, String owner, String name) {
        int count = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && owner.equals(call.owner)
                    && name.equals(call.name)) count++;
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
