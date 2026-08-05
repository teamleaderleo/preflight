package dev.starsector.preflight.cli;

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
    private static final Map<String, String> CATEGORIES = new LinkedHashMap<>(Map.ofEntries(
            Map.entry("cache/blobs", "prepared texture payloads, shared across profiles by content hash"),
            Map.entry("cache/resource-indexes", "one per prepared profile"),
            Map.entry("cache/manifests", "one per prepared profile"),
            Map.entry("cache/spec-store", "prepared JSON, rules and command-class artifacts"),
            Map.entry("cache/prepared-audio", "decoded PCM and exact-profile audio manifests"),
            Map.entry("cache/generated-bytecode", "exact-context Janino class maps and deduplicated packs"),
            Map.entry("cache/classpath", "mod jar and class inventories"),
            Map.entry("cache/comparison-state-snapshots", "benchmark comparison inputs"),
            Map.entry("cache/reports", "generated reports"),
            Map.entry("runs", "per-launch evidence: adapter reports, phase timings, recordings"),
            Map.entry("benchmarks", "recorded benchmark scenarios"),
            Map.entry("bin", "the installed copy of preflight.jar")));

    private CacheFootprint() {
    }

    static Report measure(PreflightHome home) throws IOException {
        List<Entry> entries = new ArrayList<>();
        long total = 0;
        long counted = 0;
        for (Map.Entry<String, String> category : CATEGORIES.entrySet()) {
            Path directory = home.root().resolve(category.getKey());
            Usage usage = usage(directory);
            if (usage.files() > 0 || Files.exists(directory)) {
                entries.add(new Entry(category.getKey(), category.getValue(), usage));
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
        collect(byFingerprint, home.cache().resolve("resource-indexes"), ".spfi", true);
        collect(byFingerprint, home.cache().resolve("manifests"), ".spfm", false);
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

    record Entry(String path, String description, Usage usage) {
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
