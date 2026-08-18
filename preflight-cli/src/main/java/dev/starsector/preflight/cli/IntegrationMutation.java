package dev.starsector.preflight.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Commit-boundary ownership primitive for launcher integrations.
 *
 * <p>An earlier ownership check is only a review. Replacement first moves the pathname's current
 * generation to a unique sibling quarantine without replacing anything, then proves the moved
 * object is the exact reviewed owned generation. Publication into the now-vacant pathname also
 * uses no-replace semantics. An external writer that wins either pathname race is preserved.
 */
final class IntegrationMutation {
    private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};
    private static volatile TestHook testHook = TestHook.NOOP;

    private IntegrationMutation() {}

    enum Event {
        AFTER_REVIEW,
        AFTER_QUARANTINE,
        BEFORE_PUBLISH,
        AFTER_PUBLISH,
        BEFORE_ROLLBACK,
        BEFORE_QUARANTINE_CLEANUP,
        BEFORE_STAGING_CLEANUP
    }

    @FunctionalInterface
    interface TestHook {
        TestHook NOOP = (event, integration, path) -> {};
        void on(Event event, PreflightHome.Integration integration, Path path) throws IOException;
    }

    static final class TestHookScope implements AutoCloseable {
        private final TestHook previous;
        private boolean closed;

        private TestHookScope(TestHook previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (!closed) {
                testHook = previous;
                closed = true;
            }
        }
    }

    static TestHookScope installTestHook(TestHook hook) {
        TestHook previous = testHook;
        testHook = Objects.requireNonNull(hook);
        return new TestHookScope(previous);
    }

    @FunctionalInterface
    interface ReplacementPreparation {
        ReplacementPreparation NONE = (previous, staged) -> {};
        void prepare(Path previous, Path staged) throws IOException;
    }

    record Review(PreflightHome.Integration integration, Snapshot snapshot) {}

    static Review reviewForPublication(PreflightHome.Integration integration) throws IOException {
        Path target = normalized(integration.path());
        Snapshot reviewed = Snapshot.capture(target);
        if (reviewed.present() && !integration.isOwned()) {
            throw new IOException("Refusing to replace an existing path that is not proven Preflight-owned: " + target);
        }
        fire(Event.AFTER_REVIEW, integration, target);
        return new Review(integration, reviewed);
    }

    static Publication publish(Review review, Path staged) throws IOException {
        return publish(review, staged, ReplacementPreparation.NONE);
    }

    static Publication publish(
            Review review, Path staged, ReplacementPreparation preparation) throws IOException {
        PreflightHome.Integration integration = review.integration();
        Path target = normalized(integration.path());
        Path stagedPath = normalized(staged);
        Path previous = null;

        Snapshot stagedReview = Snapshot.capture(stagedPath);
        if (!stagedReview.present() || !relocated(integration, stagedPath).isOwned()) {
            throw new IOException("Refusing to publish a launcher integration whose staged ownership cannot be proven: "
                    + stagedPath);
        }

        if (review.snapshot().present()) {
            previous = quarantine(target);
            moveNoReplace(target, previous);
            fire(Event.AFTER_QUARANTINE, integration, previous);
            if (!review.snapshot().sameAs(Snapshot.capture(previous))
                    || !relocated(integration, previous).isOwned()) {
                restoreOrPreserve(previous, target);
                cleanupStagingOrPreserve(stagedPath, stagedReview, integration);
                throw changed("replace", target, previous);
            }
            try {
                preparation.prepare(previous, stagedPath);
            } catch (IOException failure) {
                restoreOrPreserve(previous, target);
                cleanupStagingOrPreserve(stagedPath, stagedReview, integration, failure);
                throw failure;
            }
            if (!review.snapshot().sameAs(Snapshot.capture(previous))) {
                restoreOrPreserve(previous, target);
                cleanupStagingOrPreserve(stagedPath, stagedReview, integration);
                throw changed("replace", target, previous);
            }
        }

        Snapshot desired = Snapshot.capture(stagedPath);
        if (!desired.present() || !relocated(integration, stagedPath).isOwned()) {
            if (previous != null) restoreOrPreserve(previous, target);
            cleanupStagingOrPreserve(stagedPath, stagedReview, integration);
            throw new IOException("Refusing to publish a launcher integration whose staged ownership changed before commit: "
                    + stagedPath);
        }

        fire(Event.BEFORE_PUBLISH, integration, target);
        try {
            moveNoReplace(stagedPath, target);
        } catch (FileAlreadyExistsException race) {
            IOException failure = collision("publish", target);
            if (previous != null) {
                try {
                    restoreOrPreserve(previous, target);
                } catch (IOException restoreFailure) {
                    failure.addSuppressed(restoreFailure);
                }
            }
            cleanupStagingOrPreserve(stagedPath, desired, integration, failure);
            throw failure;
        } catch (IOException failure) {
            if (previous != null) {
                try {
                    restoreOrPreserve(previous, target);
                } catch (IOException restoreFailure) {
                    failure.addSuppressed(restoreFailure);
                }
            }
            cleanupStagingOrPreserve(stagedPath, desired, integration, failure);
            throw failure;
        }
        fire(Event.AFTER_PUBLISH, integration, target);

        if (!desired.sameAs(Snapshot.capture(target)) || !integration.isOwned()) {
            if (previous != null && !Files.exists(target, NOFOLLOW)) {
                restoreOrPreserve(previous, target);
            }
            throw changed("publish", target, previous);
        }
        return new Publication(review, desired, previous);
    }

    static Path createStagingDirectory(Path target) throws IOException {
        Path normalized = normalized(target);
        Path parent = normalized.getParent();
        if (parent == null) throw new IOException("Launcher integration has no parent directory: " + normalized);
        for (int attempt = 0; attempt < 16; attempt++) {
            Path candidate = sibling(normalized, ".preflight-stage-");
            try {
                return Files.createDirectory(candidate);
            } catch (FileAlreadyExistsException ignored) {
                // Preserve the collision and choose another sibling.
            }
        }
        throw new IOException("Could not reserve launcher staging directory beside " + normalized);
    }

    static Path createStagingFile(Path target) throws IOException {
        Path normalized = normalized(target);
        Path parent = normalized.getParent();
        if (parent == null) throw new IOException("Launcher integration has no parent directory: " + normalized);
        for (int attempt = 0; attempt < 16; attempt++) {
            Path candidate = sibling(normalized, ".preflight-stage-");
            try {
                return Files.createFile(candidate);
            } catch (FileAlreadyExistsException ignored) {
                // Preserve the collision and choose another sibling.
            }
        }
        throw new IOException("Could not reserve launcher staging file beside " + normalized);
    }

    /**
     * Only an empty staging reservation is safe to discard without an expected generation.
     * Populated residue from an interrupted build is deliberately preserved for recovery.
     */
    static void deleteStaging(Path path) throws IOException {
        if (path == null || !Files.exists(path, NOFOLLOW)) return;
        if (Files.isDirectory(path, NOFOLLOW)) {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                if (children.iterator().hasNext()) return;
            }
            Files.delete(path);
        }
    }

    static final class Publication {
        private final Review review;
        private final Snapshot desired;
        private Path previous;
        private boolean committed;
        private boolean rolledBack;

        private Publication(Review review, Snapshot desired, Path previous) {
            this.review = review;
            this.desired = desired;
            this.previous = previous;
        }

        void commit() throws IOException {
            if (committed || rolledBack) return;
            verifyCurrent();
            if (previous != null) {
                cleanupQuarantine(previous, review.snapshot(), review.integration());
                previous = null;
            }
            verifyCurrent();
            committed = true;
        }

        void rollback() throws IOException {
            if (rolledBack) return;
            PreflightHome.Integration integration = review.integration();
            Path target = normalized(integration.path());
            fire(Event.BEFORE_ROLLBACK, integration, target);

            if (!Files.exists(target, NOFOLLOW)) {
                if (previous != null) {
                    if (!review.snapshot().sameAs(Snapshot.capture(previous))
                            || !relocated(integration, previous).isOwned()) {
                        throw changed("restore previous", target, previous);
                    }
                    moveNoReplace(previous, target);
                    previous = null;
                }
                rolledBack = true;
                return;
            }

            Path published = quarantine(target);
            moveNoReplace(target, published);
            if (!desired.sameAs(Snapshot.capture(published))
                    || !relocated(integration, published).isOwned()) {
                restoreOrPreserve(published, target);
                throw changed("roll back", target, published);
            }

            if (previous != null) {
                if (!review.snapshot().sameAs(Snapshot.capture(previous))
                        || !relocated(integration, previous).isOwned()) {
                    restoreOrPreserve(published, target);
                    throw changed("restore previous", target, previous);
                }
                try {
                    moveNoReplace(previous, target);
                    previous = null;
                } catch (FileAlreadyExistsException externalWon) {
                    throw new IOException("A newer external launcher generation won during rollback and was preserved at "
                            + target + "; Preflight generations remain quarantined at " + published
                            + (previous == null ? "" : " and " + previous));
                }
            }
            cleanupQuarantine(published, desired, integration);
            rolledBack = true;
        }

        private void verifyCurrent() throws IOException {
            PreflightHome.Integration integration = review.integration();
            Path target = normalized(integration.path());
            if (!desired.sameAs(Snapshot.capture(target)) || !integration.isOwned()) {
                throw changed("commit", target, previous);
            }
        }
    }

    private static void cleanupStagingOrPreserve(
            Path staged, Snapshot expected, PreflightHome.Integration integration) throws IOException {
        if (!Files.exists(staged, NOFOLLOW)) return;
        fire(Event.BEFORE_STAGING_CLEANUP, integration, staged);
        expected.deleteExact(staged);
    }

    private static void cleanupStagingOrPreserve(
            Path staged,
            Snapshot expected,
            PreflightHome.Integration integration,
            IOException primary) {
        try {
            cleanupStagingOrPreserve(staged, expected, integration);
        } catch (IOException cleanupFailure) {
            primary.addSuppressed(cleanupFailure);
        }
    }

    private static void cleanupQuarantine(
            Path quarantine, Snapshot expected, PreflightHome.Integration integration) throws IOException {
        fire(Event.BEFORE_QUARANTINE_CLEANUP, integration, quarantine);
        expected.deleteExact(quarantine);
    }

    private static void restoreOrPreserve(Path quarantine, Path target) throws IOException {
        if (!Files.exists(quarantine, NOFOLLOW)) return;
        if (Files.exists(target, NOFOLLOW)) return;
        try {
            moveNoReplace(quarantine, target);
        } catch (FileAlreadyExistsException externalWon) {
            // The external generation at the public pathname wins. The quarantined generation is
            // retained so recovery never overwrites or destroys either side of the race.
        }
    }

    private static void moveNoReplace(Path source, Path target) throws IOException {
        ExclusiveMove.move(source, target);
    }

    private static Path quarantine(Path target) throws IOException {
        Path normalized = normalized(target);
        for (int attempt = 0; attempt < 16; attempt++) {
            Path candidate = sibling(normalized, ".preflight-quarantine-");
            if (!Files.exists(candidate, NOFOLLOW)) return candidate;
        }
        throw new IOException("Could not reserve launcher quarantine beside " + normalized);
    }

    private static Path sibling(Path target, String infix) {
        String fileName = target.getFileName() == null ? "integration" : target.getFileName().toString();
        return target.resolveSibling(fileName + infix + ProcessHandle.current().pid() + "-" + System.nanoTime());
    }

    private static PreflightHome.Integration relocated(PreflightHome.Integration integration, Path path) {
        return new PreflightHome.Integration(
                integration.id(), integration.label(), path, integration.directory(), integration.legacy());
    }

    private static IOException collision(String operation, Path target) {
        return new IOException("Refusing to " + operation
                + " launcher integration because another writer owns the commit-boundary pathname: " + target);
    }

    private static IOException changed(String operation, Path target, Path quarantine) {
        return new IOException("Refusing to " + operation
                + " launcher integration because the reviewed owned generation changed before commit: " + target
                + (quarantine != null && Files.exists(quarantine, NOFOLLOW)
                        ? "; preserved generation at " + quarantine
                        : ""));
    }

    private static Path normalized(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static void fire(Event event, PreflightHome.Integration integration, Path path) throws IOException {
        testHook.on(event, integration, path);
    }

    record Snapshot(boolean present, List<Entry> entries) {
        private enum Kind { FILE, DIRECTORY }

        private record Entry(
                String relative,
                Kind kind,
                long size,
                String sha256,
                String fileKey,
                boolean executable) {
            int depth() {
                if (relative.isEmpty()) return 0;
                int depth = 1;
                for (int index = 0; index < relative.length(); index++) {
                    if (relative.charAt(index) == '/') depth++;
                }
                return depth;
            }

            boolean sameAs(Path path) throws IOException {
                if (!Files.exists(path, NOFOLLOW)) return false;
                BasicFileAttributes attributes = Files.readAttributes(
                        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) return false;
                if (kind == Kind.FILE) {
                    if (!attributes.isRegularFile()) return false;
                    if (attributes.size() != size || Files.isExecutable(path) != executable) return false;
                    if (!Snapshot.sha256(path).equals(sha256)) return false;
                } else if (!attributes.isDirectory()) {
                    return false;
                }
                String currentKey = key(attributes);
                return fileKey == null || currentKey == null || fileKey.equals(currentKey);
            }
        }

        static Snapshot capture(Path path) throws IOException {
            Path root = normalized(path);
            if (!Files.exists(root, NOFOLLOW)) return new Snapshot(false, List.of());
            BasicFileAttributes rootAttributes = Files.readAttributes(
                    root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (rootAttributes.isSymbolicLink() || rootAttributes.isOther()) {
                throw new IOException("Refusing launcher integration because target is a symlink or alias: " + root);
            }
            List<Entry> entries = new ArrayList<>();
            if (rootAttributes.isRegularFile()) {
                entries.add(fileEntry(root, root, rootAttributes));
            } else if (rootAttributes.isDirectory()) {
                entries.add(directoryEntry(root, root, rootAttributes));
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs)
                            throws IOException {
                        if (!directory.equals(root)) {
                            if (!attrs.isDirectory() || attrs.isSymbolicLink()) {
                                throw new IOException("Refusing launcher integration alias in directory: " + directory);
                            }
                            entries.add(directoryEntry(root, directory, attrs));
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (!attrs.isRegularFile() || attrs.isSymbolicLink()) {
                            throw new IOException("Refusing launcher integration special file: " + file);
                        }
                        entries.add(fileEntry(root, file, attrs));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                        throw failure;
                    }
                });
            } else {
                throw new IOException("Refusing launcher integration special file: " + root);
            }
            entries.sort(Comparator.comparing(Entry::relative).thenComparing(entry -> entry.kind().name()));
            return new Snapshot(true, List.copyOf(entries));
        }

        boolean sameAs(Snapshot other) {
            if (present != other.present || entries.size() != other.entries.size()) return false;
            for (int index = 0; index < entries.size(); index++) {
                Entry left = entries.get(index);
                Entry right = other.entries.get(index);
                if (!left.relative().equals(right.relative())
                        || left.kind() != right.kind()
                        || left.size() != right.size()
                        || !left.sha256().equals(right.sha256())
                        || left.executable() != right.executable()) {
                    return false;
                }
                if (left.fileKey() != null && right.fileKey() != null
                        && !left.fileKey().equals(right.fileKey())) {
                    return false;
                }
            }
            return true;
        }

        void deleteExact(Path path) throws IOException {
            Path root = normalized(path);
            Snapshot current = capture(root);
            if (!sameAs(current)) {
                throw new IOException("Preserved changed launcher generation instead of deleting it: " + root);
            }

            List<Entry> files = entries.stream()
                    .filter(entry -> entry.kind() == Kind.FILE)
                    .sorted(Comparator.comparingInt(Entry::depth).reversed())
                    .toList();
            for (Entry entry : files) {
                anchorAndDelete(root, entry);
            }

            List<Entry> directories = entries.stream()
                    .filter(entry -> entry.kind() == Kind.DIRECTORY)
                    .sorted(Comparator.comparingInt(Entry::depth).reversed())
                    .toList();
            for (Entry entry : directories) {
                anchorAndDelete(root, entry);
            }
        }

        private static void anchorAndDelete(Path root, Entry expected) throws IOException {
            Path current = expected.relative().isEmpty()
                    ? root
                    : root.resolve(expected.relative()).normalize();
            Path anchored = quarantine(current);
            try {
                moveNoReplace(current, anchored);
            } catch (IOException failure) {
                throw new IOException("Preserved launcher generation because an expected entry could not be anchored: "
                        + current, failure);
            }

            if (!expected.sameAs(anchored)) {
                restoreOrPreserve(anchored, current);
                throw new IOException("Preserved launcher generation because an entry changed before deletion: " + current);
            }
            if (expected.kind() == Kind.DIRECTORY) {
                try (DirectoryStream<Path> children = Files.newDirectoryStream(anchored)) {
                    if (children.iterator().hasNext()) {
                        restoreOrPreserve(anchored, current);
                        throw new IOException("Preserved launcher directory after unexpected content appeared: " + current);
                    }
                }
            }
            Files.delete(anchored);
        }

        private static Entry directoryEntry(Path root, Path directory, BasicFileAttributes attrs) {
            return new Entry(relative(root, directory), Kind.DIRECTORY, 0, "", key(attrs), false);
        }

        private static Entry fileEntry(Path root, Path file, BasicFileAttributes attrs) throws IOException {
            return new Entry(
                    relative(root, file),
                    Kind.FILE,
                    attrs.size(),
                    sha256(file),
                    key(attrs),
                    Files.isExecutable(file));
        }

        private static String relative(Path root, Path path) {
            if (root.equals(path)) return "";
            return root.relativize(path).toString().replace('\\', '/');
        }

        private static String key(BasicFileAttributes attrs) {
            return attrs.fileKey() == null ? null : attrs.fileKey().toString();
        }

        private static String sha256(Path file) throws IOException {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
            }
            byte[] buffer = new byte[16 * 1024];
            try (InputStream input = Files.newInputStream(file)) {
                for (int read; (read = input.read(buffer)) >= 0; ) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
