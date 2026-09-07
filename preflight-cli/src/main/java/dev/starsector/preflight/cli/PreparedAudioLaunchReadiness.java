package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.PreparedAudioCache;
import dev.starsector.preflight.core.PreparedAudioManifest;
import dev.starsector.preflight.core.PreparedAudioManifestIO;
import dev.starsector.preflight.core.ResourceIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Exact prepared-audio admission used by both launch selection and benchmark readiness. */
final class PreparedAudioLaunchReadiness {
    private PreparedAudioLaunchReadiness() {
    }

    static Result inspect(ProfileIdentityContext context, Path cacheRoot) {
        long started = System.nanoTime();
        Path root = PreparedAudioCache.root(cacheRoot);
        if (!Files.isDirectory(root)) {
            return new Result(
                    "missing",
                    false,
                    cacheRoot.toAbsolutePath().normalize(),
                    null,
                    null,
                    null,
                    0,
                    0,
                    "game-decode",
                    "No prepared audio exists for this installation.",
                    elapsedMillis(started));
        }

        String decoder = null;
        Path manifestPath = PreparedAudioCache.manifestDirectory(cacheRoot)
                .resolve(context.resources().profileFingerprint() + ".spam")
                .toAbsolutePath().normalize();
        try {
            List<Path> gameJars = PrepareAudioCommand.jars(context.installRoot());
            decoder = PrepareAudioCommand.decoderPolicyIdentity(gameJars);
            if (!Files.isRegularFile(manifestPath)) {
                return new Result(
                        "stale",
                        false,
                        cacheRoot.toAbsolutePath().normalize(),
                        decoder,
                        null,
                        null,
                        0,
                        0,
                        "byte-hash",
                        "Prepared audio uses a legacy manifest without the validated path index.",
                        elapsedMillis(started));
            }

            PreparedAudioManifest manifest = PreparedAudioManifestIO.read(manifestPath);
            if (!manifest.profileFingerprintSha256().equals(context.resources().profileFingerprint())) {
                throw new IOException("prepared audio manifest profile does not match this launch");
            }
            if (!manifest.starsectorBuildSha256().equals(context.gameJarSha256())) {
                throw new IOException("prepared audio manifest Starsector build does not match this launch");
            }
            if (!manifest.decoderPolicyIdentitySha256().equals(decoder)) {
                throw new IOException("prepared audio manifest decoder policy does not match this launch");
            }

            List<PreparedAudioManifest.Entry> entries = manifest.entries().values().stream()
                    .filter(entry -> entry.policy().cacheEligible())
                    .toList();
            List<ResourceIndex.Provider> providers = new ArrayList<>(entries.size());
            long sourceBytes = 0;
            for (PreparedAudioManifest.Entry entry : entries) {
                ResourceIndex.Provider provider = context.resources().winner(entry.logicalPath())
                        .orElseThrow(() -> new IOException(
                                "prepared audio source is no longer present: " + entry.logicalPath()));
                if (provider.size() != entry.sourceBytes()
                        || provider.modifiedMillis() != entry.sourceModifiedMillis()) {
                    throw new IOException("prepared audio source metadata changed: " + entry.logicalPath());
                }
                providers.add(provider);
                sourceBytes = Math.addExact(sourceBytes, entry.sourceBytes());
            }
            List<Path> sources = context.resolveAll(providers);
            List<String> hashes = context.sha256All(sources);
            for (int index = 0; index < entries.size(); index++) {
                if (!entries.get(index).sourceSha256().equals(hashes.get(index))) {
                    throw new IOException(
                            "prepared audio source content changed: " + entries.get(index).logicalPath());
                }
            }
            return new Result(
                    "current",
                    true,
                    cacheRoot.toAbsolutePath().normalize(),
                    decoder,
                    manifestPath,
                    manifest.manifestSha256(),
                    entries.size(),
                    sourceBytes,
                    "path-indexed",
                    null,
                    elapsedMillis(started));
        } catch (Exception error) {
            if (decoder == null) {
                try {
                    decoder = PrepareAudioCommand.decoderPolicyIdentity(
                            PrepareAudioCommand.jars(context.installRoot()));
                } catch (Exception ignored) {
                    // A missing decoder identity means launch selection falls all the way back.
                }
            }
            return new Result(
                    "stale",
                    false,
                    cacheRoot.toAbsolutePath().normalize(),
                    decoder,
                    null,
                    null,
                    0,
                    0,
                    decoder == null ? "game-decode" : "byte-hash",
                    message(error),
                    elapsedMillis(started));
        }
    }

    private static double elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000.0;
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }

    record Result(
            String state,
            boolean current,
            Path cacheRoot,
            String decoderIdentity,
            Path manifest,
            String manifestIdentity,
            int pathEntries,
            long sourceBytes,
            String fallbackState,
            String diagnostic,
            double validationMillis) {
    }
}
