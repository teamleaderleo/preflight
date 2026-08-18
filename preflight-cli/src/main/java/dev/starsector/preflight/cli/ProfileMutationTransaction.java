package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recoverable exact-generation transactions for destructive named-profile mutations.
 *
 * <p>The transaction directory lives below the profile directory in the reserved
 * {@code .preflight-profile*} namespace, so none of its files can enumerate as a profile. A hard
 * link anchors the source inode and an independent byte snapshot anchors its reviewed contents.
 * The public source pathname is moved into the transaction atomically and then proved against both
 * anchors. This makes the destructive boundary independent of mtime/size and of Preflight's
 * process-local operation lease.
 */
final class ProfileMutationTransaction {
    private static final String PREFIX = ".preflight-profile-txn-";
    private static final String REVIEWED = "reviewed";
    private static final String SNAPSHOT = "snapshot";
    private static final String REPLACEMENT = "replacement";
    private static final String CAPTURED = "captured";
    private static final String COMMITTED = "committed";
    private static final Pattern RECORD_NAME = Pattern.compile("([0-9a-f]{64})\\.json");
    private static final String UUID_PATTERN =
            "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})";
    private static final Pattern RENAME = Pattern.compile(
            "\\.preflight-profile-txn-rename-(\\d{10,17})-([0-9a-f]{64})-([0-9a-f]{64})-"
                    + UUID_PATTERN);
    private static final Pattern DELETE = Pattern.compile(
            "\\.preflight-profile-txn-delete-(\\d{10,17})-([0-9a-f]{64})-" + UUID_PATTERN);
    private static final Hook NOOP = new Hook() {};

    private ProfileMutationTransaction() {
    }

    static RenameResult rename(
            Path profileDirectory,
            Path backupDirectory,
            Path source,
            Path target,
            byte[] replacement,
            GenerationVerifier verifier) throws IOException {
        return rename(profileDirectory, backupDirectory, source, target, replacement, verifier, NOOP);
    }

