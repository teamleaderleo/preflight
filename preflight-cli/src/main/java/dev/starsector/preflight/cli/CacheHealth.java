package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.PreparedAudioCache;
import dev.starsector.preflight.core.PreparedAudioManifest;
import dev.starsector.preflight.core.PreparedAudioManifestIO;
import dev.starsector.preflight.core.PreparedTexturePack;
import dev.starsector.preflight.core.PreparedTexturePackIO;
import dev.starsector.preflight.core.ResourceIndex;
import dev.starsector.preflight.core.ResourceIndexIO;
import dev.starsector.preflight.core.TextureManifest;
import dev.starsector.preflight.core.TextureManifestIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Bounded health and repair plan for the exact current profile's prepared artifacts. */
final class CacheHealth {
    private CacheHealth() {
    }

    static Report inspect(PreflightHome home, String profile) {
        if (profile == null) {
            return new Report("unknown", null, List.of(new Issue(
                    "profile-identity",
                    "Preflight couldn't derive the current profile from this installation.",
                    home.cache())), List.of());
        }
        Path cache;
        try {
            cache = canonicalCacheRoot(home.cache());
        } catch (IOException error) {
            return unsafe(profile, home.cache(), "The cache boundary couldn't be verified: " + message(error));
        }
        Path index = ResourceIndexIO.directory(cache).resolve(profile + ".spfi").normalize();
        Path manifest = TextureManifestIO.directory(cache).resolve(profile + ".spfm").normalize();
        Path pack = PreparedTexturePackIO.path(cache, profile).normalize();
        Path audio = PreparedAudioCache.manifestDirectory(cache)
                .resolve(profile + ".spam").toAbsolutePath().normalize();
        try {
            requireSafeArtifactPaths(cache, index, manifest, pack, audio);
        } catch (IOException | IllegalArgumentException error) {
            return unsafe(profile, cache, "Prepared-data paths couldn't be verified: " + message(error));
        }

        List<Issue> issues = new ArrayList<>();
        LinkedHashSet<Target> targets = new LinkedHashSet<>();
        boolean indexPresent = exists(index);
        boolean manifestPresent = exists(manifest);
        TextureManifest textureManifest = null;

        if (!indexPresent && !manifestPresent) {
            if (exists(pack)) {
                textureIssue(issues, targets, index, manifest, pack,
                        "The texture pack exists without its profile index and manifest.");
            }
        } else if (!indexPresent || !manifestPresent) {
            textureIssue(issues, targets, index, manifest, pack,
                    "Prepared texture data is incomplete for this profile.");
        } else if (!regularFile(index) || !regularFile(manifest)) {
            textureIssue(issues, targets, index, manifest, pack,
                    "Prepared texture metadata isn't stored as regular cache files.");
        } else {
            try {
                ResourceIndex stored = ResourceIndexIO.read(index);
                if (!profile.equals(stored.profileFingerprint())) {
                    throw new IOException("resource index profile identity differs");
                }
                textureManifest = TextureManifestIO.read(manifest);
                if (!profile.equals(textureManifest.profileFingerprint())) {
                    throw new IOException("texture manifest profile identity differs");
                }
            } catch (Exception error) {
                textureIssue(issues, targets, index, manifest, pack,
                        "Prepared texture metadata is unreadable: " + message(error));
            }
        }

        if (textureManifest != null && !textureManifest.entries().isEmpty()) {
            List<String> blobs = textureManifest.entries().values().stream()
                    .map(TextureManifest.Entry::blobRelativePath)
                    .distinct()
                    .toList();
            try {
                if (!regularFile(pack)) {
                    throw new IOException("prepared texture pack isn't a regular cache file");
                }
            } catch (Exception error) {
                textureIssue(issues, targets, index, manifest, pack,
                        "The prepared texture pack needs rebuilding: " + message(error));
            }
            if (issues.stream().noneMatch(issue -> "prepared-textures".equals(issue.artifact()))) {
                try (PreparedTexturePack ignored = PreparedTexturePackIO.open(pack, profile, blobs)) {
                    // Opening validates the bounded header, index checksum, profile, and entry set.
                } catch (Exception error) {
                    textureIssue(issues, targets, index, manifest, pack,
                            "The prepared texture pack needs rebuilding: " + message(error));
                }
            }
        }

        if (exists(audio)) {
            try {
                if (!regularFile(audio)) {
                    throw new IOException("prepared-audio manifest isn't a regular cache file");
                }
                PreparedAudioManifest prepared = PreparedAudioManifestIO.read(audio);
                if (!profile.equals(prepared.profileFingerprintSha256())) {
                    throw new IOException("prepared-audio profile identity differs");
                }
            } catch (Exception error) {
                issues.add(new Issue(
                        "prepared-audio-manifest",
                        "Prepared audio metadata is unreadable: " + message(error),
                        audio));
                targets.add(target("prepared-audio-manifest", audio));
            }
        }

        String status = !issues.isEmpty()
                ? "repair-needed"
                : indexPresent && manifestPresent ? "ready" : "cold";
        return new Report(status, profile, List.copyOf(issues), List.copyOf(targets));
    }

