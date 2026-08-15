package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Bounded retention for small recovery artifacts that Preflight creates automatically. */
final class SafetyArtifactRetention {
    static final int MAX_BACKUPS_PER_DIRECTORY = 20;
    static final int MAX_ACTIVATION_REVIEWS = 128;

    private SafetyArtifactRetention() {
    }

    static Path requireRealDirectory(Path directory) throws IOException {
        Path absolute = directory.toAbsolutePath().normalize();
        Files.createDirectories(absolute);
        if (Files.isSymbolicLink(absolute)
                || !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Safety-artifact directory is not a real directory: " + absolute);
        }
        return absolute;
    }

    static void retainNewest(Path directory, Pattern fileName, int maximum) throws IOException {
        if (maximum < 0) {
            throw new IllegalArgumentException("maximum must be nonnegative");
        }
        List<Candidate> candidates = candidates(directory, fileName);
        for (Candidate candidate : candidates.subList(Math.min(maximum, candidates.size()), candidates.size())) {
            deleteCandidate(directory, fileName, candidate);
        }
    }

    static void deleteOlderThan(Path directory, Pattern fileName, Instant cutoff) throws IOException {
        for (Candidate candidate : candidates(directory, fileName)) {
            if (candidate.modified().toInstant().isBefore(cutoff)) {
                deleteCandidate(directory, fileName, candidate);
            }
        }
    }

    private static List<Candidate> candidates(Path directory, Pattern fileName) throws IOException {
        Path absolute = directory.toAbsolutePath().normalize();
        if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        if (Files.isSymbolicLink(absolute)
                || !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Safety-artifact directory is not a real directory: " + absolute);
        }
        List<Candidate> candidates = new ArrayList<>();
        try (var entries = Files.list(absolute)) {
            for (Path path : entries.toList()) {
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && fileName.matcher(path.getFileName().toString()).matches()) {
                    candidates.add(new Candidate(
                            path.toAbsolutePath().normalize(),
                            Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS),
                            Files.size(path)));
                }
            }
        }
        candidates.sort(Comparator.comparing(Candidate::modified).reversed()
                .thenComparing(candidate -> candidate.path().getFileName().toString(),
                        Comparator.reverseOrder()));
        return List.copyOf(candidates);
    }

    private static void deleteCandidate(Path directory, Pattern fileName, Candidate candidate)
            throws IOException {
        Path absoluteDirectory = directory.toAbsolutePath().normalize();
        Path absoluteCandidate = candidate.path();
        if (!absoluteDirectory.equals(absoluteCandidate.getParent())
                || Files.isSymbolicLink(absoluteCandidate)
                || !Files.isRegularFile(absoluteCandidate, LinkOption.NOFOLLOW_LINKS)
                || !fileName.matcher(absoluteCandidate.getFileName().toString()).matches()
                || !candidate.modified().equals(
                        Files.getLastModifiedTime(absoluteCandidate, LinkOption.NOFOLLOW_LINKS))
                || candidate.bytes() != Files.size(absoluteCandidate)) {
            throw new IOException("Safety artifact changed while pruning: " + absoluteCandidate);
        }
        Files.delete(absoluteCandidate);
    }

    private record Candidate(Path path, FileTime modified, long bytes) {
    }
}
