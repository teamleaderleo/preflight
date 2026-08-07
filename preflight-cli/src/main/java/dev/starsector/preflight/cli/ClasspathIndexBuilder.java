package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.ClasspathProfileIndex;
import dev.starsector.preflight.core.ClasspathProfileIndexIO;
import dev.starsector.preflight.core.ClasspathCacheDirectories;
import dev.starsector.preflight.core.Hashes;
import dev.starsector.preflight.core.JarArchiveIndex;
import dev.starsector.preflight.core.JarArchiveIndexIO;
import dev.starsector.preflight.core.ResourceIndex;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Builds reusable content-addressed JAR indexes and an ordered profile provider index. */
final class ClasspathIndexBuilder {
    private ClasspathIndexBuilder() {
    }

    static Result build(Path installRoot, Path cacheDirectory) throws IOException {
        long started = System.nanoTime();
        Path cacheRoot = cacheDirectory.toAbsolutePath().normalize();
        Files.createDirectories(cacheRoot);
        List<String> diagnostics = new ArrayList<>();
        Discovery discovery = discover(installRoot, diagnostics);
        String fingerprint = profileFingerprint(discovery.enabledModIds(), discovery.sources());
        Path profilePath = ClasspathCacheDirectories.profiles(cacheRoot)
                .resolve(fingerprint + ".spfc").normalize();

        if (Files.isRegularFile(profilePath)) {
            try {
                ClasspathProfileIndex existing = ClasspathProfileIndexIO.read(profilePath);
                if (existing.profileFingerprint().equals(fingerprint)
                        && matchesSources(existing, discovery.sources())) {
                    return new Result(
                            existing,
                            profilePath,
                            true,
                            true,
                            0,
                            0,
                            0,
                            0,
                            0,
                            List.copyOf(new LinkedHashSet<>(diagnostics)),
                            System.nanoTime() - started);
                }
                quarantine(cacheRoot, profilePath, "stale-profile");
            } catch (IOException | RuntimeException error) {
                diagnostics.add("Cached classpath profile was rejected: " + message(error));
                quarantine(cacheRoot, profilePath, "corrupt-profile");
            }
        }

        List<ClasspathProfileIndex.Archive> archives = new ArrayList<>();
        Map<String, List<Integer>> providers = new TreeMap<>();
        int archiveHits = 0;
        int archiveBuilds = 0;
        int quarantined = 0;
        int failures = 0;
        long indexedEntries = 0;

        for (Source source : discovery.sources()) {
            String sourceHash;
            try {
                sourceHash = Hashes.sha256(source.path());
            } catch (IOException error) {
                diagnostics.add("Could not hash " + source.modId() + ":" + source.relativePath()
                        + ": " + message(error));
                failures++;
                continue;
            }

            Path archivePath = ClasspathCacheDirectories.archives(cacheRoot)
                    .resolve(sourceHash.substring(0, 2))
                    .resolve(sourceHash + ".spfj").normalize();
            String archiveRelativeText = cacheRoot.relativize(archivePath)
                    .toString().replace('\\', '/');
            if (!archivePath.startsWith(cacheRoot)) {
                diagnostics.add("Archive cache path escaped the cache root for " + source.relativePath());
                failures++;
                continue;
            }

            JarArchiveIndex archiveIndex = null;
            if (Files.isRegularFile(archivePath)) {
                try {
                    JarArchiveIndex existing = JarArchiveIndexIO.read(archivePath);
                    if (existing.sourceSha256().equals(sourceHash)
                            && existing.sourceBytes() == source.size()) {
                        archiveIndex = existing;
                        archiveHits++;
                    } else {
                        quarantined += quarantine(cacheRoot, archivePath, "identity-mismatch") ? 1 : 0;
                    }
                } catch (IOException | RuntimeException error) {
                    diagnostics.add("Cached JAR index was rejected for " + source.relativePath()
                            + ": " + message(error));
                    quarantined += quarantine(cacheRoot, archivePath, "corrupt-archive") ? 1 : 0;
                }
            }

            if (archiveIndex == null) {
                try {
                    archiveIndex = scanArchive(source.path(), sourceHash, source.size());
                    JarArchiveIndexIO.write(archivePath, archiveIndex);
                    archiveBuilds++;
                } catch (IOException | RuntimeException error) {
                    diagnostics.add("Could not index " + source.modId() + ":" + source.relativePath()
                            + ": " + message(error));
                    failures++;
                    continue;
                }
            }

            int archiveIndexNumber = archives.size();
            archives.add(new ClasspathProfileIndex.Archive(
                    source.modId(),
                    source.relativePath(),
                    source.path(),
                    sourceHash,
                    source.size(),
                    source.modifiedMillis(),
                    archiveRelativeText,
                    source.declared()));
            for (String entryName : archiveIndex.entries().keySet()) {
                providers.computeIfAbsent(entryName, ignored -> new ArrayList<>()).add(archiveIndexNumber);
                indexedEntries++;
            }
        }

        ClasspathProfileIndex profile = new ClasspathProfileIndex(fingerprint, archives, providers);
        boolean profileWritten = failures == 0;
        if (profileWritten) {
            ClasspathProfileIndexIO.write(profilePath, profile);
        } else {
            diagnostics.add("Classpath profile index was kept in memory because one or more archives failed");
        }
        return new Result(
                profile,
                profilePath,
                false,
                profileWritten,
                archiveHits,
                archiveBuilds,
                quarantined,
                failures,
                indexedEntries,
                List.copyOf(new LinkedHashSet<>(diagnostics)),
                System.nanoTime() - started);
    }

