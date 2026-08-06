package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Preview-first retention for top-level run and benchmark evidence sessions. */
final class EvidenceRetention {
    private EvidenceRetention() {
    }

    static Inventory inventory(PreflightHome home) throws IOException {
        return new Inventory(sessions("run", home.runs()), sessions("benchmark", home.benchmarks()));
    }

    static Plan plan(Inventory inventory, Integer keepRuns, Integer keepBenchmarks) {
        if (keepRuns != null && keepRuns < 0) {
            throw new IllegalArgumentException("--keep-runs must be nonnegative");
        }
        if (keepBenchmarks != null && keepBenchmarks < 0) {
            throw new IllegalArgumentException("--keep-benchmarks must be nonnegative");
        }
        List<Session> removals = new ArrayList<>();
        if (keepRuns != null) {
            removals.addAll(after(inventory.runs(), keepRuns));
        }
        if (keepBenchmarks != null) {
            removals.addAll(after(inventory.benchmarks(), keepBenchmarks));
        }
        removals.sort(Comparator.comparingLong(Session::bytes).reversed());
        return new Plan(List.copyOf(removals), keepRuns, keepBenchmarks);
    }

    static long apply(Plan plan) throws IOException {
        // Validate every target before changing anything. A benchmark or run still being written is
        // evidence, not garbage; it must survive a retention command planned against older state.
        for (Session session : plan.removals()) {
            Session current = measure(session.kind(), session.path());
            if (!session.sameSnapshot(current)) {
                throw new IOException("Evidence session changed while pruning: " + session.path());
            }
        }
        long removed = 0;
        for (Session session : plan.removals()) {
            deleteTree(session.path());
            removed = Math.addExact(removed, session.bytes());
        }
        return removed;
    }

    private static List<Session> sessions(String kind, Path root) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<Session> sessions = new ArrayList<>();
        try (var children = Files.list(root)) {
            for (Path child : children
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .toList()) {
                sessions.add(measure(kind, child));
            }
        }
        sessions.sort(Comparator.comparingLong(Session::modifiedMillis).reversed()
                .thenComparing(session -> session.path().getFileName().toString()));
        return List.copyOf(sessions);
    }

    private static List<Session> after(List<Session> sessions, int keep) {
        return keep >= sessions.size() ? List.of() : sessions.subList(keep, sessions.size());
    }

    private static Session measure(String kind, Path root) throws IOException {
        Path absolute = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(absolute)) {
            throw new IOException("Evidence session is not a real directory: " + absolute);
        }
        long[] totals = {0, 0, Files.getLastModifiedTime(absolute, LinkOption.NOFOLLOW_LINKS).toMillis()};
        Files.walkFileTree(absolute, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                totals[2] = Math.max(totals[2], attributes.lastModifiedTime().toMillis());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile()) {
                    totals[0] = Math.addExact(totals[0], attributes.size());
                    totals[1] = Math.addExact(totals[1], 1);
                }
                totals[2] = Math.max(totals[2], attributes.lastModifiedTime().toMillis());
                return FileVisitResult.CONTINUE;
            }
        });
        return new Session(kind, absolute, totals[0], totals[1], totals[2]);
    }

    private static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                    throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    record Session(String kind, Path path, long bytes, long files, long modifiedMillis) {
        boolean sameSnapshot(Session other) {
            return kind.equals(other.kind)
                    && path.equals(other.path)
                    && bytes == other.bytes
                    && files == other.files
                    && modifiedMillis == other.modifiedMillis;
        }
    }

    record Inventory(List<Session> runs, List<Session> benchmarks) {
        long bytes() {
            return sessions().stream().mapToLong(Session::bytes).sum();
        }

        long files() {
            return sessions().stream().mapToLong(Session::files).sum();
        }

        List<Session> sessions() {
            List<Session> all = new ArrayList<>(runs);
            all.addAll(benchmarks);
            return List.copyOf(all);
        }
    }

    record Plan(List<Session> removals, Integer keepRuns, Integer keepBenchmarks) {
        long bytes() {
            return removals.stream().mapToLong(Session::bytes).sum();
        }

        long files() {
            return removals.stream().mapToLong(Session::files).sum();
        }
    }
}
