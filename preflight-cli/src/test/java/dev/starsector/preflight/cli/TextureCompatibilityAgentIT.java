package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.PreparedTexture;
import dev.starsector.preflight.core.PreparedTextureIO;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceIndexIO;
import dev.starsector.preflight.core.TextureManifest;
import dev.starsector.preflight.core.TextureManifestIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class TextureCompatibilityAgentIT {
    @TempDir
    Path temporaryDirectory;

    @Test
    void packagedWarmHitPreservesPreloaderHandoffBeforeBypassingDirectDecode() throws Exception {
        Fixture fixture = fixture(false, false);

        ProcessResult result = launch(fixture, "graphics/test.png", false);

        assertSuccess(result);
        assertTrue(result.output().contains(
                "synthetic-texture:ff123456:originalCalls=0:preloaderCalls=1"), result.output());
        String report = Files.readString(fixture.adapterReport());
        assertTrue(report.contains("\"transformationsApplied\":1"), report);
        assertTrue(report.contains("\"hits\":1"), report);
        assertTrue(report.contains("\"fallbacks\":0"), report);
        Map<String, Object> values = StrictJson.object(report);
        @SuppressWarnings("unchecked")
        Map<String, Object> compatibility = (Map<String, Object>) values.get("textureCompatibility");
        @SuppressWarnings("unchecked")
        Map<String, Object> preparedPixels = (Map<String, Object>) compatibility.get("preparedPixels");
        assertEquals(Boolean.FALSE, preparedPixels.get("ready"));
    }

    @Test
    void packagedPreloadedImageWinsBeforePreparedCacheLookup() throws Exception {
        Fixture fixture = fixture(false, false);

        ProcessResult result = launch(fixture, "graphics/test.png", false, "preloaded");

        assertSuccess(result);
        assertTrue(result.output().contains(
                "synthetic-texture:ffabcdef:originalCalls=0:preloaderCalls=1"), result.output());
        String report = Files.readString(fixture.adapterReport());
        assertTrue(report.contains("\"transformationsApplied\":1"), report);
        assertTrue(report.contains("\"attempts\":0"), report);
        assertTrue(report.contains("\"hits\":0"), report);
    }

    @Test
    void packagedColdMissCallsOriginalExactlyOnce() throws Exception {
        Fixture fixture = fixture(false, false);

        ProcessResult result = launch(fixture, "graphics/missing.png", false);

        assertSuccess(result);
        assertTrue(result.output().contains("synthetic-texture:ffcc00cc:originalCalls=1"), result.output());
        String report = Files.readString(fixture.adapterReport());
        assertTrue(report.contains("\"transformationsApplied\":1"), report);
        assertTrue(report.contains("\"misses\":1"), report);
        assertTrue(report.contains("\"entry-missing\":1"), report);
    }

    @Test
    void packagedCorruptBlobQuarantinesAndCallsOriginalOnce() throws Exception {
        Fixture fixture = fixture(true, false);

        ProcessResult result = launch(fixture, "graphics/test.png", false);

        assertSuccess(result);
        assertTrue(result.output().contains("synthetic-texture:ffcc00cc:originalCalls=1"), result.output());
        String report = Files.readString(fixture.adapterReport());
        assertTrue(report.contains("\"corruptions\":1"), report);
        assertTrue(report.contains("\"quarantined\":1"), report);
        assertFalse(Files.exists(fixture.blob()));
    }

    @Test
    void packagedIdentityMismatchLeavesClassUntouched() throws Exception {
        Fixture fixture = fixture(false, true);

        ProcessResult result = launch(fixture, "graphics/test.png", false);

        assertSuccess(result);
        assertTrue(result.output().contains("synthetic-texture:ffcc00cc:originalCalls=1"), result.output());
        String report = Files.readString(fixture.adapterReport());
        assertTrue(report.contains("\"transformationsApplied\":0"), report);
        assertTrue(report.contains("\"exactMatches\":0"), report);
    }

    @Test
    void packagedKillSwitchLeavesClassUntouched() throws Exception {
        Fixture fixture = fixture(false, false);

        ProcessResult result = launch(fixture, "graphics/test.png", true);

        assertSuccess(result);
        assertTrue(result.output().contains("synthetic-texture:ffcc00cc:originalCalls=1"), result.output());
        String report = Files.readString(fixture.adapterReport());
        assertTrue(report.contains("\"killSwitchActive\":true"), report);
        assertTrue(report.contains("kill-switch"), report);
        assertTrue(report.contains("\"transformationsApplied\":0"), report);
    }

    private Fixture fixture(boolean corruptBlob, boolean wrongClassHash) throws Exception {
        Path testClasses = Path.of("target", "test-classes").toAbsolutePath().normalize();
        Path classFile = testClasses.resolve("com/fs/graphics/TextureLoader.class");
        byte[] classBytes = renameSyntheticPreloaderMethod(Files.readAllBytes(classFile));
        byte[] preloaderBytes = renameSyntheticPreloaderMethod(
                Files.readAllBytes(testClasses.resolve("com/fs/graphics/L.class")));
        Path targetJar = temporaryDirectory.resolve("starsector-core/fixture-texture.jar");
        Files.createDirectories(targetJar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(targetJar))) {
            JarEntry entry = new JarEntry("com/fs/graphics/TextureLoader.class");
            entry.setTime(0);
            output.putNextEntry(entry);
            output.write(classBytes);
            output.closeEntry();
            entry = new JarEntry("com/fs/graphics/L.class");
            entry.setTime(0);
            output.putNextEntry(entry);
            output.write(preloaderBytes);
            output.closeEntry();
        }

        String classHash = wrongClassHash ? "0".repeat(64) : Hashes.sha256(classBytes);
        String archiveHash = Hashes.sha256(targetJar);
        Path targets = temporaryDirectory.resolve("targets-" + System.nanoTime() + ".txt");
        Files.writeString(targets, """
                target synthetic-texture-loader
                class com/fs/graphics/TextureLoader
                sha256 %s
                plan texture-compatibility-v2
                source-kind STARSECTOR_CORE
                source-suffix starsector-core/fixture-texture.jar
                source-sha256 %s
                loader-class jdk/internal/loader/ClassLoaders$AppClassLoader
                loader-name app
                method Ô00000 (Ljava/lang/String;)Ljava/awt/image/BufferedImage;
                end
                """.formatted(classHash, archiveHash));

        Path cache = temporaryDirectory.resolve("cache-" + System.nanoTime());
        Path sourceRoot = temporaryDirectory.resolve("game-" + System.nanoTime());
        Path source = sourceRoot.resolve("graphics/test.png");
        Files.createDirectories(source.getParent());
        byte[] encoded = {1, 3, 3, 7};
        Files.write(source, encoded);
        String sourceHash = Hashes.sha256(encoded);
        String profile = "cd".repeat(32);
        ResourceIndex index = new ResourceIndex(
                profile,
                List.of(new ResourceIndex.Root("core", sourceRoot, true)),
                Map.of("graphics/test.png", List.of(new ResourceIndex.Provider(
                        0,
                        "graphics/test.png",
                        Files.size(source),
                        Files.getLastModifiedTime(source).toMillis()))));
        Path indexPath = cache.resolve("indexes/" + profile + ".spfi");
        ResourceIndexIO.write(indexPath, index);

        PreparedTexture texture = new PreparedTexture(
                sourceHash,
                PreparedTexture.Transformation.IDENTITY,
                1,
                1,
                1,
                1,
                3,
                0,
                0,
                0,
                new byte[] {0x12, 0x34, 0x56});
        String blobRelative = "blobs/" + sourceHash.substring(0, 2) + "/" + sourceHash + "-identity.spft";
        Path blob = cache.resolve(blobRelative);
        PreparedTextureIO.write(blob, texture);
        if (corruptBlob) {
            Files.write(blob, new byte[] {1, 2, 3});
        }
        TextureManifest manifest = new TextureManifest(profile, Map.of(
                "graphics/test.png",
                new TextureManifest.Entry(
                        sourceHash,
                        PreparedTexture.Transformation.IDENTITY,
                        blobRelative,
                        1,
                        1,
                        3,
                        3)));
        Path manifestPath = cache.resolve("manifests/" + profile + ".spfm");
        TextureManifestIO.write(manifestPath, manifest);
        Path recording = temporaryDirectory.resolve("startup-" + System.nanoTime() + ".jfr");
        Path report = temporaryDirectory.resolve("adapter-" + System.nanoTime() + ".json");
        return new Fixture(
                targetJar,
                targets,
                cache,
                manifestPath,
                indexPath,
                blob,
                recording,
                report,
                testClasses);
    }

    private ProcessResult launch(Fixture fixture, String logicalPath, boolean killSwitch) throws Exception {
        return launch(fixture, logicalPath, killSwitch, null);
    }

    private ProcessResult launch(Fixture fixture, String logicalPath, boolean killSwitch, String mode) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", executable("java"));
        Path agent = Path.of("target", "preflight.jar").toAbsolutePath().normalize();
        String agentArguments = "dest64=" + encoded(fixture.recording())
                + ",adapter=enabled"
                + ",adapterReport64=" + encoded(fixture.adapterReport())
                + ",targets64=" + encoded(fixture.targets())
                + ",textureCache64=" + encoded(fixture.cache())
                + ",textureManifest64=" + encoded(fixture.manifest())
                + ",textureIndex64=" + encoded(fixture.index());
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        if (killSwitch) {
            command.add("-Dpreflight.adapter.disabled=true");
        }
        command.add("-javaagent:" + agent + "=" + agentArguments);
        command.add("-cp");
        command.add(fixture.targetJar() + System.getProperty("path.separator") + fixture.testClasses());
        command.add("com.fs.starfarer.SyntheticTextureLauncher");
        command.add(logicalPath);
        if (mode != null) {
            command.add(mode);
        }
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        boolean completed = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(completed, completed ? process.exitValue() : -1, output);
    }

    private static byte[] renameSyntheticPreloaderMethod(byte[] original) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(
                        access,
                        "clazz".equals(name) ? "class" : name,
                        descriptor,
                        signature,
                        exceptions);
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitMethodInsn(
                            int opcode,
                            String owner,
                            String methodName,
                            String methodDescriptor,
                            boolean isInterface) {
                        super.visitMethodInsn(
                                opcode,
                                owner,
                                "com/fs/graphics/L".equals(owner) && "clazz".equals(methodName)
                                        ? "class"
                                        : methodName,
                                methodDescriptor,
                                isInterface);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static void assertSuccess(ProcessResult result) {
        assertTrue(result.completed(), result.output());
        assertEquals(0, result.exitCode(), result.output());
    }

    private static String encoded(Path path) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(path.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String executable(String name) {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? name + ".exe" : name;
    }

    private record Fixture(
            Path targetJar,
            Path targets,
            Path cache,
            Path manifest,
            Path index,
            Path blob,
            Path recording,
            Path adapterReport,
            Path testClasses) {
    }

    private record ProcessResult(boolean completed, int exitCode, String output) {
    }
}
