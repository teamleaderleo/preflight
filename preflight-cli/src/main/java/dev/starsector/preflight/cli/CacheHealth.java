package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.PreparedAudioCache;
import dev.starsector.preflight.core.PreparedAudioManifest;
import dev.starsector.preflight.core.PreparedAudioManifestIO;
import dev.starsector.preflight.core.PreparedTexturePack;
import dev.starsector.preflight.core.PreparedTexturePackIO;
import dev.starsector.preflight.core.PreparedTextureAccessOrderIO;
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
        return inspect(home, profile, null);
    }

    static Report inspect(PreflightHome home, String profile, String identityDiagnostic) {
        return inspect(home, profile, identityDiagnostic, null, null);
    }

    static Report inspect(
            PreflightHome home,
            String profile,
            String identityDiagnostic,
            String expectedAudioBuild,
            String expectedAudioDecoder) {
        if (profile == null) {
            String summary = identityDiagnostic == null || identityDiagnostic.isBlank()
                    ? "Preflight couldn't derive the current profile from this installation."
                    : identityDiagnostic;
            return new Report("unknown", null, null, null, null, false, List.of(new Issue(
                    "profile-identity",
                    summary,
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
        Path minimal = MinimalPreparationMarker.path(cache, profile).normalize();
        Path preparation = TexturePreparationReceipt.path(cache, profile).normalize();
        Path accessOrder = PreparedTextureAccessOrderIO.path(cache, profile).normalize();
        Path audio = PreparedAudioCache.manifestDirectory(cache)
                .resolve(profile + ".spam").toAbsolutePath().normalize();
        try {
            requireSafeArtifactPaths(
                    cache, index, manifest, pack, minimal, preparation, accessOrder, audio);
        } catch (IOException | IllegalArgumentException error) {
            return unsafe(profile, cache, "Prepared-data paths couldn't be verified: " + message(error));
        }

        List<Issue> issues = new ArrayList<>();
        LinkedHashSet<Target> targets = new LinkedHashSet<>();
        boolean indexPresent = exists(index);
        boolean manifestPresent = exists(manifest);
        boolean minimalPresent = exists(minimal);
        boolean minimalValid = false;
        TexturePreparationReceipt.Receipt texturePreparation = null;
        TextureManifest textureManifest = null;

        if (exists(preparation)) {
            try {
                if (!regularFile(preparation)) {
                    throw new IOException("texture-preparation receipt isn't a regular cache file");
                }
                texturePreparation = TexturePreparationReceipt.read(preparation, profile);
            } catch (Exception error) {
                issues.add(new Issue(
                        "texture-preparation",
                        "The profile's texture preparation receipt is unreadable: " + message(error),
                        preparation));
                addTargetIfPresent(targets, "texture-preparation", preparation);
            }
        }

        if (minimalPresent) {
            try {
                if (!regularFile(minimal)) {
                    throw new IOException("minimal-profile marker isn't a regular cache file");
                }
                MinimalPreparationMarker.validate(minimal, profile);
                minimalValid = true;
            } catch (Exception error) {
                issues.add(new Issue(
                        "preparation-mode",
                        "The profile's Minimal preparation marker is unreadable: " + message(error),
                        minimal));
                addTargetIfPresent(targets, "minimal-profile", minimal);
            }
        }

        if (minimalValid) {
            if (texturePreparation != null) {
                issues.add(new Issue(
                        "preparation-mode",
                        "The profile is marked as both Minimal and prepared-texture storage.",
                        preparation));
                addTargetIfPresent(targets, "texture-preparation", preparation);
                texturePreparation = null;
            }
            if (!indexPresent || !regularFile(index)) {
                issues.add(new Issue(
                        "minimal-preparation",
                        "Minimal preparation is incomplete for this profile.",
                        index));
                addTargetIfPresent(targets, "resource-index", index);
                addTargetIfPresent(targets, "minimal-profile", minimal);
            } else {
                try {
                    ResourceIndex stored = ResourceIndexIO.read(index);
                    if (!profile.equals(stored.profileFingerprint())) {
                        throw new IOException("resource index profile identity differs");
                    }
                } catch (Exception error) {
                    issues.add(new Issue(
                            "minimal-preparation",
                            "Minimal preparation metadata is unreadable: " + message(error),
                            index));
                    addTargetIfPresent(targets, "resource-index", index);
                    addTargetIfPresent(targets, "minimal-profile", minimal);
                }
            }
        } else if (!indexPresent && !manifestPresent) {
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

        if (!minimalValid && textureManifest != null && !textureManifest.entries().isEmpty()) {
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

        if (issues.stream().anyMatch(issue -> "prepared-textures".equals(issue.artifact()))) {
            addTargetIfPresent(targets, "texture-preparation", preparation);
            texturePreparation = null;
        }

        boolean compactAvailable = false;
        if (regularFile(accessOrder)) {
            try {
                compactAvailable = !PreparedTextureAccessOrderIO.read(accessOrder, profile).isEmpty();
            } catch (IOException | IllegalArgumentException ignored) {
                // Access observations are optional. An unreadable observation cannot make a
                // healthy pack unsafe; it only prevents automatic Compact graduation.
            }
        }

        boolean audioCompatibilityUnknown = false;
        if (exists(audio) && (expectedAudioBuild == null || expectedAudioDecoder == null)) {
            audioCompatibilityUnknown = true;
            issues.add(new Issue(
                    "prepared-audio-compatibility",
                    "Prepared audio is present, but its Starsector and decoder identities couldn't be verified.",
                    audio));
        } else if (exists(audio)) {
            try {
                if (!regularFile(audio)) {
                    throw new IOException("prepared-audio manifest isn't a regular cache file");
                }
                PreparedAudioManifest prepared = PreparedAudioManifestIO.read(audio);
                if (!profile.equals(prepared.profileFingerprintSha256())) {
                    throw new IOException("prepared-audio profile identity differs");
                }
                if (expectedAudioBuild != null
                        && !expectedAudioBuild.equals(prepared.starsectorBuildSha256())) {
                    throw new IOException("prepared audio belongs to a different Starsector build");
                }
                if (expectedAudioDecoder != null
                        && !expectedAudioDecoder.equals(prepared.decoderPolicyIdentitySha256())) {
                    throw new IOException("prepared audio belongs to a different decoder policy");
                }
            } catch (Exception error) {
                issues.add(new Issue(
                        "prepared-audio-manifest",
                        "Prepared audio metadata is incompatible or unreadable: " + message(error),
                        audio));
                targets.add(target("prepared-audio-manifest", audio));
            }
        }

        String status;
        if (audioCompatibilityUnknown) {
            status = "unknown";
        } else if (!issues.isEmpty()) {
            status = "repair-needed";
        } else if (minimalValid && indexPresent) {
            status = "ready";
        } else if (indexPresent && manifestPresent) {
            status = "ready";
        } else {
            status = "cold";
        }
        Boolean preparedTextures = "ready".equals(status) ? !minimalValid : null;
        String textureStorage = "ready".equals(status) && texturePreparation != null
                ? texturePreparation.storage().optionValue()
                : null;
        String textureScope = "ready".equals(status) && texturePreparation != null
                ? texturePreparation.scope().optionValue()
                : null;
        return new Report(
                status,
                profile,
                preparedTextures,
                textureStorage,
                textureScope,
                compactAvailable,
                List.copyOf(issues),
                List.copyOf(targets));
    }

    static Repair repair(PreflightHome home, String profile, boolean apply) throws IOException {
        return repair(home, profile, apply, null, null);
    }

    static Repair repair(
            PreflightHome home,
            String profile,
            boolean apply,
            String expectedAudioBuild,
            String expectedAudioDecoder) throws IOException {
        Report report = inspect(
                home, profile, null, expectedAudioBuild, expectedAudioDecoder);
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
        Report after = inspect(
                home, profile, null, expectedAudioBuild, expectedAudioDecoder);
        return new Repair(!"unsafe".equals(after.status()), true, after.status(), profile,
                removedBytes, removedFiles, report.targets());
    }

    static Map<String, Object> json(Report report) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("format", "starsector-preflight-cache-health-v1");
        value.put("status", report.status());
        value.put("profileFingerprint", report.profileFingerprint());
        value.put("preparedTextures", report.preparedTextures());
        value.put("textureStorage", report.textureStorage());
        value.put("textureScope", report.textureScope());
        value.put("compactAvailable", report.compactAvailable());
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
        return new Report("unsafe", profile, null, null, null, false,
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

    record Report(
            String status,
            String profileFingerprint,
            Boolean preparedTextures,
            String textureStorage,
            String textureScope,
            boolean compactAvailable,
            List<Issue> issues,
            List<Target> targets) {
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