    static Repair repair(PreflightHome home, String profile, boolean apply) throws IOException {
        Report report = inspect(home, profile);
        if ("unknown".equals(report.status()) || "unsafe".equals(report.status())) {
            return new Repair(false, false, report.status(), profile, 0, 0, List.of());
        }
        if (!"repair-needed".equals(report.status())) {
            return new Repair(true, false, report.status(), profile, 0, 0, List.of());
        }
        long bytes = report.targets().stream().mapToLong(Target::bytes).sum();
        if (!apply) {
            return new Repair(true, false, report.status(), profile,
                    bytes, report.targets().size(), report.targets());
        }
        long removedBytes = 0;
        int removedFiles = 0;
        Path cache = canonicalCacheRoot(home.cache());
        for (Target target : report.targets()) {
            requireSafeArtifactPaths(cache, target.path());
            if (exists(target.path())
                    && !regularFile(target.path())
                    && !Files.isSymbolicLink(target.path())) {
                throw new IOException("Refusing to remove a non-file prepared-data target: " + target.path());
            }
            if (Files.deleteIfExists(target.path())) {
                removedBytes = Math.addExact(removedBytes, target.bytes());
                removedFiles++;
            }
        }
        Report after = inspect(home, profile);
        return new Repair(!"unsafe".equals(after.status()), true, after.status(), profile,
                removedBytes, removedFiles, report.targets());
    }

    static Map<String, Object> json(Report report) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("format", "starsector-preflight-cache-health-v1");
        value.put("status", report.status());
        value.put("profileFingerprint", report.profileFingerprint());
        value.put("issues", report.issues().stream().map(issue -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("artifact", issue.artifact());
            item.put("summary", issue.summary());
            item.put("path", issue.path());
            return item;
        }).toList());
        value.put("repairBytes", report.targets().stream().mapToLong(Target::bytes).sum());
        value.put("repairFiles", report.targets().size());
        return value;
    }

    static Map<String, Object> json(Repair repair) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("format", "starsector-preflight-cache-repair-v1");
        value.put("safe", repair.safe());
        value.put("applied", repair.applied());
        value.put("status", repair.status());
        value.put("profileFingerprint", repair.profileFingerprint());
        value.put("bytes", repair.bytes());
        value.put("files", repair.files());
        value.put("targets", repair.targets().stream().map(target -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("artifact", target.artifact());
            item.put("path", target.path());
            item.put("bytes", target.bytes());
            return item;
        }).toList());
        return value;
    }

    private static void textureIssue(
            List<Issue> issues,
            LinkedHashSet<Target> targets,
            Path index,
            Path manifest,
            Path pack,
            String summary) {
        if (issues.stream().noneMatch(issue -> "prepared-textures".equals(issue.artifact()))) {
            issues.add(new Issue("prepared-textures", summary, manifest));
        }
        addTargetIfPresent(targets, "resource-index", index);
        addTargetIfPresent(targets, "texture-manifest", manifest);
        addTargetIfPresent(targets, "texture-pack", pack);
    }

    private static void addTargetIfPresent(
            LinkedHashSet<Target> targets, String artifact, Path path) {
        if (exists(path)) targets.add(target(artifact, path));
    }

    private static Target target(String artifact, Path path) {
        long bytes = 0;
        try {
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) bytes = Files.size(path);
        } catch (IOException ignored) {
            // The apply phase rechecks presence; an unreadable size is reported as zero.
        }
        return new Target(artifact, path, bytes);
    }

    private static boolean exists(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static Path canonicalCacheRoot(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        return exists(absolute) ? absolute.toRealPath() : absolute;
    }

    private static void requireSafeArtifactPaths(Path root, Path... paths) throws IOException {
        for (Path path : paths) {
            if (!path.startsWith(root)) {
                throw new IllegalArgumentException("Prepared-data path escaped the cache root");
            }
            if (!exists(path)) continue;
            Path parent = path.getParent();
            if (parent == null || !parent.toRealPath().startsWith(root)) {
                throw new IOException("Prepared-data parent escaped the cache root");
            }
            if (!regularFile(path) && !Files.isSymbolicLink(path)) {
                throw new IOException("Prepared-data target isn't a regular file or symbolic link");
            }
        }
    }

    private static boolean regularFile(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static Report unsafe(String profile, Path path, String summary) {
        return new Report("unsafe", profile,
                List.of(new Issue("cache-boundary", summary, path)), List.of());
    }

    private static String message(Exception error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }

    record Issue(String artifact, String summary, Path path) {
    }

    record Target(String artifact, Path path, long bytes) {
    }

    record Report(String status, String profileFingerprint, List<Issue> issues, List<Target> targets) {
    }

    record Repair(
            boolean safe,
            boolean applied,
            String status,
            String profileFingerprint,
            long bytes,
            int files,
            List<Target> targets) {
    }
}
