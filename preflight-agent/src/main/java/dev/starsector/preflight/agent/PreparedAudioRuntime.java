package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.PreparedAudio;
import dev.starsector.preflight.core.PreparedAudioCache;
import dev.starsector.preflight.core.PreparedAudioIO;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hands the game's sound loader PCM that was decoded before the JVM started.
 *
 * <p>A launch on the reviewed profile decodes <b>2,099 Ogg Vorbis files, 140.7 MB, into 1.23 GB of
 * PCM</b>. That is 19.7 seconds of one core, and the game spends it on a two-thread pool while the
 * main thread loads textures. Main then blocks on {@code awaitTermination} for the pool to finish:
 * on the measured launch, <b>3.46 seconds</b> of doing nothing at all.
 *
 * <p>Widening that pool is the obvious move and it is the wrong one. Each task ends by calling
 * {@code alGenBuffers}/{@code alBufferData} and writing to a plain {@code HashMap} with no lock, so
 * turning two threads into eight widens a race the game currently gets away with -- a correctness
 * change wearing a performance change's clothes. Decoding beforehand needs none of that: the pool
 * stays exactly two threads wide doing exactly what it did, and each task simply has almost nothing
 * left to do.
 *
 * <p>The seam is {@code sound.J.o00000(InputStream)}, which is pure decode -- no OpenAL, no shared
 * state -- returning a {@code sound.F} of three public fields: channel count, a direct 16-bit PCM
 * buffer, and the sample rate. This produces that same object from a cached blob.
 *
 * <p>Lookup is by SHA-256 of the encoded bytes the decoder was handed, so a served blob is keyed by
 * exactly the input it replaces. Hashing the whole corpus costs 0.5 s of CPU across the pool's two
 * threads, which is the price of never having to reason about whether a path still means what it
 * meant when the cache was baked.
 *
 * <p>Every path fails open. A miss, a malformed blob, a shape that is not what was reviewed, or a
 * disabled runtime all end in the same vanilla decode, fed from the bytes already in hand.
 */
public final class PreparedAudioRuntime {
    static final String PLAN_ID = "prepared-audio-v1";
    private static final String ENABLED_PROPERTY = "preflight.audio.prepared";

    /** {@code sound.F}: the decoded-audio object the loader expects back. */
    private static final String RESULT_CLASS = "sound.F";
    /** Channel count -- the loader turns 1 into mono16 and anything else into stereo16. */
    private static final String CHANNELS_FIELD = "o00000";
    /** The direct PCM buffer handed straight to {@code alBufferData}. */
    private static final String PCM_FIELD = "Object";
    /** Sample rate in hertz. */
    private static final String RATE_FIELD = "Ò" + "00000";

    /**
     * The decoder identity a blob must have been baked against.
     *
     * <p>Set from the manifest at startup. Until it is, nothing is served: a blob decoded by some
     * other decoder is not this decoder's output, and serving it would be inventing audio.
     */
    private static volatile String decoderPolicyIdentity;
    private static volatile Path cacheDirectory;

    private static volatile boolean shapeResolved;
    private static volatile Class<?> resultClass;
    private static volatile Field channelsField;
    private static volatile Field pcmField;
    private static volatile Field rateField;

    private static final AtomicLong decodes = new AtomicLong();
    private static final AtomicLong served = new AtomicLong();
    private static final AtomicLong servedBytes = new AtomicLong();
    private static final AtomicLong misses = new AtomicLong();
    private static final AtomicLong failures = new AtomicLong();

    private static volatile boolean enabled = "on".equalsIgnoreCase(System.getProperty(ENABLED_PROPERTY));

    private PreparedAudioRuntime() {
    }

    static void enable(boolean value) {
        enabled = value;
    }

    static boolean ready() {
        return enabled && cacheDirectory != null && decoderPolicyIdentity != null;
    }

    static void configure(Path cache, String decoderIdentitySha256) {
        cacheDirectory = cache;
        decoderPolicyIdentity = decoderIdentitySha256;
    }

