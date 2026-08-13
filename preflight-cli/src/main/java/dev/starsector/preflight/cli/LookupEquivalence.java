package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.ClasspathProfileIndex;
import dev.starsector.preflight.core.ResourceIndex;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Compares direct indexes with baseline ordered root and JAR probing. */
final class LookupEquivalence {
    private static final int MISMATCH_SAMPLE_LIMIT = 25;

    private LookupEquivalence() {
    }

    static DomainResult resources(ResourceIndex index, int queryCount, long seed) {
        List<String> queries = queries(
                new ArrayList<>(index.entries().keySet()),
                queryCount,
                seed,
                "preflight/missing/resource/",
                ".json");
        List<List<Path>> baselineResults = new ArrayList<>(queryCount);
        long baselineProbes = 0;
        long baselineStarted = System.nanoTime();
        for (String query : queries) {
            List<Path> providers = new ArrayList<>();
            for (ResourceIndex.Root root : index.roots()) {
                baselineProbes++;
                Path rootPath = root.path().toAbsolutePath().normalize();
                Path candidate = rootPath.resolve(query).normalize();
                if (candidate.startsWith(rootPath) && Files.isRegularFile(candidate)) {
                    providers.add(candidate);
                }
            }
            baselineResults.add(List.copyOf(providers));
        }
        long baselineNanos = System.nanoTime() - baselineStarted;

        List<String> mismatches = new ArrayList<>();
        long indexedProviderAccesses = 0;
        long indexedStarted = System.nanoTime();
        for (int i = 0; i < queries.size(); i++) {
            String query = queries.get(i);
            List<Path> indexed = index.providers(query).stream()
                    .map(index::resolve)
                    .toList();
            indexedProviderAccesses += indexed.size();
            if (!sameFilesInOrder(baselineResults.get(i), indexed)) {
                addMismatch(mismatches, query, baselineResults.get(i), indexed);
            }
        }
        long indexedNanos = System.nanoTime() - indexedStarted;
        return result(
                "resources",
                queries,
                index.entryCount(),
                baselineProbes,
                indexedProviderAccesses,
                baselineNanos,
                indexedNanos,
                mismatches);
    }

    private static boolean sameFilesInOrder(List<Path> baseline, List<Path> indexed) {
        if (baseline.size() != indexed.size()) {
            return false;
        }
        for (int i = 0; i < baseline.size(); i++) {
            Path left = baseline.get(i);
            Path right = indexed.get(i);
            if (left.equals(right)) {
                continue;
            }
            try {
                if (!Files.isSameFile(left, right)) {
                    return false;
                }
            } catch (IOException error) {
                return false;
            }
        }
        return true;
    }

    static DomainResult classpath(ClasspathProfileIndex index, int queryCount, long seed) throws IOException {
        List<String> queries = queries(
                new ArrayList<>(index.providers().keySet()),
                queryCount,
                seed,
                "preflight/missing/classpath/",
                ".class");
        List<ZipFile> archives = new ArrayList<>();
        Throwable primaryFailure = null;
        try {
            for (ClasspathProfileIndex.Archive archive : index.archives()) {
                archives.add(new ZipFile(archive.physicalPath().toFile()));
            }

            List<List<String>> baselineResults = new ArrayList<>(queryCount);
            long baselineProbes = 0;
            long baselineStarted = System.nanoTime();
            for (String query : queries) {
                List<String> providers = new ArrayList<>();
                for (int archiveIndex = 0; archiveIndex < archives.size(); archiveIndex++) {
                    baselineProbes++;
                    ZipEntry entry = archives.get(archiveIndex).getEntry(query);
                    if (entry != null && !entry.isDirectory()) {
                        providers.add(providerId(index.archives().get(archiveIndex)));
                    }
                }
                baselineResults.add(List.copyOf(providers));
            }
            long baselineNanos = System.nanoTime() - baselineStarted;

            List<String> mismatches = new ArrayList<>();
            long indexedProviderAccesses = 0;
            long indexedStarted = System.nanoTime();
            for (int i = 0; i < queries.size(); i++) {
                String query = queries.get(i);
                List<String> indexed = index.providerArchives(query).stream()
                        .map(LookupEquivalence::providerId)
                        .toList();
                indexedProviderAccesses += indexed.size();
                if (!baselineResults.get(i).equals(indexed)) {
                    addMismatch(mismatches, query, baselineResults.get(i), indexed);
                }
            }
            long indexedNanos = System.nanoTime() - indexedStarted;
            return result(
                    "classpath",
                    queries,
                    index.entryCount(),
                    baselineProbes,
                    indexedProviderAccesses,
                    baselineNanos,
                    indexedNanos,
                    mismatches);
        } catch (IOException | RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            closeAll(archives, primaryFailure);
        }
    }

