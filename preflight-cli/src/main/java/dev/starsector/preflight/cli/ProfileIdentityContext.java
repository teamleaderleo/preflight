package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.PathContainment;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceIndexIO;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

/**
 * The work every dependency-profile identity has in common, done once per launch.
 *
 * <p>Every content digest is tied to one strong opened-file generation. Provider hashes must match
 * the generation persisted by the ResourceIndex. Other profile inputs establish a launch-local
 * accepted generation on their first pinned read. One payload read is shared per accepted
 * generation, while every memo reuse performs a cheap generation revalidation.
 */
final class ProfileIdentityContext implements Closeable {
    private final Path installRoot;
    private final Path gameJar;
    private final String gameJarSha256;
    private final OpenedFileGenerationAuthority.Generation gameJarGeneration;
    private final ResourceIndex resources;
    private final List<Path> realRoots;
    private final ContentHasher hasher;
    private final Map<Path, Path> realDirectories = new ConcurrentHashMap<>();
    private final Map<ResourceIndex.Provider, Path> realProviders = new ConcurrentHashMap<>();
    private final Map<Path, ResourceIndex.Provider> providerByPath = new ConcurrentHashMap<>();
    private final Map<Path, OpenedFileGenerationAuthority.Generation> acceptedGenerations =
            new ConcurrentHashMap<>();
    private final Map<Path, CompletableFuture<OpenedFileGenerationAuthority.HashEvidence>> fileHashes =
            new ConcurrentHashMap<>();
    private final int workers;

    private ForkJoinPool pool;

    @FunctionalInterface
    interface ContentHasher {
        String sha256(Path publicPath, InputStream input) throws IOException;
    }

    private ProfileIdentityContext(
            Path installRoot,
            Path gameJar,
            OpenedFileGenerationAuthority.HashEvidence gameJarEvidence,
            ResourceIndex resources,
            List<Path> realRoots,
            ContentHasher hasher,
            int workers) {
        this.installRoot = installRoot;
        this.gameJar = gameJar;
        this.gameJarSha256 = gameJarEvidence.sha256();
        this.gameJarGeneration = gameJarEvidence.generation();
        this.resources = resources;
        this.realRoots = realRoots;
        this.hasher = hasher;
        this.workers = workers;
        this.fileHashes.put(gameJar, CompletableFuture.completedFuture(gameJarEvidence));
    }

    /** Reads the index from disk. The launcher does this once and shares the result. */
    static ProfileIdentityContext open(Path installRoot, Path indexFile) throws IOException {
        return of(installRoot, ResourceIndexIO.read(indexFile));
    }

    static ProfileIdentityContext of(Path installRoot, ResourceIndex resources) throws IOException {
        return of(installRoot, resources, (ignored, input) -> Hashes.sha256(input), defaultWorkers());
    }

    static ProfileIdentityContext of(
            Path installRoot,
            ResourceIndex resources,
            ContentHasher hasher,
            int workers) throws IOException {
        Path root = installRoot.toAbsolutePath().normalize();
        Path gameJar = locateGameJar(root);
        List<Path> realRoots = new ArrayList<>(resources.roots().size());
        for (ResourceIndex.Root each : resources.roots()) {
            realRoots.add(PathContainment.realDirectory(each.path()));
        }
        OpenedFileGenerationAuthority.HashEvidence gameJarEvidence =
                OpenedFileGenerationAuthority.hash(gameJar, null, hasher::sha256);
        return new ProfileIdentityContext(
                root,
                gameJar,
                gameJarEvidence,
                resources,
                List.copyOf(realRoots),
                hasher,
                workers);
    }

    static int defaultWorkers() {
        return Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
    }

    Path installRoot() {
        return installRoot;
    }

    Path gameJar() {
        return gameJar;
    }

    String gameJarSha256() {
        return gameJarSha256;
    }

    ResourceIndex resources() {
        return resources;
    }

    List<Path> resolveAll(List<ResourceIndex.Provider> providers) throws IOException {
        List<Path> sources = new ArrayList<>(providers.size());
        for (ResourceIndex.Provider provider : providers) {
            sources.add(resolve(provider));
        }
        return sources;
    }

