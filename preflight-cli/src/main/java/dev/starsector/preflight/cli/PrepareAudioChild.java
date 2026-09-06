package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.Json;
import dev.starsector.preflight.core.PreparedAudio;
import dev.starsector.preflight.core.PreparedAudioCache;
import dev.starsector.preflight.core.PreparedAudioIO;
import dev.starsector.preflight.core.PreparedAudioManifest;
import dev.starsector.preflight.core.PreparedAudioManifestIO;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Decodes the profile's sound effects with the game's own decoder, inside the game's own runtime.
 *
 * <p>The blobs this writes are served back to the game at launch in place of decoding, so they have
 * to be what the game's decoder would have produced -- not what a decoder would have produced. The
 * only way to be sure of that is to use that decoder, which is why this runs as a child process on
 * the installation's Java with the installation's jars, and calls
 * the installed decoder through {@link GameAudioDecoder}, with exact Linux/Windows bindings.
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

        List<String> workItems = readWorkItems(work);
        for (String line : workItems) {
            if (line.isBlank()) {
                continue;
            }
            int tab = line.indexOf('\t');
            String logicalPath = tab < 0 ? line : line.substring(0, tab);
            Path file = Path.of(tab < 0 ? line : line.substring(tab + 1));
            byte[] encoded;
            try {
                encoded = readEncoded(file);
            } catch (java.io.IOException unreadable) {
                undecodable++;
                record(skipped, logicalPath);
                continue;
            }
            if (encodedBytes > PrepareAudioCommand.MAX_TOTAL_ENCODED_BYTES - encoded.length) {
                throw new IllegalArgumentException("Prepared audio exceeds the encoded-byte budget");
            }
            encodedBytes += encoded.length;

            GameAudioDecoder.Decoded decoded = decoder.decode(encoded);
            if (decoded == null) {
                undecodable++;
                record(skipped, logicalPath);
                continue;
            }
            byte[] samples = decoded.samples();
            if (samples.length > PreparedAudio.MAX_PCM_BYTES
                    || pcmBytes > PrepareAudioCommand.MAX_TOTAL_PCM_BYTES - samples.length) {
                throw new java.io.IOException("Prepared audio exceeds the decoded-byte budget");
            }

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

    /** Reads the parent-generated work list without allowing replacement/growth to bypass the cap. */
    static List<String> readWorkItems(Path work) throws java.io.IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                work, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                || attributes.size() > PrepareAudioCommand.MAX_WORK_FILE_BYTES) {
            throw new java.io.IOException("Prepared-audio work list exceeds the safe byte budget");
        }
        byte[] bytes;
        try (var channel = Files.newByteChannel(
                work, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             InputStream input = Channels.newInputStream(channel)) {
            bytes = input.readNBytes(PrepareAudioCommand.MAX_WORK_FILE_BYTES + 1);
        }
        if (bytes.length > PrepareAudioCommand.MAX_WORK_FILE_BYTES) {
            throw new java.io.IOException("Prepared-audio work list exceeds the safe byte budget");
        }
        List<String> items = new String(bytes, StandardCharsets.UTF_8).lines().toList();
        if (items.size() > PrepareAudioCommand.MAX_SOUND_COUNT) {
            throw new java.io.IOException("Prepared-audio work list exceeds the safe sound count");
        }
        return items;
    }

    /** Reads one untrusted asset without allowing it to grow past the per-file budget mid-read. */
    static byte[] readEncoded(Path file) throws java.io.IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                || attributes.size() > PrepareAudioCommand.MAX_ENCODED_FILE_BYTES) {
            throw new IllegalArgumentException("Prepared audio exceeds the encoded-file budget");
        }
        try (var channel = Files.newByteChannel(
                file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             InputStream input = Channels.newInputStream(channel)) {
            byte[] encoded = input.readNBytes((int) PrepareAudioCommand.MAX_ENCODED_FILE_BYTES + 1);
            if (encoded.length > PrepareAudioCommand.MAX_ENCODED_FILE_BYTES) {
                throw new IllegalArgumentException("Prepared audio exceeds the encoded-file budget");
            }
            return encoded;
        }
    }
}