    static void closeAll(List<? extends Closeable> closeables, Throwable primaryFailure) throws IOException {
        IOException cleanupFailure = null;
        for (Closeable closeable : closeables) {
            try {
                closeable.close();
            } catch (IOException error) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(error);
                } else if (cleanupFailure == null) {
                    cleanupFailure = error;
                } else {
                    cleanupFailure.addSuppressed(error);
                }
            }
        }
        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
    }

    private static List<String> queries(
            List<String> existing,
            int queryCount,
            long seed,
            String missingPrefix,
            String missingSuffix) {
        if (queryCount < 1 || queryCount > 1_000_000) {
            throw new IllegalArgumentException("Lookup query count must be between 1 and 1,000,000");
        }
        SplittableRandom random = new SplittableRandom(seed);
        List<String> queries = new ArrayList<>(queryCount);
        for (int i = 0; i < queryCount; i++) {
            boolean hit = !existing.isEmpty() && random.nextInt(4) != 0;
            queries.add(hit
                    ? existing.get(random.nextInt(existing.size()))
                    : missingPrefix + i + missingSuffix);
        }
        return List.copyOf(queries);
    }

    private static DomainResult result(
            String domain,
            List<String> queries,
            int indexedEntryCount,
            long baselineProbes,
            long indexedProviderAccesses,
            long baselineNanos,
            long indexedNanos,
            List<String> mismatches) {
        long hitQueries = queries.stream().filter(query -> !query.startsWith("preflight/missing/")).count();
        long missQueries = queries.size() - hitQueries;
        return new DomainResult(
                domain,
                queries.size(),
                hitQueries,
                missQueries,
                indexedEntryCount,
                baselineProbes,
                queries.size(),
                indexedProviderAccesses,
                baselineNanos,
                indexedNanos,
                mismatches.size(),
                List.copyOf(mismatches));
    }

    private static String providerId(ClasspathProfileIndex.Archive archive) {
        return archive.modId() + ":" + archive.relativePath() + ":" + archive.physicalPath();
    }

    private static void addMismatch(
            List<String> samples,
            String query,
            List<?> baseline,
            List<?> indexed) {
        if (samples.size() < MISMATCH_SAMPLE_LIMIT) {
            samples.add(query + " baseline=" + baseline + " indexed=" + indexed);
        }
    }

    record DomainResult(
            String domain,
            long queries,
            long requestedHits,
            long requestedMisses,
            long indexedEntryCount,
            long baselineProbes,
            long indexedLookups,
            long indexedProviderAccesses,
            long baselineNanos,
            long indexedNanos,
            long mismatches,
            List<String> mismatchSamples) {
        boolean equivalent() {
            return mismatches == 0;
        }

        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("domain", domain);
            values.put("queries", queries);
            values.put("requestedHits", requestedHits);
            values.put("requestedMisses", requestedMisses);
            values.put("indexedEntryCount", indexedEntryCount);
            values.put("baselineProbes", baselineProbes);
            values.put("indexedLookups", indexedLookups);
            values.put("indexedProviderAccesses", indexedProviderAccesses);
            values.put("probeReduction", indexedLookups == 0 ? 0 : baselineProbes / (double) indexedLookups);
            values.put("baselineMs", baselineNanos / 1_000_000.0);
            values.put("indexedMs", indexedNanos / 1_000_000.0);
            values.put("timingRatio", indexedNanos == 0 ? 0 : baselineNanos / (double) indexedNanos);
            values.put("equivalent", equivalent());
            values.put("mismatches", mismatches);
            values.put("mismatchSamples", mismatchSamples);
            return values;
        }
    }
}