    Path resolve(ResourceIndex.Provider provider) throws IOException {
        try {
            Path resolved = realProviders.computeIfAbsent(provider, key -> {
                try {
                    return resolveUncached(key);
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                }
            });
            providerByPath.putIfAbsent(resolved, provider);
            return resolved;
        } catch (UncheckedIOException failed) {
            throw failed.getCause();
        }
    }

    String sha256(ResourceIndex.Provider provider) throws IOException {
        Path resolved = resolve(provider);
        return sha256(resolved, provider);
    }

    private Path resolveUncached(ResourceIndex.Provider provider) throws IOException {
        Path realRoot = realRoots.get(provider.rootIndex());
        Path candidate = resources.resolve(provider);
        Path parent = candidate.getParent();
        if (parent == null) {
            Path resolved = PathContainment.existingInsideRealRoot(realRoot, candidate);
            providerByPath.put(resolved, provider);
            return resolved;
        }
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(
                    candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException missing) {
            Path resolved = PathContainment.existingInsideRealRoot(realRoot, candidate);
            providerByPath.put(resolved, provider);
            return resolved;
        }
        if (attributes.isSymbolicLink()) {
            Path resolved = PathContainment.existingInsideRealRoot(realRoot, candidate);
            providerByPath.put(resolved, provider);
            return resolved;
        }
        Path realParent = realDirectory(parent);
        Path resolved = realParent.resolve(candidate.getFileName());
        if (!resolved.startsWith(realRoot)) {
            throw new IllegalArgumentException(
                    "Path escapes its root: " + candidate + " resolves to " + resolved
                            + " outside " + realRoot);
        }
        providerByPath.put(resolved, provider);
        return resolved;
    }

    private Path realDirectory(Path directory) throws IOException {
        try {
            return realDirectories.computeIfAbsent(directory, path -> {
                try {
                    return path.toRealPath();
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                }
            });
        } catch (UncheckedIOException failed) {
            throw failed.getCause();
        }
    }

    /** SHA-256 of every source, in the order requested. */
    List<String> sha256All(List<Path> sources) throws IOException {
        int count = sources.size();
        String[] digests = new String[count];
        if (count == 0) {
            return List.of();
        }
        if (count == 1 || workers == 1) {
            for (int index = 0; index < count; index++) {
                digests[index] = sha256(sources.get(index));
            }
            return List.of(digests);
        }
        try {
            pool().submit(() -> IntStream.range(0, count).parallel().forEach(index -> {
                try {
                    digests[index] = sha256(sources.get(index));
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                }
            })).get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while hashing profile inputs", interrupted);
        } catch (ExecutionException failed) {
            throw unwrap(failed);
        } catch (UncheckedIOException unchecked) {
            throw unchecked.getCause();
        }
        return List.of(digests);
    }

    private String sha256(Path source) throws IOException {
        Path resolved = source.toAbsolutePath().normalize();
        return sha256(resolved, providerByPath.get(resolved));
    }

    private String sha256(Path resolved, ResourceIndex.Provider provider) throws IOException {
        OpenedFileGenerationAuthority.Generation required = requiredGeneration(resolved, provider);

        while (true) {
            CompletableFuture<OpenedFileGenerationAuthority.HashEvidence> proposed = new CompletableFuture<>();
            CompletableFuture<OpenedFileGenerationAuthority.HashEvidence> shared =
                    fileHashes.putIfAbsent(resolved, proposed);
            if (shared == null) {
                shared = proposed;
                try {
                    proposed.complete(computeStableHash(resolved, provider, required));
                } catch (IOException | RuntimeException | Error failure) {
                    proposed.completeExceptionally(failure);
                    fileHashes.remove(resolved, proposed);
                }
            }

            OpenedFileGenerationAuthority.HashEvidence evidence = awaitHash(resolved, shared);
            OpenedFileGenerationAuthority.Generation accepted = required;
            if (accepted == null) {
                OpenedFileGenerationAuthority.Generation prior =
                        acceptedGenerations.putIfAbsent(resolved, evidence.generation());
                accepted = prior == null ? evidence.generation() : prior;
            }
            if (!accepted.equals(evidence.generation())) {
                fileHashes.remove(resolved, shared);
                throw new OpenedFileGenerationAuthority.StaleGenerationException(
                        "Memoized hash belongs to another file generation: " + resolved);
            }
            try {
                OpenedFileGenerationAuthority.requireCurrent(resolved, accepted);
                return evidence.sha256();
            } catch (OpenedFileGenerationAuthority.StaleGenerationException stale) {
                fileHashes.remove(resolved, shared);
                throw stale;
            }
        }
    }

