package dev.starsector.preflight.agent;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.PreparedAudio;
import dev.starsector.preflight.core.PreparedAudioCache;
import dev.starsector.preflight.core.PreparedAudioIO;
import dev.starsector.preflight.core.PreparedAudioManifest;
import dev.starsector.preflight.core.PreparedAudioManifestIO;
import dev.starsector.preflight.core.ResourceIndex;
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
 * <p>The native launcher content-validates a checksummed path-to-source-hash manifest for the exact
 * resource profile before the x86 game starts. The reviewed sound-store callsite supplies that path
 * beside the untouched input stream, avoiding a roughly 0.5 CPU-second SHA pass under Rosetta. An
 * unknown path or any manifest/blob problem invokes this class's original byte-SHA lookup, so a
 * served blob remains keyed by exactly the input it replaces whenever the stronger pre-launch proof
 * is unavailable.
 *
 * <p>Every path fails open. A miss, a malformed blob, a shape that is not what was reviewed, or a
 * disabled runtime all end in the same vanilla decode, fed from the bytes already in hand.
 */
public final class PreparedAudioRuntime {
    static final String PLAN_ID = "prepared-audio-v1";
    private static final String ENABLED_PROPERTY = "preflight.audio.prepared";
    /** Opt back in to hashing every prepared PCM blob on the game's two loader threads. */
    public static final String VERIFY_BLOB_CHECKSUM_PROPERTY = "preflight.audio.verifyBlobChecksum";

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
    private static volatile Map<String, String> sourceHashesByPath = Map.of();
    private static volatile String pathManifestStatus = "not-configured";

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
    private static final AtomicLong pathLookups = new AtomicLong();
    private static final AtomicLong pathHits = new AtomicLong();
    private static final AtomicLong pathMisses = new AtomicLong();
    private static final AtomicLong byteHashLookups = new AtomicLong();

    private static volatile boolean enabled = "on".equalsIgnoreCase(System.getProperty(ENABLED_PROPERTY));

    private PreparedAudioRuntime() {
    }

    static void enable(boolean value) {
        enabled = value;
    }

    static boolean ready() {
        return enabled && cacheDirectory != null && decoderPolicyIdentity != null;
    }

    static boolean pathLookupReady() {
        return ready() && !sourceHashesByPath.isEmpty();
    }

    static void configure(Path cache, String decoderIdentitySha256) {
        configure(cache, decoderIdentitySha256, null, null);
    }

    static void configure(
            Path cache,
            String decoderIdentitySha256,
            Path manifestPath,
            String expectedManifestIdentity) {
        cacheDirectory = cache;
        decoderPolicyIdentity = decoderIdentitySha256;
        sourceHashesByPath = Map.of();
        pathManifestStatus = "not-configured";
        if (manifestPath == null || expectedManifestIdentity == null) {
            return;
        }
        try {
            PreparedAudioManifest manifest = PreparedAudioManifestIO.read(manifestPath);
            if (!manifest.manifestSha256().equals(expectedManifestIdentity)
                    || !manifest.decoderPolicyIdentitySha256().equals(decoderIdentitySha256)) {
                pathManifestStatus = "identity-mismatch";
                return;
            }
            Map<String, String> paths = new LinkedHashMap<>();
            for (PreparedAudioManifest.Entry entry : manifest.entries().values()) {
                if (entry.policy().cacheEligible()) {
                    paths.put(entry.logicalPath(), entry.sourceSha256());
                }
            }
            sourceHashesByPath = Map.copyOf(paths);
            pathManifestStatus = "ready";
        } catch (RuntimeException | java.io.IOException unreadable) {
            pathManifestStatus = "unreadable";
        }
    }

    /**
     * Replaces the game's Ogg Vorbis decode.
     *
     * <p>Public because the rewritten game class calls it, and that class is not in this package.
     */
    public static Object decode(Object decoder, InputStream input, MethodHandle vanilla)
            throws Throwable {
        return decode(decoder, input, vanilla, null);
    }

