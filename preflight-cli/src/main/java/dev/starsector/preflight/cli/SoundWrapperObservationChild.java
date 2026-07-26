package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.Json;
import dev.starsector.preflight.core.PreparedAudio;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Evidence-only child for comparing installed JOrbis output with Starsector's exact sound wrapper. */
public final class SoundWrapperObservationChild {
    private static final int MAX_PCM_BYTES = PreparedAudio.MAX_PCM_BYTES;
    private static final int MAX_CLASS_BYTES = 16 * 1024 * 1024;
    private static final int READ_BUFFER_BYTES = 4_096;
    private static final int FIELD_LIMIT = 128;
    private static final int FAILURE_DETAIL_LIMIT = 1_024;
    private static final String LOADER_CLASS = "jdk.internal.loader.ClassLoaders$AppClassLoader";
    private static final String LOADER_NAME = "app";

    private static final List<Fixture> FIXTURES = List.of(
            new Fixture("mono-22050", "mono-22050.ogg", 1, 22_050),
            new Fixture("stereo-44100", "stereo-44100.ogg", 2, 44_100),
            new Fixture("silence-mono-8000", "silence-mono-8000.ogg", 1, 8_000),
            new Fixture("clipping-stereo-48000", "clipping-stereo-48000.ogg", 2, 48_000),
            new Fixture("packet-boundary-mono-44100", "packet-boundary-mono-44100.ogg", 1, 44_100));

