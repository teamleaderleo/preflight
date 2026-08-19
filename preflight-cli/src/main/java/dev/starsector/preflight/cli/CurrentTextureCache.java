package dev.starsector.preflight.cli;

import dev.starsector.preflight.agent.TextureCompatibilityRuntime;
import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceIndexIO;
import dev.starsector.preflight.core.TextureManifest;
import dev.starsector.preflight.core.TextureManifestIO;
import dev.starsector.preflight.core.TextureSourceGenerationAuthority;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

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

        // The fresh index build above is the cheap whole-profile accidental-drift check. Exact
        // prepared-texture authority comes from the generation proof sealed after preparation had
        // re-hashed every prepared source against the manifest. Launch compares only those tokens;
        // it never re-reads the source bytes merely to authorize a cache hit.
        TextureSourceGenerationAuthority.Validation generation =
                TextureSourceGenerationAuthority.validate(realCache, manifest, prepared, stored);
        Path launchManifest = manifest;
        if (generation.valid()) {
            System.out.printf(
                    Locale.ROOT,
                    "Preflight verified %,d prepared texture source generations with %s "
                            + "(%.1f MB covered) in %.1fms.%n",
                    generation.checkedEntries(),
                    generation.provider(),
                    generation.sourceBytes() / 1_000_000.0,
                    generation.durationMillis());
        } else {
            // Pass a guaranteed-absent manifest path into the agent. Its existing fail-open
            // configure path then leaves the original game decoder active, while the resource
            // index remains available to unrelated launch caches.
            launchManifest = realCache.resolve(".prepared-textures-disabled-"
                    + ProcessHandle.current().pid() + '-' + System.nanoTime() + ".spfm");
            System.err.println("Preflight prepared textures are disabled for this launch: "
                    + generation.problem() + "; original texture loading remains active.");
        }

        // No second ResourceIndexValidator pass over the files. `current` was just built by walking
        // this installation and reading each file's attributes, and the comparison above proves
        // `stored` holds exactly the same roots and providers. Content authority is now the separate
        // generation proof rather than another size/mtime sweep or source SHA inside the game JVM.
        return new Resolution(
                realCache,
                launchManifest,
                index,
                stored,
                fingerprint,
                Hashes.sha256(manifest),
                Hashes.sha256(index),
                current.providerCount(),
                currentBuild.durationMillis(),
                generation.valid(),
                generation.provider(),
                generation.checkedEntries(),
                generation.sourceBytes(),
                generation.durationMillis(),
                generation.problem());
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
            double indexBuildMillis,
            boolean sourceGenerationValidated,
            String sourceGenerationProvider,
            int sourceGenerationEntries,
            long sourceGenerationBytes,
            double sourceGenerationValidationMillis,
            String sourceGenerationProblem) {
    }
}
