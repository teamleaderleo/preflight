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

class TexturePreparedPixelAgentIT {
    @TempDir
    Path temporaryDirectory;

    @Test
    void packagedWarmHitBypassesDecodeAndConversionButRunsOriginalCleanup() throws Exception {
        Fixture fixture = fixture(false, false);

        ProcessResult result = launch(fixture, "graphics/test.png", false);

        assertSuccess(result);
        assertTrue(result.output().contains(
                "synthetic-pixels:123456789abcdef011223344:colors=ff0a141e,ff28323c,ff46505a:decode=0:convert=0:cleanup=1"),
                result.output());
        String report = Files.readString(fixture.adapterReport());
        assertTrue(report.contains("\"transformationsApplied\":1"), report);
        assertTrue(report.contains("\"hits\":1"), report);
        assertTrue(report.contains("\"fallbacks\":0"), report);
    }

    @Test
    void packagedNpotHitUsesOriginalUploadAndClassifiesItsLayout() throws Exception {
        byte[] source = sequential(3 * 3 * 3);
        Fixture fixture = fixture(false, false, 3, 3, 3, source);

        ProcessResult result = launch(
                fixture, "graphics/test.png", false, false, false, true);

        assertSuccess(result);
        assertTrue(result.output().contains(
                "synthetic-pixels:"
                        + "000000000000000000000000"
                        + "010203040506070809000000"
                        + "0a0b0c0d0e0f101112000000"
                        + "131415161718191a1b000000"
                        + ":colors=ffcc00cc,ff00ff00,ff0000ff:decode=1:convert=1:cleanup=1"),
                result.output());
        String report = Files.readString(fixture.adapterReport());
        assertTrue(report.contains("\"transformationsApplied\":1"), report);
        assertTrue(report.contains("\"hits\":0"), report);
        assertTrue(report.contains("\"fallbacks\":1"), report);
        assertTrue(report.contains("\"npotProbeFallbacks\":1"), report);
        assertTrue(report.contains("zero-rows-then-row-pad-source"), report);
        assertTrue(report.contains("\"layoutObservationErrors\":0"), report);
        assertTrue(report.contains("\"paddedUploads\":0"), report);
        assertTrue(report.contains("\"activeBuffers\":0"), report);
        assertTrue(report.contains("\"activeDirectBytes\":0"), report);
    }

    @Test
    void packagedNpotCoherentCarrierBypassesDecodeButRunsOriginalConverter() throws Exception {
        byte[] source = sequential(3 * 3 * 3);
        Fixture fixture = fixture(false, false, 3, 3, 3, source);

        ProcessResult result = launch(
                fixture, "graphics/test.png", false, false, false, false, true);

        assertSuccess(result);
        assertTrue(result.output().contains(
                "synthetic-pixels:"
                        + "010203040506070809000000"
                        + "0a0b0c0d0e0f101112000000"
                        + "131415161718191a1b000000"
                        + "000000000000000000000000"
                        + ":colors=ff131415,ff00ff00,ff0000ff:decode=0:convert=1:cleanup=1"),
                result.output());
        String report = Files.readString(fixture.adapterReport());
        assertTrue(report.contains("\"transformationsApplied\":1"), report);
        assertTrue(report.contains("\"coherentOriginalConvertEnabled\":true"), report);
        assertTrue(report.contains("\"coherentCarriers\":1"), report);
        assertTrue(report.contains("\"coherentCarrierBytes\":27"), report);
        assertTrue(report.contains("\"hits\":0"), report);
        assertTrue(report.contains("\"fallbacks\":1"), report);
        assertTrue(report.contains("\"npotProbeFallbacks\":1"), report);
        assertTrue(report.contains("\"coherentOriginalConvertFallbacks\":1"), report);
        assertTrue(report.contains("\"coherentOriginalDecodeBypasses\":1"), report);
        assertTrue(report.contains("\"imageDecodesBypassed\":1"), report);
        assertTrue(report.contains("\"conversionCallsBypassed\":0"), report);
        assertTrue(report.contains("row-pad-source-then-zero-rows"), report);
        assertTrue(report.contains("\"carrierRasterWidth\":3"), report);
        assertTrue(report.contains("\"carrierRasterHeight\":3"), report);
        assertTrue(report.contains("\"coherentOriginalConvert\":true"), report);
        assertTrue(report.contains("\"layoutObservationErrors\":0"), report);
        assertTrue(report.contains("\"activeBuffers\":0"), report);
        assertTrue(report.contains("\"activeDirectBytes\":0"), report);
    }

