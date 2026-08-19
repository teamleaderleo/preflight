package dev.starsector.preflight.agent;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Content fingerprints for explicitly constrained local code-source archives.
 *
 * <p>The first authorization for one live classloader/archive generation reads and hashes the
 * archive bytes. Later authorizations may reuse that exact digest only for the same classloader
 * object, canonical path, regular-file identity, byte size, and nanosecond modification time.
 * Those filesystem fields select a digest that was already established from bytes; they are never
 * accepted as persistent content proof.
 *
 * <p>The persisted journal is advisory. A new JVM/classloader generation always hashes before a
 * journal entry can count as a verified match. This keeps cross-launch and replacement mutations
 * from inheriting stale authority without rereading the same archive for every transformed class.
 *
 * <p>The active-launch boundary is deliberate: once a classloader generation has established an
 * archive digest, a same-user writer that mutates that same file in place and restores every
 * observed identity field before the next authorization is treated as mutating an immutable live
 * code generation. Restarting creates a new generation and hashes the bytes again.
 */
final class SourceArchiveHashes {
    private static final long MAX_ARCHIVE_BYTES = 4L * 1024 * 1024 * 1024;
    private static final int BUFFER_BYTES = 1024 * 1024;
    private static final int JOURNAL_MAGIC = 0x50464148;
    private static final int JOURNAL_VERSION = 1;
    private static final int MAX_JOURNAL_ENTRIES = 10_000;
    private static final String JOURNAL_NAME = "adapter-source-hashes-v1.bin";
    private static final Object DEFAULT_GENERATION = new Object();
    private static final Object BOOTSTRAP_GENERATION = new Object();
    private static final ConcurrentHashMap<GenerationKey, CompletableFuture<Result>> CACHE =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Path, JournalEntry> JOURNAL = new ConcurrentHashMap<>();
    private static final ThreadLocal<byte[]> BUFFER =
            ThreadLocal.withInitial(() -> new byte[BUFFER_BYTES]);
    private static final AtomicBoolean SHUTDOWN_HOOK_INSTALLED = new AtomicBoolean();
    private static final AtomicLong CALLS = new AtomicLong();
    private static final AtomicLong SESSION_HITS = new AtomicLong();
    private static final AtomicLong DISTINCT_GENERATIONS = new AtomicLong();
    private static final AtomicLong JOURNAL_HITS = new AtomicLong();
    private static final AtomicLong HASHES = new AtomicLong();
    private static final AtomicLong HASHED_BYTES = new AtomicLong();
    private static final AtomicLong HASH_NANOS = new AtomicLong();
    private static final AtomicLong JOURNAL_LOAD_FAILURES = new AtomicLong();
    private static final AtomicLong JOURNAL_WRITE_FAILURES = new AtomicLong();

    private static volatile Path journalFile;
    private static volatile boolean journalLoaded;
    private static volatile boolean journalDirty;

    private SourceArchiveHashes() {
    }

    static void beginSession() {
        CACHE.clear();
        JOURNAL.clear();
        CALLS.set(0);
        SESSION_HITS.set(0);
        DISTINCT_GENERATIONS.set(0);
        JOURNAL_HITS.set(0);
        HASHES.set(0);
        HASHED_BYTES.set(0);
        HASH_NANOS.set(0);
        JOURNAL_LOAD_FAILURES.set(0);
        JOURNAL_WRITE_FAILURES.set(0);
        journalFile = null;
        journalLoaded = false;
        journalDirty = false;
    }

    static void configure(Path cacheDirectory) {
        if (cacheDirectory == null) {
            return;
        }
        journalFile = cacheDirectory.toAbsolutePath().normalize().resolve(JOURNAL_NAME);
        ensureJournalLoaded();
        if (SHUTDOWN_HOOK_INSTALLED.compareAndSet(false, true)) {
            try {
                Runtime.getRuntime().addShutdownHook(new Thread(
                        SourceArchiveHashes::flushJournal,
                        "preflight-adapter-source-hash-journal"));
            } catch (IllegalStateException | SecurityException ignored) {
                // The journal is advisory. Exact content hashing remains the fallback.
            }
        }
    }