    private SoundWrapperObservationChild() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        Map<String, Object> report = run(options);
        Path output = options.output().toAbsolutePath().normalize();
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        Files.writeString(output, Json.object(report) + System.lineSeparator(), StandardCharsets.UTF_8);
        boolean complete = Boolean.TRUE.equals(report.get("observationComplete"));
        System.out.println(complete ? "sound-wrapper-observation-complete" : "sound-wrapper-observation-incomplete");
        System.exit(complete ? 0 : 7);
    }

    static Map<String, Object> run(Options options) throws Exception {
        Class<?> soundJClass = Class.forName("sound.J");
        Class<?> soundFClass = Class.forName("sound.F");
        Class<?> joggClass = Class.forName("com.jcraft.jogg.SyncState");
        Class<?> infoClass = Class.forName("com.jcraft.jorbis.Info");
        Class<?> dspStateClass = Class.forName("com.jcraft.jorbis.DspState");
        Class<?> blockClass = Class.forName("com.jcraft.jorbis.Block");

        Identity soundJ = identity(soundJClass, options.expectedSoundSha256());
        Identity soundF = identity(soundFClass, options.expectedSoundSha256());
        Identity jogg = identity(joggClass, options.expectedJoggSha256());
        Identity info = identity(infoClass, options.expectedJorbisSha256());
        Identity dspState = identity(dspStateClass, options.expectedJorbisSha256());
        Identity block = identity(blockClass, options.expectedJorbisSha256());
        boolean identityExact = List.of(soundJ, soundF, jogg, info, dspState, block).stream()
                .allMatch(identity -> identity.exact() && appLoader(identity));

        Wrapper wrapper = new Wrapper(soundJClass, soundFClass);
        Decoder decoder = new Decoder();
        List<Fixture> fixtures = "ci".equals(options.fixtureProfile()) ? FIXTURES.subList(0, 2) : FIXTURES;

        List<Map<String, Object>> validCases = new ArrayList<>();
        boolean validObserved = true;
        boolean payloadMatched = true;
        boolean metadataCandidates = true;
        for (Fixture fixture : fixtures) {
            ValidObservation observation = observeValid(wrapper, decoder, fixture);
            validCases.add(observation.toMap());
            validObserved &= observation.observed();
            payloadMatched &= observation.payloadMatched();
            metadataCandidates &= observation.metadataCandidatesPresent();
        }

        List<Map<String, Object>> invalidCases = new ArrayList<>();
        boolean invalidStable = true;
        for (InvalidFixture fixture : invalidFixtures()) {
            WrapperOutcome first = invokeWrapper(wrapper, fixture.source());
            WrapperOutcome second = invokeWrapper(wrapper, fixture.source());
            boolean stable = first.behaviorKey().equals(second.behaviorKey());
            Map<String, Object> value = new LinkedHashMap<>(first.toMap(fixture.id()));
            value.put("repeatReturned", second.returned());
            value.put("repeatFailureClass", second.failure().className());
            value.put("behaviorStable", stable);
            value.put("activationEligible", false);
            invalidCases.add(Map.copyOf(value));
            invalidStable &= stable;
        }

        boolean complete = identityExact && validObserved && invalidStable;
        boolean candidateEquivalence = complete && payloadMatched && metadataCandidates;
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "starsector-preflight-sound-wrapper-observation-v1");
        root.put("generatedAt", Instant.now());
        root.put("fixtureProfile", options.fixtureProfile());
        root.put("observationComplete", complete);
        root.put("identityExact", identityExact);
        root.put("soundJ", soundJ.toMap());
        root.put("soundF", soundF.toMap());
        root.put("jogg", jogg.toMap());
        root.put("dspState", dspState.toMap());
        root.put("block", block.toMap());
        root.put("info", info.toMap());
        root.put("primarySeam", "sound/J.o00000(Ljava/io/InputStream;)Lsound/F;");
        root.put("wrapperMethodStatic", wrapper.methodStatic());
        root.put("validCaseCount", validCases.size());
        root.put("invalidCaseCount", invalidCases.size());
        root.put("allValidWrapperInvocationsReturned", validObserved);
        root.put("wrapperPayloadMatchesDirectJorbis", payloadMatched);
        root.put("wrapperMetadataCandidatesPresent", metadataCandidates);
        root.put("invalidWrapperBehaviorStable", invalidStable);
        root.put("candidateEquivalence", candidateEquivalence);
        root.put("equivalenceEstablished", false);
        root.put("requiresHumanReview", true);
        root.put("automaticAllowlistGenerated", false);
        root.put("identityPinnedForActivation", false);
        root.put("decodedAudioIncluded", false);
        root.put("classBytesIncluded", false);
        root.put("literalFailureMessagesIncluded", false);
        root.put("fullyDecodedEffectsEligible", false);
        root.put("streamedMusicEligible", false);
        root.put("preparedAudioReadsEnabled", false);
        root.put("preparedAudioWritesEnabled", false);
        root.put("cacheReadsEnabled", false);
        root.put("cacheWritesEnabled", false);
        root.put("liveTransformEnabled", false);
        root.put("validCases", List.copyOf(validCases));
        root.put("invalidCases", List.copyOf(invalidCases));
        return Map.copyOf(root);
    }

    private static ValidObservation observeValid(Wrapper wrapper, Decoder decoder, Fixture fixture) {
        byte[] source;
        try {
            source = fixture(fixture.resource());
        } catch (Throwable failure) {
            return ValidObservation.failed(fixture, new byte[0], rootCause(failure));
        }

        Decoded direct;
        try {
            direct = decoder.decode(new ByteArrayInputStream(source));
        } catch (Throwable failure) {
            return ValidObservation.failed(fixture, source, rootCause(failure));
        }

        WrapperOutcome wrapperOutcome = invokeWrapper(wrapper, source);
        if (!wrapperOutcome.returned()) {
            return ValidObservation.wrapperFailed(fixture, source, direct, wrapperOutcome);
        }
        try {
            return ValidObservation.success(
                    fixture,
                    source,
                    direct,
                    wrapperOutcome,
                    observeFields(wrapperOutcome.value(), direct));
        } catch (Throwable failure) {
            return ValidObservation.failed(fixture, source, rootCause(failure));
        }
    }

    static FieldSummary observeFields(Object value, Decoded direct) {
        List<Map<String, Object>> observations = new ArrayList<>();
        List<String> payloadMatches = new ArrayList<>();
        List<String> channelCandidates = new ArrayList<>();
        List<String> sampleRateCandidates = new ArrayList<>();
        boolean truncated = false;

        for (Field field : allFields(value.getClass())) {
            if (observations.size() >= FIELD_LIMIT) {
                truncated = true;
                break;
            }
            String key = field.getDeclaringClass().getName() + "#" + field.getName();
            Map<String, Object> observation = new LinkedHashMap<>();
            observation.put("field", key);
            observation.put("type", field.getType().getTypeName());
            observation.put("modifiers", field.getModifiers());
            if (Modifier.isStatic(field.getModifiers())) {
                observation.put("kind", "static-skipped");
                observations.add(Map.copyOf(observation));
                continue;
            }
            try {
                field.setAccessible(true);
                Object fieldValue = field.get(value);
                describeValue(fieldValue, field.getType(), observation, direct, key, payloadMatches);
                if (fieldValue instanceof Number number) {
                    long numeric = number.longValue();
                    if (numeric == direct.channels()) channelCandidates.add(key);
                    if (numeric == direct.sampleRate()) sampleRateCandidates.add(key);
                }
            } catch (Throwable failure) {
                observation.put("kind", "unreadable");
                observation.put("failure", redactFailure(rootCause(failure)).toMap());
            }
            observations.add(Map.copyOf(observation));
        }
        return new FieldSummary(
                List.copyOf(observations),
                List.copyOf(payloadMatches),
                List.copyOf(channelCandidates),
                List.copyOf(sampleRateCandidates),
                truncated);
    }

    private static void describeValue(
            Object value,
            Class<?> declaredType,
            Map<String, Object> observation,
            Decoded direct,
            String key,
            List<String> payloadMatches) throws IOException {
        if (value == null) {
            observation.put("kind", "null");
        } else if (value instanceof byte[] bytes) {
            observation.put("kind", "byte-array");
            observation.put("length", bytes.length);
            observation.put("sha256", Hashes.sha256(bytes));
            boolean matches = Arrays.equals(bytes, direct.pcm());
            observation.put("matchesDirectPcm", matches);
            if (matches) payloadMatches.add(key);
        } else if (value instanceof short[] shorts) {
            byte[] bytes = shortsToLittleEndian(shorts);
            observation.put("kind", "short-array");
            observation.put("length", shorts.length);
            observation.put("byteLength", bytes.length);
            observation.put("littleEndianSha256", Hashes.sha256(bytes));
            boolean matches = Arrays.equals(bytes, direct.pcm());
            observation.put("matchesDirectPcm", matches);
            if (matches) payloadMatches.add(key);
        } else if (value instanceof ByteBuffer buffer) {
            byte[] remaining = bufferBytes(buffer, buffer.position(), buffer.limit());
            byte[] zeroToLimit = bufferBytes(buffer, 0, buffer.limit());
            observation.put("kind", "byte-buffer");
            observation.put("capacity", buffer.capacity());
            observation.put("position", buffer.position());
            observation.put("limit", buffer.limit());
            observation.put("remaining", buffer.remaining());
            observation.put("direct", buffer.isDirect());
            observation.put("readOnly", buffer.isReadOnly());
            observation.put("byteOrder", buffer.order().toString());
            observation.put("remainingSha256", Hashes.sha256(remaining));
            observation.put("zeroToLimitSha256", Hashes.sha256(zeroToLimit));
            boolean remainingMatch = Arrays.equals(remaining, direct.pcm());
            boolean zeroToLimitMatch = Arrays.equals(zeroToLimit, direct.pcm());
            observation.put("remainingMatchesDirectPcm", remainingMatch);
            observation.put("zeroToLimitMatchesDirectPcm", zeroToLimitMatch);
            if (remainingMatch || zeroToLimitMatch) payloadMatches.add(key);
        } else if (declaredType.isPrimitive()
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character) {
            observation.put("kind", "scalar");
            observation.put("value", value instanceof Character character ? (int) character.charValue() : value);
        } else if (value instanceof String text) {
            observation.put("kind", "string-redacted");
            observation.put("length", text.length());
            observation.put("sha256", Hashes.sha256(text.getBytes(StandardCharsets.UTF_8)));
        } else if (value.getClass().isArray()) {
            observation.put("kind", "array-metadata-only");
            observation.put("componentType", value.getClass().getComponentType().getTypeName());
            observation.put("length", Array.getLength(value));
        } else {
            observation.put("kind", "object-metadata-only");
            observation.put("runtimeType", value.getClass().getName());
        }
    }

    private static byte[] bufferBytes(ByteBuffer source, int start, int end) throws IOException {
        if (start < 0 || end < start || end > source.capacity()) throw new IOException("Invalid ByteBuffer bounds");
        int length = end - start;
        if (length > MAX_PCM_BYTES) throw new IOException("ByteBuffer payload exceeds the safety limit");
        ByteBuffer copy = source.duplicate();
        copy.position(start);
        copy.limit(end);
        byte[] result = new byte[length];
        copy.get(result);
        return result;
    }

    private static byte[] shortsToLittleEndian(short[] values) throws IOException {
        long length = Math.multiplyExact((long) values.length, 2L);
        if (length > MAX_PCM_BYTES) throw new IOException("short[] payload exceeds the safety limit");
        ByteBuffer bytes = ByteBuffer.allocate((int) length).order(ByteOrder.LITTLE_ENDIAN);
        for (short value : values) bytes.putShort(value);
        return bytes.array();
    }

    private static List<Field> allFields(Class<?> type) {
        List<Field> result = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            result.addAll(Arrays.asList(current.getDeclaredFields()));
        }
        result.sort(Comparator.comparing((Field field) -> field.getDeclaringClass().getName())
                .thenComparing(Field::getName)
                .thenComparing(field -> field.getType().getTypeName()));
        return result;
    }

    private static WrapperOutcome invokeWrapper(Wrapper wrapper, byte[] source) {
        TrackingInputStream input = new TrackingInputStream(source);
        try {
            Object value = wrapper.invoke(input);
            boolean closedDuring = input.closeCount() != 0;
            input.close();
            return WrapperOutcome.returned(value, input, closedDuring);
        } catch (Throwable thrown) {
            boolean closedDuring = input.closeCount() != 0;
            try {
                input.close();
            } catch (IOException ignored) {
            }
            return WrapperOutcome.failed(rootCause(thrown), input, closedDuring);
        }
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

    private static Identity identity(Class<?> type, String expectedSourceSha256) throws Exception {
        ClassLoader loader = type.getClassLoader();
        CodeSource source = type.getProtectionDomain().getCodeSource();
        Path sourcePath = source == null || source.getLocation() == null
                ? null
                : Path.of(new URI(source.getLocation().toString())).toAbsolutePath().normalize();
        String sourceSha256 = sourcePath != null && Files.isRegularFile(sourcePath) ? Hashes.sha256(sourcePath) : "";
        String resource = type.getName().replace('.', '/') + ".class";
        InputStream input = loader == null
                ? ClassLoader.getSystemResourceAsStream(resource)
                : loader.getResourceAsStream(resource);
        if (input == null) throw new IOException("Could not read class resource " + resource);
        byte[] classBytes;
        try (input) {
            classBytes = input.readNBytes(MAX_CLASS_BYTES + 1);
        }
        if (classBytes.length > MAX_CLASS_BYTES) throw new IOException("Class resource exceeds safety limit: " + resource);
        return new Identity(
                type.getName(),
                sourcePath == null ? "" : sourcePath.toString(),
                expectedSourceSha256,
                sourceSha256,
                Hashes.sha256(classBytes),
                loader == null ? "<bootstrap>" : loader.getClass().getName(),
                loader == null ? "<bootstrap>" : String.valueOf(loader.getName()),
                expectedSourceSha256.equals(sourceSha256));
    }

    private static boolean appLoader(Identity identity) {
        return LOADER_CLASS.equals(identity.loaderClass()) && LOADER_NAME.equals(identity.loaderName());
    }

    private static byte[] fixture(String name) throws IOException {
        String base = "/audio/ogg-v1/" + name + ".b64";
        InputStream single = SoundWrapperObservationChild.class.getResourceAsStream(base);
        if (single != null) {
            try (single) {
                return Base64.getMimeDecoder().decode(single.readAllBytes());
            }
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        int parts = 0;
        for (int i = 0; i < 100; i++) {
            InputStream part = SoundWrapperObservationChild.class.getResourceAsStream(
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

    private static Failure redactFailure(Throwable failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        String bounded = message.length() <= FAILURE_DETAIL_LIMIT
                ? message
                : message.substring(0, FAILURE_DETAIL_LIMIT);
        return new Failure(
                failure.getClass().getName(),
                message.length(),
                Hashes.sha256(bounded.getBytes(StandardCharsets.UTF_8)),
                message.length() > FAILURE_DETAIL_LIMIT);
    }

    record Options(
            String expectedSoundSha256,
            String expectedJoggSha256,
            String expectedJorbisSha256,
            String fixtureProfile,
            Path output) {
        Options {
            Hashes.decodeSha256(expectedSoundSha256);
            Hashes.decodeSha256(expectedJoggSha256);
            Hashes.decodeSha256(expectedJorbisSha256);
            if (!"full".equals(fixtureProfile) && !"ci".equals(fixtureProfile)) {
                throw new IllegalArgumentException("fixtureProfile must be full or ci");
            }
            if (output == null) throw new IllegalArgumentException("output is required");
        }

        static Options parse(String[] args) {
            String sound = null;
            String jogg = null;
            String jorbis = null;
            String profile = "full";
            Path output = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--expected-sound-sha256" -> sound = value(args, ++i);
                    case "--expected-jogg-sha256" -> jogg = value(args, ++i);
                    case "--expected-jorbis-sha256" -> jorbis = value(args, ++i);
                    case "--fixture-profile" -> profile = value(args, ++i);
                    case "--output" -> output = Path.of(value(args, ++i));
                    default -> throw new IllegalArgumentException("Unknown sound-wrapper child option: " + args[i]);
                }
            }
            return new Options(
                    required(sound, "--expected-sound-sha256"),
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

    private static final class Wrapper {
        private final Method method;
        private final Constructor<?> constructor;

        private Wrapper(Class<?> soundJ, Class<?> soundF) throws Exception {
            method = soundJ.getDeclaredMethod("o00000", InputStream.class);
            method.setAccessible(true);
            if (!soundF.equals(method.getReturnType())) {
                throw new NoSuchMethodException("Primary seam return type is not sound.F");
            }
            if (Modifier.isStatic(method.getModifiers())) {
                constructor = null;
            } else {
                constructor = soundJ.getDeclaredConstructor();
                constructor.setAccessible(true);
            }
        }

        private Object invoke(InputStream input) throws Exception {
            Object receiver = constructor == null ? null : constructor.newInstance();
            Object result = method.invoke(receiver, input);
            if (result == null) throw new IOException("Sound wrapper returned null");
            return result;
        }

        private boolean methodStatic() {
            return constructor == null;
        }
    }

    /**
     * Produces the PCM that {@code sound/J}'s output is compared against.
     *
     * <p>This used to drive {@code com.jcraft.jorbis.VorbisFile}, which no shipped JAR references. The
     * comparison was therefore between the real wrapper and a decoder the game never runs, and it is
     * what produced the negative `wrapperPayloadMatchesDirectJorbis` on 2026-07-18 — four buffers of
     * the right length whose contents did not match, which is exactly what comparing real audio against
     * a near-silent decode looks like.
     */
    private static final class Decoder {
        private final LowLevelVorbisDecoder delegate;

        private Decoder() throws Exception {
            delegate = new LowLevelVorbisDecoder();
        }

        private Decoded decode(InputStream input) throws Exception {
            LowLevelVorbisDecoder.Decoded decoded = delegate.decode(input);
            return new Decoded(
                    decoded.pcm(), decoded.channels(), decoded.sampleRate(), decoded.packets(), 0);
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

    record Fixture(String id, String resource, int expectedChannels, int expectedSampleRate) {
    }

    record InvalidFixture(String id, byte[] source) {
    }

    record Decoded(byte[] pcm, int channels, int sampleRate, int readCalls, int bitstream) {
    }

    record Identity(
            String className,
            String source,
            String expectedSourceSha256,
            String actualSourceSha256,
            String classSha256,
            String loaderClass,
            String loaderName,
            boolean exact) {
        Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("className", className);
            value.put("source", source);
            value.put("expectedSourceSha256", expectedSourceSha256);
            value.put("actualSourceSha256", actualSourceSha256);
            value.put("classSha256", classSha256);
            value.put("loaderClass", loaderClass);
            value.put("loaderName", loaderName);
            value.put("exact", exact);
            return Map.copyOf(value);
        }
    }

    record Failure(String className, int messageLength, String messageSha256, boolean messageTruncated) {
        static Failure none() { return new Failure("", 0, "", false); }

        Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("className", className);
            value.put("messageLength", messageLength);
            value.put("messageSha256", messageSha256);
            value.put("messageTruncated", messageTruncated);
            return Map.copyOf(value);
        }
    }

    record FieldSummary(
            List<Map<String, Object>> fields,
            List<String> payloadMatchFields,
            List<String> channelCandidateFields,
            List<String> sampleRateCandidateFields,
            boolean truncated) {
        boolean payloadMatched() { return !payloadMatchFields.isEmpty(); }
        boolean metadataCandidatesPresent() {
            return !channelCandidateFields.isEmpty() && !sampleRateCandidateFields.isEmpty();
        }

        Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("fields", fields);
            value.put("payloadMatchFields", payloadMatchFields);
            value.put("channelCandidateFields", channelCandidateFields);
            value.put("sampleRateCandidateFields", sampleRateCandidateFields);
            value.put("payloadMatched", payloadMatched());
            value.put("metadataCandidatesPresent", metadataCandidatesPresent());
            value.put("truncated", truncated);
            return Map.copyOf(value);
        }
    }

    record WrapperOutcome(
            boolean returned,
            Object value,
            Failure failure,
            long sourceBytesRead,
            long sourceReadCalls,
            long sourceEofReads,
            boolean streamClosedDuringInvocation,
            int finalCloseCount) {
        static WrapperOutcome returned(Object value, TrackingInputStream input, boolean closedDuring) {
            return new WrapperOutcome(
                    true, value, Failure.none(), input.bytesRead(), input.readCalls(), input.eofReads(),
                    closedDuring, input.closeCount());
        }

        static WrapperOutcome failed(Throwable thrown, TrackingInputStream input, boolean closedDuring) {
            return new WrapperOutcome(
                    false, null, SoundWrapperObservationChild.redactFailure(thrown), input.bytesRead(),
                    input.readCalls(), input.eofReads(), closedDuring, input.closeCount());
        }

        String behaviorKey() {
            return returned + "|" + failure.className() + "|" + sourceBytesRead + "|" + sourceReadCalls
                    + "|" + sourceEofReads + "|" + streamClosedDuringInvocation + "|" + finalCloseCount;
        }

        Map<String, Object> toMap(String id) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", id);
            value.put("returned", returned);
            value.put("failure", failure.toMap());
            value.put("sourceBytesRead", sourceBytesRead);
            value.put("sourceReadCalls", sourceReadCalls);
            value.put("sourceEofReads", sourceEofReads);
            value.put("streamClosedDuringInvocation", streamClosedDuringInvocation);
            value.put("finalCloseCount", finalCloseCount);
            return Map.copyOf(value);
        }
    }

    record ValidObservation(
            Fixture fixture,
            byte[] source,
            Decoded direct,
            WrapperOutcome wrapper,
            FieldSummary fields,
            Failure setupFailure) {
        static ValidObservation success(
                Fixture fixture, byte[] source, Decoded direct, WrapperOutcome wrapper, FieldSummary fields) {
            return new ValidObservation(fixture, source, direct, wrapper, fields, Failure.none());
        }

        static ValidObservation wrapperFailed(
                Fixture fixture, byte[] source, Decoded direct, WrapperOutcome wrapper) {
            return new ValidObservation(fixture, source, direct, wrapper, null, Failure.none());
        }

        static ValidObservation failed(Fixture fixture, byte[] source, Throwable failure) {
            return new ValidObservation(
                    fixture, source, null, null, null, SoundWrapperObservationChild.redactFailure(failure));
        }

        boolean observed() { return direct != null && wrapper != null && wrapper.returned(); }
        boolean payloadMatched() { return fields != null && fields.payloadMatched(); }
        boolean metadataCandidatesPresent() { return fields != null && fields.metadataCandidatesPresent(); }

        Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", fixture.id());
            value.put("sourceBytes", source.length);
            value.put("sourceSha256", Hashes.sha256(source));
            value.put("expectedChannels", fixture.expectedChannels());
            value.put("expectedSampleRate", fixture.expectedSampleRate());
            value.put("setupFailure", setupFailure.toMap());
            if (direct != null) {
                value.put("directPcmBytes", direct.pcm().length);
                value.put("directPcmSha256", Hashes.sha256(direct.pcm()));
                value.put("directChannels", direct.channels());
                value.put("directSampleRate", direct.sampleRate());
                value.put("directReadCalls", direct.readCalls());
                value.put("directBitstream", direct.bitstream());
            }
            if (wrapper != null) value.put("wrapper", wrapper.toMap(fixture.id()));
            if (fields != null) value.put("returnedObject", fields.toMap());
            value.put("wrapperReturned", observed());
            value.put("payloadMatched", payloadMatched());
            value.put("metadataCandidatesPresent", metadataCandidatesPresent());
            value.put("activationEligible", false);
            return Map.copyOf(value);
        }
    }
}