    static Validation validate(
            ClasspathProfileIndex profile,
            Path cacheDirectory,
            boolean deep) {
        Path cacheRoot = cacheDirectory.toAbsolutePath().normalize();
        List<String> problems = new ArrayList<>();
        long checkedArchives = 0;
        long checkedEntries = 0;
        for (ClasspathProfileIndex.Archive archive : profile.archives()) {
            checkedArchives++;
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(
                        archive.physicalPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException error) {
                problems.add("Source JAR is unavailable: " + archive.physicalPath() + " (" + message(error) + ")");
                continue;
            }
            if (!attributes.isRegularFile()) {
                problems.add("Source JAR is not a regular file: " + archive.physicalPath());
                continue;
            }
            if (attributes.size() != archive.sourceBytes()) {
                problems.add("Source JAR size changed: " + archive.physicalPath());
                continue;
            }
            if (Math.max(0, attributes.lastModifiedTime().toMillis()) != archive.modifiedMillis()) {
                problems.add("Source JAR modification time changed: " + archive.physicalPath());
                continue;
            }
            if (deep) {
                try {
                    if (!Hashes.sha256(archive.physicalPath()).equals(archive.sourceSha256())) {
                        problems.add("Source JAR content hash changed: " + archive.physicalPath());
                        continue;
                    }
                } catch (IOException error) {
                    problems.add("Source JAR could not be hashed: " + archive.physicalPath()
                            + " (" + message(error) + ")");
                    continue;
                }
            }

            Path archiveIndexPath = cacheRoot.resolve(archive.archiveIndexRelativePath()).normalize();
            if (!archiveIndexPath.startsWith(cacheRoot)) {
                problems.add("Archive index path escapes the cache root: " + archive.archiveIndexRelativePath());
                continue;
            }
            try {
                JarArchiveIndex index = JarArchiveIndexIO.read(archiveIndexPath);
                if (!index.sourceSha256().equals(archive.sourceSha256())
                        || index.sourceBytes() != archive.sourceBytes()) {
                    problems.add("Archive index identity differs from profile: " + archiveIndexPath);
                } else {
                    checkedEntries += index.entryCount();
                }
            } catch (IOException | RuntimeException error) {
                problems.add("Archive index is invalid: " + archiveIndexPath + " (" + message(error) + ")");
            }
        }
        return new Validation(problems.isEmpty(), checkedArchives, checkedEntries, List.copyOf(problems), deep);
    }

