package dev.starsector.preflight.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.PreparedAudio;
import dev.starsector.preflight.core.PreparedAudioCache;
import dev.starsector.preflight.core.PreparedAudioIO;
import dev.starsector.preflight.core.PreparedAudioManifest;
import dev.starsector.preflight.core.PreparedAudioManifestIO;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedAudioRuntimeTest {
    private static final String DECODER =
            Hashes.sha256("decoder-policy-identity".getBytes(StandardCharsets.UTF_8));
    private static final byte[] ENCODED = "pretend this is an ogg file".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path cache;

    @BeforeEach
    void enable() {
        PreparedAudioRuntime.reset();
        PreparedAudioRuntime.enable(true);
        PreparedAudioRuntime.configure(cache, DECODER);
    }

    @AfterEach
    void disable() {
        PreparedAudioRuntime.enable(false);
        PreparedAudioRuntime.configure(null, null);
        PreparedAudioRuntime.reset();
    }

    @Test
    void servesTheBakedPcmWithoutDecoding() throws Throwable {
        byte[] pcm = pcm(512);
        bake(ENCODED, pcm, 2, 44_100);
        Vanilla vanilla = new Vanilla();

        Object result = PreparedAudioRuntime.decode(
                null, new ByteArrayInputStream(ENCODED), vanilla.handle());

        assertNotNull(result);
        assertEquals(0, vanilla.calls, "a served sound must not reach the decoder at all");
        sound.F decoded = (sound.F) result;
        assertEquals(2, decoded.o00000);
        assertEquals(44_100, decoded.Ò00000);
        assertTrue(decoded.Object.isDirect(), "alBufferData only accepts a direct buffer");
        assertEquals(0, decoded.Object.position());
        byte[] served = new byte[decoded.Object.remaining()];
        decoded.Object.duplicate().get(served);
        assertArrayEquals(pcm, served);
    }

    @Test
    void aSoundWithNoBakedBlobIsDecodedFromTheSameBytes() throws Throwable {
        // The runtime drains the stream to hash it, so the bytes handed to the decoder come from
        // the runtime rather than from the caller. Getting that wrong would decode nothing at all.
        Vanilla vanilla = new Vanilla();

        Object result = PreparedAudioRuntime.decode(
                null, new ByteArrayInputStream(ENCODED), vanilla.handle());

        assertEquals(1, vanilla.calls);
        assertArrayEquals(ENCODED, vanilla.seen);
        assertNotNull(result);
        assertEquals(1L, PreparedAudioRuntime.report().get("decodedByTheGame"));
    }

    @Test
    void aBlobBakedByADifferentDecoderIsNotServed() throws Throwable {
        // Same source bytes, a decoder identity that is not this one. Its output is not this
        // decoder's output, and handing it over would be inventing audio.
        byte[] pcm = pcm(128);
        PreparedAudioRuntime.configure(cache, DECODER);
        bakeUnder(Hashes.sha256("some-other-decoder".getBytes(StandardCharsets.UTF_8)),
                ENCODED, pcm, 1, 22_050);
        Vanilla vanilla = new Vanilla();

        PreparedAudioRuntime.decode(null, new ByteArrayInputStream(ENCODED), vanilla.handle());

        assertEquals(1, vanilla.calls);
    }

    @Test
    void aCrossKeyBlobIsNotServedEvenByTheTrustedReader() throws Throwable {
        byte[] other = "different encoded source".getBytes(StandardCharsets.UTF_8);
        byte[] pcm = pcm(128);
        PreparedAudio audio = prepared(other, DECODER, pcm, 1, 22_050);
        Path expected = PreparedAudioCache.blobPath(cache, Hashes.sha256(ENCODED), DECODER,
                PreparedAudio.Policy.FULLY_DECODED_EFFECT);
        Files.createDirectories(expected.getParent());
        Files.write(expected, PreparedAudioIO.toBytes(audio));
        Vanilla vanilla = new Vanilla();

        PreparedAudioRuntime.decode(null, new ByteArrayInputStream(ENCODED), vanilla.handle());

        assertEquals(1, vanilla.calls);
        assertArrayEquals(ENCODED, vanilla.seen);
    }

    @Test
    void anUnconfiguredRuntimeIsExactlyTheGameDecoder() throws Throwable {
        PreparedAudioRuntime.configure(null, null);
        assertFalse(PreparedAudioRuntime.ready());
        Vanilla vanilla = new Vanilla();

        PreparedAudioRuntime.decode(null, new ByteArrayInputStream(ENCODED), vanilla.handle());

        assertEquals(1, vanilla.calls);
        assertEquals(0L, PreparedAudioRuntime.report().get("decodesIntercepted"));
    }

    @Test
    void aValidatedPathServesWithoutReadingOrHashingTheEncodedInput() throws Throwable {
        byte[] pcm = pcm(512);
        PreparedAudio audio = prepared(ENCODED, DECODER, pcm, 2, 44_100);
        bake(ENCODED, pcm, 2, 44_100);
        Path manifestPath = cache.resolve("profile.spam");
        PreparedAudioManifest manifest = manifest("sounds/test.ogg", audio);
        PreparedAudioManifestIO.write(manifestPath, manifest);
        PreparedAudioRuntime.configure(cache, DECODER, manifestPath, manifest.manifestSha256());
        Vanilla vanilla = new Vanilla();
        InputStream unreadable = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("a path hit must not read the encoded stream");
            }
        };

        Object result = PreparedAudioRuntime.decodePath(
                null, "sounds/test.ogg", unreadable, vanilla.handle());

        assertNotNull(result);
        assertEquals(0, vanilla.calls);
        assertEquals(1L, PreparedAudioRuntime.report().get("pathHits"));
        assertEquals(0L, PreparedAudioRuntime.report().get("byteHashLookups"));
    }

    @Test
    void anUnknownPathFallsBackWithTheOriginalStreamUntouched() throws Throwable {
        byte[] pcm = pcm(128);
        PreparedAudio audio = prepared(ENCODED, DECODER, pcm, 1, 22_050);
        Path manifestPath = cache.resolve("profile.spam");
        PreparedAudioManifest manifest = manifest("sounds/known.ogg", audio);
        PreparedAudioManifestIO.write(manifestPath, manifest);
        PreparedAudioRuntime.configure(cache, DECODER, manifestPath, manifest.manifestSha256());
        Vanilla vanilla = new Vanilla();

        PreparedAudioRuntime.decodePath(
                null, "sounds/unknown.ogg", new ByteArrayInputStream(ENCODED), vanilla.handle());

        assertEquals(1, vanilla.calls);
        assertArrayEquals(ENCODED, vanilla.seen);
        assertEquals(1L, PreparedAudioRuntime.report().get("pathMisses"));
    }

    @Test
    void aManifestIdentityMismatchLeavesTheContentHashPathActive() throws Throwable {
        byte[] pcm = pcm(128);
        bake(ENCODED, pcm, 1, 22_050);
        PreparedAudio audio = prepared(ENCODED, DECODER, pcm, 1, 22_050);
        Path manifestPath = cache.resolve("profile.spam");
        PreparedAudioManifest manifest = manifest("sounds/test.ogg", audio);
        PreparedAudioManifestIO.write(manifestPath, manifest);
        String wrongIdentity = Hashes.sha256("wrong manifest".getBytes(StandardCharsets.UTF_8));
        PreparedAudioRuntime.configure(cache, DECODER, manifestPath, wrongIdentity);
        Vanilla vanilla = new Vanilla();
        ContentHashFallback contentHashFallback = new ContentHashFallback(vanilla);

        Object result = PreparedAudioRuntime.decodePath(
                null,
                "sounds/test.ogg",
                new ByteArrayInputStream(ENCODED),
                contentHashFallback.handle());

        assertNotNull(result);
        assertEquals(0, vanilla.calls, "the ordinary content-addressed prepared lookup still serves");
        assertEquals("identity-mismatch", PreparedAudioRuntime.report().get("pathManifestStatus"));
        assertEquals(0L, PreparedAudioRuntime.report().get("pathLookups"));
        assertEquals(1L, PreparedAudioRuntime.report().get("byteHashLookups"));
    }

    private void bake(byte[] encoded, byte[] pcm, int channels, int rate) throws Exception {
        bakeUnder(DECODER, encoded, pcm, channels, rate);
    }

    private void bakeUnder(String decoder, byte[] encoded, byte[] pcm, int channels, int rate)
            throws Exception {
        PreparedAudio audio = prepared(encoded, decoder, pcm, channels, rate);
        Path blob = PreparedAudioCache.blobPath(cache, Hashes.sha256(encoded), decoder,
                PreparedAudio.Policy.FULLY_DECODED_EFFECT);
        Files.createDirectories(blob.getParent());
        Files.write(blob, PreparedAudioIO.toBytes(audio));
    }

    private static PreparedAudio prepared(
            byte[] encoded, String decoder, byte[] pcm, int channels, int rate) {
        return new PreparedAudio(
                Hashes.sha256(encoded), decoder, PreparedAudio.Policy.FULLY_DECODED_EFFECT,
                PreparedAudio.PcmEncoding.PCM_SIGNED, 16, PreparedAudio.ByteOrder.LITTLE_ENDIAN,
                rate, channels, pcm.length / (long) (channels * 2), pcm);
    }

    private static PreparedAudioManifest manifest(String path, PreparedAudio audio) {
        String profile = Hashes.sha256("profile".getBytes(StandardCharsets.UTF_8));
        String build = Hashes.sha256("build".getBytes(StandardCharsets.UTF_8));
        return new PreparedAudioManifest(
                profile,
                build,
                DECODER,
                Map.of(path, PreparedAudioManifest.Entry.prepared(
                        path, ENCODED.length, 1L, audio)));
    }

    private static byte[] pcm(int bytes) {
        byte[] samples = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            samples[i] = (byte) (i * 31);
        }
        return samples;
    }

    /** Stands in for the renamed original, and records what it was actually handed. */
    private static final class Vanilla {
        int calls;
        byte[] seen;

        @SuppressWarnings("unused") // reached through the MethodHandle below.
        sound.F decode(InputStream input) throws java.io.IOException {
            calls++;
            seen = input.readAllBytes();
            sound.F result = new sound.F();
            result.Object = ByteBuffer.allocateDirect(2);
            return result;
        }

        MethodHandle handle() throws Exception {
            MethodHandle bound = MethodHandles.lookup()
                    .findVirtual(Vanilla.class, "decode",
                            MethodType.methodType(sound.F.class, InputStream.class))
                    .bindTo(this);
            // The rewritten method invokes with the decoder as a leading argument; this stand-in is
            // already bound to itself, so make room for one and ignore it.
            return MethodHandles.dropArguments(bound, 0, Object.class)
                    .asType(MethodType.methodType(Object.class, Object.class, InputStream.class));
        }
    }

    /** Models the already-transformed decoder entry that the path adapter invokes on a miss. */
    private static final class ContentHashFallback {
        private final Vanilla vanilla;

        ContentHashFallback(Vanilla vanilla) {
            this.vanilla = vanilla;
        }

        @SuppressWarnings("unused") // reached through the MethodHandle below.
        Object decode(Object decoder, InputStream input) throws Throwable {
            return PreparedAudioRuntime.decode(decoder, input, vanilla.handle());
        }

        MethodHandle handle() throws Exception {
            return MethodHandles.lookup()
                    .findVirtual(ContentHashFallback.class, "decode", MethodType.methodType(
                            Object.class, Object.class, InputStream.class))
                    .bindTo(this);
        }
    }
}