    static Result notRequested() {
        return new Result("", "");
    }

    /** Test/backwards-compatible seam: one process-local default generation. */
    static Result sha256(Path source) {
        return sha256(source, DEFAULT_GENERATION);
    }

    /**
     * Returns the exact archive digest for one live generation.
     *
     * <p>{@code generationOwner} is compared by object identity. Production passes the defining
     * {@link ClassLoader}; null represents the bootstrap loader.
     */
    static Result sha256(Path source, Object generationOwner) {
        CALLS.incrementAndGet();
        if (source == null) {
            return Result.failure("code source is not a local file");
        }
        Path real;
        FileStamp before;
        try {
            real = source.toRealPath();
            before = FileStamp.capture(real);
        } catch (IOException error) {
            return Result.failure("could not inspect code source: " + message(error));
        }
        if (before.size() > MAX_ARCHIVE_BYTES) {
            return Result.failure("code source exceeds " + MAX_ARCHIVE_BYTES + " bytes");
        }

        Object owner = generationOwner == null ? BOOTSTRAP_GENERATION : generationOwner;
        GenerationKey key = new GenerationKey(new IdentityKey(owner), real, before);
        CompletableFuture<Result> proposed = new CompletableFuture<>();
        CompletableFuture<Result> shared = CACHE.putIfAbsent(key, proposed);
        if (shared != null) {
            Result reused = shared.join();
            if (reused.successful()) {
                SESSION_HITS.incrementAndGet();
            }
            return reused;
        }

        Result hashed;
        try {
            hashed = hash(real, before);
            proposed.complete(hashed);
        } catch (RuntimeException | Error failure) {
            proposed.completeExceptionally(failure);
            CACHE.remove(key, proposed);
            throw failure;
        }
        if (!hashed.successful()) {
            CACHE.remove(key, proposed);
            return hashed;
        }

        DISTINCT_GENERATIONS.incrementAndGet();
        ensureJournalLoaded();
        JournalEntry stored = JOURNAL.get(real);
        if (stored != null && before.equals(stored.stamp())
                && hashed.sha256().equals(stored.sha256())) {
            JOURNAL_HITS.incrementAndGet();
        }
        JOURNAL.put(real, new JournalEntry(before, hashed.sha256()));
        journalDirty = true;
        return hashed;
    }

