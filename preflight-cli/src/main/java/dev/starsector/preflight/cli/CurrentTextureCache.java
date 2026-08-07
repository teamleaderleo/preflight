package dev.starsector.preflight.cli;

import dev.starsector.preflight.agent.TextureCompatibilityRuntime;
import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceIndexIO;
import dev.starsector.preflight.core.TextureManifest;
import dev.starsector.preflight.core.TextureManifestIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Resolves only an exact, already-prepared texture cache for the current installed profile. */
final class CurrentTextureCache {
    private CurrentTextureCache() {
    }

    static Resolution resolve(Path installRoot, Path requestedCache) throws IOException {
        Path cache = (requestedCache == null ? PrepareCommand.defaultCacheDirectory() : requestedCache)
                .toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(cache)) {
            throw new IOException("Texture cache directory does not exist: " + cache
                    + ". Run `preflight prepare` first.");
        }
        Path realCache = cache.toRealPath();
        ResourceIndexBuilder.BuildResult currentBuild = ResourceIndexBuilder.build(installRoot);
        ResourceIndex current = currentBuild.index();
        String fingerprint = current.profileFingerprint();
        if (currentBuild.diagnostics().stream()
                .anyMatch(value -> value.startsWith("Enabled mod directory not found for ID:"))) {
            throw new IOException("The enabled-mod profile contains missing mod directories; prepare and launch "
                    + "only after the profile is internally consistent");
        }

        Path indexCandidate = ResourceIndexIO.directory(realCache).resolve(fingerprint + ".spfi");
        Path index = Files.isRegularFile(indexCandidate)
                ? artifact(realCache, indexCandidate)
                : firstArtifact(realCache, fingerprint + ".spfi", List.of("indexes"));
        Path manifest = artifact(realCache,
                TextureManifestIO.directory(realCache).resolve(fingerprint + ".spfm"));
        ResourceIndex stored = ResourceIndexIO.read(index);
        TextureManifest prepared = TextureManifestIO.read(manifest);

        if (!fingerprint.equals(stored.profileFingerprint())
                || !fingerprint.equals(prepared.profileFingerprint())) {
            throw new IOException("Prepared texture artifacts do not match the current profile fingerprint "
                    + fingerprint);
        }
        if (stored.entryCount() > TextureCompatibilityRuntime.MAX_MANIFEST_ENTRIES
                || stored.providerCount() > TextureCompatibilityRuntime.MAX_INDEX_PROVIDERS
                || prepared.entryCount() > TextureCompatibilityRuntime.MAX_MANIFEST_ENTRIES) {
            throw new IOException("Prepared texture artifacts exceed the live adapter safety limits");
        }
        if (!stored.roots().equals(current.roots()) || !stored.entries().equals(current.entries())) {
            throw new IOException("Prepared texture index does not exactly describe the selected installation");
        }
        // No second pass over the files. `current` was just built by walking this installation and
        // reading each file's attributes, and the comparison above proves `stored` holds exactly the
        // same roots and the same providers -- same relative path, same size, same modification
        // time, in the same resolution order. Validating `stored` against disk after that is asking
        // the filesystem a question it has already answered: 61,693 more toRealPath and
        // readAttributes calls for 513ms, in the window before the game's JVM even starts.
        //
        // The check is not weakened, only deduplicated. A file that vanished, changed size, was
        // touched, stopped being a regular file, or escaped its root cannot survive the equality
        // above, because the build would not have produced the same provider for it.
        //
        // ResourceIndexValidator still exists and is still used where there is no freshly built
        // index to compare against -- `doctor`, and the preparation path that checks an artifact
        // long after it was written.
        // Carry the checksummed stored index forward. RunCommand's dependency identities need the
        // same object immediately after this; reading and decoding the 8 MB artifact again added
        // about 0.21 s while proving nothing the equality check above had not already proved.
        return new Resolution(
                realCache,
                manifest,
                index,
                stored,
                fingerprint,
                Hashes.sha256(manifest),
                Hashes.sha256(index),
                current.providerCount(),
                currentBuild.durationMillis());
    }

    private static Path firstArtifact(Path cache, String fileName, List<String> directories) throws IOException {
        for (String directory : directories) {
            Path candidate = cache.resolve(directory).resolve(fileName);
            if (Files.isRegularFile(candidate)) {
                return artifact(cache, candidate);
            }
        }
        throw new IOException("No prepared texture index matches the current profile: " + fileName
                + ". Run `preflight prepare` first.");
    }

    private static Path artifact(Path cache, Path candidate) throws IOException {
        if (!Files.isRegularFile(candidate)) {
            throw new IOException("Prepared texture artifact does not exist: " + candidate
                    + ". Run `preflight prepare` first.");
        }
        Path real = candidate.toRealPath();
        if (!real.startsWith(cache) || !Files.isRegularFile(real)) {
            throw new IOException("Prepared texture artifact escapes the cache directory: " + candidate);
        }
        return real;
    }

    record Resolution(
            Path cacheDirectory,
            Path manifest,
            Path index,
            ResourceIndex resourceIndex,
            String profileFingerprint,
            String manifestSha256,
            String indexSha256,
            long checkedProviders,
            double indexBuildMillis) {
    }
}
