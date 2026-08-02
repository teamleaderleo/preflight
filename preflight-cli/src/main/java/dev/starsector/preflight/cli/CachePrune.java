package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.TextureManifest;
import dev.starsector.preflight.core.TextureManifestIO;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Works out which cache files no surviving profile can reach.
 *
 * <p>Artifacts fall into two kinds and only one of them is safe to delete by name. Resource
 * indexes, texture manifests and spec-store artifacts carry their profile's fingerprint or identity
 * in the filename, so a file belonging to a discarded profile is simply a file whose name is not in
 * the survivor set. Texture blobs are not like that: they are content-addressed and <b>shared</b>,
 * so the same blob is routinely referenced by several profiles and its name says nothing about who
 * needs it. Deleting a blob is only safe after reading every surviving manifest and confirming none
 * of them points at it.
 *
 * <p>That asymmetry drives the safety rule here: <b>a manifest that cannot be read aborts the whole
 * plan.</b> An unreadable survivor means an incomplete reachable set, and an incomplete reachable
 * set means deleting blobs a live profile still needs -- which would not fail loudly, it would fail
 * on the next launch as a cache miss on a texture that was supposed to be prepared. Refusing to
 * plan is the only correct response.
 */
final class CachePrune {
    /** Spec-store corpora, and the extension each writes. */
    private static final List<String> SPEC_STORE_EXTENSIONS =
            List.of(".spvj", ".spwj", ".sppj", ".sphj", ".sprc", ".sprk");

    private CachePrune() {
    }

    /**
     * Plans a prune that keeps exactly {@code survivors}.
     *
     * @param keepIdentities spec-store identities the surviving profiles resolve to, or empty to
     *     leave every spec-store artifact alone rather than guess at which ones are live
     */
    static Plan plan(PreflightHome home, Set<String> survivors, Set<String> keepIdentities)
            throws IOException {
        Path cache = home.cache();
        List<Removal> removals = new ArrayList<>();
        List<String> refusals = new ArrayList<>();

        removals.addAll(byFingerprint(cache.resolve("resource-indexes"), ".spfi", survivors));
        removals.addAll(byFingerprint(cache.resolve("manifests"), ".spfm", survivors));

        Set<String> reachable = new HashSet<>();
        for (String survivor : survivors) {
            Path manifest = cache.resolve("manifests").resolve(survivor + ".spfm");
            if (!Files.isRegularFile(manifest)) {
                // A survivor with no manifest simply contributes no blobs; it is not an error,
                // because a profile can be indexed without having had its textures prepared.
                continue;
            }
            try {
                TextureManifest read = TextureManifestIO.read(manifest);
                for (TextureManifest.Entry entry : read.entries().values()) {
                    reachable.add(entry.blobRelativePath());
                }
            } catch (Exception unreadable) {
                refusals.add("manifest for surviving profile " + survivor.substring(0, 16)
                        + " could not be read (" + message(unreadable) + "), so the set of blobs it"
                        + " still needs is unknown");
            }
        }

        List<Removal> blobs = refusals.isEmpty()
                ? unreachableBlobs(cache, reachable)
                : List.of();
        removals.addAll(blobs);

        if (!keepIdentities.isEmpty()) {
            removals.addAll(specStore(cache, keepIdentities));
        }

        removals.sort(Comparator.comparingLong(Removal::bytes).reversed());
        return new Plan(List.copyOf(removals), List.copyOf(refusals), reachable.size());
    }

    private static List<Removal> byFingerprint(Path directory, String extension, Set<String> keep)
            throws IOException {
        List<Removal> removals = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return removals;
        }
        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(extension)) {
                    continue;
                }
                String fingerprint = name.substring(0, name.length() - extension.length());
                if (fingerprint.matches("[0-9a-f]{64}") && !keep.contains(fingerprint)) {
                    removals.add(new Removal(file, Files.size(file), "profile " + short16(fingerprint)));
                }
            }
        }
        return removals;
    }

    private static List<Removal> unreachableBlobs(Path cache, Set<String> reachable)
            throws IOException {
        Path blobs = cache.resolve("blobs");
        List<Removal> removals = new ArrayList<>();
        if (!Files.isDirectory(blobs)) {
            return removals;
        }
        Files.walkFileTree(blobs, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (!attributes.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                String relative = cache.relativize(file).toString().replace('\\', '/');
                if (!reachable.contains(relative)) {
                    removals.add(new Removal(file, attributes.size(), "unreferenced blob"));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return removals;
    }

    private static List<Removal> specStore(Path cache, Set<String> keepIdentities)
            throws IOException {
        Path store = cache.resolve("spec-store");
        List<Removal> removals = new ArrayList<>();
        if (!Files.isDirectory(store)) {
            return removals;
        }
        try (var directories = Files.list(store)) {
            for (Path corpus : directories.filter(Files::isDirectory).toList()) {
                try (var files = Files.list(corpus)) {
                    for (Path file : files.filter(Files::isRegularFile).toList()) {
                        String name = file.getFileName().toString();
                        String identity = SPEC_STORE_EXTENSIONS.stream()
                                .filter(name::endsWith)
                                .findFirst()
                                .map(extension ->
                                        name.substring(0, name.length() - extension.length()))
                                .orElse(null);
                        if (identity != null
                                && identity.matches("[0-9a-f]{64}")
                                && !keepIdentities.contains(identity)) {
                            removals.add(new Removal(
                                    file, Files.size(file), "stale " + corpus.getFileName()));
                        }
                    }
                }
            }
        }
        return removals;
    }

    static long apply(Plan plan) throws IOException {
        long freed = 0;
        Set<Path> parents = new LinkedHashSet<>();
        for (Removal removal : plan.removals()) {
            if (Files.deleteIfExists(removal.path())) {
                freed = Math.addExact(freed, removal.bytes());
            }
            Path parent = removal.path().getParent();
            if (parent != null) {
                parents.add(parent);
            }
        }
        for (Path parent : parents) {
            // A blob shard that emptied out is noise, not cache. Non-empty directories throw and
            // are left alone, which is exactly the wanted behaviour.
            try {
                Files.deleteIfExists(parent);
            } catch (IOException stillPopulated) {
                // Intentionally ignored.
            }
        }
        return freed;
    }

    private static String short16(String fingerprint) {
        return fingerprint.substring(0, 16);
    }

    private static String message(Throwable error) {
        String text = error.getMessage();
        return text == null || text.isBlank() ? error.getClass().getSimpleName() : text;
    }

    record Removal(Path path, long bytes, String reason) {
    }

    record Plan(List<Removal> removals, List<String> refusals, int reachableBlobs) {
        long bytes() {
            return removals.stream().mapToLong(Removal::bytes).sum();
        }

        boolean safe() {
            return refusals.isEmpty();
        }
    }
}