    private static Result hash(Path source, FileStamp before) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            return Result.failure("SHA-256 is unavailable");
        }
        long started = System.nanoTime();
        byte[] buffer = BUFFER.get();
        try (InputStream input = Files.newInputStream(source)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            FileStamp after = FileStamp.capture(source);
            if (!before.equals(after)) {
                return Result.failure("code source changed while it was being hashed");
            }
            HASHES.incrementAndGet();
            HASHED_BYTES.addAndGet(before.size());
            return Result.success(HexFormat.of().formatHex(digest.digest()));
        } catch (IOException error) {
            return Result.failure("could not hash code source: " + message(error));
        } finally {
            HASH_NANOS.addAndGet(System.nanoTime() - started);
        }
    }

    static Map<String, Object> telemetry() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("calls", CALLS.get());
        values.put("sessionHits", SESSION_HITS.get());
        values.put("distinctGenerations", DISTINCT_GENERATIONS.get());
        values.put("journalHits", JOURNAL_HITS.get());
        values.put("hashes", HASHES.get());
        values.put("hashedBytes", HASHED_BYTES.get());
        values.put("hashMillis", HASH_NANOS.get() / 1_000_000L);
        values.put("journalEntries", JOURNAL.size());
        values.put("journalLoadFailures", JOURNAL_LOAD_FAILURES.get());
        values.put("journalWriteFailures", JOURNAL_WRITE_FAILURES.get());
        return values;
    }

    private static void ensureJournalLoaded() {
        if (journalLoaded) {
            return;
        }
        synchronized (SourceArchiveHashes.class) {
            if (journalLoaded) {
                return;
            }
            Path source = journalFile;
            if (source != null && Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                        Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)))) {
                    if (input.readInt() != JOURNAL_MAGIC || input.readInt() != JOURNAL_VERSION) {
                        throw new IOException("adapter source hash journal identity differs");
                    }
                    int count = input.readInt();
                    if (count < 0 || count > MAX_JOURNAL_ENTRIES) {
                        throw new IOException("adapter source hash journal count is invalid");
                    }
                    for (int index = 0; index < count; index++) {
                        Path path = Path.of(input.readUTF()).toAbsolutePath().normalize();
                        FileStamp stamp = new FileStamp(
                                input.readLong(), input.readLong(), input.readUTF());
                        String sha256 = input.readUTF();
                        if (!path.isAbsolute() || !validSha256(sha256)
                                || JOURNAL.putIfAbsent(path, new JournalEntry(stamp, sha256)) != null) {
                            throw new IOException("adapter source hash journal entry is invalid");
                        }
                    }
                    if (input.read() != -1) {
                        throw new IOException("adapter source hash journal has trailing data");
                    }
                } catch (IOException | RuntimeException error) {
                    JOURNAL.clear();
                    JOURNAL_LOAD_FAILURES.incrementAndGet();
                }
            }
            journalLoaded = true;
        }
    }

    private static void flushJournal() {
        Path destination = journalFile;
        if (!journalDirty || destination == null || JOURNAL.isEmpty()) {
            return;
        }
        synchronized (SourceArchiveHashes.class) {
            if (!journalDirty) {
                return;
            }
            Path temporary = null;
            try {
                Files.createDirectories(destination.getParent());
                Map<Path, JournalEntry> snapshot = new TreeMap<>(JOURNAL);
                temporary = Files.createTempFile(destination.getParent(),
                        ".adapter-source-hashes-", ".tmp");
                try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                        Files.newOutputStream(temporary)))) {
                    output.writeInt(JOURNAL_MAGIC);
                    output.writeInt(JOURNAL_VERSION);
                    output.writeInt(snapshot.size());
                    for (Map.Entry<Path, JournalEntry> entry : snapshot.entrySet()) {
                        output.writeUTF(entry.getKey().toString());
                        output.writeLong(entry.getValue().stamp().size());
                        output.writeLong(entry.getValue().stamp().modifiedNanos());
                        output.writeUTF(entry.getValue().stamp().fileKey());
                        output.writeUTF(entry.getValue().sha256());
                    }
                }
                try {
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException unsupported) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                journalDirty = false;
            } catch (IOException | RuntimeException error) {
                JOURNAL_WRITE_FAILURES.incrementAndGet();
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException ignored) {
                        // Best-effort cleanup of an optional journal temporary.
                    }
                }
            }
        }
    }

    private static boolean validSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }

    record Result(String sha256, String problem) {
        Result {
            sha256 = sha256 == null ? "" : sha256;
            problem = problem == null ? "" : problem;
        }

        static Result success(String sha256) {
            return new Result(sha256, "");
        }

        static Result failure(String problem) {
            return new Result("", problem);
        }

        boolean successful() {
            return !sha256.isBlank();
        }
    }

    static void flushJournalForTest() {
        flushJournal();
    }

    private record FileStamp(long size, long modifiedNanos, String fileKey) {
        static FileStamp capture(Path path) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                throw new IOException("code source is not a regular archive file");
            }
            Object key = attributes.fileKey();
            return new FileStamp(
                    attributes.size(),
                    attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS),
                    key == null ? "" : key.toString());
        }
    }

    private record JournalEntry(FileStamp stamp, String sha256) {
    }

    private record GenerationKey(IdentityKey owner, Path path, FileStamp stamp) {
    }

    private static final class IdentityKey {
        private final Object value;
        private final int hashCode;

        private IdentityKey(Object value) {
            this.value = value;
            this.hashCode = System.identityHashCode(value);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof IdentityKey identity && value == identity.value;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}
