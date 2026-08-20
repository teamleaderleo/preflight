package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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
     * expected to reach it; when it is reached the oldest physical lines go, since a history's
     * recent append end is the end anybody asks about.
     */
    static final int MAX_ENTRIES = 10_000;

    /**
     * One canonical entry is normally a few hundred encoded bytes. Eight KiB leaves ample margin
     * for every current field while making a corrupt single JSONL row cheap to discard.
     */
    static final int MAX_ENCODED_ENTRY_BYTES = 8 * 1024;

    /**
     * Enough work to retain {@link #MAX_ENTRIES} rows at the writer ceiling, including a CRLF
     * ending per row plus one context byte when an oversized prefix is skipped. The count, row,
     * and work ceilings therefore compose without discarding otherwise-valid retained rows.
     */
    static final long MAX_ENCODED_FILE_BYTES =
            requiredScanWorkBytes(MAX_ENCODED_ENTRY_BYTES, MAX_ENTRIES);

    private static final int READ_CHUNK_BYTES = 8 * 1024;
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

    /**
     * Drops old physical rows once count, line, or aggregate byte bounds are crossed.
     *
     * <p>The same fixed-extent scanner used by history reads supplies the retained suffix, so a
     * hostile prefix or a concurrently growing file cannot turn post-append maintenance into an
     * unbounded read. A short read caused by concurrent shrink leaves the file alone.
     */
    private static void trim(Path path) throws IOException {
        RetainedLines retained = retainedLines(path);
        if (!retained.rewriteNeeded()) {
            return;
        }

        Path temporary = Files.createTempFile(path.getParent(), ".launches-", ".tmp");
        boolean moved = false;
        try {
            byte[] separator = System.lineSeparator().getBytes(StandardCharsets.UTF_8);
            try (FileChannel output = FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                for (RetainedLine line : retained.lines()) {
                    if (line.bytes() == null) continue;
                    writeFully(output, ByteBuffer.wrap(line.bytes()));
                    writeFully(output, ByteBuffer.wrap(separator));
                }
            }
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

    /** Every readable retained line, oldest first. Unreadable lines are skipped, not fatal. */
    static List<Entry> read(PreflightHome home) throws IOException {
        if (historyDirectory(home, false) == null) {
            return List.of();
        }
        return withExclusiveLock(home, () -> readUnlocked(home));
    }

    static void appendUnlocked(PreflightHome home, Entry entry) throws IOException {
        appendAllUnlocked(home, List.of(entry));
    }

    /**
     * Appends a batch in physical order and trims once.
     *
     * <p>If a one-time historical backfill contains more than the retention count, its older prefix
     * can never survive the final physical-order trim, so only the suffix that can survive is
     * written. This keeps backfill ledger maintenance bounded without changing which rows remain.
     */
    static void appendAllUnlocked(PreflightHome home, List<Entry> entries) throws IOException {
        if (entries.isEmpty()) return;

        int firstRetainable = Math.max(0, entries.size() - MAX_ENTRIES);
        List<byte[]> encoded = new ArrayList<>(entries.size() - firstRetainable);
        for (int index = firstRetainable; index < entries.size(); index++) {
            encoded.add(encodedEntry(entries.get(index)));
        }

        Path path = writablePath(home);
        byte[] separator = System.lineSeparator().getBytes(StandardCharsets.UTF_8);
        try (FileChannel output = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
                LinkOption.NOFOLLOW_LINKS)) {
            for (byte[] line : encoded) {
                writeFully(output, ByteBuffer.wrap(line));
                writeFully(output, ByteBuffer.wrap(separator));
            }
        }
        trim(path);
    }

    static List<Entry> readUnlocked(PreflightHome home) throws IOException {
        Path path = readablePath(home);
        if (path == null) {
            return List.of();
        }
        RetainedLines retained = retainedLines(path);
        List<Entry> entries = new ArrayList<>(retained.lines().size());
        for (RetainedLine line : retained.lines()) {
            if (line.bytes() == null) continue;
            String decoded = decodeUtf8(line.bytes());
            if (decoded == null) continue;
            Entry entry = Entry.parse(decoded);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return List.copyOf(entries);
    }

    private static byte[] encodedEntry(Entry entry) throws IOException {
        byte[] encoded = Json.object(entry.toMap()).getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_ENCODED_ENTRY_BYTES) {
            throw new IOException("Launch-history entry exceeds the "
                    + MAX_ENCODED_ENTRY_BYTES + "-byte encoded line limit");
        }
        return encoded;
    }

    static boolean fitsEncodedEntryLimit(Entry entry) {
        return Json.object(entry.toMap()).getBytes(StandardCharsets.UTF_8).length
                <= MAX_ENCODED_ENTRY_BYTES;
    }

    private static String decodeUtf8(byte[] encoded) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoded))
                    .toString();
        } catch (CharacterCodingException malformed) {
            return null;
        }
    }

    private static RetainedLines retainedLines(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            long sampledExtent = channel.size();
            return scanRetainedLines(
                    channel,
                    sampledExtent,
                    MAX_ENCODED_ENTRY_BYTES,
                    MAX_ENCODED_FILE_BYTES,
                    MAX_ENTRIES);
        }
    }

    /**
     * Incrementally scans at most {@code maxWorkBytes} from a fixed opened-file extent.
     *
     * <p>When the sampled file is oversized, one context byte plus the bounded tail window are read.
     * A leading partial row consumes a physical retention slot but is never materialized. The same
     * is true for an overlong row. This keeps append position authoritative even when bad rows are
     * skipped.
     */
    static long requiredScanWorkBytes(int maxLineBytes, int maxEntries) {
        if (maxLineBytes < 1 || maxEntries < 1) {
            throw new IllegalArgumentException("Invalid launch-history scan bounds");
        }
        try {
            long physicalLineBytes = Math.addExact((long) maxLineBytes, 2L);
            return Math.addExact(1L, Math.multiplyExact((long) maxEntries, physicalLineBytes));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Launch-history scan bounds overflow", overflow);
        }
    }

    static RetainedLines scanRetainedLines(
            SeekableByteChannel channel,
            long sampledExtent,
            int maxLineBytes,
            long maxWorkBytes,
            int maxEntries) throws IOException {
        if (sampledExtent < 0 || maxWorkBytes < 2) {
            throw new IllegalArgumentException("Invalid launch-history scan bounds");
        }
        long requiredWorkBytes = requiredScanWorkBytes(maxLineBytes, maxEntries);
        if (maxWorkBytes < requiredWorkBytes) {
            throw new IllegalArgumentException(
                    "Launch-history scan work bound cannot cover its row-retention contract");
        }

        boolean prefixTruncated = sampledExtent > maxWorkBytes;
        long scanStart = 0L;
        long remaining = sampledExtent;
        boolean leadingPartial = false;
        long bytesRead = 0L;
        boolean shortRead = false;

        if (prefixTruncated) {
            // Reserve one byte of the work budget to learn whether the retained suffix begins on a
            // line boundary. The remaining maxWorkBytes - 1 bytes are the actual suffix window.
            scanStart = sampledExtent - (maxWorkBytes - 1L);
            ByteBuffer context = ByteBuffer.allocate(1);
            channel.position(scanStart - 1L);
            int contextRead = readOnce(channel, context);
            if (contextRead != 1) {
                return new RetainedLines(List.of(), true, false, false, true, sampledExtent, 0L);
            }
            bytesRead = 1L;
            context.flip();
            leadingPartial = context.get() != (byte) '\n';
            channel.position(scanStart);
            remaining = sampledExtent - scanStart;
        } else {
            channel.position(0L);
        }

        LineCollector collector = new LineCollector(maxLineBytes, maxEntries, leadingPartial);
        ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(
                READ_CHUNK_BYTES, Math.max(1L, Math.min(Integer.MAX_VALUE, remaining))));
        int zeroReads = 0;

        while (remaining > 0L) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), remaining));
            int read = channel.read(buffer);
            if (read < 0) {
                shortRead = true;
                break;
            }
            if (read == 0) {
                if (++zeroReads >= 3) {
                    shortRead = true;
                    break;
                }
                continue;
            }
            zeroReads = 0;
            remaining -= read;
            bytesRead += read;
            if (bytesRead > maxWorkBytes) {
                throw new IOException("Launch-history scan exceeded its encoded work limit");
            }
            buffer.flip();
            while (buffer.hasRemaining()) {
                collector.accept(buffer.get());
            }
        }

        if (!shortRead && remaining == 0L) {
            collector.finish();
        } else {
            collector.discardPartial();
        }

        return collector.result(prefixTruncated, shortRead, sampledExtent, bytesRead);
    }

    private static int readOnce(SeekableByteChannel channel, ByteBuffer buffer) throws IOException {
        int zeroReads = 0;
        while (true) {
            int read = channel.read(buffer);
            if (read != 0) return read;
            if (++zeroReads >= 3) return 0;
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static final class LineCollector {
        private final int maxLineBytes;
        private final int maxEntries;
        private final ArrayDeque<RetainedLine> retained = new ArrayDeque<>();
        private final ByteArrayOutputStream current = new ByteArrayOutputStream();
        private boolean leadingPartial;
        private boolean overlong;
        private boolean sawOverlong;
        private boolean countTruncated;

        private LineCollector(int maxLineBytes, int maxEntries, boolean leadingPartial) {
            this.maxLineBytes = maxLineBytes;
            this.maxEntries = maxEntries;
            this.leadingPartial = leadingPartial;
        }

        private void accept(byte value) {
            if (value == (byte) '\n') {
                finishLine();
                return;
            }
            if (leadingPartial || overlong) {
                return;
            }
            // Allow one extra byte while waiting to see whether it is the CR in a CRLF delimiter.
            // The encoded-entry ceiling applies to the JSON bytes, not to its line ending.
            if (current.size() == maxLineBytes + 1) {
                current.reset();
                overlong = true;
                sawOverlong = true;
                return;
            }
            current.write(value);
        }

        private void finishLine() {
            if (leadingPartial) {
                retain(new RetainedLine(null));
                leadingPartial = false;
            } else if (overlong) {
                retain(new RetainedLine(null));
                overlong = false;
            } else {
                byte[] line = withoutCarriageReturn(current.toByteArray());
                if (line.length > maxLineBytes) {
                    sawOverlong = true;
                    retain(new RetainedLine(null));
                } else {
                    retain(new RetainedLine(line));
                }
            }
            current.reset();
        }

        private void finish() {
            if (leadingPartial) {
                retain(new RetainedLine(null));
                leadingPartial = false;
            } else if (overlong) {
                retain(new RetainedLine(null));
                overlong = false;
            } else if (current.size() > 0) {
                byte[] line = withoutCarriageReturn(current.toByteArray());
                if (line.length > maxLineBytes) {
                    sawOverlong = true;
                    retain(new RetainedLine(null));
                } else {
                    retain(new RetainedLine(line));
                }
            }
            current.reset();
        }

        private void discardPartial() {
            current.reset();
            leadingPartial = false;
            overlong = false;
        }

        private void retain(RetainedLine line) {
            if (retained.size() == maxEntries) {
                retained.removeFirst();
                countTruncated = true;
            }
            retained.addLast(line);
        }

        private RetainedLines result(
                boolean prefixTruncated,
                boolean shortRead,
                long sampledExtent,
                long bytesRead) {
            return new RetainedLines(
                    List.copyOf(retained),
                    prefixTruncated,
                    countTruncated,
                    sawOverlong,
                    shortRead,
                    sampledExtent,
                    bytesRead);
        }

        private static byte[] withoutCarriageReturn(byte[] bytes) {
            if (bytes.length == 0 || bytes[bytes.length - 1] != (byte) '\r') {
                return bytes;
            }
            return Arrays.copyOf(bytes, bytes.length - 1);
        }
    }

    record RetainedLine(byte[] bytes) {
    }

    record RetainedLines(
            List<RetainedLine> lines,
            boolean prefixTruncated,
            boolean countTruncated,
            boolean sawOverlongLine,
            boolean shortRead,
            long sampledExtent,
            long bytesRead) {
        boolean rewriteNeeded() {
            return !shortRead && (prefixTruncated || countTruncated || sawOverlongLine);
        }
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
