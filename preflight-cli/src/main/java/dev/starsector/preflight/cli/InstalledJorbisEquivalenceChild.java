package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.Json;
import dev.starsector.preflight.core.PreparedAudio;
import dev.starsector.preflight.core.PreparedAudioIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs fixture decoding inside a child JVM whose application classpath contains the installed decoder jars. */
public final class InstalledJorbisEquivalenceChild {
    private static final int MAX_FAILURE_DETAIL_CHARS = 1_024;
    private static final String LOADER_CLASS = "jdk.internal.loader.ClassLoaders$AppClassLoader";
    private static final String LOADER_NAME = "app";

    /**
     * Largest per-sample difference tolerated against the libvorbis reference.
     *
     * <p>Vorbis does not require bit-exact decoding, so two conformant decoders disagree in the low
     * bits of the inverse transform. Measured against the reviewed installation the disagreement is at
     * most two steps of a 16-bit sample, symmetrically distributed around zero. This is deliberately
     * not zero and deliberately not loose: it is tight enough that a decoder producing silence, or
     * producing audio through a different API, fails immediately.
     */
    private static final int MAX_REFERENCE_SAMPLE_DELTA = 2;

    /**
     * Largest untrimmed tail tolerated beyond the reference, in frames.
     *
     * <p>libvorbis trims the final block against the last page's granule position and JOrbis does not,
     * so the installed decoder returns slightly more audio than the reference — 256 mono or 128 stereo
     * frames on these fixtures. The bound is the Vorbis maximum block size, which is the most a single
     * untrimmed block can contribute.
     */
    private static final int MAX_UNTRIMMED_TAIL_FRAMES = 8_192;

    private static final List<Fixture> FULL_FIXTURES = List.of(
            new Fixture("mono-22050", "mono-22050.ogg", "mono-22050-reference.s16le", 1, 22_050, true),
            new Fixture("stereo-44100", "stereo-44100.ogg", "stereo-44100-reference.s16le", 2, 44_100, true),
            new Fixture("silence-mono-8000", "silence-mono-8000.ogg",
                    "silence-mono-8000-reference.s16le", 1, 8_000, false),
            new Fixture("clipping-stereo-48000", "clipping-stereo-48000.ogg", null, 2, 48_000, true),
            new Fixture("packet-boundary-mono-44100", "packet-boundary-mono-44100.ogg", null, 1, 44_100, true));