    /**
     * Replaces the game's Ogg Vorbis decode.
     *
     * <p>Public because the rewritten game class calls it, and that class is not in this package.
     */
    public static Object decode(Object decoder, InputStream input, MethodHandle vanilla)
            throws Throwable {
        if (!ready()) {
            return vanilla.invoke(decoder, input);
        }
        byte[] encoded;
        try {
            encoded = input.readAllBytes();
        } catch (RuntimeException | java.io.IOException unreadable) {
            // The stream is spent either way; there is nothing to fall back to but the failure.
            failures.incrementAndGet();
            throw unreadable;
        }
        decodes.incrementAndGet();
        try {
            Object prepared = serve(encoded);
            if (prepared != null) {
                return prepared;
            }
            misses.incrementAndGet();
        } catch (RuntimeException | ReflectiveOperationException | java.io.IOException
                | LinkageError unexpected) {
            failures.incrementAndGet();
        }
        return vanilla.invoke(decoder, new ByteArrayInputStream(encoded));
    }

    private static Object serve(byte[] encoded) throws ReflectiveOperationException,
            java.io.IOException {
        Path blob = PreparedAudioCache.blobPath(
                cacheDirectory,
                Hashes.sha256(encoded),
                decoderPolicyIdentity,
                PreparedAudio.Policy.FULLY_DECODED_EFFECT);
        if (!Files.isRegularFile(blob)) {
            return null;
        }
        PreparedAudio audio = PreparedAudioIO.fromBytes(Files.readAllBytes(blob));
        // The loader reads three fields and hands the buffer to alBufferData as 16-bit PCM. A blob
        // that is not that shape is not this decoder's output, whatever else it may be.
        if (audio.bitsPerSample() != 16
                || audio.encoding() != PreparedAudio.PcmEncoding.PCM_SIGNED
                || audio.byteOrder() != PreparedAudio.ByteOrder.LITTLE_ENDIAN) {
            return null;
        }
        if (!resolveShape()) {
            return null;
        }
        byte[] pcm = audio.pcmBytes();
        // alBufferData needs a direct buffer, and the vanilla decoder hands back a direct one with
        // position 0. Anything else is a different object than the loader was written against.
        ByteBuffer buffer = ByteBuffer.allocateDirect(pcm.length);
        buffer.put(pcm);
        buffer.flip();

        Object result = resultClass.getDeclaredConstructor().newInstance();
        channelsField.setInt(result, audio.channels());
        rateField.setInt(result, audio.sampleRateHz());
        pcmField.set(result, buffer);
        served.incrementAndGet();
        servedBytes.addAndGet(pcm.length);
        return result;
    }

    private static synchronized boolean resolveShape() throws ReflectiveOperationException {
        if (shapeResolved) {
            return resultClass != null;
        }
        shapeResolved = true;
        Class<?> found = Class.forName(RESULT_CLASS, false,
                PreparedAudioRuntime.class.getClassLoader());
        Field channels = found.getField(CHANNELS_FIELD);
        Field pcm = found.getField(PCM_FIELD);
        Field rate = found.getField(RATE_FIELD);
        if (channels.getType() != int.class || rate.getType() != int.class
                || pcm.getType() != ByteBuffer.class) {
            return false;
        }
        channelsField = channels;
        pcmField = pcm;
        rateField = rate;
        resultClass = found;
        return true;
    }

    static Map<String, Object> report() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("enabled", enabled);
        report.put("ready", ready());
        report.put("decodesIntercepted", decodes.get());
        report.put("servedFromCache", served.get());
        report.put("pcmBytesServed", servedBytes.get());
        report.put("decodedByTheGame", misses.get());
        report.put("failures", failures.get());
        return report;
    }

    static void reset() {
        decodes.set(0);
        served.set(0);
        servedBytes.set(0);
        misses.set(0);
        failures.set(0);
        shapeResolved = false;
        resultClass = null;
        channelsField = null;
        pcmField = null;
        rateField = null;
    }
}
