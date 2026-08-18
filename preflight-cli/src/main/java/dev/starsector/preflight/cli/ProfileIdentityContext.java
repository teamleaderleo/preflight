package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.PathContainment;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceIndexIO;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
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
 * <p>Six caches each need an exact identity for the files that can change their answer, and until
 * this existed each of them independently re-read the resource index, re-hashed the game jar, and
 * re-resolved every provider through {@code toRealPath}. On the measured 83-mod profile that cost
 * <b>1,612ms in the launcher before the JVM even starts</b>, of which roughly a third was five
 * redundant reads of the same 8&nbsp;MB index file.
 *
 * <p>Five things are shared here:
 *
 * <ul>
 *   <li><b>the index and the game jar hash</b>, read and computed once;
 *   <li><b>containment</b>, which resolves each provider's <i>parent directory</i> through
 *       {@code toRealPath} and memoises it. 12,797 providers on this profile live in 694 distinct
 *       directories, so the symlink-escape check costs 694 resolutions instead of 12,797;
 *   <li><b>provider resolution</b>, memoised after the first full containment check so overlapping
 *       identities do not repeat {@code readAttributes} for the same indexed provider;
 *   <li><b>hashing</b>, which runs across every core but hands results back in request order, so the
 *       bytes fed to a caller's digest are exactly the bytes a serial loop would have fed it;
 *   <li><b>content digests</b>, memoised by resolved path for this preparation. The broad
 *       {@code data/} identity overlaps the four spec corpora, so those files are read once even
 *       though more than one identity consumes their digest.
 * </ul>
 *
 * <p>Nothing here weakens what an identity covers. Every file that was hashed before is still hashed,
 * by content, on every launch. This is the same answer computed faster, which is why
 * {@code ProfileIdentityContextTest} checks the parallel result against a serial reference rather
 * than against a recorded constant.
 */
final class ProfileIdentityContext implements Closeable {
    private final Path installRoot;
    private final Path gameJar;
    private final String gameJarSha256;
    private final ResourceIndex resources;
    private final List<Path> realRoots;
    private final ContentHasher hasher;
    private final Map<Path, Path> realDirectories = new ConcurrentHashMap<>();
    private final Map<ResourceIndex.Provider, Path> realProviders = new ConcurrentHashMap<>();
    private final Map<Path, ResourceIndex.Provider> providerByPath = new ConcurrentHashMap<>();
    private final Map<Path, CompletableFuture<String>> fileHashes = new ConcurrentHashMap<>();
    private final int workers;

    private ForkJoinPool pool;

    @FunctionalInterface
    interface ContentHasher {
        String sha256(Path file) throws IOException;
    }

    private ProfileIdentityContext(
            Path installRoot,
            Path gameJar,
            String gameJarSha256,
            ResourceIndex resources,
            List<Path> realRoots,
            ContentHasher hasher,
            int workers) {
        this.installRoot = installRoot;
        this.gameJar = gameJar;
        this.gameJarSha256 = gameJarSha256;
        this.resources = resources;
        this.realRoots = realRoots;
        this.hasher = hasher;
        this.workers = workers;
        this.fileHashes.put(gameJar, CompletableFuture.completedFuture(gameJarSha256));
    }

    /** Reads the index from disk. The launcher does this once and shares the result. */
    static ProfileIdentityContext open(Path installRoot, Path indexFile) throws IOException {
        return of(installRoot, ResourceIndexIO.read(indexFile));
    }

    static ProfileIdentityContext of(Path installRoot, ResourceIndex resources) throws IOException {
        return of(installRoot, resources, Hashes::sha256, defaultWorkers());
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
        BasicFileAttributes jarBefore = Files.readAttributes(
                gameJar, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        FileIdentity jarBeforeId = FileIdentity.from(jarBefore);
        String gameJarSha256 = hasher.sha256(gameJar);
        BasicFileAttributes jarAfter = Files.readAttributes(
                gameJar, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!jarBeforeId.equals(FileIdentity.from(jarAfter))) {
            throw new IOException("Game jar modified or replaced during hash read: " + gameJar);
        }
        return new ProfileIdentityContext(
                root, gameJar, gameJarSha256, resources, List.copyOf(realRoots),
                hasher, workers);
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

    /**
     * Resolves providers to real files, rejecting anything that escapes its root.
     *
     * <p>Equivalent to calling {@link PathContainment#existingInsideRealRoot} per provider. The
     * parent directory is resolved through {@code toRealPath} and memoised, and the file itself is
     * confirmed to exist and not to be a symbolic link; a symlink falls back to the full resolution
     * so the containment decision is the one the unmemoised check would have made.
     */
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
            // Let the unmemoised path produce the same failure the callers already handle.
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

    /**
     * SHA-256 of every source, in the order requested.
     *
     * <p>Order is what makes this substitutable for a serial loop: a caller feeding
     * {@code sha256All(sources).get(i)} into its digest writes the same bytes in the same sequence it
     * wrote before, so identities computed by an older build still match.
     */
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

    /** One content read per resolved path for the lifetime of this preparation context. */
    private String sha256(Path source) throws IOException {
        Path resolved = source.toAbsolutePath().normalize();
        CompletableFuture<String> proposed = new CompletableFuture<>();
        CompletableFuture<String> shared = fileHashes.putIfAbsent(resolved, proposed);
        if (shared == null) {
            shared = proposed;
            try {
                proposed.complete(computeStableHash(resolved));
            } catch (IOException | RuntimeException | Error failure) {
                proposed.completeExceptionally(failure);
                fileHashes.remove(resolved, proposed);
            }
        }
        return awaitHash(resolved, shared);
    }

    private String computeStableHash(Path resolved) throws IOException {
        ResourceIndex.Provider provider = providerByPath.get(resolved);
        BasicFileAttributes before;
        try {
            before = Files.readAttributes(
                    resolved, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException error) {
            throw new IOException("Failed to read file attributes before hashing: " + resolved, error);
        }
        if (!before.isRegularFile()) {
            throw new IOException("Profile source is not a regular file: " + resolved);
        }
        if (provider != null && !matchesIndexedMetadata(provider, before)) {
            throw new IOException("Provider metadata does not match indexed state before hashing: " + resolved);
        }

        FileIdentity beforeIdentity = FileIdentity.from(before);
        String digest = hasher.sha256(resolved);

        BasicFileAttributes after;
        try {
            after = Files.readAttributes(
                    resolved, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException error) {
            throw new IOException("Failed to read file attributes after hashing: " + resolved, error);
        }
        if (!after.isRegularFile()) {
            throw new IOException("Profile source became non-regular file during hashing: " + resolved);
        }
        if (provider != null && !matchesIndexedMetadata(provider, after)) {
            throw new IOException("Provider metadata changed during hashing: " + resolved);
        }
        if (!beforeIdentity.equals(FileIdentity.from(after))) {
            throw new IOException("Source file identity changed or replaced during hash read: " + resolved);
        }
        return digest;
    }

    private static boolean matchesIndexedMetadata(
            ResourceIndex.Provider provider,
            BasicFileAttributes attributes) {
        return attributes.size() == provider.size()
                && Math.max(0, attributes.lastModifiedTime().toMillis()) == provider.modifiedMillis();
    }

    private static String awaitHash(Path resolved, CompletableFuture<String> shared) throws IOException {
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

    private record FileIdentity(long size, FileTime modified, Object fileKey) {
        static FileIdentity from(BasicFileAttributes attributes) {
            return new FileIdentity(
                    attributes.size(),
                    attributes.lastModifiedTime(),
                    attributes.fileKey());
        }
    }
}
