package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Exact-generation compare-and-replace for {@code enabled_mods.json} activation.
 *
 * <p>The transaction binds both dimensions that can race with another mod manager: the reviewed
 * file generation and the reviewed parent-directory generation. A hard-link anchor plus byte
 * snapshot prove the file captured for replacement. {@link ParentBoundDirectory} keeps capture,
 * no-replace publication, rollback, and cleanup attached to one opened parent generation even if
 * the public {@code mods} pathname is renamed or replaced. Every evidence object is a direct
 * randomized sibling in that pinned parent; there are no intermediate path components to swap.
 * Publication is a hard link from the completed staged replacement, so the public file appears
 * complete in one no-replace operation.
 */
final class EnabledModsActivationTransaction {
    private static final String PREFIX = ".preflight-enabled-mods-txn-";
    private static final String REVIEWED = "reviewed";
    private static final String SNAPSHOT = "snapshot";
    private static final String REPLACEMENT = "replacement";
    private static final String CAPTURED = "captured";
    private static final String COMMITTED = "committed";
    private static final Hook NOOP = new Hook() {};

    private EnabledModsActivationTransaction() {}

    static Result replace(Path source, byte[] expected, byte[] replacement) throws IOException {
        return replace(source, expected, replacement, NOOP);
    }

    static Result replace(Path source, byte[] expected, byte[] replacement, Hook hook) throws IOException {
        if (expected == null || replacement == null || hook == null) {
            throw new IllegalArgumentException("Activation generation, replacement, and hook are required");
        }
        Path target = source.toAbsolutePath().normalize();
        recover(target);
        Path parent = requireRealParent(target);
        String sourceName = target.getFileName().toString();
        String transactionName = PREFIX + Instant.now().toEpochMilli() + "-" + UUID.randomUUID();
        String reviewed = artifact(transactionName, REVIEWED);
        String snapshot = artifact(transactionName, SNAPSHOT);
        String stagedReplacement = artifact(transactionName, REPLACEMENT);
        String captured = artifact(transactionName, CAPTURED);
        String committedMarker = artifact(transactionName, COMMITTED);
        int sourceMode = sourceMode(target);
        boolean sourceCaptured = false;
        boolean published = false;
        boolean committed = false;

        try (ParentBoundDirectory directory = ParentBoundDirectory.open(parent)) {
            directory.requireCurrent();
            directory.createDirectory(transactionName);
            try {
                byte[] reviewedBytes = anchorReviewedSource(
                        directory, sourceName, reviewed, snapshot, expected);
                directory.createFile(stagedReplacement, replacement, sourceMode);

                hook.beforeSourceCapture(target);
                requireCurrentOrStale(directory);
                directory.capture(sourceName, captured);
                sourceCaptured = true;
                directory.requireCurrent();
                hook.afterSourceCapture(parent.resolve(captured));
                if (!exactCapture(directory, reviewed, snapshot, captured, reviewedBytes)) {
                    restoreCapturedOrLeaveExternal(directory, captured, sourceName);
                    sourceCaptured = false;
                    cleanupAborted(directory, transactionName);
                    throw staleCommit();
                }

                hook.beforeTargetPublication(target);
                requireCurrentOrStale(directory);
                try {
                    // A hard link is the no-replace publication primitive here. The staged inode is
                    // complete and synced first, and it remains available as an exact post-publication
                    // identity anchor until transaction cleanup.
                    directory.link(stagedReplacement, sourceName);
                    published = true;
                } catch (FileAlreadyExistsException collision) {
                    restoreCapturedOrLeaveExternal(directory, captured, sourceName);
                    sourceCaptured = false;
                    cleanupAborted(directory, transactionName);
                    throw new StaleGenerationException(
                            "The current mod selection changed at the profile-switch commit boundary; review the switch again",
                            collision);
                }

                try {
                    directory.requireCurrent();
                } catch (IOException parentChanged) {
                    rollbackPublishedAfterParentChange(
                            directory, sourceName, stagedReplacement, captured);
                    published = false;
                    sourceCaptured = false;
                    cleanupAborted(directory, transactionName);
                    throw new StaleGenerationException(
                            "The mods directory changed at the profile-switch commit boundary; review the switch again",
                            parentChanged);
                }

                // The post-publication parent proof is the transaction's commit point. A parent
                // rename after this point is a later external mutation, just like a later rewrite of
                // enabled_mods.json itself.
                directory.createFile(
                        committedMarker,
                        ("committed " + Instant.now() + System.lineSeparator())
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        0600);
                committed = true;
                hook.afterTargetPublicationBeforeCleanup(target);
                boolean cleanupPending = !cleanupCommitted(directory, transactionName);
                return new Result(cleanupPending);
            } catch (IOException | RuntimeException failure) {
                if (!committed && sourceCaptured) {
                    try {
                        if (published) {
                            rollbackPublishedAfterParentChange(
                                    directory, sourceName, stagedReplacement, captured);
                        } else {
                            restoreCapturedOrLeaveExternal(directory, captured, sourceName);
                        }
                    } catch (IOException rollbackFailure) {
                        failure.addSuppressed(rollbackFailure);
                    }
                }
                if (!committed) {
                    try {
                        cleanupAborted(directory, transactionName);
                    } catch (IOException cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                throw failure;
            }
        }
    }

    /**
     * Repairs any interrupted activation for an installation while holding the ordinary profile
     * operation lease. The lease keeps a concurrent live activation from being mistaken for crash
     * debris by Desktop bootstrap or a second CLI process.
     */
    static void recoverInterrupted(PreflightHome home, Path installRoot) throws Exception {
        Path root = installRoot.toAbsolutePath().normalize();
        List<Path> pending = pendingSources(root);
        if (pending.isEmpty()) return;
        OperationLease.Acquisition ownership =
                OperationLease.acquire(home, "recovering-profile-switch", root);
        try (OperationLease ignored = ownership.lease()) {
            for (Path source : pendingSources(root)) {
                try {
                    recover(source);
                } catch (StaleGenerationException recovered) {
                    // Recovery already restored or preserved the authoritative public state. The
                    // caller should describe that state rather than failing on retired debris.
                }
            }
        }
    }

    /** Repairs an interrupted activation before a caller consumes {@code enabled_mods.json}. */
    static void recover(Path source) throws IOException {
        Path target = source.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null || !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(parent) || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Enabled-mods parent is not a real directory: " + parent);
        }
        String sourceName = target.getFileName().toString();
        try (ParentBoundDirectory directory = ParentBoundDirectory.open(parent)) {
            directory.requireCurrent();
            List<String> transactions;
            try (var entries = Files.list(parent)) {
                transactions = entries
                        .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.startsWith(PREFIX))
                        .sorted()
                        .toList();
            }
            directory.requireCurrent();
            for (String transaction : transactions) {
                recoverOne(directory, sourceName, transaction);
            }
        }
    }

