package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Checks real free space at each large preparation write instead of trusting one forecast. */
final class PreparationDiskSpaceGuard {
    private final UsableSpaceSupplier usableSpace;
    private final long reserveBytes;
    private long concurrentlyReservedBytes;

    static PreparationDiskSpaceGuard forCache(Path cacheRoot, long reserveBytes) throws IOException {
        Path existing = nearestExisting(cacheRoot.toAbsolutePath().normalize());
        FileStore store = Files.getFileStore(existing);
        return new PreparationDiskSpaceGuard(store::getUsableSpace, reserveBytes);
    }

    PreparationDiskSpaceGuard(UsableSpaceSupplier usableSpace, long reserveBytes) {
        this.usableSpace = Objects.requireNonNull(usableSpace, "usableSpace");
        if (reserveBytes < 0) {
            throw new IllegalArgumentException("Disk-space reserve cannot be negative");
        }
        this.reserveBytes = reserveBytes;
    }

    synchronized Lease reserve(long writeBytes, String operation) throws IOException {
        if (writeBytes < 0) {
            throw new IllegalArgumentException("Write size cannot be negative");
        }
        long usable = Math.max(0, usableSpace.get());
        long required = Math.addExact(
                Math.addExact(concurrentlyReservedBytes, writeBytes), reserveBytes);
        if (usable < required) {
            throw new IOException("Preparation stopped before " + operation + ": "
                    + PreparationStoragePlanner.humanBytes(required) + " is needed now, with "
                    + PreparationStoragePlanner.humanBytes(usable) + " available");
        }
        concurrentlyReservedBytes = Math.addExact(concurrentlyReservedBytes, writeBytes);
        return new Lease(writeBytes);
    }

    /**
     * Checks a serial exchange write that immediately releases at least the same amount of
     * rebuildable data. The ordinary reserve remains available as headroom during the copy rather
     * than being counted twice as permanently unavailable space.
     */
    synchronized void requireTransient(long writeBytes, String operation) throws IOException {
        if (writeBytes < 0) {
            throw new IllegalArgumentException("Write size cannot be negative");
        }
        long usable = Math.max(0, usableSpace.get());
        long required = Math.addExact(concurrentlyReservedBytes, writeBytes);
        if (usable < required) {
            throw new IOException("Preparation stopped before " + operation + ": "
                    + PreparationStoragePlanner.humanBytes(required) + " is needed now, with "
                    + PreparationStoragePlanner.humanBytes(usable) + " available");
        }
    }

    @FunctionalInterface
    interface UsableSpaceSupplier {
        long get() throws IOException;
    }

    private static Path nearestExisting(Path path) throws IOException {
        Path candidate = path;
        while (candidate != null && !Files.exists(candidate)) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IOException("Could not identify the cache filesystem for " + path);
        }
        return candidate;
    }

    final class Lease implements AutoCloseable {
        private final long bytes;
        private boolean closed;

        private Lease(long bytes) {
            this.bytes = bytes;
        }

        @Override
        public void close() {
            synchronized (PreparationDiskSpaceGuard.this) {
                if (closed) {
                    return;
                }
                concurrentlyReservedBytes -= bytes;
                closed = true;
            }
        }
    }
}