    private InstalledJorbisEquivalenceChild() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        Map<String, Object> report = run(options);
        Path output = options.output().toAbsolutePath().normalize();
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.writeString(output, Json.object(report) + System.lineSeparator(), StandardCharsets.UTF_8);
        boolean equivalent = Boolean.TRUE.equals(report.get("equivalent"));
        System.out.println(equivalent ? "installed-jorbis-equivalent" : "installed-jorbis-mismatch");
        System.exit(equivalent ? 0 : 6);
    }

    static Map<String, Object> run(Options options) throws Exception {
        // The classes checked here are the ones sound/void actually drives. An earlier version of this
        // gate pinned com.jcraft.jorbis.VorbisFile, which no JAR in the installation references, and so
        // it proved the identity of a class the game never loads.
        Class<?> syncStateClass = Class.forName("com.jcraft.jogg.SyncState");
        Class<?> infoClass = Class.forName("com.jcraft.jorbis.Info");
        Class<?> dspStateClass = Class.forName("com.jcraft.jorbis.DspState");
        Class<?> blockClass = Class.forName("com.jcraft.jorbis.Block");
        Identity jogg = identity(syncStateClass, options.expectedJoggSha256());
        Identity info = identity(infoClass, options.expectedJorbisSha256());
        Identity dspState = identity(dspStateClass, options.expectedJorbisSha256());
        Identity block = identity(blockClass, options.expectedJorbisSha256());
        List<Identity> identities = List.of(jogg, info, dspState, block);
        boolean identityExact = identities.stream().allMatch(it -> it.exact() && appLoader(it));

        // The decode path is part of what this identity names. It has to change when the path changes,
        // or a cache written by one decoder could be read back as though the other had produced it.
        String decoderPolicyIdentity = Hashes.sha256((
                "installed-jorbis-equivalence-v2\n"
                        + options.expectedJoggSha256() + "\n"
                        + options.expectedJorbisSha256() + "\n"
                        + LOADER_CLASS + "\n"
                        + LOADER_NAME + "\n"
                        + "pcm-signed-16-little-endian\n"
                        + "jogg-jorbis-low-level-synthesis\n"
                        + "sound/J.o00000(Ljava/io/InputStream;)Lsound/F;\n"
                        + "fully-decoded-effect-only").getBytes(StandardCharsets.UTF_8));

        List<Fixture> fixtures = "ci".equals(options.fixtureProfile())
                ? FULL_FIXTURES.subList(0, 3)
                : FULL_FIXTURES;
        LowLevelVorbisDecoder decoder = new LowLevelVorbisDecoder();
        List<Map<String, Object>> cases = new ArrayList<>();
        boolean validEquivalent = true;
        for (Fixture fixture : fixtures) {
            ValidResult result = decodeValid(decoder, fixture, decoderPolicyIdentity);
            cases.add(result.toMap());
            validEquivalent &= result.equivalent();
        }

        List<InvalidFixture> invalidFixtures = invalidFixtures();
        boolean invalidStable = true;
        for (InvalidFixture fixture : invalidFixtures) {
            Observation first = observe(decoder, fixture.source());
            Observation second = observe(decoder, fixture.source());
            boolean stable = first.behaviorKey().equals(second.behaviorKey());
            Map<String, Object> value = new LinkedHashMap<>(first.toMap(fixture.id()));
            value.put("repeatDecoded", second.decoded());
            value.put("repeatFailureClass", second.failureClass());
            value.put("behaviorStable", stable);
            value.put("equivalent", stable);
            value.put("preparedAudioEligible", false);
            cases.add(Map.copyOf(value));
            invalidStable &= stable;
        }

        boolean equivalent = identityExact && validEquivalent && invalidStable;
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "starsector-preflight-installed-jorbis-equivalence-v2");
        root.put("generatedAt", Instant.now());
        root.put("fixtureProfile", options.fixtureProfile());
        root.put("equivalent", equivalent);
        root.put("identityExact", identityExact);
        root.put("validPcmEquivalent", validEquivalent);
        root.put("invalidBehaviorStable", invalidStable);
        root.put("decoderApi", "jogg-jorbis-low-level-synthesis");
        root.put("jogg", jogg.toMap());
        root.put("info", info.toMap());
        root.put("dspState", dspState.toMap());
        root.put("block", block.toMap());
        root.put("decoderPolicyIdentitySha256", decoderPolicyIdentity);
        root.put("maxReferenceSampleDelta", MAX_REFERENCE_SAMPLE_DELTA);
        root.put("maxUntrimmedTailFrames", MAX_UNTRIMMED_TAIL_FRAMES);
        root.put("pcmEncoding", "PCM_SIGNED");
        root.put("bitsPerSample", 16);
        root.put("byteOrder", "LITTLE_ENDIAN");
        root.put("primaryWrapperSeam", "sound/J.o00000(Ljava/io/InputStream;)Lsound/F;");
        root.put("fullyDecodedEffectsEligible", identityExact && validEquivalent);
        root.put("streamedMusicEligible", false);
        root.put("preparedAudioWritesEnabled", false);
        root.put("liveTransformEnabled", false);
        root.put("validCaseCount", fixtures.size());
        root.put("invalidCaseCount", invalidFixtures.size());
        root.put("caseCount", cases.size());
        root.put("cases", List.copyOf(cases));
        return Map.copyOf(root);
    }

    private static List<InvalidFixture> invalidFixtures() throws IOException {
        byte[] mono = fixture("mono-22050.ogg");
        byte[] stereo = fixture("stereo-44100.ogg");
        byte[] corrupt = stereo.clone();
        corrupt[Math.min(corrupt.length - 1, 192)] ^= 0x40;
        return List.of(
                new InvalidFixture("opus-unsupported", fixture("mono-22050-opus.ogg")),
                new InvalidFixture("non-ogg", "not an ogg stream".getBytes(StandardCharsets.US_ASCII)),
                new InvalidFixture("truncated-header", Arrays.copyOf(mono, 19)),
                new InvalidFixture("truncated-packet", Arrays.copyOf(mono, mono.length / 2)),
                new InvalidFixture("corrupt-packet", corrupt));
    }

    private static boolean appLoader(Identity identity) {
        return LOADER_CLASS.equals(identity.loaderClass()) && LOADER_NAME.equals(identity.loaderName());
    }

    /**
     * Checks one fixture two ways.
     *
     * <p>Exactly, against the installed decoder itself: the same bytes must decode to the same PCM
     * twice, and a {@link PreparedAudio} built from that decode must survive a serialisation round trip
     * unchanged. Those are the properties a cache actually depends on, and they are reachable exactly
     * because the same implementation is on both sides.
     *
     * <p>Within tolerance, against the committed libvorbis reference. This is the part that catches a
     * decoder wired to the wrong thing. Self-consistency cannot: decoding silence twice is perfectly
     * deterministic and round trips perfectly, which is why the superseded gate could report a decode
     * that was entirely silent and flag only a hash mismatch.
     */
    private static ValidResult decodeValid(
            LowLevelVorbisDecoder decoder, Fixture fixture, String decoderPolicyIdentity) {
        byte[] source;
        try {
            source = fixture(fixture.sourceResource());
        } catch (IOException failure) {
            return ValidResult.failure(fixture, new byte[0], failure, false, 0, 0, 0);
        }
        TrackingInputStream input = new TrackingInputStream(source);
        try {
            LowLevelVorbisDecoder.Decoded decoded = decoder.decode(input);
            boolean closedDuringDecode = input.closeCount() != 0;
            input.close();
            boolean ownershipExact = !closedDuringDecode && input.closeCount() == 1;
            int frameBytes = Math.multiplyExact(decoded.channels(), 2);
            long frames = decoded.pcm().length / frameBytes;
            PreparedAudio prepared = new PreparedAudio(
                    Hashes.sha256(source),
                    decoderPolicyIdentity,
                    PreparedAudio.Policy.FULLY_DECODED_EFFECT,
                    PreparedAudio.PcmEncoding.PCM_SIGNED,
                    16,
                    PreparedAudio.ByteOrder.LITTLE_ENDIAN,
                    decoded.sampleRate(),
                    decoded.channels(),
                    frames,
                    decoded.pcm());

            LowLevelVorbisDecoder.Decoded repeat = decoder.decode(new TrackingInputStream(source));
            boolean deterministic = Arrays.equals(decoded.pcm(), repeat.pcm())
                    && decoded.channels() == repeat.channels()
                    && decoded.sampleRate() == repeat.sampleRate();

            PreparedAudio restored = PreparedAudioIO.fromBytes(PreparedAudioIO.toBytes(prepared));
            boolean roundTripExact = restored.equals(prepared);

            Reference reference = compareToReference(fixture, decoded);
            boolean metadataExact = decoded.channels() == fixture.channels()
                    && decoded.sampleRate() == fixture.sampleRate()
                    && prepared.frameCount() == frames
                    && prepared.sampleCount() == frames * decoded.channels();
            // Two fixtures have no libvorbis reference, so the tolerance check cannot speak for them.
            // Silence is the one failure that survives every self-consistency check — it is perfectly
            // deterministic and round trips perfectly — so it is worth asserting on its own, for every
            // fixture, whether or not a reference exists to catch it.
            boolean audiblyCorrect = !fixture.containsAudio() || !allZero(decoded.pcm());
            boolean exact = metadataExact
                    && deterministic
                    && roundTripExact
                    && audiblyCorrect
                    && reference.withinTolerance()
                    && ownershipExact
                    && decoded.sawEndOfStream()
                    && input.bytesRead() == source.length;
            return ValidResult.success(
                    fixture, source, decoded, prepared, prepared.pcmSha256(), deterministic,
                    roundTripExact, audiblyCorrect, reference, closedDuringDecode, input,
                    ownershipExact, exact);
        } catch (Throwable failure) {
            boolean closedDuringDecode = input.closeCount() != 0;
            try {
                input.close();
            } catch (IOException ignored) {
            }
            return ValidResult.failure(
                    fixture, source, rootCause(failure), closedDuringDecode,
                    input.closeCount(), input.bytesRead(), input.readCalls());
        }
    }

    /**
     * Compares a decode against the committed libvorbis reference, where one exists.
     *
     * <p>The reference is expected to be a shorter prefix of the same audio: the installed decoder
     * leaves the final block untrimmed. A decode shorter than the reference is always a failure, since
     * nothing legitimate removes audio.
     */
    private static Reference compareToReference(Fixture fixture, LowLevelVorbisDecoder.Decoded decoded)
            throws IOException {
        if (fixture.referenceResource() == null) {
            return Reference.absent();
        }
        byte[] reference = fixture(fixture.referenceResource());
        byte[] actual = decoded.pcm();
        if (actual.length < reference.length) {
            return Reference.shorterThanReference(reference.length, actual.length);
        }
        int shared = reference.length / 2;
        int maxDelta = 0;
        for (int index = 0; index < shared; index++) {
            int left = sample(actual, index);
            int right = sample(reference, index);
            maxDelta = Math.max(maxDelta, Math.abs(left - right));
        }
        int excessFrames = (actual.length - reference.length)
                / Math.multiplyExact(decoded.channels(), 2);
        boolean within = maxDelta <= MAX_REFERENCE_SAMPLE_DELTA
                && excessFrames <= MAX_UNTRIMMED_TAIL_FRAMES;
        return new Reference(true, reference.length, maxDelta, excessFrames, within);
    }

    private static boolean allZero(byte[] pcm) {
        for (byte value : pcm) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static int sample(byte[] pcm, int index) {
        return (short) ((pcm[index * 2] & 0xff) | (pcm[index * 2 + 1] << 8));
    }

    private static Observation observe(LowLevelVorbisDecoder decoder, byte[] source) {
        TrackingInputStream input = new TrackingInputStream(source);
        try {
            LowLevelVorbisDecoder.Decoded decoded = decoder.decode(input);
            boolean closedDuringDecode = input.closeCount() != 0;
            input.close();
            return Observation.decoded(decoded, input, closedDuringDecode);
        } catch (Throwable failure) {
            boolean closedDuringDecode = input.closeCount() != 0;
            try {
                input.close();
            } catch (IOException ignored) {
            }
            return Observation.failed(rootCause(failure), input, closedDuringDecode);
        }
    }

    private static Identity identity(Class<?> type, String expectedSha256) throws Exception {
        ClassLoader loader = type.getClassLoader();
        CodeSource source = type.getProtectionDomain().getCodeSource();
        Path path = source == null || source.getLocation() == null
                ? null
                : Path.of(new URI(source.getLocation().toString())).toAbsolutePath().normalize();
        String actual = path != null && Files.isRegularFile(path) ? Hashes.sha256(path) : "";
        return new Identity(
                type.getName(),
                path == null ? "" : path.toString(),
                expectedSha256,
                actual,
                loader == null ? "<bootstrap>" : loader.getClass().getName(),
                loader == null ? "<bootstrap>" : String.valueOf(loader.getName()),
                expectedSha256.equals(actual));
    }

    private static byte[] fixture(String name) throws IOException {
        String base = "/audio/ogg-v1/" + name + ".b64";
        InputStream single = InstalledJorbisEquivalenceChild.class.getResourceAsStream(base);
        if (single != null) {
            try (single) {
                return Base64.getMimeDecoder().decode(single.readAllBytes());
            }
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        int parts = 0;
        for (int i = 0; i < 100; i++) {
            InputStream part = InstalledJorbisEquivalenceChild.class.getResourceAsStream(
                    base + ".part" + String.format("%02d", i));
            if (part == null) break;
            try (part) {
                part.transferTo(encoded);
            }
            parts++;
        }
        if (parts == 0) throw new IOException("Missing packaged audio fixture " + base);
        return Base64.getMimeDecoder().decode(encoded.toByteArray());
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof InvocationTargetException || current.getCause() != null)
                && current.getCause() != null
                && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static String bounded(String value) {
        String text = value == null ? "" : value;
        return text.length() <= MAX_FAILURE_DETAIL_CHARS
                ? text
                : text.substring(0, MAX_FAILURE_DETAIL_CHARS);
    }

    record Options(String expectedJoggSha256, String expectedJorbisSha256, String fixtureProfile, Path output) {
        Options {
            Hashes.decodeSha256(expectedJoggSha256);
            Hashes.decodeSha256(expectedJorbisSha256);
            if (!"full".equals(fixtureProfile) && !"ci".equals(fixtureProfile)) {
                throw new IllegalArgumentException("fixtureProfile must be full or ci");
            }
            if (output == null) throw new IllegalArgumentException("output is required");
        }

        static Options parse(String[] args) {
            String jogg = null;
            String jorbis = null;
            String profile = "full";
            Path output = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--expected-jogg-sha256" -> jogg = value(args, ++i);
                    case "--expected-jorbis-sha256" -> jorbis = value(args, ++i);
                    case "--fixture-profile" -> profile = value(args, ++i);
                    case "--output" -> output = Path.of(value(args, ++i));
                    default -> throw new IllegalArgumentException("Unknown child option: " + args[i]);
                }
            }
            return new Options(
                    required(jogg, "--expected-jogg-sha256"),
                    required(jorbis, "--expected-jorbis-sha256"),
                    profile,
                    output);
        }

        private static String value(String[] args, int index) {
            if (index >= args.length) throw new IllegalArgumentException("Missing child option value");
            return args[index];
        }

        private static String required(String value, String name) {
            if (value == null) throw new IllegalArgumentException("Missing " + name);
            return value;
        }
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private long bytesRead;
        private long readCalls;
        private long eofReads;
        private int closeCount;

        private TrackingInputStream(byte[] source) {
            super(source);
        }

        @Override
        public synchronized int read() {
            readCalls++;
            int value = super.read();
            if (value < 0) eofReads++; else bytesRead++;
            return value;
        }

        @Override
        public synchronized int read(byte[] bytes, int offset, int length) {
            readCalls++;
            int count = super.read(bytes, offset, length);
            if (count < 0) eofReads++; else bytesRead += count;
            return count;
        }

        @Override
        public void close() throws IOException {
            closeCount++;
            super.close();
        }

        private long bytesRead() { return bytesRead; }
        private long readCalls() { return readCalls; }
        private long eofReads() { return eofReads; }
        private int closeCount() { return closeCount; }
    }

    /**
     * A fixture and, where one has been generated, the libvorbis PCM to sanity-check the decode against.
     *
     * <p>{@code referenceResource} is null for fixtures that never had a reference produced. Those are
     * still checked exactly against the installed decoder; they simply cannot contribute the external
     * check, and the report says so per case rather than implying a comparison happened.
     */
    private record Fixture(
            String id,
            String sourceResource,
            String referenceResource,
            int channels,
            int sampleRate,
            boolean containsAudio) {
        private Fixture {
            if (channels < 1 || sampleRate < 1) {
                throw new IllegalArgumentException("Fixture format is invalid: " + id);
            }
        }
    }

    /** Outcome of comparing a decode against the committed libvorbis reference. */
    private record Reference(
            boolean compared,
            int referencePcmBytes,
            int maxSampleDelta,
            int excessFrames,
            boolean withinTolerance) {

        private static Reference absent() {
            // Nothing to compare against, so nothing to fail on.
            return new Reference(false, 0, 0, 0, true);
        }

        private static Reference shorterThanReference(int referenceBytes, int actualBytes) {
            return new Reference(true, referenceBytes, Integer.MAX_VALUE,
                    actualBytes - referenceBytes, false);
        }

        private Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("referenceCompared", compared);
            values.put("referencePcmBytes", referencePcmBytes);
            values.put("referenceMaxSampleDelta", maxSampleDelta);
            values.put("referenceExcessFrames", excessFrames);
            values.put("referenceWithinTolerance", withinTolerance);
            return values;
        }
    }

    private record InvalidFixture(String id, byte[] source) {
        private InvalidFixture { source = source.clone(); }
        @Override public byte[] source() { return source.clone(); }
    }

    private record Identity(
            String className,
            String source,
            String expectedSha256,
            String actualSha256,
            String loaderClass,
            String loaderName,
            boolean exact) {
        private Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("className", className);
            values.put("source", source);
            values.put("expectedSha256", expectedSha256);
            values.put("actualSha256", actualSha256);
            values.put("loaderClass", loaderClass);
            values.put("loaderName", loaderName);
            values.put("exact", exact);
            return Map.copyOf(values);
        }
    }

    private record ValidResult(
            String id,
            boolean equivalent,
            boolean decoded,
            String sourceSha256,
            int sourceBytes,
            String actualPcmSha256,
            int actualPcmBytes,
            int expectedChannels,
            int actualChannels,
            int expectedSampleRate,
            int actualSampleRate,
            long frameCount,
            long sampleCount,
            int audioPackets,
            boolean sawEndOfStream,
            boolean deterministic,
            boolean preparedRoundTripExact,
            boolean audiblyCorrect,
            Reference reference,
            long sourceBytesRead,
            long sourceReadCalls,
            boolean streamClosedDuringDecode,
            int finalCloseCount,
            boolean streamOwnershipExact,
            String failureClass,
            String failureDetail) {

        private static ValidResult success(
                Fixture fixture,
                byte[] source,
                LowLevelVorbisDecoder.Decoded decoded,
                PreparedAudio prepared,
                String actualPcmSha256,
                boolean deterministic,
                boolean roundTripExact,
                boolean audiblyCorrect,
                Reference reference,
                boolean closedDuring,
                TrackingInputStream input,
                boolean ownership,
                boolean exact) {
            return new ValidResult(
                    fixture.id(), exact, true, Hashes.sha256(source), source.length,
                    actualPcmSha256, decoded.pcm().length,
                    fixture.channels(), decoded.channels(), fixture.sampleRate(), decoded.sampleRate(),
                    prepared.frameCount(), prepared.sampleCount(), decoded.packets(),
                    decoded.sawEndOfStream(), deterministic, roundTripExact, audiblyCorrect, reference,
                    input.bytesRead(), input.readCalls(), closedDuring, input.closeCount(), ownership, "", "");
        }

        private static ValidResult failure(
                Fixture fixture,
                byte[] source,
                Throwable failure,
                boolean closedDuring,
                int closeCount,
                long bytesRead,
                long readCalls) {
            return new ValidResult(
                    fixture.id(), false, false, source.length == 0 ? "" : Hashes.sha256(source), source.length,
                    "", 0, fixture.channels(), 0, fixture.sampleRate(), 0, 0, 0, 0, false, false, false,
                    false, Reference.absent(), bytesRead, readCalls, closedDuring, closeCount,
                    !closedDuring && closeCount == 1,
                    failure.getClass().getName(), bounded(failure.getMessage()));
        }

        private Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("id", id);
            values.put("validInput", true);
            values.put("equivalent", equivalent);
            values.put("decoded", decoded);
            values.put("preparedAudioEligible", equivalent);
            values.put("sourceSha256", sourceSha256);
            values.put("sourceBytes", sourceBytes);
            values.put("actualPcmSha256", actualPcmSha256);
            values.put("actualPcmBytes", actualPcmBytes);
            values.put("expectedChannels", expectedChannels);
            values.put("actualChannels", actualChannels);
            values.put("expectedSampleRate", expectedSampleRate);
            values.put("actualSampleRate", actualSampleRate);
            values.put("frameCount", frameCount);
            values.put("sampleCount", sampleCount);
            values.put("audioPackets", audioPackets);
            values.put("sawEndOfStream", sawEndOfStream);
            values.put("deterministic", deterministic);
            values.put("preparedRoundTripExact", preparedRoundTripExact);
            values.put("audiblyCorrect", audiblyCorrect);
            values.putAll(reference.toMap());
            values.put("sourceBytesRead", sourceBytesRead);
            values.put("sourceReadCalls", sourceReadCalls);
            values.put("streamClosedDuringDecode", streamClosedDuringDecode);
            values.put("finalCloseCount", finalCloseCount);
            values.put("streamOwnershipExact", streamOwnershipExact);
            values.put("failureClass", failureClass);
            values.put("failureDetail", failureDetail);
            return Map.copyOf(values);
        }
    }

    private record Observation(
            boolean decoded,
            String pcmSha256,
            int pcmBytes,
            int channels,
            int sampleRate,
            int audioPackets,
            long sourceBytesRead,
            long sourceReadCalls,
            long sourceEofReads,
            boolean streamClosedDuringDecode,
            int finalCloseCount,
            String failureClass,
            String failureDetail) {

        private static Observation decoded(
                LowLevelVorbisDecoder.Decoded decoded, TrackingInputStream input, boolean closedDuring) {
            return new Observation(
                    true, Hashes.sha256(decoded.pcm()), decoded.pcm().length,
                    decoded.channels(), decoded.sampleRate(), decoded.packets(), input.bytesRead(),
                    input.readCalls(), input.eofReads(), closedDuring, input.closeCount(), "", "");
        }

        private static Observation failed(Throwable failure, TrackingInputStream input, boolean closedDuring) {
            return new Observation(
                    false, "", 0, 0, 0, 0, input.bytesRead(), input.readCalls(), input.eofReads(),
                    closedDuring, input.closeCount(), failure.getClass().getName(), bounded(failure.getMessage()));
        }

        private String behaviorKey() {
            return decoded + "|" + pcmSha256 + "|" + pcmBytes + "|" + channels + "|" + sampleRate
                    + "|" + sourceBytesRead + "|" + sourceReadCalls + "|" + sourceEofReads
                    + "|" + streamClosedDuringDecode + "|" + finalCloseCount + "|" + failureClass;
        }

        private Map<String, Object> toMap(String id) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("id", id);
            values.put("validInput", false);
            values.put("decoded", decoded);
            values.put("actualPcmSha256", pcmSha256);
            values.put("actualPcmBytes", pcmBytes);
            values.put("actualChannels", channels);
            values.put("actualSampleRate", sampleRate);
            values.put("audioPackets", audioPackets);
            values.put("sourceBytesRead", sourceBytesRead);
            values.put("sourceReadCalls", sourceReadCalls);
            values.put("sourceEofReads", sourceEofReads);
            values.put("streamClosedDuringDecode", streamClosedDuringDecode);
            values.put("finalCloseCount", finalCloseCount);
            values.put("streamOwnershipExact", !streamClosedDuringDecode && finalCloseCount == 1);
            values.put("failureClass", failureClass);
            values.put("failureDetail", failureDetail);
            return Map.copyOf(values);
        }
    }
}
