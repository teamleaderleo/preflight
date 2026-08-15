package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One line per launch, kept after the launch's evidence is gone.
 *
 * <p>A run directory is about a megabyte and is worth that for a few days: it answers "what
 * happened during this launch", which is a question with a short shelf life. What stays worth
 * keeping is much smaller -- when it ran, how long it took, whether it worked, and under which
 * settings. Those few fields are what a history is made of, and holding them costs a couple of
 * hundred bytes instead of a megabyte.
 *
 * <p>Separating the two means retention no longer has to choose between forgetting last month and
 * carrying last month's diagnostics. Evidence can be evicted early precisely because the ledger is
 * not evidence: it is the record that the launch happened and how it went.
 *
 * <p>Append-only, one JSON object per line. A launch never fails because its ledger line could not
 * be written, and a line that cannot be parsed later is skipped rather than fatal -- a history that
 * refuses to load because of one bad row is worse than one with a gap.
 */
final class LaunchLedger {
    static final String FORMAT = "starsector-preflight-launch-ledger-v1";

    /**
     * Roughly 2 MB at the record sizes this writes, and about a launch a day for thirty years.
     * The cap exists so the file has an answer to "how large can this get", not because anyone is
     * expected to reach it; when it is reached the oldest lines go, since a history's recent end is
     * the end anybody asks about.
     */
    static final int MAX_ENTRIES = 10_000;
    private static final ReentrantLock JVM_LOCK = new ReentrantLock();

    private LaunchLedger() {
    }

    static Path path(PreflightHome home) {
        return home.root().resolve("history").resolve("launches.jsonl");
    }

    /**
     * Appends one launch, and never throws.
     *
     * @return the reason it could not be recorded, or null when it was
     */
    static String record(PreflightHome home, Entry entry) {
        try {
            withExclusiveLock(home, () -> {
                appendUnlocked(home, entry);
                return null;
            });
            return null;
        } catch (IOException | RuntimeException error) {
            String message = error.getMessage();
            return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
        }
    }