    private static Discovery discover(Path installRoot, List<String> diagnostics) throws IOException {
        GameLayout layout = GameLayout.locate(installRoot);
        List<String> enabledIds = JsonText.stringArray(
                Files.readString(layout.enabledModsFile(), StandardCharsets.UTF_8), "enabledMods");
        Map<String, Path> installed = discoverMods(layout.modsDirectory(), diagnostics);
        List<Source> sources = new ArrayList<>();
        for (String modId : enabledIds) {
            Path directory = installed.get(modId);
            if (directory == null) {
                diagnostics.add("Enabled mod directory not found for ID: " + modId);
                continue;
            }
            String metadata = readMetadata(directory, diagnostics);
            List<String> declared = metadata == null
                    ? List.of()
                    : normalizeDeclaredJars(JsonText.stringArray(metadata, "jars"), modId, diagnostics);
            List<String> actual = discoverJars(directory, diagnostics);
            Set<String> actualSet = new LinkedHashSet<>(actual);
            Set<String> declaredSet = new LinkedHashSet<>(declared);
            List<String> ordered = new ArrayList<>();
            declared.stream().filter(actualSet::contains).forEach(ordered::add);
            actual.stream().filter(path -> !declaredSet.contains(path)).forEach(ordered::add);

            for (String relative : ordered) {
                Path file = directory.resolve(relative).normalize();
                if (!file.startsWith(directory.toAbsolutePath().normalize())) {
                    diagnostics.add("JAR path escapes mod directory for " + modId + ": " + relative);
                    continue;
                }
                try {
                    BasicFileAttributes attributes = Files.readAttributes(
                            file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!attributes.isRegularFile()) {
                        diagnostics.add("JAR is not a regular file for " + modId + ": " + relative);
                        continue;
                    }
                    sources.add(new Source(
                            modId,
                            relative,
                            file.toAbsolutePath().normalize(),
                            attributes.size(),
                            Math.max(0, attributes.lastModifiedTime().toMillis()),
                            declaredSet.contains(relative)));
                } catch (IOException error) {
                    diagnostics.add("Could not inspect JAR for " + modId + ":" + relative
                            + ": " + message(error));
                }
            }
        }
        return new Discovery(List.copyOf(enabledIds), List.copyOf(sources));
    }

    private static Map<String, Path> discoverMods(Path modsDirectory, List<String> diagnostics) throws IOException {
        Map<String, Path> result = new LinkedHashMap<>();
        try (var stream = Files.list(modsDirectory)) {
            for (Path directory : stream.filter(Files::isDirectory).sorted().toList()) {
                String id = directory.getFileName().toString();
                String metadata = readMetadata(directory, diagnostics);
                if (metadata != null) {
                    String declared = JsonText.string(metadata, "id");
                    if (declared != null && !declared.isBlank()) {
                        id = declared;
                    }
                }
                Path normalized = directory.toAbsolutePath().normalize();
                Path prior = result.putIfAbsent(id, normalized);
                if (prior != null) {
                    diagnostics.add("Duplicate mod ID " + id + " in " + prior + " and " + normalized);
                }
            }
        }
        return result;
    }

    private static String readMetadata(Path directory, List<String> diagnostics) {
        Path file = directory.resolve("mod_info.json");
        if (!Files.isRegularFile(file)) {
            diagnostics.add("mod_info.json is missing from " + directory);
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException error) {
            diagnostics.add("Could not read " + file + ": " + message(error));
            return null;
        }
    }

