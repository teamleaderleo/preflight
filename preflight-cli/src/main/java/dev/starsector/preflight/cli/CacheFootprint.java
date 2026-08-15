package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.PreparedAudioCache;
import dev.starsector.preflight.core.ClasspathCacheDirectories;
import dev.starsector.preflight.core.GeneratedBytecodeCache;
import dev.starsector.preflight.core.PreparedTexturePackIO;
import dev.starsector.preflight.core.PreparedTextureIO;
import dev.starsector.preflight.core.ResourceIndexIO;
import dev.starsector.preflight.core.TextureManifestIO;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Measures what Preflight is storing, so the operator can see it rather than discover it.
 *
 * <p>Sizes are on-disk allocation where the filesystem reports it and apparent length otherwise,
 * which is the number {@code du} shows and therefore the number the operator can check this
 * against.
 */
final class CacheFootprint {
    /** Directories under the home whose contents are reported separately. */
    private static final Map<String, Category> CATEGORIES = new LinkedHashMap<>(Map.ofEntries(
            Map.entry(relative(PreparedTextureIO.cacheDirectory(Path.of("cache"))), acceleration(
                    "prepared texture payloads, shared across profiles by content hash")),
            Map.entry(relative(PreparedTexturePackIO.directory(Path.of("cache"))),
                    acceleration("profile texture packs and learned read order")),
            Map.entry(relative(ResourceIndexIO.directory(Path.of("cache"))),
                    acceleration("one per prepared profile")),
            Map.entry(relative(TextureManifestIO.directory(Path.of("cache"))),
                    acceleration("one per prepared profile")),
            Map.entry("cache/spec-store", acceleration(
                    "prepared JSON, rules and command-class artifacts")),
            Map.entry(relative(PreparedAudioCache.root(Path.of("cache"))), acceleration(
                    "decoded PCM and exact-profile audio manifests")),
            Map.entry(relative(GeneratedBytecodeCache.root(Path.of("cache"))), acceleration(
                    "exact-context Janino class maps and deduplicated packs")),
            Map.entry("cache/adapter-transformations", acceleration(
                    "exact-context transformed game and mod classes")),
            Map.entry(relative(ClasspathCacheDirectories.root(Path.of("cache"))),
                    acceleration("mod jar and class inventories")),
            Map.entry("cache/comparison-state-snapshots", evidence(
                    "benchmark comparison inputs")),
            Map.entry("cache/reports", evidence("generated diagnostic reports")),
            Map.entry("runs", evidence(
                    "per-launch evidence: adapter reports, phase timings, recordings")),
            Map.entry("benchmarks", evidence("recorded benchmark scenarios")),
            Map.entry("profiles", configuration("named enabled-mod profiles")),
            Map.entry("profile-backups", configuration("enabled-mod backups from profile activation")),
            Map.entry("launcher-preference-backups", configuration(
                    "launcher preference snapshots from explicit settings changes")),
            Map.entry("launcher-file-backups", configuration(
                    "exact launcher-file snapshots from explicit memory changes")),
            Map.entry("state", configuration("operation coordination and reviewed actions")),
            Map.entry("bin", application("the installed copy of preflight.jar"))));

    private CacheFootprint() {
    }

    private static String relative(Path path) {
        return path.toString().replace('\\', '/');
    }

    static Report measure(PreflightHome home) throws IOException {
        List<Entry> entries = new ArrayList<>();
        long total = 0;
        long counted = 0;
        for (Map.Entry<String, Category> category : CATEGORIES.entrySet()) {
            Path directory = home.root().resolve(category.getKey());
            Usage usage = usage(directory);
            if (usage.files() > 0 || Files.exists(directory)) {
                // Only evidence is broken down. Acceleration categories are content-hashed blobs
                // whose file names are hashes, so grouping by name would produce one row per file
                // and say nothing; what matters there is the total, and that it is deliberate.
                entries.add(new Entry(
                        category.getKey(),
                        category.getValue().group(),
                        category.getValue().description(),
                        usage,
                        "evidence".equals(category.getValue().group())
                                ? artifacts(directory)
                                : List.of()));
                counted = Math.addExact(counted, usage.bytes());
            }
        }
        Usage whole = usage(home.root());
        total = whole.bytes();
        entries.sort(Comparator.comparingLong((Entry entry) -> entry.usage().bytes()).reversed());
        return new Report(
                home.root(),
                Files.isDirectory(home.root()),
                entries,
                whole,
                Math.max(0, total - counted),
                profiles(home));
    }

    private static Category acceleration(String description) {
        return new Category("acceleration", description);
    }

    private static Category evidence(String description) {
        return new Category("evidence", description);
    }

    private static Category configuration(String description) {
        return new Category("configuration", description);
    }

    private static Category application(String description) {
        return new Category("application", description);
    }

