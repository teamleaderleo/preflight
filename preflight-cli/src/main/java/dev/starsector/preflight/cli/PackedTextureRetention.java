package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.PreparedTexturePack;
import dev.starsector.preflight.core.PreparedTexturePackIO;
import dev.starsector.preflight.core.TextureManifest;
import dev.starsector.preflight.core.TextureManifestIO;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Removes loose texture blobs once an exact pack is the durable copy. */
final class PackedTextureRetention {
    private PackedTextureRetention() {
    }

    static Result release(Path cacheDirectory, TextureManifest current) throws IOException {
        Path cache = cacheDirectory.toAbsolutePath().normalize();
        Path manifests = TextureManifestIO.directory(cache);
        List<String> currentBlobs = blobPaths(current);
        if (currentBlobs.isEmpty()) {
            return new Result(0, 0, 0);
        }

        // Never trade the loose copy away until the complete current pack has passed its bounded
        // header, identity, index and entry-set checks.
        requireExactPack(cache, current, currentBlobs);

        Set<String> looseRequiredByAnotherProfile = new HashSet<>();
        if (Files.isDirectory(manifests, LinkOption.NOFOLLOW_LINKS)) {
            try (var files = Files.list(manifests)) {
                for (Path file : files
                        .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> path.getFileName().toString().endsWith(".spfm"))
                        .toList()) {
                    TextureManifest manifest = TextureManifestIO.read(file);
                    String fileName = file.getFileName().toString();
                    String fileProfile = fileName.substring(0, fileName.length() - ".spfm".length());
                    if (!fileProfile.equals(manifest.profileFingerprint())) {
                        throw new IOException("Texture manifest profile does not match its filename: " + file);
                    }
                    if (current.profileFingerprint().equals(fileProfile)) {
                        continue;
                    }
                    List<String> blobs = blobPaths(manifest);
                    if (!hasExactPack(cache, manifest, blobs)) {
                        looseRequiredByAnotherProfile.addAll(blobs);
                    }
                }
            }
        }

        // Balanced may encode LZ4 first and then choose RAW when compression is ineffective. The
        // losing LZ4 blob is not named by the final manifest, so deleting only currentBlobs leaves
        // hundreds of megabytes of redundant cache data behind. Once every surviving manifest has been
        // read and every exact pack has been proved, any loose blob outside this required set is
        // redundant. Plan the complete set before deleting anything so an unreadable tree fails
        // without partially changing retention.
        List<LooseBlob> redundant = redundantLooseBlobs(cache, looseRequiredByAnotherProfile);
        long releasedBytes = 0;
        int releasedBlobs = 0;
        for (LooseBlob blob : redundant) {
            if (CacheDeletionBoundary.deleteOwnedRegularFile(cache, blob.path())) {
                releasedBytes = Math.addExact(releasedBytes, blob.bytes());
                releasedBlobs++;
            }
        }
        return new Result(releasedBlobs, releasedBytes, looseRequiredByAnotherProfile.size());
    }

    private static List<LooseBlob> redundantLooseBlobs(Path cache, Set<String> required)
            throws IOException {
        Path root = dev.starsector.preflight.core.PreparedTextureIO.cacheDirectory(cache);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<LooseBlob> redundant = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                if (!attributes.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                Path normalized = file.toAbsolutePath().normalize();
                if (!normalized.startsWith(cache)) {
                    throw new IOException("Prepared texture blob escaped the cache root: " + file);
                }
                String relative = cache.relativize(normalized).toString().replace('\\', '/');
                if (!required.contains(relative)) {
                    redundant.add(new LooseBlob(normalized, attributes.size()));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return List.copyOf(redundant);
    }

    static boolean hasExactPack(Path cache, TextureManifest manifest, List<String> blobs) {
        Path pack = PreparedTexturePackIO.path(cache, manifest.profileFingerprint());
        if (!Files.isRegularFile(pack, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (PreparedTexturePack ignored = PreparedTexturePackIO.open(
                pack, manifest.profileFingerprint(), blobs)) {
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    static boolean isExactPackOnly(Path cache, TextureManifest manifest) {
        List<String> blobs = blobPaths(manifest);
        if (blobs.isEmpty() || blobs.stream()
                .map(cache::resolve)
                .anyMatch(path -> Files.exists(path, LinkOption.NOFOLLOW_LINKS))) {
            return false;
        }
        return hasExactPack(cache, manifest, blobs);
    }

    private static void requireExactPack(
            Path cache, TextureManifest manifest, List<String> blobs) throws IOException {
        Path pack = PreparedTexturePackIO.path(cache, manifest.profileFingerprint());
        try (PreparedTexturePack ignored = PreparedTexturePackIO.open(
                pack, manifest.profileFingerprint(), blobs)) {
            // Opening proves the pack is the exact durable representation of this manifest.
        }
    }

    static List<String> blobPaths(TextureManifest manifest) {
        return manifest.entries().values().stream()
                .map(TextureManifest.Entry::blobRelativePath)
                .distinct()
                .toList();
    }

    record Result(int releasedBlobs, long releasedBytes, int protectedBlobs) {
    }

    private record LooseBlob(Path path, long bytes) {
    }
}
