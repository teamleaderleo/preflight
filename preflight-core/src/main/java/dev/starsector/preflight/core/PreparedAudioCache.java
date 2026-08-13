package dev.starsector.preflight.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** Fail-open content-addressed lookup for exact prepared-audio identities. */
public final class PreparedAudioCache {
    private static final String KEY_SCHEMA = "starsector-preflight-prepared-audio-key-v1";
    private static final int ESTABLISHED_AUDIO_FORMAT_VERSION = 1;
    private static final int ESTABLISHED_MANIFEST_FORMAT_VERSION = 1;

    private PreparedAudioCache() {
    }

    public static Path root(Path cacheRoot) {
        return CacheFormatNamespace.directory(
                Objects.requireNonNull(cacheRoot, "cacheRoot"),
                "prepared-audio",
                PreparedAudio.FORMAT_VERSION,
                ESTABLISHED_AUDIO_FORMAT_VERSION);
    }

    public static Path manifestDirectory(Path cacheRoot) {
        return CacheFormatNamespace.directory(
                root(cacheRoot),
                "manifests",
                PreparedAudioManifest.FORMAT_VERSION,
                ESTABLISHED_MANIFEST_FORMAT_VERSION);
    }

    public static String cacheKeySha256(
            String sourceSha256,
            String decoderPolicyIdentitySha256,
            PreparedAudio.Policy policy) {
        String source = canonicalHash(sourceSha256);
        String decoder = canonicalHash(decoderPolicyIdentitySha256);
        Objects.requireNonNull(policy, "policy");
        String canonical = KEY_SCHEMA + "\n"
                + "source=" + source + "\n"
                + "decoder-policy=" + decoder + "\n"
                + "policy=" + policy.name() + "\n";
        return Hashes.sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    public static Path blobPath(
            Path cacheRoot,
            String sourceSha256,
            String decoderPolicyIdentitySha256,
            PreparedAudio.Policy policy) {
        Objects.requireNonNull(cacheRoot, "cacheRoot");
        String key = cacheKeySha256(sourceSha256, decoderPolicyIdentitySha256, policy);
        Path cacheBoundary = cacheRoot.toAbsolutePath().normalize();
        Path target = root(cacheRoot).toAbsolutePath().normalize()
                .resolve(key.substring(0, 2))
                .resolve(key + ".spau")
                .normalize();
        if (!target.startsWith(cacheBoundary)) {
            throw new IllegalArgumentException("Prepared audio path escaped the cache root");
        }
        return target;
    }

    public static void write(Path cacheRoot, PreparedAudio audio) throws IOException {
        Objects.requireNonNull(audio, "audio");
        Path target = blobPath(
                cacheRoot,
                audio.sourceSha256(),
                audio.decoderPolicyIdentitySha256(),
                audio.policy());
        PreparedAudioIO.write(target, audio);
    }

    public static Lookup lookup(
            Path cacheRoot,
            String sourceSha256,
            String decoderPolicyIdentitySha256,
            PreparedAudio.Policy policy) {
        if (policy == null) {
            return Lookup.failure(Status.ERROR, null, "Prepared audio policy is missing");
        }
        if (!policy.cacheEligible()) {
            return Lookup.failure(Status.INELIGIBLE, null, "Audio policy is " + policy);
        }
        final String source;
        final String decoder;
        final Path target;
        try {
            source = canonicalHash(sourceSha256);
            decoder = canonicalHash(decoderPolicyIdentitySha256);
            target = blobPath(cacheRoot, source, decoder, policy);
        } catch (RuntimeException error) {
            return Lookup.failure(Status.ERROR, null, message(error));
        }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return Lookup.failure(Status.MISS, target, "No prepared audio exists for the exact identity");
        }
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return Lookup.failure(Status.ERROR, target, "Prepared audio path is not a regular file");
        }
        try {
            PreparedAudio audio = PreparedAudioIO.read(target);
            if (!audio.sourceSha256().equals(source)
                    || !audio.decoderPolicyIdentitySha256().equals(decoder)
                    || audio.policy() != policy) {
                return Lookup.failure(Status.CORRUPT, target, "Prepared audio identity differs from its path");
            }
            return Lookup.hit(target, audio);
        } catch (NoSuchFileException error) {
            return Lookup.failure(Status.MISS, target, "Prepared audio disappeared during lookup");
        } catch (IOException | IllegalArgumentException error) {
            return Lookup.failure(Status.CORRUPT, target, message(error));
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            return Lookup.failure(Status.ERROR, target, message(error));
        }
    }

    private static String canonicalHash(String value) {
        Objects.requireNonNull(value, "sha256");
        Hashes.decodeSha256(value);
        return value.toLowerCase(Locale.ROOT);
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }

    public enum Status {
        HIT,
        MISS,
        INELIGIBLE,
        CORRUPT,
        ERROR
    }

    public record Lookup(Status status, Path path, PreparedAudio audio, String detail) {
        public Lookup {
            status = Objects.requireNonNull(status, "status");
            detail = detail == null ? "" : detail;
            if (status == Status.HIT && audio == null) {
                throw new IllegalArgumentException("A prepared-audio cache hit requires audio");
            }
            if (status != Status.HIT && audio != null) {
                throw new IllegalArgumentException("Only a prepared-audio cache hit may contain audio");
            }
        }

        static Lookup hit(Path path, PreparedAudio audio) {
            return new Lookup(Status.HIT, path, Objects.requireNonNull(audio, "audio"), "");
        }

        static Lookup failure(Status status, Path path, String detail) {
            if (status == Status.HIT) throw new IllegalArgumentException("Use hit() for cache hits");
            return new Lookup(status, path, null, detail);
        }
    }
}