    /**
     * Every prepared profile the cache holds, newest first.
     *
     * <p>A profile is identified by the fingerprint in its artifact names, so a resource index and
     * a texture manifest sharing a fingerprint are two parts of one profile. Multiple profiles
     * coexisting is the normal state and the reason the cache survives a mod-set change: switching
     * back to a previous set finds its artifacts still here.
     */
    private static List<Profile> profiles(PreflightHome home) throws IOException {
        Map<String, Profile> byFingerprint = new TreeMap<>();
        collect(byFingerprint, ResourceIndexIO.directory(home.cache()), ".spfi", true);
        collect(byFingerprint, TextureManifestIO.directory(home.cache()), ".spfm", false);
        List<Profile> profiles = new ArrayList<>(byFingerprint.values());
        profiles.sort(Comparator.comparingLong(Profile::lastModifiedMillis).reversed());
        return List.copyOf(profiles);
    }

    private static void collect(
            Map<String, Profile> profiles, Path directory, String extension, boolean index)
            throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(extension)) {
                    continue;
                }
                String fingerprint = name.substring(0, name.length() - extension.length());
                if (!fingerprint.matches("[0-9a-f]{64}")) {
                    continue;
                }
                long size = Files.size(file);
                long modified = Files.getLastModifiedTime(file).toMillis();
                profiles.merge(
                        fingerprint,
                        new Profile(fingerprint, index ? size : 0, index ? 0 : size, modified),
                        (existing, added) -> new Profile(
                                fingerprint,
                                existing.indexBytes() + added.indexBytes(),
                                existing.manifestBytes() + added.manifestBytes(),
                                Math.max(existing.lastModifiedMillis(), added.lastModifiedMillis())));
            }
        }
    }

    /**
     * What a category is made of, by artifact rather than by folder.
     *
     * <p>Categories answer what a directory is <em>for</em>. That is the question that let #471
     * sit here for months: {@code runs} was modeled, described accurately as per-launch evidence,
     * and so a megabyte per launch of documents nothing read looked exactly like the thing the
     * category said it was. A total cannot notice that its contents stopped being used.
     *
     * <p>Evidence directories hold one directory per session and the same file names inside each,
     * so grouping by file name and summing across sessions turns "3.4 GB of runs" into "315 copies
     * of this, 76 MB" -- which is a sentence somebody can disagree with.
     *
     * <p>Bounded to {@link #ARTIFACT_LIMIT} names with the rest summed into one remainder, so a
     * directory of uniquely-named files cannot make this report unbounded.
     */
    private static final int ARTIFACT_LIMIT = 12;

    static List<Artifact> artifacts(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        Map<String, long[]> byName = new TreeMap<>();
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile()) {
                    long[] totals = byName.computeIfAbsent(
                            file.getFileName().toString(), name -> new long[2]);
                    totals[0] = totals[0] + attributes.size();
                    totals[1] = totals[1] + 1;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) {
                return FileVisitResult.CONTINUE;
            }
        });
        List<Artifact> all = new ArrayList<>(byName.size());
        byName.forEach((name, totals) -> all.add(new Artifact(name, totals[0], totals[1])));
        all.sort(Comparator.comparingLong(Artifact::bytes).reversed()
                .thenComparing(Artifact::name));
        if (all.size() <= ARTIFACT_LIMIT) {
            return List.copyOf(all);
        }
        List<Artifact> bounded = new ArrayList<>(all.subList(0, ARTIFACT_LIMIT));
        long bytes = 0;
        long files = 0;
        for (Artifact artifact : all.subList(ARTIFACT_LIMIT, all.size())) {
            bytes = Math.addExact(bytes, artifact.bytes());
            files = Math.addExact(files, artifact.files());
        }
        bounded.add(new Artifact(
                "(" + (all.size() - ARTIFACT_LIMIT) + " other names)", bytes, files));
        return List.copyOf(bounded);
    }

    static Usage usage(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return new Usage(0, 0);
        }
        long[] totals = new long[2];
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile()) {
                    totals[0] = totals[0] + attributes.size();
                    totals[1] = totals[1] + 1;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) {
                // A file that vanished mid-walk is not a reason to refuse to report a total.
                return FileVisitResult.CONTINUE;
            }
        });
        return new Usage(totals[0], totals[1]);
    }

    static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value = value / 1024;
            unit++;
        }
        return String.format(Locale.ROOT, value >= 100 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }

    record Usage(long bytes, long files) {
    }

    private record Category(String group, String description) {
    }

    record Entry(
            String path, String group, String description, Usage usage, List<Artifact> artifacts) {
    }

    /** One file name, summed across every session directory in a category. */
    record Artifact(String name, long bytes, long files) {
    }

    record Profile(
            String fingerprint, long indexBytes, long manifestBytes, long lastModifiedMillis) {
        long bytes() {
            return indexBytes + manifestBytes;
        }
    }

    record Report(
            Path root,
            boolean present,
            List<Entry> entries,
            Usage whole,
            long uncategorizedBytes,
            List<Profile> profiles) {
    }
}