    private static List<Path> pendingSources(Path root) throws IOException {
        LinkedHashSet<Path> sources = new LinkedHashSet<>();
        for (Path mods : List.of(
                root.resolve("mods"),
                root.resolve("starsector-core").resolve("mods"),
                root.resolve("Contents").resolve("Resources").resolve("Java").resolve("mods"),
                root.resolve("Contents").resolve("Resources").resolve("mods"),
                root.resolve("Contents").resolve("Java").resolve("mods"))) {
            if (hasPending(mods.resolve("enabled_mods.json"))) sources.add(mods.resolve("enabled_mods.json"));
        }
        if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            try (var matches = Files.find(
                    root,
                    7,
                    (path, attributes) -> attributes.isDirectory()
                            && path.getFileName().toString().startsWith(PREFIX)
                            && path.getParent() != null
                            && path.getParent().getFileName().toString().equalsIgnoreCase("mods"))) {
                matches.forEach(transaction -> sources.add(
                        transaction.getParent().resolve("enabled_mods.json").toAbsolutePath().normalize()));
            }
        }
        return List.copyOf(sources);
    }

    private static boolean hasPending(Path source) throws IOException {
        Path parent = source.toAbsolutePath().normalize().getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) return false;
        try (var entries = Files.list(parent)) {
            return entries.anyMatch(path ->
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                            && path.getFileName().toString().startsWith(PREFIX));
        }
    }

    private static void recoverOne(
            ParentBoundDirectory directory,
            String sourceName,
            String transaction) throws IOException {
        String reviewed = artifact(transaction, REVIEWED);
        String snapshot = artifact(transaction, SNAPSHOT);
        String stagedReplacement = artifact(transaction, REPLACEMENT);
        String captured = artifact(transaction, CAPTURED);
        String committed = artifact(transaction, COMMITTED);
        boolean capturedPresent = directory.existsFile(captured);
        if (!capturedPresent) {
            cleanupStrict(directory, transaction);
            return;
        }

        boolean exact = exactCapture(directory, reviewed, snapshot, captured, null);
        boolean targetPresent = directory.existsFile(sourceName);
        boolean replacementPresent = directory.existsFile(stagedReplacement);
        boolean committedPresent = directory.existsFile(committed);

        if (committedPresent) {
            cleanupCommitted(directory, transaction);
            return;
        }
        if (targetPresent && replacementPresent
                && directory.sameFile(sourceName, stagedReplacement)) {
            // Publication completed and the process stopped before its marker. The target itself is
            // durable pre-marker commit evidence because it is the exact staged inode.
            cleanupCommitted(directory, transaction);
            return;
        }
        if (targetPresent) {
            // A newer external target is authoritative. Never resurrect the captured generation.
            cleanupAborted(directory, transaction);
            throw new StaleGenerationException(
                    "Recovered an interrupted profile switch after a newer mod selection won; review it again");
        }

        if (!exact) {
            // The public name is absent and this may be the only surviving pre-commit generation.
            // Keep the ambiguous evidence for inspection/recovery, but never publish it after its
            // reviewed hard-link + byte-snapshot proof failed.
            throw new StaleGenerationException(
                    "Recovered an interrupted profile switch with unproven captured state; review or recover it before retrying");
        }
        restoreCapturedOrLeaveExternal(directory, captured, sourceName);
        cleanupAborted(directory, transaction);
        throw new StaleGenerationException(
                "Recovered an interrupted profile switch before commit; review the current mod selection again");
    }

    private static Path requireRealParent(Path target) throws IOException {
        Path parent = target.getParent();
        if (parent == null
                || Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Enabled-mods parent is not a real directory: " + parent);
        }
        return parent;
    }

    private static byte[] anchorReviewedSource(
            ParentBoundDirectory directory,
            String source,
            String reviewed,
            String snapshot,
            byte[] expected) throws IOException {
        directory.link(source, reviewed);
        byte[] bytes = directory.readFile(reviewed);
        if (!Arrays.equals(expected, bytes)) throw staleCommit();
        directory.createFile(snapshot, bytes, 0600);
        return bytes;
    }

    private static boolean exactCapture(
            ParentBoundDirectory directory,
            String reviewed,
            String snapshot,
            String captured,
            byte[] expectedBytes) throws IOException {
        if (!directory.existsFile(reviewed)
                || !directory.existsFile(snapshot)
                || !directory.existsFile(captured)
                || !directory.sameFile(reviewed, captured)) {
            return false;
        }
        byte[] expected = expectedBytes == null ? directory.readFile(snapshot) : expectedBytes;
        return Arrays.equals(expected, directory.readFile(captured));
    }

    private static void restoreCapturedOrLeaveExternal(
            ParentBoundDirectory directory,
            String captured,
            String target) throws IOException {
        if (!directory.existsFile(captured) || directory.existsFile(target)) return;
        try {
            directory.link(captured, target);
        } catch (FileAlreadyExistsException externalWinner) {
            // The external generation that won the public name remains authoritative.
        }
    }

    private static void rollbackPublishedAfterParentChange(
            ParentBoundDirectory directory,
            String target,
            String stagedReplacement,
            String captured) throws IOException {
        if (directory.existsFile(target)) {
            if (!directory.sameFile(target, stagedReplacement)) {
                // A later external writer already replaced our publication in the reviewed parent.
                return;
            }
            directory.deleteFile(target);
        }
        restoreCapturedOrLeaveExternal(directory, captured, target);
    }

    private static boolean cleanupCommitted(ParentBoundDirectory directory, String transaction) {
        return deleteForCleanup(directory, artifact(transaction, CAPTURED), false)
                && deleteForCleanup(directory, artifact(transaction, REVIEWED), false)
                && deleteForCleanup(directory, artifact(transaction, SNAPSHOT), false)
                && deleteForCleanup(directory, artifact(transaction, REPLACEMENT), false)
                && deleteForCleanup(directory, artifact(transaction, COMMITTED), false)
                && deleteForCleanup(directory, transaction, true);
    }

    private static void cleanupAborted(ParentBoundDirectory directory, String transaction) throws IOException {
        directory.deleteFile(artifact(transaction, CAPTURED));
        directory.deleteFile(artifact(transaction, REVIEWED));
        directory.deleteFile(artifact(transaction, SNAPSHOT));
        directory.deleteFile(artifact(transaction, REPLACEMENT));
        directory.deleteFile(artifact(transaction, COMMITTED));
        directory.deleteDirectory(transaction);
    }

    private static void cleanupStrict(ParentBoundDirectory directory, String transaction) throws IOException {
        cleanupAborted(directory, transaction);
    }

    private static boolean deleteForCleanup(
            ParentBoundDirectory directory,
            String relative,
            boolean directoryEntry) {
        try {
            if (directoryEntry) directory.deleteDirectory(relative);
            else directory.deleteFile(relative);
            return true;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static int sourceMode(Path source) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(source, LinkOption.NOFOLLOW_LINKS);
            int mode = 0;
            if (permissions.contains(PosixFilePermission.OWNER_READ)) mode |= 0400;
            if (permissions.contains(PosixFilePermission.OWNER_WRITE)) mode |= 0200;
            if (permissions.contains(PosixFilePermission.OWNER_EXECUTE)) mode |= 0100;
            if (permissions.contains(PosixFilePermission.GROUP_READ)) mode |= 0040;
            if (permissions.contains(PosixFilePermission.GROUP_WRITE)) mode |= 0020;
            if (permissions.contains(PosixFilePermission.GROUP_EXECUTE)) mode |= 0010;
            if (permissions.contains(PosixFilePermission.OTHERS_READ)) mode |= 0004;
            if (permissions.contains(PosixFilePermission.OTHERS_WRITE)) mode |= 0002;
            if (permissions.contains(PosixFilePermission.OTHERS_EXECUTE)) mode |= 0001;
            return mode;
        } catch (UnsupportedOperationException | IOException unavailable) {
            return 0600;
        }
    }

    private static String artifact(String transaction, String name) {
        return transaction + "-" + name;
    }

    private static void requireCurrentOrStale(ParentBoundDirectory directory)
            throws StaleGenerationException {
        try {
            directory.requireCurrent();
        } catch (IOException parentChanged) {
            throw new StaleGenerationException(
                    "The mods directory changed at the profile-switch commit boundary; review the switch again",
                    parentChanged);
        }
    }

    private static StaleGenerationException staleCommit() {
        return new StaleGenerationException(
                "The current mod selection changed at the profile-switch commit boundary; review the switch again");
    }

    interface Hook {
        default void beforeSourceCapture(Path source) throws IOException {}

        default void afterSourceCapture(Path captured) throws IOException {}

        default void beforeTargetPublication(Path target) throws IOException {}

        default void afterTargetPublicationBeforeCleanup(Path target) throws IOException {}
    }

    record Result(boolean cleanupPending) {}

    static final class StaleGenerationException extends IOException {
        StaleGenerationException(String message) {
            super(message);
        }

        StaleGenerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