    private OpenedFileGenerationAuthority.HashEvidence computeStableHash(
            Path resolved,
            ResourceIndex.Provider provider,
            OpenedFileGenerationAuthority.Generation required) throws IOException {
        if (provider != null) {
            BasicFileAttributes before = Files.readAttributes(
                    resolved, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile()) {
                throw new IOException("Profile source is not a regular file: " + resolved);
            }
            if (!matchesIndexedMetadata(provider, before)) {
                throw new OpenedFileGenerationAuthority.StaleGenerationException(
                        "Provider metadata does not match indexed state before hashing: " + resolved);
            }
        }

        OpenedFileGenerationAuthority.HashEvidence evidence =
                OpenedFileGenerationAuthority.hash(resolved, required, hasher::sha256);

        if (provider != null) {
            BasicFileAttributes after = Files.readAttributes(
                    resolved, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!after.isRegularFile() || !matchesIndexedMetadata(provider, after)) {
                throw new OpenedFileGenerationAuthority.StaleGenerationException(
                        "Provider metadata changed during hashing: " + resolved);
            }
        }
        return evidence;
    }

    private OpenedFileGenerationAuthority.Generation requiredGeneration(
            Path resolved, ResourceIndex.Provider provider) throws IOException {
        if (provider != null) {
            if (!provider.hasGenerationAuthority()) {
                throw new IOException("Provider has no exact file-generation authority: " + resolved);
            }
            return new OpenedFileGenerationAuthority.Generation(
                    provider.generationProvider(), provider.generationToken());
        }
        if (resolved.equals(gameJar)) {
            return gameJarGeneration;
        }
        return acceptedGenerations.get(resolved);
    }

    private static boolean matchesIndexedMetadata(
            ResourceIndex.Provider provider,
            BasicFileAttributes attributes) {
        return attributes.size() == provider.size()
                && Math.max(0, attributes.lastModifiedTime().toMillis()) == provider.modifiedMillis();
    }

    private static OpenedFileGenerationAuthority.HashEvidence awaitHash(
            Path resolved,
            CompletableFuture<OpenedFileGenerationAuthority.HashEvidence> shared) throws IOException {
        try {
            return shared.join();
        } catch (CompletionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("Hashing profile source failed: " + resolved, cause);
        }
    }

    private IOException unwrap(ExecutionException failed) {
        Throwable cause = failed.getCause();
        while (cause instanceof UncheckedIOException unchecked) {
            cause = unchecked.getCause();
        }
        if (cause instanceof IOException io) {
            return io;
        }
        if (cause instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IOException("Hashing profile inputs failed", cause);
    }

    private synchronized ForkJoinPool pool() {
        if (pool == null) {
            pool = new ForkJoinPool(workers);
        }
        return pool;
    }

    @Override
    public synchronized void close() {
        if (pool != null) {
            pool.shutdown();
            pool = null;
        }
        realProviders.clear();
        providerByPath.clear();
        acceptedGenerations.clear();
        fileHashes.clear();
    }

    private static Path locateGameJar(Path installRoot) throws IOException {
        List<Path> candidates = List.of(
                installRoot.resolve("Contents/Resources/Java/starfarer_obf.jar"),
                installRoot.resolve("starsector-core/starfarer_obf.jar"),
                installRoot.resolve("starfarer_obf.jar"));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IOException("Could not locate starfarer_obf.jar under " + installRoot);
    }
}
