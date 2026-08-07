package dev.starsector.preflight.cli;

import dev.starsector.preflight.core.Json;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One durable owner for operations that must not overlap.
 *
 * <p>The operating system releases the file lock if the process exits or crashes. The adjacent
 * JSON record is deliberately only descriptive: it lets every entrypoint explain who owns the
 * lock, and lets the next owner report that it recovered an interrupted operation. Correctness
 * comes from the lock rather than PID polling or a frontend-local flag.
 */
final class OperationLease implements AutoCloseable {
    static final String FORMAT = "preflight-operation-v1";
    static final String REMOVE_ALL_OPERATION = "remove-all-data";
    private static final String REMOVAL_MARKER = "removal.pending";

    private final Path ownerFile;
    private final FileChannel channel;
    private final FileLock lock;
    private final String token;
    private boolean closed;

    private OperationLease(Path ownerFile, FileChannel channel, FileLock lock, String token) {
        this.ownerFile = ownerFile;
        this.channel = channel;
        this.lock = lock;
        this.token = token;
    }

    static Acquisition acquire(PreflightHome home, String operation, Path installRoot) throws IOException {
        if (operation == null || !operation.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("Invalid operation name: " + operation);
        }
        Path state = home.state().toAbsolutePath().normalize();
        refuseIncompleteRemoval(state, operation);
        Files.createDirectories(state);
        Path lockFile = state.resolve("operation.lock");
        Path ownerFile = state.resolve("operation.json");
        FileChannel channel = FileChannel.open(
                lockFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        FileLock lock = null;
        try {
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException alreadyHeldHere) {
                // Same-JVM callers are still independent entrypoints and receive the same refusal.
            }
            if (lock == null) {
                Owner owner = readOwner(ownerFile);
                throw new BusyException(owner == null
                        ? "Another Preflight operation is already running."
                        : "Preflight is already " + owner.operation() + " (process " + owner.pid()
                                + ", started " + owner.startedAt() + ").");
            }

            refuseIncompleteRemoval(state, operation);

            Owner recovered = readOwner(ownerFile);
            int recoveredTemporaryFiles = recovered == null
                    ? 0
                    : removeInterruptedTemporaryFiles(home.root(), recovered.pid());
            String token = UUID.randomUUID().toString();
            Owner current = new Owner(
                    operation,
                    ProcessHandle.current().pid(),
                    Instant.now().toString(),
                    installRoot == null ? null : installRoot.toAbsolutePath().normalize(),
                    token);
            writeOwner(ownerFile, current);
            return new Acquisition(
                    new OperationLease(ownerFile, channel, lock, token),
                    recovered,
                    recoveredTemporaryFiles);
        } catch (IOException | RuntimeException failed) {
            if (lock != null) {
                try {
                    lock.release();
                } catch (IOException releaseFailed) {
                    failed.addSuppressed(releaseFailed);
                }
            }
            try {
                channel.close();
            } catch (IOException closeFailed) {
                failed.addSuppressed(closeFailed);
            }
            throw failed;
        }
    }

    static Path removalMarker(PreflightHome home) {
        return home.state().resolve(REMOVAL_MARKER);
    }

    private static void refuseIncompleteRemoval(Path state, String operation) throws BusyException {
        if (!REMOVE_ALL_OPERATION.equals(operation) && Files.exists(state.resolve(REMOVAL_MARKER))) {
            throw new BusyException(
                    "Preflight data removal was interrupted. Re-run `preflight uninstall --purge --yes` to finish it.");
        }
    }

    static Owner readOwner(PreflightHome home) {
        return readOwner(home.state().resolve("operation.json"));
    }

    private static Owner readOwner(Path ownerFile) {
        if (!Files.isRegularFile(ownerFile)) return null;
        try {
            String text = Files.readString(ownerFile, StandardCharsets.UTF_8);
            if (!FORMAT.equals(JsonText.string(text, "format"))) return null;
            String operation = JsonText.string(text, "operation");
            Long pid = JsonText.integer(text, "pid");
            String startedAt = JsonText.string(text, "startedAt");
            String install = JsonText.string(text, "installRoot");
            String token = JsonText.string(text, "token");
            if (operation == null || pid == null || startedAt == null || token == null) return null;
            return new Owner(operation, pid, startedAt, install == null ? null : Path.of(install), token);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static void writeOwner(Path ownerFile, Owner owner) throws IOException {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("format", FORMAT);
        values.put("operation", owner.operation());
        values.put("pid", owner.pid());
        values.put("startedAt", owner.startedAt());
        values.put("installRoot", owner.installRoot());
        values.put("token", owner.token());
        Path temporary = ownerFile.resolveSibling(
                ownerFile.getFileName() + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        try {
            Files.writeString(
                    temporary,
                    Json.object(values) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            try {
                Files.move(temporary, ownerFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, ownerFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Deletes only PID-tagged atomic-write remnants inside Preflight's own root. */
    private static int removeInterruptedTemporaryFiles(Path root, long pid) {
        Path ownedRoot = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(ownedRoot)) return 0;
        String marker = ".tmp-" + pid + "-";
        int removed = 0;
        try (var paths = Files.walk(ownedRoot)) {
            for (Path path : paths.filter(candidate -> candidate.getFileName() != null
                    && candidate.getFileName().toString().contains(marker)).toList()) {
                Path normalized = path.toAbsolutePath().normalize();
                if (!normalized.startsWith(ownedRoot)
                        || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                try {
                    if (Files.deleteIfExists(normalized)) removed++;
                } catch (IOException ignored) {
                    // A leftover is harmless and remains eligible for an explicit cleanup pass.
                }
            }
        } catch (IOException ignored) {
            // Recovery must not turn an inaccessible stale temp file into a startup blocker.
        }
        return removed;
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        try {
            Owner owner = readOwner(ownerFile);
            if (owner != null && token.equals(owner.token())) Files.deleteIfExists(ownerFile);
        } catch (IOException error) {
            failure = error;
        }
        try {
            lock.release();
        } catch (IOException error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        try {
            channel.close();
        } catch (IOException error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        if (failure != null) throw failure;
    }

    record Acquisition(OperationLease lease, Owner recovered, int recoveredTemporaryFiles) {}

    record Owner(String operation, long pid, String startedAt, Path installRoot, String token) {}

    static final class BusyException extends IOException {
        BusyException(String message) {
            super(message);
        }
    }
}
