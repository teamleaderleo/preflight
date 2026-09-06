package dev.starsector.preflight.cli;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * The game's own Ogg Vorbis decode, reached reflectively through a loader holding its jars.
 *
 * <p>The blobs this produces are served back to the game at launch in place of decoding, so they
 * have to be what the game's decoder would have produced -- not what a decoder would have produced.
 * That is why the names below are the obfuscated ones and why nothing here substitutes a library of
 * its own.
 *
 * <p>Bound once per run rather than per file: resolving the class, method, and fields is the same
 * work every time, but a decoder instance is not reusable and is constructed per decode.
 */
final class GameAudioDecoder {
    /** {@code sound.J}, the Ogg Vorbis decode. */
    private static final String DECODER_CLASS = "sound.J";
    private static final String DECODE_METHOD = "o00000";
    /** {@code sound.F}: channel count, the direct PCM buffer, and the sample rate. */
    private static final String RESULT_CLASS = "sound.F";
    private static final String CHANNELS_FIELD = "o00000";
    private static final String PCM_FIELD = "Object";
    private static final String RATE_FIELD = "Ò" + "00000";

    private final Class<?> decoderClass;
    private final Method decode;
    private final Field channels;
    private final Field pcm;
    private final Field rate;

    private GameAudioDecoder(Class<?> decoderClass, Method decode, Field channels, Field pcm, Field rate) {
        this.decoderClass = decoderClass;
        this.decode = decode;
        this.channels = channels;
        this.pcm = pcm;
        this.rate = rate;
    }

    /** Signed 16-bit little-endian PCM, as the game holds it. */
    record Decoded(byte[] samples, int channels, int sampleRate) {
        long frames() {
            return samples.length / ((long) channels * 2L);
        }
    }

    /**
     * Resolves the decoder against {@code game}.
     *
     * @throws ReflectiveOperationException when the loader does not hold the game's sound classes,
     *     which is a failure worth reporting rather than a file to skip
     */
    static GameAudioDecoder boundTo(ClassLoader game) throws ReflectiveOperationException {
        boolean windows = exactClass(game, "sound/O0oO.class",
                "4b28c09ee5004a353ea2f0d61611eb4c7e0504abfc7b1f5328d6a7123f7f72b7")
                && exactClass(game, "sound/G.class",
                        "c7dbba1261cfba676dba014709c68e10563c3d06b0e8b5e664a5c1d2ee5e6616");
        boolean linux = exactClass(game, "sound/J.class",
                "b079051dc57de2708ad8c249b35b35469d3dc6cabbb255afdbfb713ea6bbaf8c")
                && exactClass(game, "sound/F.class",
                        "5d03d4031ee2b7cac51ec6838730b91824489128fd3b538bcb41d2f6208b13e2");
        Class<?> decoderClass = Class.forName(windows ? "sound.O0oO" : DECODER_CLASS, true, game);
        Class<?> resultClass = Class.forName(windows ? "sound.G" : RESULT_CLASS, true, game);
        return new GameAudioDecoder(
                decoderClass,
                decoderClass.getMethod(windows || linux ? "super" : DECODE_METHOD, InputStream.class),
                resultClass.getField(CHANNELS_FIELD),
                resultClass.getField(PCM_FIELD),
                resultClass.getField(RATE_FIELD));
    }

    private static boolean exactClass(ClassLoader game, String resource, String sha256) {
        try (InputStream input = game.getResourceAsStream(resource)) {
            return input != null && dev.starsector.preflight.core.Hashes.sha256(
                    input.readNBytes(1_048_577)).equals(sha256);
        } catch (java.io.IOException | RuntimeException failure) {
            return false;
        }
    }

    /**
     * Decodes one file, or returns {@code null} when the game's decoder cannot read it.
     *
     * <p>A file the decoder refuses is not an error. The game logs one and carries on with an empty
     * result, and 50 of the 2,099 sounds on the reviewed profile do exactly that; they are skipped
     * here and fall through at launch to the same failing decode they always had. A result whose
     * shape cannot describe PCM is treated the same way, because serving it would be worse than
     * serving nothing.
     */
    Decoded decode(byte[] encoded) {
        byte[] samples;
        int channelCount;
        int sampleRate;
        try {
            // A fresh decoder per file: one instance does not survive reuse.
            Object decoded = decode.invoke(
                    decoderClass.getDeclaredConstructor().newInstance(),
                    new ByteArrayInputStream(encoded));
            ByteBuffer buffer = ((ByteBuffer) pcm.get(decoded)).duplicate();
            samples = new byte[buffer.remaining()];
            buffer.get(samples);
            channelCount = channels.getInt(decoded);
            sampleRate = rate.getInt(decoded);
        } catch (ReflectiveOperationException | RuntimeException failed) {
            return null;
        }
        if (!validPcmShape(samples.length, channelCount, sampleRate)) {
            return null;
        }
        return new Decoded(samples, channelCount, sampleRate);
    }

    static boolean validPcmShape(int sampleBytes, int channelCount, int sampleRate) {
        if (channelCount < 1 || sampleRate < 1 || sampleBytes < 1) {
            return false;
        }
        long frameBytes = (long) channelCount * 2L;
        return sampleBytes % frameBytes == 0;
    }
}
