package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.Json;
import dev.starsector.preflight.core.PreparedAudio;
import dev.starsector.preflight.core.PreparedAudioCache;
import dev.starsector.preflight.core.PreparedAudioIO;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decodes the profile's sound effects with the game's own decoder, inside the game's own runtime.
 *
 * <p>The blobs this writes are served back to the game at launch in place of decoding, so they have
 * to be what the game's decoder would have produced -- not what a decoder would have produced. The
 * only way to be sure of that is to use that decoder, which is why this runs as a child process on
 * the installation's Java with the installation's jars, and calls
 * {@code sound.J.o00000(InputStream)} directly.
 *
 * <p>A file the decoder cannot read is not an error here. The game logs one and carries on with an
 * empty result, and 50 of the 2,099 sounds on the reviewed profile do exactly that. They are
 * skipped, and at launch they fall through to the same failing decode they always had.
 */
public final class PrepareAudioChild {
    /** {@code sound.J}, the Ogg Vorbis decode. */
    private static final String DECODER_CLASS = "sound.J";
    private static final String DECODE_METHOD = "o00000";
    /** {@code sound.F}: channel count, the direct PCM buffer, and the sample rate. */
    private static final String RESULT_CLASS = "sound.F";
    private static final String CHANNELS_FIELD = "o00000";
    private static final String PCM_FIELD = "Object";
    private static final String RATE_FIELD = "Ò" + "00000";

    private PrepareAudioChild() {
    }

    public static void main(String[] args) throws Exception {
        Path work = Path.of(args[0]);
        Path cache = Path.of(args[1]);
        String decoderIdentity = args[2];
        Path output = Path.of(args[3]);

        Class<?> decoderClass = Class.forName(DECODER_CLASS);
        Method decode = decoderClass.getMethod(DECODE_METHOD, InputStream.class);
        Class<?> resultClass = Class.forName(RESULT_CLASS);
        Field channels = resultClass.getField(CHANNELS_FIELD);
        Field pcm = resultClass.getField(PCM_FIELD);
        Field rate = resultClass.getField(RATE_FIELD);

        int prepared = 0;
        int undecodable = 0;
        long encodedBytes = 0;
        long pcmBytes = 0;
        List<String> skipped = new ArrayList<>();
        long start = System.nanoTime();

        for (String line : Files.readAllLines(work, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            int tab = line.indexOf('\t');
            String logicalPath = tab < 0 ? line : line.substring(0, tab);
            Path file = Path.of(tab < 0 ? line : line.substring(tab + 1));
            byte[] encoded;
            try {
                encoded = Files.readAllBytes(file);
            } catch (java.io.IOException unreadable) {
                undecodable++;
                record(skipped, logicalPath);
                continue;
            }
            encodedBytes += encoded.length;

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
                undecodable++;
                record(skipped, logicalPath);
                continue;
            }
            if (channelCount < 1 || sampleRate < 1 || samples.length == 0
                    || samples.length % (channelCount * 2) != 0) {
                undecodable++;
                record(skipped, logicalPath);
                continue;
            }

            String sourceSha256 = Hashes.sha256(encoded);
            PreparedAudio audio = new PreparedAudio(
                    sourceSha256,
                    decoderIdentity,
                    PreparedAudio.Policy.FULLY_DECODED_EFFECT,
                    PreparedAudio.PcmEncoding.PCM_SIGNED,
                    16,
                    PreparedAudio.ByteOrder.LITTLE_ENDIAN,
                    sampleRate,
                    channelCount,
                    samples.length / (long) (channelCount * 2),
                    samples);
            Path blob = PreparedAudioCache.blobPath(
                    cache, sourceSha256, decoderIdentity, PreparedAudio.Policy.FULLY_DECODED_EFFECT);
            Files.createDirectories(blob.getParent());
            PreparedAudioIO.write(blob, audio);
            prepared++;
            pcmBytes += samples.length;
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("reportFormat", "starsector-preflight-prepared-audio-bake-v1");
        report.put("decoderPolicyIdentitySha256", decoderIdentity);
        report.put("prepared", prepared);
        report.put("undecodable", undecodable);
        report.put("encodedBytes", encodedBytes);
        report.put("pcmBytes", pcmBytes);
        report.put("elapsedSeconds", Math.round((System.nanoTime() - start) / 1e6) / 1000.0);
        report.put("skippedExamples", skipped);
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.writeString(output, Json.object(report) + System.lineSeparator(), StandardCharsets.UTF_8);
        System.out.println("prepared-audio-bake-complete");
    }

    private static void record(List<String> skipped, String logicalPath) {
        if (skipped.size() < 20) {
            skipped.add(logicalPath);
        }
    }
}