    private static List<String> normalizeDeclaredJars(
            List<String> paths, String modId, List<String> diagnostics) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String path : paths) {
            try {
                String normalized = ResourceIndex.normalizeRelativePath(path);
                if (!result.add(normalized)) {
                    diagnostics.add("Duplicate declared JAR for " + modId + ": " + normalized);
                }
            } catch (IllegalArgumentException error) {
                diagnostics.add("Invalid declared JAR for " + modId + ": " + path
                        + " (" + message(error) + ")");
            }
        }
        return List.copyOf(result);
    }

    private static List<String> discoverJars(Path directory, List<String> diagnostics) {
        try (var stream = Files.walk(directory)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .map(path -> directory.relativize(path).toString()
                            .replace(path.getFileSystem().getSeparator(), "/"))
                    .sorted()
                    .toList();
        } catch (IOException error) {
            diagnostics.add("Could not enumerate JARs under " + directory + ": " + message(error));
            return List.of();
        }
    }

    private static String profileFingerprint(List<String> enabledIds, List<Source> sources) {
        MessageDigest digest = sha256();
        update(digest, "preflight-classpath-profile-v1");
        enabledIds.forEach(id -> update(digest, id));
        for (Source source : sources) {
            update(digest, source.modId());
            update(digest, source.relativePath());
            update(digest, source.path().toString());
            update(digest, Long.toString(source.size()));
            update(digest, Long.toString(source.modifiedMillis()));
            update(digest, Boolean.toString(source.declared()));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean matchesSources(ClasspathProfileIndex index, List<Source> sources) {
        if (index.archives().size() != sources.size()) {
            return false;
        }
        for (int i = 0; i < sources.size(); i++) {
            Source source = sources.get(i);
            ClasspathProfileIndex.Archive archive = index.archives().get(i);
            if (!archive.modId().equals(source.modId())
                    || !archive.relativePath().equals(source.relativePath())
                    || !archive.physicalPath().equals(source.path())
                    || archive.sourceBytes() != source.size()
                    || archive.modifiedMillis() != source.modifiedMillis()
                    || archive.declared() != source.declared()) {
                return false;
            }
        }
        return true;
    }

    private static JarArchiveIndex scanArchive(Path jar, String hash, long sourceBytes) throws IOException {
        Map<String, JarArchiveIndex.Entry> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            List<? extends ZipEntry> sorted = zip.stream()
                    .filter(entry -> !entry.isDirectory())
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .toList();
            for (ZipEntry entry : sorted) {
                String name = JarArchiveIndex.normalizeEntryName(entry.getName());
                JarArchiveIndex.Entry value = new JarArchiveIndex.Entry(
                        name,
                        Math.max(0, entry.getSize()),
                        Math.max(0, entry.getCompressedSize()),
                        entry.getCrc(),
                        entry.getMethod());
                if (entries.put(name, value) != null) {
                    throw new IOException("JAR contains duplicate normalized entry: " + name);
                }
            }
        }
        return new JarArchiveIndex(hash, sourceBytes, entries);
    }

    private static boolean quarantine(Path cacheRoot, Path file, String reason) {
        try {
            Path directory = cacheRoot.resolve("quarantine");
            Files.createDirectories(directory);
            Path target = directory.resolve(file.getFileName() + "." + reason + "." + Instant.now().toEpochMilli());
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException error) {
            try {
                Files.deleteIfExists(file);
                return true;
            } catch (IOException ignored) {
                return false;
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }

    record Result(
            ClasspathProfileIndex profile,
            Path profilePath,
            boolean profileHit,
            boolean profileWritten,
            int archiveHits,
            int archiveBuilds,
            int quarantinedIndexes,
            int failedArchives,
            long indexedEntries,
            List<String> diagnostics,
            long durationNanos) {
        double durationMillis() {
            return durationNanos / 1_000_000.0;
        }
    }

    record Validation(
            boolean valid,
            long checkedArchives,
            long checkedEntries,
            List<String> problems,
            boolean deep) {
    }

    private record Discovery(List<String> enabledModIds, List<Source> sources) {
    }

    private record Source(
            String modId,
            String relativePath,
            Path path,
            long size,
            long modifiedMillis,
            boolean declared) {
    }
}