    private static final ClassValue<Boolean> WINDOWS_RESULTS = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            if (!type.getName().equals("sound.G")) return false;
            try (InputStream bytes = type.getResourceAsStream("/sound/G.class")) {
                return bytes != null && Hashes.sha256(bytes.readNBytes(1_048_577)).equals(
                        "c7dbba1261cfba676dba014709c68e10563c3d06b0e8b5e664a5c1d2ee5e6616");
            } catch (java.io.IOException | RuntimeException failure) {
                return false;
            }
        }
    };

    private static final ClassValue<Boolean> LINUX_RESULTS = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            if (!type.getName().equals("sound.F")) return false;
            try (InputStream bytes = type.getResourceAsStream("/sound/F.class")) {
                return bytes != null && Hashes.sha256(bytes.readNBytes(1_048_577)).equals(
                        "5d03d4031ee2b7cac51ec6838730b91824489128fd3b538bcb41d2f6208b13e2");
            } catch (java.io.IOException | RuntimeException failure) {
                return false;
            }
        }
    };

    /** Linux's exact decoder result is resolved through its original loader. */
    public static Object decodeLinux(Object decoder, InputStream input, MethodHandle vanilla)
            throws Throwable {
        Class<?> shape = vanilla.type().returnType();
        if (!ready() || input == null || input.getClass() != ByteArrayInputStream.class
                || !LINUX_RESULTS.get(shape)) {
            return vanilla.invoke(decoder, input);
        }
        return decode(decoder, input, vanilla, shape);
    }

    /** The exact Windows decoder has a different result class, supplied by its original handle. */
    public static Object decodeWindows(Object decoder, InputStream input, MethodHandle vanilla)
            throws Throwable {
        Class<?> shape = vanilla.type().returnType();
        // The reviewed resource batch supplies complete byte-array streams. Custom streams can
        // have different read/failure semantics; let the original decoder own those untouched.
        if (!ready() || input == null || input.getClass() != ByteArrayInputStream.class
                || !WINDOWS_RESULTS.get(shape)) {
            return vanilla.invoke(decoder, input);
        }
        return decode(decoder, input, vanilla, shape);
    }

    private static Object decode(Object decoder, InputStream input, MethodHandle vanilla,
            Class<?> explicitResult) throws Throwable {
        if (!ready() || input == null) {
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
            Object prepared = serve(encoded, explicitResult);
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

    private static Object serve(byte[] encoded, Class<?> explicitResult) throws ReflectiveOperationException,
            java.io.IOException {
        byteHashLookups.incrementAndGet();
        String sourceSha256 = Hashes.sha256(encoded);
        return serve(sourceSha256, explicitResult);
    }

    /**
     * Uses the exact source hash selected and content-validated by the native launcher. The input
     * stream remains untouched, so every failure can still execute the game's decoder on it.
     */
    public static Object decodePath(
            Object decoder,
            String logicalPath,
            InputStream input,
            MethodHandle decoderEntry) throws Throwable {
        if (!ready() || sourceHashesByPath.isEmpty()) {
            return decoderEntry.invoke(decoder, input);
        }
        pathLookups.incrementAndGet();
        String sourceSha256 = null;
        try {
            sourceSha256 = sourceHashesByPath.get(ResourceIndex.normalizeLogicalPath(logicalPath));
        } catch (IllegalArgumentException ignored) {
            // An unfamiliar identifier is not a prepared resource path.
        }
        if (sourceSha256 != null) {
            decodes.incrementAndGet();
            try {
                Object prepared = serve(sourceSha256);
                if (prepared != null) {
                    pathHits.incrementAndGet();
                    return prepared;
                }
                misses.incrementAndGet();
            } catch (RuntimeException | ReflectiveOperationException | java.io.IOException
                    | LinkageError unexpected) {
                failures.incrementAndGet();
            }
        }
        pathMisses.incrementAndGet();
        return decoderEntry.invoke(decoder, input);
    }

    private static Object serve(String sourceSha256) throws ReflectiveOperationException,
            java.io.IOException {
        return serve(sourceSha256, null);
    }

    private static Object serve(String sourceSha256, Class<?> explicitResult) throws ReflectiveOperationException,
            java.io.IOException {
        Path blob = PreparedAudioCache.blobPath(
                cacheDirectory,
                sourceSha256,
                decoderPolicyIdentity,
                PreparedAudio.Policy.FULLY_DECODED_EFFECT);
        if (!Files.isRegularFile(blob)) {
            return null;
        }
        PreparedAudio audio = Boolean.getBoolean(VERIFY_BLOB_CHECKSUM_PROPERTY)
                ? PreparedAudioIO.read(blob)
                : PreparedAudioIO.readTrusted(blob);
        // The path is content-addressed, but match its contents as well. This catches accidental
        // cross-key substitution even in trusted-read mode and ensures a blob can only stand in for
        // the exact encoded input and decoder policy the game was about to execute.
        if (!audio.sourceSha256().equals(sourceSha256)
                || !audio.decoderPolicyIdentitySha256().equals(decoderPolicyIdentity)
                || audio.policy() != PreparedAudio.Policy.FULLY_DECODED_EFFECT) {
            return null;
        }
        // The loader reads three fields and hands the buffer to alBufferData as 16-bit PCM. A blob
        // that is not that shape is not this decoder's output, whatever else it may be.
        if (audio.bitsPerSample() != 16
                || audio.encoding() != PreparedAudio.PcmEncoding.PCM_SIGNED
                || audio.byteOrder() != PreparedAudio.ByteOrder.LITTLE_ENDIAN) {
            return null;
        }
        if (explicitResult == null && !resolveShape()) {
            return null;
        }
        // alBufferData needs a direct buffer, and the vanilla decoder hands back a direct one with
        // position 0. Anything else is a different object than the loader was written against.
        ByteBuffer buffer = ByteBuffer.allocateDirect(audio.pcmByteCount());
        audio.copyPcmTo(buffer);
        buffer.flip();

        Class<?> type = explicitResult == null ? resultClass : explicitResult;
        Object result = type.getDeclaredConstructor().newInstance();
        (explicitResult == null ? channelsField : type.getField(CHANNELS_FIELD)).setInt(result, audio.channels());
        (explicitResult == null ? rateField : type.getField(RATE_FIELD)).setInt(result, audio.sampleRateHz());
        (explicitResult == null ? pcmField : type.getField(PCM_FIELD)).set(result, buffer);
        served.incrementAndGet();
        servedBytes.addAndGet(audio.pcmByteCount());
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
        report.put("blobChecksumVerification", Boolean.getBoolean(VERIFY_BLOB_CHECKSUM_PROPERTY));
        report.put("pathManifestStatus", pathManifestStatus);
        report.put("pathManifestEntries", sourceHashesByPath.size());
        report.put("pathLookups", pathLookups.get());
        report.put("pathHits", pathHits.get());
        report.put("pathMisses", pathMisses.get());
        report.put("byteHashLookups", byteHashLookups.get());
        return report;
    }

    static void reset() {
        decodes.set(0);
        served.set(0);
        servedBytes.set(0);
        misses.set(0);
        failures.set(0);
        pathLookups.set(0);
        pathHits.set(0);
        pathMisses.set(0);
        byteHashLookups.set(0);
        shapeResolved = false;
        resultClass = null;
        channelsField = null;
        pcmField = null;
        rateField = null;
    }
}
