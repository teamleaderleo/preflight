package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.PreparedTexturePack;
import dev.starsector.preflight.core.PreparedTexturePackIO;
import dev.starsector.preflight.core.TextureManifest;
import dev.starsector.preflight.core.TextureManifestIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
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

        long releasedBytes = 0;
        int releasedBlobs = 0;
        for (String relative : currentBlobs) {
            if (looseRequiredByAnotherProfile.contains(relative)) {
                continue;
            }
            Path blob = cache.resolve(relative).normalize();
            if (!blob.startsWith(cache)) {
                throw new IOException("Prepared texture blob escaped the cache root: " + relative);
            }
            if (!Files.exists(blob, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (!Files.isRegularFile(blob, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Prepared texture blob is not a regular file: " + blob);
            }
            long bytes = Files.size(blob);
            if (CacheDeletionBoundary.deleteOwnedRegularFile(cache, blob)) {
                releasedBytes = Math.addExact(releasedBytes, bytes);
                releasedBlobs++;
            }
        }
        return new Result(releasedBlobs, releasedBytes, looseRequiredByAnotherProfile.size());
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
}