    @Test
    void packagedNpotCoherentDirectBypassesDecodeAndConversion() throws Exception {
        byte[] source = sequential(3 * 3 * 3);
        Fixture fixture = fixture(false, false, 3, 3, 3, source);

        ProcessResult result = launch(
                fixture, "graphics/test.png", false, false, false, false, false, true);

        assertSuccess(result);
        assertTrue(result.output().contains(
                "synthetic-pixels:"
                        + "010203040506070809000000"
                        + "0a0b0c0d0e0f101112000000"
                        + "131415161718191a1b000000"
                        + "000000000000000000000000"
                        + ":colors=ff0a141e,ff28323c,ff46505a:decode=0:convert=0:cleanup=1"),
                result.output());
        String report = Files.readString(fixture.adapterReport());
        assertTrue(report.contains("\"transformationsApplied\":1"), report);
        assertTrue(report.contains("\"coherentDirectEnabled\":true"), report);
        assertTrue(report.contains("\"coherentDirectCarriers\":1"), report);
        assertTrue(report.contains("\"coherentDirectHits\":1"), report);
        assertTrue(report.contains("\"coherentCarriers\":1"), report);
        assertTrue(report.contains("\"carrierRasterMaterializations\":0"), report);
        assertTrue(report.contains("\"coherentCarrierBytes\":0"), report);
        assertTrue(report.contains("\"hits\":1"), report);
        assertTrue(report.contains("\"fallbacks\":0"), report);
        assertTrue(report.contains("\"npotProbeFallbacks\":0"), report);
        assertTrue(report.contains("\"paddedUploads\":1"), report);
        assertTrue(report.contains("\"paddingBytes\":21"), report);
        assertTrue(report.contains("\"imageDecodesBypassed\":1"), report);
        assertTrue(report.contains("\"conversionCallsBypassed\":1"), report);
        assertTrue(report.contains("\"activeBuffers\":0"), report);
        assertTrue(report.contains("\"activeDirectBytes\":0"), report);
        assertTrue(report.contains("\"releases\":1"), report);
        assertTrue(report.contains("\"releasedBytes\":48"), report);
    }

    @Test
    void packagedUploadExceptionPreservesOriginalFailureAndReleasesDirectBuffer() throws Exception {
        Fixture fixture = fixture(false, false);

        ProcessResult result = launch(
                fixture, "graphics/test.png", false, false, true, false);

        assertSuccess(result);
        assertTrue(result.output().contains(
                "synthetic-upload-failure:IllegalStateException:synthetic upload failure:decode=0:convert=0:cleanup=0"),
                result.output());
        String report = Files.readString(fixture.adapterReport());
        assertTrue(report.contains("\"hits\":1"), report);
        assertTrue(report.contains("\"activeBuffers\":0"), report);
        assertTrue(report.contains("\"activeDirectBytes\":0"), report);
        assertTrue(report.contains("\"releases\":1"), report);
        assertTrue(report.contains("\"releasedBytes\":12"), report);
    }

    @Test
    void packagedPreloadedImageWinsBeforePreparedPixelLookup() throws Exception {
        Fixture fixture = fixture(false, false);

        ProcessResult result = launch(
                fixture, "graphics/test.png", false, true, false, false);

        assertSuccess(result);
        // The preloaded image is one pixel, and get2Fold floors at two, so the original path uploads
        // a 2x2 RGBA buffer with the single source pixel in the bottom-left and the rest zero. The
        // three-byte expectation this replaced came from the fixture's own power-of-two arithmetic,
        // which returned 1 for a one-pixel edge and so modelled a padding rule the engine does not have.
        assertTrue(result.output().contains(
                "synthetic-pixels:abcdefff000000000000000000000000:colors=ffabcdef,ff00ff00,ff0000ff"
                        + ":decode=0:convert=1:cleanup=1:preloaderCalls=1"),
                result.output());
        String report = Files.readString(fixture.adapterReport());
        assertTrue(report.contains("\"transformationsApplied\":1"), report);
        assertTrue(report.contains("\"attempts\":0"), report);
        assertTrue(report.contains("\"hits\":0"), report);
    }

