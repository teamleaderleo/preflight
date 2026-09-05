package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.PreparedTexture;
import dev.starsector.preflight.core.PreparedTextureIO;
import dev.starsector.preflight.core.PreparedTexturePackIO;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceIndexIO;
import dev.starsector.preflight.core.TextureManifest;
import dev.starsector.preflight.core.TextureManifestIO;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextureCompatibilityPackTelemetryTest {
    @TempDir Path temporaryDirectory;

    @BeforeEach
    @AfterEach
    void reset() {
        TextureCompatibilityRuntime.beginSession();
        System.clearProperty(TextureCompatibilityRuntime.VERIFY_BLOB_CHECKSUM_PROPERTY);
    }

    @Test
    void invalidPackOpenFallsBackWithoutDisablingOrRetainingPaths() throws Exception {
        Fixture f = fixture();
        Path pack = writePack(f);
        Files.write(pack, new byte[] {1});
        for (int i = 0; i < 20; i++) {
            configure(f);
            assertNotNull(TextureCompatibilityRuntime.lookup("graphics/test.png"));
        }
        assertCount("packOpenFailures", 20);
        assertCount("packReadFailures", 0);
        assertCount("packDisables", 0);
        assertCount("packCloses", 0);
        assertCount("packFallbacks", 20);
        assertCount("fallbacks", 0);
        assertEquals(Map.of("open-io", 20L), reasons());
        assertFalse(reasons().toString().contains(temporaryDirectory.toString()));
        assertThrows(UnsupportedOperationException.class, () -> reasons().put("path", 1L));
    }

    @Test
    void readFailureDisablesOnceAndCountsEveryLooseAttempt() throws Exception {
        Fixture f = fixture();
        Path pack = writePack(f);
        configure(f);
        Files.write(pack, new byte[] {1});
        assertNotNull(TextureCompatibilityRuntime.lookup("graphics/test.png"));
        assertNotNull(TextureCompatibilityRuntime.lookup("graphics/test.png"));
        Files.delete(f.blob());
        assertNull(TextureCompatibilityRuntime.lookup("graphics/test.png"));
        assertCount("packFailures", 1);
        assertCount("packReadFailures", 1);
        assertCount("packDisables", 1);
        assertCount("packFallbacks", 3);
        assertCount("fallbacks", 1);
        assertEquals(Map.of("read-io", 1L), reasons());
        assertEquals(false, TextureCompatibilityRuntime.telemetry().get("packedStoreActive"));
        TextureCompatibilityRuntime.disable(TextureCompatibilityRuntime.DisableReason.MISSING_CONFIGURATION);
        assertCount("packCloses", 1);
        TextureCompatibilityRuntime.disable(TextureCompatibilityRuntime.DisableReason.MISSING_CONFIGURATION);
        assertCount("packCloses", 1);
        TextureCompatibilityRuntime.beginSession();
        for (String key : List.of("packOpenFailures", "packReadFailures", "packDisables",
                "packCloses", "packCloseFailures", "packFallbacks")) assertCount(key, 0);
        assertEquals(Map.of(), reasons());
    }

    @Test
    void identityMismatchUsesDistinctBoundedReason() throws Exception {
        Fixture f = fixture();
        writePack(f);
        TextureManifest original = TextureManifestIO.read(f.manifest());
        TextureManifest.Entry e = original.entries().firstEntry().getValue();
        TextureManifestIO.write(f.manifest(), new TextureManifest(original.profileFingerprint(), Map.of(
                "graphics/test.png", new TextureManifest.Entry("cd".repeat(32), e.transformation(),
                        e.blobRelativePath(), 2, 2, 3, 12))));
        configure(f);
        assertNull(TextureCompatibilityRuntime.lookup("graphics/test.png"));
        assertEquals(Map.of("read-identity-mismatch", 1L), reasons());
        assertCount("packDisables", 1);
        assertCount("packFallbacks", 1);
    }

    @Test
    void successfulPackAndChecksumOptOutHaveSeparateFallbackAccounting() throws Exception {
        Fixture f = fixture();
        writePack(f);
        configure(f);
        assertNotNull(TextureCompatibilityRuntime.lookup("graphics/test.png"));
        assertCount("packHits", 1);
        assertCount("packFallbacks", 0);
        assertEquals(Map.of(), reasons());
        TextureCompatibilityRuntime.beginSession();
        System.setProperty(TextureCompatibilityRuntime.VERIFY_BLOB_CHECKSUM_PROPERTY, "true");
        configure(f);
        assertNotNull(TextureCompatibilityRuntime.lookup("graphics/test.png"));
        assertCount("packOpenFailures", 0);
        assertCount("packFallbacks", 1);
        assertNull(TextureCompatibilityRuntime.lookup("graphics/absent.png"));
        assertCount("packFallbacks", 1);
    }

    @Test
    void closeIOExceptionIsCountedAndStillSuppressed() throws Exception {
        Fixture f = fixture();
        writePack(f);
        configure(f);
        injectChannel(new FaultChannel(null));
        TextureCompatibilityRuntime.disable(TextureCompatibilityRuntime.DisableReason.MISSING_CONFIGURATION);
        assertFalse(TextureCompatibilityRuntime.ready());
        assertCount("packCloses", 1);
        assertCount("packCloseFailures", 1);
        assertEquals(Map.of("close-io", 1L), reasons());
    }

    @Test
    void fatalReadAndCloseErrorsPropagateUnchanged() throws Exception {
        assertFatalPropagation(new ThreadDeath());
    }

    @Test
    void virtualMachineErrorsPropagateUnchanged() throws Exception {
        assertFatalPropagation(new OutOfMemoryError("synthetic fatal"));
    }

    private void assertFatalPropagation(Error fatal) throws Exception {
        Fixture f = fixture();
        writePack(f);
        configure(f);
        injectChannel(new FaultChannel(fatal));
        assertSame(fatal, assertThrows(fatal.getClass(),
                () -> TextureCompatibilityRuntime.lookup("graphics/test.png")));
        assertCount("packReadFailures", 0);
        assertCount("packDisables", 0);
        assertCount("packFallbacks", 0);
        assertSame(fatal, assertThrows(fatal.getClass(), () -> TextureCompatibilityRuntime.disable(
                TextureCompatibilityRuntime.DisableReason.MISSING_CONFIGURATION)));
        assertCount("packCloseFailures", 0);
    }

    @Test
    void lifecycleCountersSaturate() throws Exception {
        Fixture f = fixture();
        writePack(f);
        configure(f);
        Object telemetry = field(TextureCompatibilityRuntime.class, "TELEMETRY").get(null);
        for (String key : List.of("packFailures", "packDisables", "packCloses",
                "packCloseFailures", "packFallbacks")) {
            field(telemetry.getClass(), key).setLong(telemetry, Long.MAX_VALUE);
        }
        injectChannel(new FaultChannel(null));
        assertNotNull(TextureCompatibilityRuntime.lookup("graphics/test.png"));
        TextureCompatibilityRuntime.disable(TextureCompatibilityRuntime.DisableReason.MISSING_CONFIGURATION);
        for (String key : List.of("packFailures", "packReadFailures", "packDisables", "packCloses",
                "packCloseFailures", "packFallbacks")) assertCount(key, Long.MAX_VALUE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void openFailureAndReasonCountsSaturate() throws Exception {
        Fixture f = fixture();
        Files.write(writePack(f), new byte[] {1});
        configure(f);
        Object telemetry = field(TextureCompatibilityRuntime.class, "TELEMETRY").get(null);
        field(telemetry.getClass(), "packOpenFailures").setLong(telemetry, Long.MAX_VALUE);
        Map<Object, Long> counts = (Map<Object, Long>) field(
                telemetry.getClass(), "packFailureReasons").get(telemetry);
        counts.replaceAll((reason, count) -> Long.MAX_VALUE);
        configure(f);
        assertCount("packOpenFailures", Long.MAX_VALUE);
        assertEquals(Map.of("open-io", Long.MAX_VALUE), reasons());
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void injectChannel(FileChannel replacement) throws Exception {
        Object state = field(TextureCompatibilityRuntime.class, "state").get(null);
        Object pack = field(state.getClass(), "pack").get(state);
        Field channel = field(pack.getClass(), "channel");
        ((FileChannel) channel.get(pack)).close();
        channel.set(pack, replacement);
    }

    private static final class FaultChannel extends FileChannel {
        private final Error fatal;
        FaultChannel(Error fatal) { this.fatal = fatal; }
        private IOException failure() {
            if (fatal != null) throw fatal;
            return new IOException("unbounded private path must not reach telemetry");
        }
        @Override protected void implCloseChannel() throws IOException { throw failure(); }
        @Override public int read(ByteBuffer b, long p) throws IOException { throw failure(); }
        @Override public int read(ByteBuffer b) { throw new UnsupportedOperationException(); }
        @Override public long read(ByteBuffer[] b, int o, int l) { throw new UnsupportedOperationException(); }
        @Override public int write(ByteBuffer b) { throw new UnsupportedOperationException(); }
        @Override public int write(ByteBuffer b, long p) { throw new UnsupportedOperationException(); }
        @Override public long write(ByteBuffer[] b, int o, int l) { throw new UnsupportedOperationException(); }
        @Override public long position() { throw new UnsupportedOperationException(); }
        @Override public FileChannel position(long p) { throw new UnsupportedOperationException(); }
        @Override public long size() { throw new UnsupportedOperationException(); }
        @Override public FileChannel truncate(long s) { throw new UnsupportedOperationException(); }
        @Override public void force(boolean m) { throw new UnsupportedOperationException(); }
        @Override public long transferTo(long p, long c, WritableByteChannel t) { throw new UnsupportedOperationException(); }
        @Override public long transferFrom(ReadableByteChannel s, long p, long c) { throw new UnsupportedOperationException(); }
        @Override public MappedByteBuffer map(MapMode m, long p, long s) { throw new UnsupportedOperationException(); }
        @Override public FileLock lock(long p, long s, boolean shared) { throw new UnsupportedOperationException(); }
        @Override public FileLock tryLock(long p, long s, boolean shared) { throw new UnsupportedOperationException(); }
    }

    private static void assertCount(String key, long count) {
        assertEquals(count, TextureCompatibilityRuntime.telemetry().get(key), key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> reasons() {
        return (Map<String, Long>) TextureCompatibilityRuntime.telemetry().get("packFailureReasons");
    }

    private static void configure(Fixture f) {
        assertTrue(TextureCompatibilityRuntime.configure(f.cache(), f.manifest(), f.index()));
    }

    private static Path writePack(Fixture f) throws Exception {
        TextureManifest manifest = TextureManifestIO.read(f.manifest());
        Path pack = PreparedTexturePackIO.path(f.cache(), manifest.profileFingerprint());
        PreparedTexturePackIO.write(pack, manifest.profileFingerprint(), f.cache(),
                List.of(manifest.entries().firstEntry().getValue().blobRelativePath()));
        return pack;
    }

    private Fixture fixture() throws Exception {
        int channels = 3;
        Path cache = temporaryDirectory.resolve("cache");
        Path sourceRoot = temporaryDirectory.resolve("game");
        Path source = sourceRoot.resolve("graphics/test.png");
        Files.createDirectories(source.getParent());
        byte[] encoded = {1, 2, 3, 4};
        Files.write(source, encoded);
        String sourceHash = Hashes.sha256(encoded);
        String profile = "ab".repeat(32);
        ResourceIndex index = new ResourceIndex(
                profile,
                List.of(new ResourceIndex.Root("core", sourceRoot, true)),
                Map.of("graphics/test.png", List.of(new ResourceIndex.Provider(
                        0,
                        "graphics/test.png",
                        Files.size(source),
                        Files.getLastModifiedTime(source).toMillis()))));
        Path indexPath = cache.resolve("indexes").resolve(profile + ".spfi");
        ResourceIndexIO.write(indexPath, index);

        byte[] bottomUpRgb = {
            0, 0, (byte) 255, (byte) 255, (byte) 255, (byte) 255,
            (byte) 255, 0, 0, 0, (byte) 255, 0
        };
        PreparedTexture texture = new PreparedTexture(
                sourceHash,
                PreparedTexture.Transformation.IDENTITY,
                2,
                2,
                2,
                2,
                channels,
                0,
                0,
                0,
                bottomUpRgb);
        String blobRelative = "blobs/" + sourceHash.substring(0, 2) + "/" + sourceHash + "-identity.spft";
        Path blob = cache.resolve(blobRelative);
        PreparedTextureIO.write(blob, texture);
        TextureManifest manifest = new TextureManifest(profile, Map.of(
                "graphics/test.png",
                new TextureManifest.Entry(
                        sourceHash,
                        PreparedTexture.Transformation.IDENTITY,
                        blobRelative,
                        2,
                        2,
                        channels,
                        bottomUpRgb.length)));
        Path manifestPath = cache.resolve("manifests").resolve(profile + ".spfm");
        TextureManifestIO.write(manifestPath, manifest);
        return new Fixture(cache, source, indexPath, manifestPath, blob);
    }

    private record Fixture(Path cache, Path source, Path index, Path manifest, Path blob) {}
}