    /** Drops the oldest lines once the file passes the cap, rewriting through a temporary. */
    private static void trim(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.size() <= MAX_ENTRIES) {
            return;
        }
        List<String> kept = lines.subList(lines.size() - MAX_ENTRIES, lines.size());
        Path temporary = Files.createTempFile(path.getParent(), ".launches-", ".tmp");
        boolean moved = false;
        try {
            Files.write(temporary, kept, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    /** Every readable line, oldest first. Unreadable lines are skipped, not fatal. */
    static List<Entry> read(PreflightHome home) throws IOException {
        if (historyDirectory(home, false) == null) {
            return List.of();
        }
        return withExclusiveLock(home, () -> readUnlocked(home));
    }

    static void appendUnlocked(PreflightHome home, Entry entry) throws IOException {
        Path path = writablePath(home);
        Files.writeString(
                path,
                Json.object(entry.toMap()) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
                LinkOption.NOFOLLOW_LINKS);
        trim(path);
    }

    static List<Entry> readUnlocked(PreflightHome home) throws IOException {
        Path path = readablePath(home);
        if (path == null) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            Entry entry = Entry.parse(line);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return List.copyOf(entries);
    }

    static <T> T withExclusiveLock(PreflightHome home, IoOperation<T> operation)
            throws IOException {
        JVM_LOCK.lock();
        try {
            // Directory creation is part of the same critical section. Keeping it before the JVM
            // lock let two first-ever records race: one created history and the other reported the
            // newly existing path as an error before either reached the file lock.
            Path history = historyDirectory(home, true);
            Path lockFile = history.resolve(".launches.lock");
            requireRegularFileIfPresent(lockFile, "Launch-history lock");
            try (FileChannel channel = FileChannel.open(
                            lockFile,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS);
                    FileLock ignored = channel.lock()) {
                return operation.run();
            }
        } finally {
            JVM_LOCK.unlock();
        }
    }

    private static Path writablePath(PreflightHome home) throws IOException {
        Path history = historyDirectory(home, true);
        Path ledger = history.resolve("launches.jsonl");
        requireRegularFileIfPresent(ledger, "Launch history");
        return ledger;
    }

    private static Path readablePath(PreflightHome home) throws IOException {
        Path history = historyDirectory(home, false);
        if (history == null) return null;
        Path ledger = history.resolve("launches.jsonl");
        if (!Files.exists(ledger, LinkOption.NOFOLLOW_LINKS)) return null;
        requireRegularFileIfPresent(ledger, "Launch history");
        return ledger;
    }

    static Path historyDirectory(PreflightHome home, boolean create) throws IOException {
        Path root = home.root().toAbsolutePath().normalize();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            if (!create) return null;
            Files.createDirectories(root);
        }
        requireDirectory(root, "Preflight home");
        Path realRoot = root.toRealPath();
        Path history = realRoot.resolve("history");
        if (!Files.exists(history, LinkOption.NOFOLLOW_LINKS)) {
            if (!create) return null;
            try {
                Files.createDirectory(history);
            } catch (FileAlreadyExistsException createdByAnotherProcess) {
                // Another Preflight process can win between the no-follow existence check and the
                // create. The type and containment checks below decide whether its result is safe.
            }
        }
        requireDirectory(history, "Launch-history directory");
        Path realHistory = history.toRealPath();
        if (!realHistory.getParent().equals(realRoot)) {
            throw new IOException("Launch-history directory escapes the Preflight home");
        }
        return realHistory;
    }

    private static void requireDirectory(Path directory, String label) throws IOException {
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " isn't a regular directory: " + directory);
        }
    }

    static void requireRegularFileIfPresent(Path file, String label) throws IOException {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException(label + " isn't a regular file: " + file);
        }
    }

    @FunctionalInterface
    interface IoOperation<T> {
        T run() throws IOException;
    }

    /** What a history says when you ask it how things have been going. */
    static Summary summarize(List<Entry> entries) {
        long completed = entries.stream().filter(Entry::succeeded).count();
        long fatal = entries.stream().filter(Entry::fatalDetected).count();
        // By timestamp, not by position. The file is appended to in the order launches finish,
        // which is chronological right up until a backfill writes months of older launches onto the
        // end of it -- and then the first line is the newest row in the file. Reading "recorded
        // since" off line one produced today's date on a history going back to July.
        Instant first = entries.stream().map(Entry::started).min(Instant::compareTo).orElse(null);
        Instant last = entries.stream().map(Entry::started).max(Instant::compareTo).orElse(null);
        return new Summary(entries.size(), completed, fatal, first, last);
    }

    record Summary(int launches, long completed, long fatal, Instant first, Instant last) {
    }

    /**
     * @param profileFingerprint which mod set this launch ran, when the launch resolved one.
     *     Playtime totalled across every profile answers "how long have I played Starsector"; the
     *     same total split by fingerprint answers "how long have I played <em>this</em>", which for
     *     somebody who keeps several mod sets is the more interesting of the two. Null when a
     *     launch did not resolve a profile, which is normal for an unaccelerated launch, and is
     *     kept as its own bucket rather than folded into any named set.
     */
    record Entry(
            String launchId,
            Instant started,
            Long elapsedMillis,
            String outcome,
            Integer exitCode,
            boolean fatalDetected,
            String optimizationPreset,
            List<String> disabledOptimizationDomains,
            String runDirectory,
            String profileFingerprint) {

        boolean succeeded() {
            return "COMPLETED".equals(outcome) && !fatalDetected;
        }

        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("format", FORMAT);
            values.put("launchId", launchId);
            values.put("started", started);
            values.put("elapsedMillis", elapsedMillis);
            values.put("outcome", outcome);
            values.put("exitCode", exitCode);
            values.put("fatalDetected", fatalDetected);
            values.put("optimizationPreset", optimizationPreset);
            values.put("disabledOptimizationDomains", disabledOptimizationDomains);
            values.put("runDirectory", runDirectory);
            values.put("profileFingerprint", profileFingerprint);
            return values;
        }

        /**
         * One line back into an entry, or null when it is not one.
         *
         * <p>Deliberately forgiving. A ledger is read to answer "how has this been going", and a
         * single malformed line -- a partial write from a machine that lost power mid-launch, most
         * likely -- should cost that line and nothing else.
         */
        static Entry parse(String line) {
            if (line == null || line.isBlank()) {
                return null;
            }
            Map<String, Object> values;
            try {
                values = StrictJson.object(line);
            } catch (RuntimeException notJson) {
                return null;
            }
            if (!FORMAT.equals(values.get("format"))) {
                return null;
            }
            Instant started = instant(values.get("started"));
            if (started == null) {
                return null;
            }
            return new Entry(
                    // Rows written before launches had ids get one derived from what they do have,
                    // so an old ledger and a re-import agree on which launch is which.
                    values.get("launchId") instanceof String id && !id.isBlank()
                            ? id
                            : LaunchIdentity.imported(
                                    started,
                                    values.get("runDirectory") instanceof String d ? d : null),
                    started,
                    values.get("elapsedMillis") instanceof Number elapsed ? elapsed.longValue() : null,
                    values.get("outcome") instanceof String outcome ? outcome : null,
                    values.get("exitCode") instanceof Number code ? code.intValue() : null,
                    Boolean.TRUE.equals(values.get("fatalDetected")),
                    values.get("optimizationPreset") instanceof String preset ? preset : null,
                    strings(values.get("disabledOptimizationDomains")),
                    values.get("runDirectory") instanceof String directory ? directory : null,
                    // Absent on rows written before profiles were recorded. A missing fingerprint
                    // is its own bucket, not a reason to discard an otherwise good launch.
                    values.get("profileFingerprint") instanceof String profile ? profile : null);
        }

        private static Instant instant(Object value) {
            if (!(value instanceof String text)) {
                return null;
            }
            try {
                return Instant.parse(text);
            } catch (RuntimeException notAnInstant) {
                return null;
            }
        }

        private static List<String> strings(Object value) {
            if (!(value instanceof List<?> list)) {
                return List.of();
            }
            List<String> result = new ArrayList<>(list.size());
            for (Object element : list) {
                if (element instanceof String text) {
                    result.add(text);
                }
            }
            return List.copyOf(result);
        }
    }
}