    @Test
    void packagedColdMissExecutesOriginalDecodeAndConversionOnce() throws Exception {
        Fixture fixture = fixture(false, false);

        ProcessResult result = launch(fixture, "graphics/missing.png", false);

        assertSuccess(result);
        assertTrue(result.output().contains(
                "synthetic-pixels:cc00ccff000000000000000000000000"
                        + ":colors=ffcc00cc,ff00ff00,ff0000ff:decode=1:convert=1:cleanup=1"),
                result.output());
        String report = Files.readString(fixture.adapterReport());
        assertTrue(report.contains("\"misses\":1"), report);
        assertTrue(report.contains("\"entry-missing\":1"), report);
    }

    @Test
    void packagedCorruptBlobQuarantinesThenExecutesOriginalPathOnce() throws Exception {
        Fixture fixture = fixture(true, false);

        ProcessResult result = launch(fixture, "graphics/test.png", false);

        assertSuccess(result);
        assertTrue(result.output().contains("decode=1:convert=1:cleanup=1"), result.output());
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
        assertTrue(result.output().contains("decode=1:convert=1:cleanup=1"), result.output());
        String report = Files.readString(fixture.adapterReport());
        assertTrue(report.contains("\"transformationsApplied\":0"), report);
        assertTrue(report.contains("\"exactMatches\":0"), report);
    }

    @Test
    void packagedKillSwitchLeavesClassUntouched() throws Exception {
        Fixture fixture = fixture(false, false);

        ProcessResult result = launch(fixture, "graphics/test.png", true);

        assertSuccess(result);
        assertTrue(result.output().contains("decode=1:convert=1:cleanup=1"), result.output());
        String report = Files.readString(fixture.adapterReport());
        assertTrue(report.contains("\"killSwitchActive\":true"), report);
        assertTrue(report.contains("kill-switch"), report);
        assertTrue(report.contains("\"transformationsApplied\":0"), report);
    }

    /**
     * The default texture is power-of-two on both edges, because only a texture needing no padding is
     * served directly — {@link dev.starsector.preflight.agent.TexturePreparedPixelRuntime} keeps every
     * NPOT texture on Starsector's original path unless the coherent-direct diagnostic is on.
     *
     * <p>It used to be one pixel square, which satisfied that rule only by accident: the agent's local
     * next-power-of-two returned 1 for a one-pixel edge. PR #179 replaced it with Slick's
     * {@code get2Fold}, which floors at two, so a one-pixel texture became a 2x2 upload with nine bytes
     * of padding and the agent began declining it — correctly, and silently, since falling open is what
     * it is designed to do. The warm-hit tests below went on asserting a bypass that no longer happened.
     */
    private Fixture fixture(boolean corruptBlob, boolean wrongClassHash) throws Exception {
        return fixture(corruptBlob, wrongClassHash, 2, 2, 3, new byte[] {
                0x12, 0x34, 0x56, 0x78, (byte) 0x9a, (byte) 0xbc,
                (byte) 0xde, (byte) 0xf0, 0x11, 0x22, 0x33, 0x44});
    }

