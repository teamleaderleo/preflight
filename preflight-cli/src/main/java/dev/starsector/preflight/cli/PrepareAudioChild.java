package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.Json;
import dev.starsector.preflight.core.PreparedAudio;
import dev.starsector.preflight.core.PreparedAudioCache;
import dev.starsector.preflight.core.PreparedAudioIO;
import dev.starsector.preflight.core.PreparedAudioManifest;
import dev.starsector.preflight.core.PreparedAudioManifestIO;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
    private PrepareAudioChild() {
    }

    /** Arguments before the game's own jars begin. */
    private static final int FIXED_ARGUMENTS = 7;

    /**
     * Loads the installation's jars from arguments rather than from {@code -cp}.
     *
     * <p>The launcher consumes a class path itself, before any Preflight code exists to decode one,
     * and Windows converts that value to the system code page on the way in — so an installation
     * whose path falls outside that page arrives as question marks and its classes are simply not
     * found. Arguments can be carried as Base64 and a class path cannot, so the jars travel as
     * arguments and are opened here, where the strings have already been decoded.
     *
     * <p>Delegation stays parent-first. On the flat class path this replaces, the game's jars came
     * before Preflight's, so the game won any name they shared; parent-first would reverse that.
     * It is safe because they share none — verified against the reviewed installation, whose jars
     * have no class in common with the shipped one — and the decode this exists to perform must be
     * the game's own.
     *
     * <p>Returned as the closeable type it is. Nothing closes it here, because the child exits when
     * the decode does and the handles go with it, but a caller that outlives its loader has to be
     * able to let the jars go: Windows refuses to delete a file something still holds open.
     */
    static URLClassLoader gameClassLoader(String[] args, int from) throws java.net.MalformedURLException {
        URL[] jars = new URL[args.length - from];
        for (int index = from; index < args.length; index++) {
            jars[index - from] = Path.of(args[index]).toUri().toURL();
        }
        return new URLClassLoader(jars, PrepareAudioChild.class.getClassLoader());
    }

    public static void main(String[] rawArgs) throws Exception {
        String[] args = Utf8Argv.decode(rawArgs);
        Path work = Path.of(args[0]);
        Path cache = Path.of(args[1]);
        String decoderIdentity = args[2];
        Path output = Path.of(args[3]);
        String profileFingerprint = args[4];
        String starsectorBuildIdentity = args[5];
        Path manifestOutput = Path.of(args[6]);

        ClassLoader game = gameClassLoader(args, FIXED_ARGUMENTS);
        GameAudioDecoder decoder = GameAudioDecoder.boundTo(game);

        int prepared = 0;
        int undecodable = 0;
        long encodedBytes = 0;
        long pcmBytes = 0;
        List<String> skipped = new ArrayList<>();
        Map<String, PreparedAudioManifest.Entry> manifestEntries = new TreeMap<>();
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

            GameAudioDecoder.Decoded decoded = decoder.decode(encoded);
            if (decoded == null) {
                undecodable++;
                record(skipped, logicalPath);
                continue;
            }
            byte[] samples = decoded.samples();

            String sourceSha256 = Hashes.sha256(encoded);
            PreparedAudio audio = new PreparedAudio(
                    sourceSha256,
                    decoderIdentity,
                    PreparedAudio.Policy.FULLY_DECODED_EFFECT,
                    PreparedAudio.PcmEncoding.PCM_SIGNED,
                    16,
                    PreparedAudio.ByteOrder.LITTLE_ENDIAN,
                    decoded.sampleRate(),
                    decoded.channels(),
                    decoded.frames(),
                    samples);
            Path blob = PreparedAudioCache.blobPath(
                    cache, sourceSha256, decoderIdentity, PreparedAudio.Policy.FULLY_DECODED_EFFECT);
            Files.createDirectories(blob.getParent());
            PreparedAudioIO.write(blob, audio);
            manifestEntries.put(logicalPath, PreparedAudioManifest.Entry.prepared(
                    logicalPath,
                    encoded.length,
                    Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis(),
                    audio));
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
        PreparedAudioManifest manifest = new PreparedAudioManifest(
                profileFingerprint,
                starsectorBuildIdentity,
                decoderIdentity,
                manifestEntries);
        PreparedAudioManifestIO.write(manifestOutput, manifest);
        report.put("manifest", manifestOutput.toAbsolutePath().normalize().toString());
        report.put("manifestSha256", manifest.manifestSha256());
        report.put("manifestEntries", manifest.entryCount());
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