    static RenameResult rename(
            Path profileDirectory,
            Path backupDirectory,
            Path source,
            Path target,
            byte[] replacement,
            GenerationVerifier verifier,
            Hook hook) throws IOException {
        requireArguments(verifier, hook);
        Path profiles = SafetyArtifactRetention.requireRealDirectory(profileDirectory);
        Path backups = SafetyArtifactRetention.requireRealDirectory(backupDirectory);
        Descriptor descriptor = Descriptor.rename(profiles, source, target);
        Path transaction = createTransaction(descriptor);
        Path reviewed = transaction.resolve(REVIEWED);
        Path snapshot = transaction.resolve(SNAPSHOT);
        Path stagedReplacement = transaction.resolve(REPLACEMENT);
        Path captured = transaction.resolve(CAPTURED);
        boolean sourceCaptured = false;
        boolean committed = false;
        IOException pendingAfterCommit = null;
        try {
            byte[] reviewedBytes = anchorReviewedSource(source, reviewed, snapshot, verifier);
            Files.write(stagedReplacement, replacement, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

            hook.beforeSourceCapture(source);
            atomicCapture(source, captured);
            sourceCaptured = true;
            hook.afterSourceCapture(captured);
            if (!exactCapture(reviewed, snapshot, captured, reviewedBytes)) {
                restoreCapturedOrPreserve(captured, source, backups, descriptor);
                sourceCaptured = false;
                cleanupAbortedRename(transaction, reviewed, snapshot, stagedReplacement);
                throw staleSource();
            }

            hook.beforeTargetPublication(target);
            try {
                Files.createLink(target, stagedReplacement);
            } catch (UnsupportedOperationException unsupported) {
                restoreCapturedOrPreserve(captured, source, backups, descriptor);
                sourceCaptured = false;
                cleanupAbortedRename(transaction, reviewed, snapshot, stagedReplacement);
                throw new IOException("Filesystem cannot publish a renamed profile safely: " + target, unsupported);
            } catch (FileAlreadyExistsException collision) {
                Path conflict = restoreCapturedOrPreserve(captured, source, backups, descriptor);
                sourceCaptured = false;
                cleanupAbortedRename(transaction, reviewed, snapshot, stagedReplacement);
                IOException failure = new IOException("A named profile already exists: " + target.getFileName(), collision);
                if (conflict != null) {
                    failure.addSuppressed(new IOException(
                            "A newer source also won the rollback race; reviewed bytes were preserved at " + conflict));
                }
                throw failure;
            }

            committed = true;
            try {
                writeCommitMarker(transaction);
            } catch (IOException markerFailure) {
                pendingAfterCommit = markerFailure;
            }
            try {
                hook.afterCommit(target);
            } catch (IOException | RuntimeException ancillaryFailure) {
                if (pendingAfterCommit == null) {
                    pendingAfterCommit = asIOException("Post-commit profile hook failed", ancillaryFailure);
                } else {
                    pendingAfterCommit.addSuppressed(ancillaryFailure);
                }
            }
            boolean cleanupPending = !cleanupCommittedRename(transaction, hook);
            if (pendingAfterCommit != null) cleanupPending = true;
            return new RenameResult(cleanupPending);
        } catch (IOException | RuntimeException failure) {
            if (!committed && sourceCaptured) {
                try {
                    restoreCapturedOrPreserve(captured, source, backups, descriptor);
                } catch (IOException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            if (!committed) {
                try {
                    cleanupAbortedRename(transaction, reviewed, snapshot, stagedReplacement);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    static DeleteResult delete(
            Path profileDirectory,
            Path backupDirectory,
            Path source,
            GenerationVerifier verifier) throws IOException {
        return delete(profileDirectory, backupDirectory, source, verifier, NOOP);
    }

    static DeleteResult delete(
            Path profileDirectory,
            Path backupDirectory,
            Path source,
            GenerationVerifier verifier,
            Hook hook) throws IOException {
        requireArguments(verifier, hook);
        Path profiles = SafetyArtifactRetention.requireRealDirectory(profileDirectory);
        Path backups = SafetyArtifactRetention.requireRealDirectory(backupDirectory);
        Descriptor descriptor = Descriptor.delete(profiles, source);
        Path transaction = createTransaction(descriptor);
        Path reviewed = transaction.resolve(REVIEWED);
        Path snapshot = transaction.resolve(SNAPSHOT);
        Path captured = transaction.resolve(CAPTURED);
        Path backup = descriptor.backup(backups);
        boolean sourceCaptured = false;
        boolean committed = false;
        IOException pendingAfterCommit = null;
        try {
            byte[] reviewedBytes = anchorReviewedSource(source, reviewed, snapshot, verifier);

            hook.beforeSourceCapture(source);
            atomicCapture(source, captured);
            sourceCaptured = true;
            hook.afterSourceCapture(captured);
            if (!exactCapture(reviewed, snapshot, captured, reviewedBytes)) {
                restoreCapturedOrPreserve(captured, source, backups, descriptor);
                sourceCaptured = false;
                cleanupAbortedDelete(transaction, reviewed, snapshot);
                throw staleSource();
            }

            try {
                Files.createLink(backup, snapshot);
            } catch (UnsupportedOperationException unsupported) {
                restoreCapturedOrPreserve(captured, source, backups, descriptor);
                sourceCaptured = false;
                cleanupAbortedDelete(transaction, reviewed, snapshot);
                throw new IOException("Filesystem cannot publish a deleted-profile backup safely: " + backup, unsupported);
            } catch (FileAlreadyExistsException collision) {
                restoreCapturedOrPreserve(captured, source, backups, descriptor);
                sourceCaptured = false;
                cleanupAbortedDelete(transaction, reviewed, snapshot);
                throw new IOException("Deleted-profile backup path already exists: " + backup, collision);
            }

            committed = true;
            try {
                writeCommitMarker(transaction);
            } catch (IOException markerFailure) {
                pendingAfterCommit = markerFailure;
            }
            try {
                hook.afterCommit(backup);
            } catch (IOException | RuntimeException ancillaryFailure) {
                if (pendingAfterCommit == null) {
                    pendingAfterCommit = asIOException("Post-commit profile hook failed", ancillaryFailure);
                } else {
                    pendingAfterCommit.addSuppressed(ancillaryFailure);
                }
            }
            boolean cleanupPending = !cleanupCommittedDelete(transaction, hook);
            if (pendingAfterCommit != null) cleanupPending = true;
            return new DeleteResult(backup, cleanupPending);
        } catch (IOException | RuntimeException failure) {
            if (!committed && sourceCaptured) {
                try {
                    restoreCapturedOrPreserve(captured, source, backups, descriptor);
                } catch (IOException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            if (!committed) {
                try {
                    cleanupAbortedDelete(transaction, reviewed, snapshot);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    static void recover(Path profileDirectory, Path backupDirectory) throws IOException {
        Path profiles = profileDirectory.toAbsolutePath().normalize();
        if (!Files.exists(profiles, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(profiles)
                || !Files.isDirectory(profiles, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Named-profile directory is not a real directory: " + profiles);
        }
        List<Path> transactions;
        try (var entries = Files.list(profiles)) {
            transactions = entries
                    .filter(path -> path.getFileName().toString().startsWith(PREFIX))
                    .sorted()
                    .toList();
        }
        if (transactions.isEmpty()) {
            return;
        }
        Path backups = SafetyArtifactRetention.requireRealDirectory(backupDirectory);
        for (Path transaction : transactions) {
            if (Files.isSymbolicLink(transaction)
                    || !Files.isDirectory(transaction, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Interrupted profile transaction is not a real directory: " + transaction);
            }
            Descriptor descriptor = Descriptor.parse(profiles, transaction);
            if (descriptor == null) {
                throw new IOException("Unrecognized interrupted profile transaction: " + transaction);
            }
            recoverOne(descriptor, backups);
        }
    }

    private static void recoverOne(Descriptor descriptor, Path backups) throws IOException {
        Path transaction = descriptor.transaction();
        Path source = descriptor.source();
        Path reviewed = transaction.resolve(REVIEWED);
        Path snapshot = transaction.resolve(SNAPSHOT);
        Path captured = transaction.resolve(CAPTURED);
        Path commit = transaction.resolve(COMMITTED);
        boolean committedMarker = Files.isRegularFile(commit, LinkOption.NOFOLLOW_LINKS);

        if (descriptor.operation() == Operation.RENAME) {
            Path replacement = transaction.resolve(REPLACEMENT);
            Path target = descriptor.target();
            boolean targetIsPublished = sameRegularFile(target, replacement);
            if (!Files.exists(captured, LinkOption.NOFOLLOW_LINKS)) {
                if (committedMarker || targetIsPublished) {
                    cleanupCommittedRename(transaction, NOOP);
                } else {
                    cleanupAbortedRename(transaction, reviewed, snapshot, replacement);
                }
                return;
            }
            boolean exact = exactCapture(reviewed, snapshot, captured, null);
            if (!exact) {
                restoreCapturedOrPreserve(captured, source, backups, descriptor);
                rollbackPublishedTarget(target, replacement, transaction.resolve("rollback-target"), backups, descriptor);
                cleanupAbortedRename(transaction, reviewed, snapshot, replacement);
                throw staleRecovery(descriptor);
            }
            if (committedMarker || targetIsPublished) {
                cleanupCommittedRename(transaction, NOOP);
                return;
            }
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                restoreCapturedOrPreserve(captured, source, backups, descriptor);
                cleanupAbortedRename(transaction, reviewed, snapshot, replacement);
                throw staleRecovery(descriptor);
            }
            Path sourceConflict = restoreCapturedOrPreserve(captured, source, backups, descriptor);
            cleanupAbortedRename(transaction, reviewed, snapshot, replacement);
            String sourceResult = sourceConflict == null
                    ? "The reviewed source was restored under its original name."
                    : "A newer source also exists; the reviewed source bytes were preserved at " + sourceConflict + ".";
            throw new IOException(
                    "Interrupted profile rename found newer external destination state. " + sourceResult
                            + " Review the current profiles before trying again.");
        }

        Path backup = descriptor.backup(backups);
        boolean backupPublished = sameRegularFile(backup, snapshot);
        if (!Files.exists(captured, LinkOption.NOFOLLOW_LINKS)) {
            if (committedMarker || backupPublished) {
                cleanupCommittedDelete(transaction, NOOP);
            } else {
                cleanupAbortedDelete(transaction, reviewed, snapshot);
            }
            return;
        }
        boolean exact = exactCapture(reviewed, snapshot, captured, null);
        if (!exact) {
            restoreCapturedOrPreserve(captured, source, backups, descriptor);
            cleanupAbortedDelete(transaction, reviewed, snapshot);
            throw staleRecovery(descriptor);
        }
        if (committedMarker || backupPublished) {
            cleanupCommittedDelete(transaction, NOOP);
            return;
        }
        if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
            restoreCapturedOrPreserve(captured, source, backups, descriptor);
            cleanupAbortedDelete(transaction, reviewed, snapshot);
            throw staleRecovery(descriptor);
        }
        Path sourceConflict = restoreCapturedOrPreserve(captured, source, backups, descriptor);
        cleanupAbortedDelete(transaction, reviewed, snapshot);
        String sourceResult = sourceConflict == null
                ? "The reviewed source was restored under its original name."
                : "A newer source also exists; the reviewed source bytes were preserved at " + sourceConflict + ".";
        throw new IOException(
                "Interrupted profile delete found newer external backup state. " + sourceResult
                        + " Review the current profiles before trying again.");
    }

    private static byte[] anchorReviewedSource(
            Path source,
            Path reviewed,
            Path snapshot,
            GenerationVerifier verifier) throws IOException {
        ProfileRecordFiles.requireRegularRecord(source);
        try {
            Files.createLink(reviewed, source);
        } catch (UnsupportedOperationException unsupported) {
            throw new IOException("Filesystem cannot anchor a named profile generation safely: " + source, unsupported);
        }
        if (!Files.isRegularFile(reviewed, LinkOption.NOFOLLOW_LINKS)) {
            throw staleSource();
        }
        byte[] bytes = Files.readAllBytes(reviewed);
        verifier.verify(bytes);
        Files.write(snapshot, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return bytes;
    }

    private static boolean exactCapture(
            Path reviewed, Path snapshot, Path captured, byte[] expectedBytes) throws IOException {
        if (!Files.isRegularFile(reviewed, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(snapshot, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(captured, LinkOption.NOFOLLOW_LINKS)
                || !Files.isSameFile(reviewed, captured)) {
            return false;
        }
        byte[] snapshotBytes = expectedBytes == null ? Files.readAllBytes(snapshot) : expectedBytes;
        return Arrays.equals(snapshotBytes, Files.readAllBytes(captured));
    }

    private static void atomicCapture(Path source, Path captured) throws IOException {
        try {
            Files.move(source, captured, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("Filesystem cannot capture a named profile generation atomically: " + source, unsupported);
        }
    }

    private static Path restoreCapturedOrPreserve(
            Path captured, Path source, Path backups, Descriptor descriptor) throws IOException {
        if (!Files.exists(captured, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            Files.createLink(source, captured);
            Files.deleteIfExists(captured);
            return null;
        } catch (FileAlreadyExistsException externalWinner) {
            Path preserved = preserveConflict(captured, backups, descriptor);
            Files.deleteIfExists(captured);
            return preserved;
        } catch (UnsupportedOperationException unsupported) {
            throw new IOException("Filesystem cannot restore a captured profile safely: " + source, unsupported);
        }
    }

    private static void rollbackPublishedTarget(
            Path target,
            Path expected,
            Path quarantine,
            Path backups,
            Descriptor descriptor) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            atomicCapture(target, quarantine);
        } catch (java.nio.file.NoSuchFileException racedAway) {
            return;
        }
        if (sameRegularFile(quarantine, expected)) {
            Files.deleteIfExists(quarantine);
            return;
        }
        restoreCapturedOrPreserve(quarantine, target, backups, descriptor);
    }

    private static Path preserveConflict(Path source, Path backups, Descriptor descriptor) throws IOException {
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Could not preserve non-regular profile recovery artifact: " + source);
        }
        Path destination = backups.resolve(
                "conflicted-profile-" + descriptor.timestamp() + "-" + descriptor.id() + ".json");
        Path staged = Files.createTempFile(
                backups,
                ".conflicted-profile.tmp-" + ProcessHandle.current().pid() + "-",
                "");
        try {
            Files.write(staged, Files.readAllBytes(source), StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.createLink(destination, staged);
            } catch (UnsupportedOperationException unsupported) {
                throw new IOException("Filesystem cannot preserve profile conflict bytes safely: " + destination, unsupported);
            }
            return destination.toAbsolutePath().normalize();
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static void writeCommitMarker(Path transaction) throws IOException {
        Files.writeString(
                transaction.resolve(COMMITTED),
                "committed " + Instant.now() + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    private static boolean cleanupCommittedRename(Path transaction, Hook hook) {
        return deleteForCleanup(hook, transaction.resolve(CAPTURED))
                && deleteForCleanup(hook, transaction.resolve(REVIEWED))
                && deleteForCleanup(hook, transaction.resolve(SNAPSHOT))
                && deleteForCleanup(hook, transaction.resolve(REPLACEMENT))
                && deleteForCleanup(hook, transaction.resolve(COMMITTED))
                && deleteForCleanup(hook, transaction);
    }

    private static boolean cleanupCommittedDelete(Path transaction, Hook hook) {
        return deleteForCleanup(hook, transaction.resolve(CAPTURED))
                && deleteForCleanup(hook, transaction.resolve(REVIEWED))
                && deleteForCleanup(hook, transaction.resolve(SNAPSHOT))
                && deleteForCleanup(hook, transaction.resolve(COMMITTED))
                && deleteForCleanup(hook, transaction);
    }

    private static boolean deleteForCleanup(Hook hook, Path path) {
        try {
            hook.delete(path);
            return true;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static void cleanupAbortedRename(
            Path transaction, Path reviewed, Path snapshot, Path replacement) throws IOException {
        Files.deleteIfExists(transaction.resolve(CAPTURED));
        Files.deleteIfExists(reviewed);
        Files.deleteIfExists(snapshot);
        Files.deleteIfExists(replacement);
        Files.deleteIfExists(transaction.resolve(COMMITTED));
        Files.deleteIfExists(transaction.resolve("rollback-target"));
        Files.deleteIfExists(transaction);
    }

    private static void cleanupAbortedDelete(Path transaction, Path reviewed, Path snapshot) throws IOException {
        Files.deleteIfExists(transaction.resolve(CAPTURED));
        Files.deleteIfExists(reviewed);
        Files.deleteIfExists(snapshot);
        Files.deleteIfExists(transaction.resolve(COMMITTED));
        Files.deleteIfExists(transaction);
    }

    private static Path createTransaction(Descriptor descriptor) throws IOException {
        Files.createDirectory(descriptor.transaction());
        return descriptor.transaction();
    }

    private static boolean sameRegularFile(Path left, Path right) {
        try {
            return Files.isRegularFile(left, LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(right, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSameFile(left, right);
        } catch (IOException | RuntimeException unavailable) {
            return false;
        }
    }

    private static IOException staleSource() {
        return new IOException("Named profile changed at the destructive commit boundary; review it again");
    }

    private static IOException staleRecovery(Descriptor descriptor) {
        return new IOException(
                "Recovered an interrupted " + descriptor.operation().name().toLowerCase(java.util.Locale.ROOT)
                        + " after the source profile changed; review the current profile again");
    }

    private static IOException asIOException(String context, Throwable failure) {
        if (failure instanceof IOException io) return io;
        return new IOException(context + ": " + failure.getMessage(), failure);
    }

    private static void requireArguments(GenerationVerifier verifier, Hook hook) {
        if (verifier == null || hook == null) {
            throw new IllegalArgumentException("Profile generation verifier and mutation hook are required");
        }
    }

    @FunctionalInterface
    interface GenerationVerifier {
        void verify(byte[] bytes) throws IOException;
    }

    interface Hook {
        default void beforeSourceCapture(Path source) throws IOException {
        }

        default void afterSourceCapture(Path captured) throws IOException {
        }

        default void beforeTargetPublication(Path target) throws IOException {
        }

        default void afterCommit(Path publicArtifact) throws IOException {
        }

        default void delete(Path path) throws IOException {
            Files.deleteIfExists(path);
        }
    }

    record RenameResult(boolean cleanupPending) {
    }

    record DeleteResult(Path backup, boolean cleanupPending) {
    }

    private enum Operation {
        RENAME,
        DELETE
    }

    private record Descriptor(
            Operation operation,
            long timestamp,
            String sourceHash,
            String targetHash,
            String id,
            Path transaction,
            Path source,
            Path target) {
        static Descriptor rename(Path profiles, Path source, Path target) throws IOException {
            String sourceHash = recordHash(source);
            String targetHash = recordHash(target);
            long timestamp = Instant.now().toEpochMilli();
            String id = UUID.randomUUID().toString();
            Path transaction = profiles.resolve(PREFIX + "rename-" + timestamp + "-"
                    + sourceHash + "-" + targetHash + "-" + id);
            return new Descriptor(Operation.RENAME, timestamp, sourceHash, targetHash, id,
                    transaction, profiles.resolve(sourceHash + ".json"), profiles.resolve(targetHash + ".json"));
        }

        static Descriptor delete(Path profiles, Path source) throws IOException {
            String sourceHash = recordHash(source);
            long timestamp = Instant.now().toEpochMilli();
            String id = UUID.randomUUID().toString();
            Path transaction = profiles.resolve(PREFIX + "delete-" + timestamp + "-" + sourceHash + "-" + id);
            return new Descriptor(Operation.DELETE, timestamp, sourceHash, null, id,
                    transaction, profiles.resolve(sourceHash + ".json"), null);
        }

        static Descriptor parse(Path profiles, Path transaction) {
            String name = transaction.getFileName().toString();
            Matcher rename = RENAME.matcher(name);
            if (rename.matches()) {
                long timestamp = Long.parseLong(rename.group(1));
                String source = rename.group(2);
                String target = rename.group(3);
                String id = rename.group(4);
                return new Descriptor(Operation.RENAME, timestamp, source, target, id,
                        transaction, profiles.resolve(source + ".json"), profiles.resolve(target + ".json"));
            }
            Matcher delete = DELETE.matcher(name);
            if (delete.matches()) {
                long timestamp = Long.parseLong(delete.group(1));
                String source = delete.group(2);
                String id = delete.group(3);
                return new Descriptor(Operation.DELETE, timestamp, source, null, id,
                        transaction, profiles.resolve(source + ".json"), null);
            }
            return null;
        }

        Path backup(Path backups) {
            return backups.resolve("deleted-profile-" + timestamp + "-" + id + ".json")
                    .toAbsolutePath().normalize();
        }

        private static String recordHash(Path file) throws IOException {
            String name = file.getFileName() == null ? "" : file.getFileName().toString();
            Matcher matcher = RECORD_NAME.matcher(name);
            if (!matcher.matches()) {
                throw new IOException("Profile mutation path is not canonical: " + file);
            }
            return matcher.group(1);
        }
    }
}