    private Fixture fixture(
            boolean corruptBlob,
            boolean wrongClassHash,
            int width,
            int height,
            int channels,
            byte[] pixels) throws Exception {
        Path testClasses = Path.of("target", "test-classes").toAbsolutePath().normalize();
        Path classFile = testClasses.resolve("com/fs/graphics/TextureLoader.class");
        byte[] classBytes = renameSyntheticPreloaderMethod(Files.readAllBytes(classFile));
        byte[] preloaderBytes = renameSyntheticPreloaderMethod(
                Files.readAllBytes(testClasses.resolve("com/fs/graphics/L.class")));
        Path targetJar = temporaryDirectory.resolve(
                "starsector-core/fixture-texture-pixels-" + System.nanoTime() + ".jar");
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
        Path targets = temporaryDirectory.resolve("targets-pixels-" + System.nanoTime() + ".txt");
        Files.writeString(targets, """
                target synthetic-texture-loader-pixels
                class com/fs/graphics/TextureLoader
                sha256 %s
                plan texture-prepared-pixels-v2
                source-kind STARSECTOR_CORE
                source-suffix starsector-core/%s
                source-sha256 %s
                loader-class jdk/internal/loader/ClassLoaders$AppClassLoader
                loader-name app
                method Ô00000 (Ljava/lang/String;)Ljava/awt/image/BufferedImage;
                method o00000 (Ljava/awt/image/BufferedImage;Lcom/fs/graphics/Object;)Ljava/nio/ByteBuffer;
                method o00000 (Ljava/nio/ByteBuffer;Ljava/lang/String;)V
                end
                """.formatted(classHash, targetJar.getFileName(), archiveHash));

        Path cache = temporaryDirectory.resolve("cache-pixels-" + System.nanoTime());
        Path sourceRoot = temporaryDirectory.resolve("game-pixels-" + System.nanoTime());
        Path source = sourceRoot.resolve("graphics/test.png");
        Files.createDirectories(source.getParent());
        byte[] encoded = {1, 3, 3, 7};
        Files.write(source, encoded);
        String sourceHash = Hashes.sha256(encoded);
        String profile = "ef".repeat(32);
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
                width,
                height,
                width,
                height,
                channels,
                PreparedTexture.rgba(10, 20, 30, 255),
                PreparedTexture.rgba(40, 50, 60, 255),
                PreparedTexture.rgba(70, 80, 90, 255),
                pixels);
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
                        width,
                        height,
                        channels,
                        pixels.length)));
        Path manifestPath = cache.resolve("manifests/" + profile + ".spfm");
        TextureManifestIO.write(manifestPath, manifest);
        Path recording = temporaryDirectory.resolve("startup-pixels-" + System.nanoTime() + ".jfr");
        Path report = temporaryDirectory.resolve("adapter-pixels-" + System.nanoTime() + ".json");
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
        return launch(fixture, logicalPath, killSwitch, false, false, false, false, false);
    }

    private ProcessResult launch(
            Fixture fixture,
            String logicalPath,
            boolean killSwitch,
            boolean preloaded,
            boolean uploadFailure,
            boolean originalUpperLayout) throws Exception {
        return launch(
                fixture,
                logicalPath,
                killSwitch,
                preloaded,
                uploadFailure,
                originalUpperLayout,
                false,
                false);
    }

    private ProcessResult launch(
            Fixture fixture,
            String logicalPath,
            boolean killSwitch,
            boolean preloaded,
            boolean uploadFailure,
            boolean originalUpperLayout,
            boolean coherentOriginalConvert) throws Exception {
        return launch(
                fixture,
                logicalPath,
                killSwitch,
                preloaded,
                uploadFailure,
                originalUpperLayout,
                coherentOriginalConvert,
                false);
    }

    private ProcessResult launch(
            Fixture fixture,
            String logicalPath,
            boolean killSwitch,
            boolean preloaded,
            boolean uploadFailure,
            boolean originalUpperLayout,
            boolean coherentOriginalConvert,
            boolean coherentDirect) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", executable("java"));
        Path agent = Path.of("target", "preflight.jar").toAbsolutePath().normalize();
        String agentArguments = "dest64=" + encoded(fixture.recording())
                + ",adapter=enabled"
                + ",textureMode=prepared-pixels"
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
        if (coherentOriginalConvert) {
            command.add("-Dpreflight.preparedPixels.coherentOriginalConvert=true");
        }
        if (coherentDirect) {
            command.add("-Dpreflight.preparedPixels.coherentDirect=true");
        }
        command.add("-javaagent:" + agent + "=" + agentArguments);
        command.add("-cp");
        command.add(fixture.targetJar() + System.getProperty("path.separator") + fixture.testClasses());
        command.add("com.fs.starfarer.SyntheticTextureLauncher");
        command.add(logicalPath);
        command.add("prepared-pixels");
        if (preloaded) {
            command.add("preloaded");
        }
        if (uploadFailure) {
            command.add("upload-failure");
        }
        if (originalUpperLayout) {
            command.add("original-upper-layout");
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

    private static byte[] sequential(int length) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < length; index++) {
            bytes[index] = (byte) (index + 1);
        }
        return bytes;
    }

    private static String encoded(Path path) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(path.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String executable(String name) {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? name + ".exe" : name;
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
