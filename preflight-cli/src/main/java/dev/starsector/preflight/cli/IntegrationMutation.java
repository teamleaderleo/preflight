package dev.starsector.preflight.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Commit-boundary ownership primitive for launcher integrations.
 *
 * <p>The reviewed target generation and the opened parent-directory generation form one mutation
 * authority. Staging, quarantine, publication, rollback, cleanup, and removal use only native
 * descriptor/handle-relative operations beneath that authority. Replacing the public parent path
 * therefore either fails the next commit proof or leaves compensation attached to the reviewed
 * parent generation; it never redirects a launcher write into the replacement tree.
 */
final class IntegrationMutation {
    private static final int MAX_OWNERSHIP_TEXT_BYTES = 64 * 1024;
    private static final String MAC_BUNDLE_ID =
            "<key>CFBundleIdentifier</key><string>dev.starsector.preflight.launcher</string>";
    private static final String MAC_EXECUTABLE =
            "<key>CFBundleExecutable</key><string>%s</string>";
    private static volatile TestHook testHook = TestHook.NOOP;

    private IntegrationMutation() {}

    enum Event {
        AFTER_REVIEW,
        AFTER_QUARANTINE,
        BEFORE_PUBLISH,
        AFTER_PUBLISH,
        AFTER_COMMIT,
        BEFORE_ROLLBACK,
        BEFORE_QUARANTINE_CLEANUP,
        BEFORE_STAGING_CLEANUP,
        AFTER_REMOVAL_REVIEW,
        AFTER_REMOVE_QUARANTINE,
        BEFORE_REMOVE_CLEANUP
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
        void prepare(Anchored previous, Staging staged) throws IOException;
    }

    static final class Review implements AutoCloseable {
        private final PreflightHome.Integration integration;
        private final Snapshot snapshot;
        private final IntegrationParentDirectory parent;
        private final String targetName;
        private boolean closed;

        private Review(
                PreflightHome.Integration integration,
                Snapshot snapshot,
                IntegrationParentDirectory parent,
                String targetName) {
            this.integration = integration;
            this.snapshot = snapshot;
            this.parent = parent;
            this.targetName = targetName;
        }

        PreflightHome.Integration integration() { return integration; }
        Snapshot snapshot() { return snapshot; }

        Staging createStagingFile(String content, boolean executable) throws IOException {
            return createStagingFile(content.getBytes(StandardCharsets.UTF_8), executable);
        }

        Staging createStagingFile(byte[] content, boolean executable) throws IOException {
            requireOpen();
            parent.requireCurrent();
            FileAlreadyExistsException lastCollision = null;
            for (int attempt = 0; attempt < 16; attempt++) {
                String candidate = siblingName(targetName, ".preflight-stage-");
                try {
                    parent.createFile(candidate, content, executable);
                } catch (FileAlreadyExistsException collision) {
                    lastCollision = collision;
                    continue;
                }
                Snapshot expected = null;
                try {
                    expected = Snapshot.capture(parent, candidate);
                    parent.requireCurrent();
                    return new Staging(this, candidate, expected);
                } catch (IOException failure) {
                    if (expected != null && expected.present()) {
                        try {
                            expected.deleteExact(parent, candidate);
                        } catch (IOException cleanupFailure) {
                            failure.addSuppressed(cleanupFailure);
                        }
                    }
                    throw failure;
                }
            }
            throw new IOException("Could not create launcher staging file beside " + targetPath(), lastCollision);
        }

        Staging createStagingDirectory() throws IOException {
            requireOpen();
            parent.requireCurrent();
            FileAlreadyExistsException lastCollision = null;
            for (int attempt = 0; attempt < 16; attempt++) {
                String candidate = siblingName(targetName, ".preflight-stage-");
                try {
                    parent.createDirectory(candidate);
                } catch (FileAlreadyExistsException collision) {
                    lastCollision = collision;
                    continue;
                }
                Snapshot expected = null;
                try {
                    expected = Snapshot.capture(parent, candidate);
                    parent.requireCurrent();
                    return new Staging(this, candidate, expected);
                } catch (IOException failure) {
                    if (expected != null && expected.present()) {
                        try {
                            expected.deleteExact(parent, candidate);
                        } catch (IOException cleanupFailure) {
                            failure.addSuppressed(cleanupFailure);
                        }
                    }
                    throw failure;
                }
            }
            throw new IOException("Could not create launcher staging directory beside " + targetPath(), lastCollision);
        }

        private Path targetPath() {
            return normalized(integration.path());
        }

        private Path display(String name) throws IOException {
            return parent.displayPath(name);
        }

        private void requireOpen() throws IOException {
            if (closed) throw new IOException("Launcher integration review is already closed: " + targetPath());
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            if (parent != null) parent.close();
        }
    }

    static final class Staging {
        private final Review review;
        private final String name;
        private Snapshot created;
        private boolean published;

        private Staging(Review review, String name, Snapshot created) {
            this.review = review;
            this.name = name;
            this.created = created;
        }

        Path path() throws IOException { return review.display(name); }

        void createDirectory(String relative) throws IOException {
            review.requireOpen();
            review.parent.requireCurrent();
            try (IntegrationParentDirectory root = review.parent.openDirectory(name)) {
                ensureDirectory(root, relative);
            }
            refreshAndRequireCurrent();
        }

        void createFile(String relative, String content, boolean executable) throws IOException {
            createFile(relative, content.getBytes(StandardCharsets.UTF_8), executable);
        }

        void createFile(String relative, byte[] content, boolean executable) throws IOException {
            review.requireOpen();
            review.parent.requireCurrent();
            RelativePath path = RelativePath.parse(relative);
            try (IntegrationParentDirectory root = review.parent.openDirectory(name);
                    OpenedParent destination = openParent(root, path.parentComponents(), false)) {
                destination.directory().createFile(path.fileName(), content, executable);
            }
            refreshAndRequireCurrent();
        }

        void copyOptionalFile(Anchored source, String relative) throws IOException {
            Entry expected = source.snapshot.entry(relative);
            if (expected == null) return;
            if (expected.kind() != Kind.FILE) {
                throw new IOException("Refusing launcher metadata special entry: " + relative);
            }
            FileRead read = readRelativeFile(review.parent, source.name, relative);
            if (!expected.sameFile(read.info())) {
                throw changed("prepare replacement", review.targetPath(), source.path());
            }
            createFile(relative, read.info().bytes(), read.info().executable());
        }

        private void refreshAndRequireCurrent() throws IOException {
            created = Snapshot.capture(review.parent, name);
            try {
                review.parent.requireCurrent();
            } catch (IOException parentChanged) {
                try {
                    created.deleteExact(review.parent, name);
                } catch (IOException cleanupFailure) {
                    parentChanged.addSuppressed(cleanupFailure);
                }
                throw parentChanged;
            }
        }

        private Snapshot snapshot() throws IOException {
            return Snapshot.capture(review.parent, name);
        }

        private void markPublished() { published = true; }
    }

    static final class Anchored {
        private final Review review;
        private final String name;
        private final Snapshot snapshot;

        private Anchored(Review review, String name, Snapshot snapshot) {
            this.review = review;
            this.name = name;
            this.snapshot = snapshot;
        }

        Path path() throws IOException { return review.display(name); }
    }

    static Review reviewForPublication(PreflightHome.Integration integration) throws IOException {
        Path target = normalized(integration.path());
        Path parentPath = target.getParent();
        if (parentPath == null || target.getFileName() == null) {
            throw new IOException("Launcher integration has no parent directory: " + target);
        }
        IntegrationParentDirectory parent = IntegrationParentDirectory.ensureAndOpen(parentPath);
        boolean keep = false;
        try {
            String targetName = target.getFileName().toString();
            parent.requireCurrent();
            Snapshot reviewed = stableCapture(parent, targetName);
            if (reviewed.present() && !reviewed.provesOwned(integration)) {
                throw new IOException("Refusing to replace an existing path that is not proven Preflight-owned: " + target);
            }
            Review review = new Review(integration, reviewed, parent, targetName);
            fire(Event.AFTER_REVIEW, integration, target);
            keep = true;
            return review;
        } finally {
            if (!keep) parent.close();
        }
    }

    static Review reviewForRemoval(PreflightHome.Integration integration) throws IOException {
        Path target = normalized(integration.path());
        Path parentPath = target.getParent();
        if (parentPath == null || target.getFileName() == null) {
            throw new IOException("Launcher integration has no parent directory: " + target);
        }
        IntegrationParentDirectory parent;
        try {
            parent = IntegrationParentDirectory.open(parentPath);
        } catch (NoSuchFileException missingParent) {
            return new Review(integration, Snapshot.absent(), null, target.getFileName().toString());
        }
        boolean keep = false;
        try {
            String targetName = target.getFileName().toString();
            parent.requireCurrent();
            Snapshot reviewed = stableCapture(parent, targetName);
            if (!reviewed.present()) {
                Review review = new Review(integration, reviewed, parent, targetName);
                keep = true;
                return review;
            }
            if (!reviewed.provesOwned(integration)) {
                throw new IOException("Existing " + integration.label() + " at " + target
                        + " is not proven Preflight-owned and is preserved untouched.");
            }
            Review review = new Review(integration, reviewed, parent, targetName);
            fire(Event.AFTER_REMOVAL_REVIEW, integration, target);
            keep = true;
            return review;
        } finally {
            if (!keep) parent.close();
        }
    }

    static Removal remove(Review review) throws IOException {
        review.requireOpen();
        if (!review.snapshot.present()) return new Removal(review, null, false);
        PreflightHome.Integration integration = review.integration;
        Path target = review.targetPath();
        review.parent.requireCurrent();
        String anchoredName;
        try {
            anchoredName = moveToQuarantine(review, review.targetName);
        } catch (IOException failure) {
            throw new IOException("Refusing to remove launcher integration because its reviewed generation"
                    + " could not be anchored at the commit boundary: " + target, failure);
        }
        Path anchored = review.display(anchoredName);
        fire(Event.AFTER_REMOVE_QUARANTINE, integration, anchored);

        try {
            Snapshot current = Snapshot.capture(review.parent, anchoredName);
            if (!review.snapshot.sameAs(current) || !current.provesOwned(integration)) {
                throw changed("remove", target, anchored);
            }
            review.parent.requireCurrent();
        } catch (IOException verificationFailure) {
            try {
                restoreOrPreserve(review.parent, anchoredName, review.targetName);
            } catch (IOException restoreFailure) {
                verificationFailure.addSuppressed(restoreFailure);
            }
            throw verificationFailure;
        }
        return new Removal(review, anchoredName, true);
    }

    static Publication publish(Review review, Staging staged) throws IOException {
        return publish(review, staged, ReplacementPreparation.NONE);
    }

    static Publication publish(Review review, Staging staged, ReplacementPreparation preparation) throws IOException {
        review.requireOpen();
        if (staged == null || staged.review != review || staged.published) {
            throw new IOException("Launcher staging does not belong to this reviewed parent generation");
        }
        PreflightHome.Integration integration = review.integration;
        Path target = review.targetPath();
        Snapshot stagedReview = staged.snapshot();
        if (!stagedReview.present() || !stagedReview.provesOwned(integration)) {
            throw new IOException("Refusing to publish a launcher integration whose staged ownership cannot be proven: "
                    + staged.path());
        }

        review.parent.requireCurrent();
        String previousName = null;
        Snapshot previousSnapshot = null;
        if (review.snapshot.present()) {
            previousName = moveToQuarantine(review, review.targetName);
            Path previousPath = review.display(previousName);
            fire(Event.AFTER_QUARANTINE, integration, previousPath);
            try {
                previousSnapshot = Snapshot.capture(review.parent, previousName);
                if (!review.snapshot.sameAs(previousSnapshot) || !previousSnapshot.provesOwned(integration)) {
                    throw changed("replace", target, previousPath);
                }
                review.parent.requireCurrent();
            } catch (IOException failure) {
                restoreOrPreserve(review.parent, previousName, review.targetName);
                cleanupStagingOrPreserve(staged, stagedReview, failure);
                throw failure;
            }

            Anchored previous = new Anchored(review, previousName, previousSnapshot);
            try {
                preparation.prepare(previous, staged);
                previousSnapshot = Snapshot.capture(review.parent, previousName);
                if (!review.snapshot.sameAs(previousSnapshot) || !previousSnapshot.provesOwned(integration)) {
                    throw changed("replace", target, previousPath);
                }
                review.parent.requireCurrent();
            } catch (IOException failure) {
                restoreOrPreserve(review.parent, previousName, review.targetName);
                cleanupStagingOrPreserve(staged, stagedReview, failure);
                throw failure;
            }
        }

        Snapshot desired = staged.snapshot();
        if (!desired.present() || !desired.provesOwned(integration)) {
            if (previousName != null) restoreOrPreserve(review.parent, previousName, review.targetName);
            cleanupStagingOrPreserve(staged, stagedReview);
            throw new IOException("Refusing to publish a launcher integration whose staged ownership changed before commit: "
                    + staged.path());
        }

        fire(Event.BEFORE_PUBLISH, integration, target);
        try {
            review.parent.requireCurrent();
            moveNoReplace(review.parent, staged.name, review.targetName);
            staged.markPublished();
        } catch (FileAlreadyExistsException race) {
            IOException failure = collision("publish", target);
            if (previousName != null) restoreSuppressing(review.parent, previousName, review.targetName, failure);
            cleanupStagingOrPreserve(staged, desired, failure);
            throw failure;
        } catch (IOException failure) {
            if (previousName != null) restoreSuppressing(review.parent, previousName, review.targetName, failure);
            cleanupStagingOrPreserve(staged, desired, failure);
            throw failure;
        }
        fire(Event.AFTER_PUBLISH, integration, target);

        try {
            review.parent.requireCurrent();
            Snapshot current = Snapshot.capture(review.parent, review.targetName);
            if (!desired.sameAs(current) || !current.provesOwned(integration)) {
                throw changed("publish", target, previousName == null ? null : review.display(previousName));
            }
        } catch (IOException failure) {
            rollbackUncommittedPublication(review, desired, previousName, failure);
            throw failure;
        }
        return new Publication(review, desired, previousName);
    }

    static void deleteStaging(Staging staged) throws IOException {
        if (staged == null || staged.published) return;
        Review review = staged.review;
        if (review.closed || review.parent == null) return;
        Snapshot current = Snapshot.capture(review.parent, staged.name);
        if (!current.present()) return;
        fire(Event.BEFORE_STAGING_CLEANUP, review.integration, staged.path());
        staged.created.deleteExact(review.parent, staged.name);
    }

    static final class Removal {
        private final Review review;
        private String quarantine;
        private final boolean removed;

        private Removal(Review review, String quarantine, boolean removed) {
            this.review = review;
            this.quarantine = quarantine;
            this.removed = removed;
        }

        boolean removed() { return removed; }

        Path quarantine() throws IOException {
            return quarantine == null || review.parent == null ? null : review.display(quarantine);
        }

        void cleanupCommitted() throws IOException {
            if (!removed || quarantine == null) return;
            String anchored = quarantine;
            fire(Event.BEFORE_REMOVE_CLEANUP, review.integration, review.display(anchored));
            review.snapshot.deleteExact(review.parent, anchored);
            quarantine = null;
        }
    }

    static final class Publication {
        private final Review review;
        private final Snapshot desired;
        private String previous;
        private boolean committed;
        private boolean rolledBack;

        private Publication(Review review, Snapshot desired, String previous) {
            this.review = review;
            this.desired = desired;
            this.previous = previous;
        }

        void commit() throws IOException {
            if (committed || rolledBack) return;
            verifyCurrent();
            committed = true;
            fire(Event.AFTER_COMMIT, review.integration, review.targetPath());
        }

        void cleanupCommitted() throws IOException {
            if (!committed || rolledBack || previous == null) return;
            String quarantined = previous;
            cleanupQuarantine(review, quarantined, review.snapshot);
            previous = null;
        }

        void rollback() throws IOException { rollback(true); }

        void rollbackWithoutRestore() throws IOException { rollback(false); }

        private void rollback(boolean restorePrevious) throws IOException {
            if (rolledBack) return;
            PreflightHome.Integration integration = review.integration;
            Path target = review.targetPath();
            fire(Event.BEFORE_ROLLBACK, integration, target);

            String published;
            try {
                published = moveToQuarantine(review, review.targetName);
            } catch (NoSuchFileException absent) {
                if (restorePrevious && previous != null) {
                    requireExactOwned(review, review.snapshot, previous, "restore previous");
                    try {
                        moveNoReplace(review.parent, previous, review.targetName);
                        previous = null;
                    } catch (FileAlreadyExistsException externalWon) {
                        // A writer filled the public name after the absence proof. It wins.
                    }
                }
                rolledBack = true;
                return;
            }
            Snapshot publishedSnapshot = Snapshot.capture(review.parent, published);
            if (!desired.sameAs(publishedSnapshot) || !publishedSnapshot.provesOwned(integration)) {
                restoreOrPreserve(review.parent, published, review.targetName);
                throw changed("roll back", target, review.display(published));
            }

            if (restorePrevious && previous != null) {
                try {
                    requireExactOwned(review, review.snapshot, previous, "restore previous");
                    moveNoReplace(review.parent, previous, review.targetName);
                    previous = null;
                } catch (FileAlreadyExistsException externalWon) {
                    throw new IOException("A newer external launcher generation won during rollback and was preserved at "
                            + target + "; Preflight generations remain quarantined at " + review.display(published)
                            + (previous == null ? "" : " and " + review.display(previous)));
                } catch (IOException failure) {
                    restoreOrPreserve(review.parent, published, review.targetName);
                    throw failure;
                }
            }
            cleanupQuarantine(review, published, desired);
            rolledBack = true;
        }

        private void verifyCurrent() throws IOException {
            review.parent.requireCurrent();
            Snapshot current = Snapshot.capture(review.parent, review.targetName);
            if (!desired.sameAs(current) || !current.provesOwned(review.integration)) {
                throw changed("commit", review.targetPath(), previous == null ? null : review.display(previous));
            }
        }
    }

    private static Snapshot stableCapture(IntegrationParentDirectory parent, String name) throws IOException {
        Snapshot first = Snapshot.capture(parent, name);
        Snapshot second = Snapshot.capture(parent, name);
        if (!first.sameAs(second)) {
            throw new IOException("Launcher integration changed while it was being reviewed: " + parent.displayPath(name));
        }
        parent.requireCurrent();
        return first;
    }

    private static void rollbackUncommittedPublication(
            Review review, Snapshot desired, String previous, IOException primary) {
        try {
            String published;
            try {
                published = moveToQuarantine(review, review.targetName);
            } catch (NoSuchFileException absent) {
                if (previous != null) restoreOrPreserve(review.parent, previous, review.targetName);
                return;
            }
            Snapshot current = Snapshot.capture(review.parent, published);
            if (desired.sameAs(current) && current.provesOwned(review.integration)) {
                if (previous != null) restoreOrPreserve(review.parent, previous, review.targetName);
                current.deleteExact(review.parent, published);
            } else {
                restoreOrPreserve(review.parent, published, review.targetName);
            }
        } catch (IOException rollbackFailure) {
            primary.addSuppressed(rollbackFailure);
        }
    }

    private static void cleanupStagingOrPreserve(Staging staged, Snapshot expected) throws IOException {
        if (staged.published) return;
        Snapshot current = Snapshot.capture(staged.review.parent, staged.name);
        if (!current.present()) return;
        fire(Event.BEFORE_STAGING_CLEANUP, staged.review.integration, staged.path());
        expected.deleteExact(staged.review.parent, staged.name);
    }

    private static void cleanupStagingOrPreserve(Staging staged, Snapshot expected, IOException primary) {
        try {
            cleanupStagingOrPreserve(staged, expected);
        } catch (IOException cleanupFailure) {
            primary.addSuppressed(cleanupFailure);
        }
    }

    private static void cleanupQuarantine(Review review, String quarantine, Snapshot expected) throws IOException {
        fire(Event.BEFORE_QUARANTINE_CLEANUP, review.integration, review.display(quarantine));
        expected.deleteExact(review.parent, quarantine);
    }

    private static void requireExactOwned(Review review, Snapshot expected, String name, String operation)
            throws IOException {
        Snapshot current = Snapshot.capture(review.parent, name);
        if (!expected.sameAs(current) || !current.provesOwned(review.integration)) {
            throw changed(operation, review.targetPath(), review.display(name));
        }
    }

    private static void restoreSuppressing(
            IntegrationParentDirectory parent, String quarantine, String target, IOException primary) {
        try {
            restoreOrPreserve(parent, quarantine, target);
        } catch (IOException restoreFailure) {
            primary.addSuppressed(restoreFailure);
        }
    }

    private static void restoreOrPreserve(
            IntegrationParentDirectory parent, String quarantine, String target) throws IOException {
        try {
            moveNoReplace(parent, quarantine, target);
        } catch (NoSuchFileException gone) {
            // The quarantined generation was already retired.
        } catch (FileAlreadyExistsException externalWon) {
            // The external generation at the public name wins. The quarantined generation remains
            // available for recovery and never replaces the newcomer.
        }
    }

    private static void moveNoReplace(IntegrationParentDirectory parent, String source, String target)
            throws IOException {
        parent.moveNoReplace(source, target);
    }

    private static String moveToQuarantine(Review review, String source) throws IOException {
        FileAlreadyExistsException lastCollision = null;
        for (int attempt = 0; attempt < 16; attempt++) {
            String candidate = siblingName(source, ".preflight-quarantine-");
            try {
                moveNoReplace(review.parent, source, candidate);
                return candidate;
            } catch (FileAlreadyExistsException collision) {
                lastCollision = collision;
            }
        }
        throw new IOException("Could not anchor launcher generation beside " + review.targetPath(), lastCollision);
    }

    private static String siblingName(String targetName, String infix) {
        String fileName = targetName == null || targetName.isBlank() ? "integration" : targetName;
        return fileName + infix + ProcessHandle.current().pid() + "-" + System.nanoTime();
    }

    private static IOException collision(String operation, Path target) {
        return new IOException("Refusing to " + operation
                + " launcher integration because another writer owns the commit-boundary pathname: " + target);
    }

    private static IOException changed(String operation, Path target, Path quarantine) {
        return new IOException("Refusing to " + operation
                + " launcher integration because the reviewed owned generation changed before commit: " + target
                + (quarantine == null ? "" : "; preserved generation at " + quarantine));
    }

    private static Path normalized(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static void fire(Event event, PreflightHome.Integration integration, Path path) throws IOException {
        testHook.on(event, integration, path);
    }

    private static void ensureDirectory(IntegrationParentDirectory root, String relative) throws IOException {
        RelativePath path = RelativePath.parseDirectory(relative);
        IntegrationParentDirectory current = root;
        List<IntegrationParentDirectory> opened = new ArrayList<>();
        try {
            for (String component : path.components()) {
                IntegrationParentDirectory next = current.tryOpenDirectory(component);
                if (next == null) {
                    try {
                        current.createDirectory(component);
                    } catch (FileAlreadyExistsException collision) {
                        // A peer created the component; opening it below proves it is a real directory.
                    }
                    next = current.openDirectory(component);
                }
                opened.add(next);
                current = next;
            }
        } finally {
            closeReverse(opened);
        }
    }

    private static OpenedParent openParent(
            IntegrationParentDirectory root, List<String> components, boolean createMissing) throws IOException {
        IntegrationParentDirectory current = root;
        List<IntegrationParentDirectory> opened = new ArrayList<>();
        try {
            for (String component : components) {
                IntegrationParentDirectory next = current.tryOpenDirectory(component);
                if (next == null) {
                    if (!createMissing) throw new IOException("Launcher staging directory is missing: " + component);
                    try {
                        current.createDirectory(component);
                    } catch (FileAlreadyExistsException collision) {
                        // Re-open below and prove the peer-created entry is a real directory.
                    }
                    next = current.openDirectory(component);
                }
                opened.add(next);
                current = next;
            }
            return new OpenedParent(current, opened);
        } catch (IOException failure) {
            closeReverse(opened);
            throw failure;
        }
    }

    private static void closeReverse(List<IntegrationParentDirectory> opened) throws IOException {
        IOException failure = null;
        for (int index = opened.size() - 1; index >= 0; index--) {
            try {
                opened.get(index).close();
            } catch (IOException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) throw failure;
    }

    private static FileRead readRelativeFile(
            IntegrationParentDirectory parent, String rootName, String relative) throws IOException {
        RelativePath path = RelativePath.parse(relative);
        try (IntegrationParentDirectory root = parent.openDirectory(rootName);
                OpenedParent source = openParent(root, path.parentComponents(), false)) {
            return new FileRead(source.directory().readFile(path.fileName()));
        }
    }

    private record FileRead(IntegrationParentDirectory.FileInfo info) {}

    private static final class OpenedParent implements AutoCloseable {
        private final IntegrationParentDirectory directory;
        private final List<IntegrationParentDirectory> opened;

        OpenedParent(IntegrationParentDirectory directory, List<IntegrationParentDirectory> opened) {
            this.directory = directory;
            this.opened = opened;
        }

        IntegrationParentDirectory directory() { return directory; }

        @Override public void close() throws IOException { closeReverse(opened); }
    }

    private record RelativePath(List<String> components) {
        static RelativePath parse(String value) throws IOException {
            Path path = Path.of(value).normalize();
            if (path.isAbsolute() || path.getNameCount() < 1 || path.startsWith("..") || path.toString().equals(".")) {
                throw new IOException("Unsafe launcher staging relative path: " + value);
            }
            List<String> components = new ArrayList<>();
            for (Path component : path) {
                String name = component.toString();
                if (name.equals(".") || name.equals("..") || name.isBlank()) {
                    throw new IOException("Unsafe launcher staging relative path: " + value);
                }
                components.add(name);
            }
            return new RelativePath(List.copyOf(components));
        }

        static RelativePath parseDirectory(String value) throws IOException { return parse(value); }

        List<String> parentComponents() {
            return components.subList(0, components.size() - 1);
        }

        String fileName() { return components.get(components.size() - 1); }
    }

    private enum Kind { FILE, DIRECTORY }

    private record Entry(
            String relative,
            Kind kind,
            IntegrationParentDirectory.Identity identity,
            long size,
            String sha256,
            boolean executable,
            byte[] content) {
        Entry {
            content = content == null ? null : content.clone();
        }

        @Override public byte[] content() { return content == null ? null : content.clone(); }

        int depth() {
            if (relative.isEmpty()) return 0;
            int depth = 1;
            for (int index = 0; index < relative.length(); index++) if (relative.charAt(index) == '/') depth++;
            return depth;
        }

        boolean sameFile(IntegrationParentDirectory.FileInfo info) {
            return kind == Kind.FILE
                    && identity.equals(info.identity())
                    && size == info.bytes().length
                    && executable == info.executable()
                    && sha256.equals(Snapshot.sha256(info.bytes()));
        }
    }

    record Snapshot(boolean present, List<Entry> entries) {
        static Snapshot absent() { return new Snapshot(false, List.of()); }

        static Snapshot capture(IntegrationParentDirectory parent, String rootName) throws IOException {
            IntegrationParentDirectory rootDirectory;
            try {
                rootDirectory = parent.tryOpenDirectory(rootName);
            } catch (NoSuchFileException missing) {
                try {
                    IntegrationParentDirectory.FileInfo file = parent.readFile(rootName);
                    return fileSnapshot(file);
                } catch (NoSuchFileException stillMissing) {
                    return absent();
                }
            }
            if (rootDirectory == null) {
                try {
                    return fileSnapshot(parent.readFile(rootName));
                } catch (NoSuchFileException missing) {
                    return absent();
                }
            }
            try (IntegrationParentDirectory root = rootDirectory) {
                List<Entry> entries = new ArrayList<>();
                entries.add(new Entry("", Kind.DIRECTORY, root.identity(), 0, "", false, null));
                captureDirectory(root, "", entries);
                entries.sort(Comparator.comparing(Entry::relative).thenComparing(entry -> entry.kind().name()));
                return new Snapshot(true, List.copyOf(entries));
            }
        }

        private static Snapshot fileSnapshot(IntegrationParentDirectory.FileInfo file) {
            byte[] bytes = file.bytes();
            Entry entry = new Entry("", Kind.FILE, file.identity(), bytes.length, sha256(bytes), file.executable(), bytes);
            return new Snapshot(true, List.of(entry));
        }

        private static void captureDirectory(
                IntegrationParentDirectory directory, String prefix, List<Entry> entries) throws IOException {
            for (String name : directory.listNames()) {
                if (name.equals(".") || name.equals("..")) continue;
                String relative = prefix.isEmpty() ? name : prefix + "/" + name;
                IntegrationParentDirectory child = directory.tryOpenDirectory(name);
                if (child != null) {
                    try (child) {
                        entries.add(new Entry(relative, Kind.DIRECTORY, child.identity(), 0, "", false, null));
                        captureDirectory(child, relative, entries);
                    }
                } else {
                    IntegrationParentDirectory.FileInfo file = directory.readFile(name);
                    byte[] bytes = file.bytes();
                    entries.add(new Entry(
                            relative,
                            Kind.FILE,
                            file.identity(),
                            bytes.length,
                            sha256(bytes),
                            file.executable(),
                            bytes));
                }
            }
        }

        boolean sameAs(Snapshot other) {
            if (other == null || present != other.present || entries.size() != other.entries.size()) return false;
            for (int index = 0; index < entries.size(); index++) {
                Entry left = entries.get(index);
                Entry right = other.entries.get(index);
                if (!left.relative().equals(right.relative())
                        || left.kind() != right.kind()
                        || !left.identity().equals(right.identity())
                        || left.size() != right.size()
                        || !left.sha256().equals(right.sha256())
                        || left.executable() != right.executable()) {
                    return false;
                }
            }
            return true;
        }

        Entry entry(String relative) {
            for (Entry entry : entries) if (entry.relative().equals(relative)) return entry;
            return null;
        }

        boolean provesOwned(PreflightHome.Integration integration) {
            if (!present) return false;
            return switch (integration.id()) {
                case LINUX_COMMAND, LEGACY_LINUX_COMMAND -> ownedPosixCommand();
                case LINUX_DESKTOP_ENTRY, LEGACY_LINUX_DESKTOP_ENTRY -> ownedDesktop();
                case WINDOWS_COMMAND, LEGACY_WINDOWS_COMMAND -> ownedWindowsCommand();
                case WINDOWS_DIRECTORY, LEGACY_WINDOWS_DIRECTORY -> ownedWindowsDirectory(integration.id());
                case MAC_APP, LEGACY_MAC_APP -> ownedMacApp(integration.id());
            };
        }

        private boolean ownedPosixCommand() {
            if (entries.size() != 1) return false;
            Entry file = entry("");
            String text = text(file);
            return file != null && file.kind() == Kind.FILE
                    && text != null && IntegrationOwnership.isPreflightLauncherScript(text);
        }

        private boolean ownedDesktop() {
            if (entries.size() != 1) return false;
            String content = text(entry(""));
            return content != null
                    && content.startsWith("[Desktop Entry]\n")
                    && content.lines().anyMatch(IntegrationOwnership.DESKTOP_MARKER::equals)
                    && content.lines().anyMatch(line -> line.startsWith("Exec="));
        }

        private boolean ownedWindowsCommand() {
            if (entries.size() != 1) return false;
            String content = text(entry(""));
            return content != null && IntegrationOwnership.isPreflightWindowsScript(content);
        }

        private boolean ownedWindowsDirectory(PreflightHome.Id id) {
            Entry root = entry("");
            if (root == null || root.kind() != Kind.DIRECTORY) return false;
            String commandName = id == PreflightHome.Id.LEGACY_WINDOWS_DIRECTORY
                    ? "Starsector Preflight.cmd" : "Preflight.cmd";
            Set<String> allowed = Set.of("", commandName, "desktop.ini", ".DS_Store");
            for (Entry value : entries) {
                if (!allowed.contains(value.relative())) return false;
                if (!value.relative().isEmpty() && value.kind() != Kind.FILE) return false;
            }
            String command = text(entry(commandName));
            return command != null && IntegrationOwnership.isPreflightWindowsScript(command);
        }

        private boolean ownedMacApp(PreflightHome.Id id) {
            Entry root = entry("");
            Entry contents = entry("Contents");
            Entry macos = entry("Contents/MacOS");
            if (root == null || root.kind() != Kind.DIRECTORY
                    || contents == null || contents.kind() != Kind.DIRECTORY
                    || macos == null || macos.kind() != Kind.DIRECTORY) {
                return false;
            }
            String executableName = id == PreflightHome.Id.LEGACY_MAC_APP ? "starsector-preflight" : "preflight";
            String executablePath = "Contents/MacOS/" + executableName;
            Set<String> allowed = new LinkedHashSet<>(List.of(
                    "", "Contents", "Contents/MacOS", "Contents/Info.plist", executablePath,
                    ".DS_Store", "Contents/.DS_Store", "Contents/MacOS/.DS_Store"));
            for (Entry value : entries) {
                if (!allowed.contains(value.relative())) return false;
                if ((value.relative().equals("Contents") || value.relative().equals("Contents/MacOS"))
                        && value.kind() != Kind.DIRECTORY) return false;
                if (!value.relative().isEmpty()
                        && !value.relative().equals("Contents")
                        && !value.relative().equals("Contents/MacOS")
                        && value.kind() != Kind.FILE) return false;
            }
            String plist = text(entry("Contents/Info.plist"));
            Entry executable = entry(executablePath);
            String script = text(executable);
            return plist != null
                    && plist.contains(MAC_BUNDLE_ID)
                    && plist.contains(MAC_EXECUTABLE.formatted(executableName))
                    && executable != null
                    && script != null
                    && IntegrationOwnership.isPreflightLauncherScript(script);
        }

        private static String text(Entry entry) {
            if (entry == null || entry.kind() != Kind.FILE || entry.content() == null
                    || entry.content().length > MAX_OWNERSHIP_TEXT_BYTES) return null;
            return new String(entry.content(), StandardCharsets.UTF_8);
        }

        void deleteExact(IntegrationParentDirectory parent, String rootName) throws IOException {
            Snapshot current = capture(parent, rootName);
            if (!sameAs(current)) {
                throw new IOException("Preserved changed launcher generation instead of deleting it: "
                        + parent.displayPath(rootName));
            }

            List<Entry> files = entries.stream()
                    .filter(entry -> entry.kind() == Kind.FILE)
                    .sorted(Comparator.comparingInt(Entry::depth).reversed())
                    .toList();
            for (Entry expected : files) anchorAndDelete(parent, rootName, expected);

            List<Entry> directories = entries.stream()
                    .filter(entry -> entry.kind() == Kind.DIRECTORY)
                    .sorted(Comparator.comparingInt(Entry::depth).reversed())
                    .toList();
            for (Entry expected : directories) anchorAndDelete(parent, rootName, expected);
        }

        private static void anchorAndDelete(
                IntegrationParentDirectory parent, String rootName, Entry expected) throws IOException {
            EntryLocation location = locate(parent, rootName, expected.relative());
            try (location) {
                String anchored = siblingName(location.name(), ".preflight-delete-");
                try {
                    location.parent().moveNoReplace(location.name(), anchored);
                } catch (IOException failure) {
                    throw new IOException("Preserved launcher generation because an expected entry could not be anchored: "
                            + location.displayPath(), failure);
                }

                try {
                    if (expected.kind() == Kind.FILE) {
                        IntegrationParentDirectory.FileInfo actual = location.parent().readFile(anchored);
                        if (!expected.sameFile(actual)) {
                            restoreOrPreserve(location.parent(), anchored, location.name());
                            throw new IOException("Preserved launcher generation because an entry changed before deletion: "
                                    + location.displayPath());
                        }
                        location.parent().deleteFile(anchored);
                    } else {
                        IntegrationParentDirectory directory = location.parent().openDirectory(anchored);
                        try (directory) {
                            if (!expected.identity().equals(directory.identity()) || !directory.listNames().isEmpty()) {
                                restoreOrPreserve(location.parent(), anchored, location.name());
                                throw new IOException("Preserved launcher directory after unexpected content appeared: "
                                        + location.displayPath());
                            }
                        }
                        location.parent().deleteDirectory(anchored);
                    }
                } catch (IOException failure) {
                    if (location.parent().exists(anchored) && !location.parent().exists(location.name())) {
                        try {
                            restoreOrPreserve(location.parent(), anchored, location.name());
                        } catch (IOException restoreFailure) {
                            failure.addSuppressed(restoreFailure);
                        }
                    }
                    throw failure;
                }
            }
        }

        private static EntryLocation locate(
                IntegrationParentDirectory parent, String rootName, String relative) throws IOException {
            if (relative.isEmpty()) {
                return new EntryLocation(parent, rootName, List.of(), parent.displayPath(rootName));
            }
            RelativePath path = RelativePath.parse(relative);
            List<IntegrationParentDirectory> opened = new ArrayList<>();
            IntegrationParentDirectory current = parent.openDirectory(rootName);
            opened.add(current);
            try {
                for (String component : path.parentComponents()) {
                    current = current.openDirectory(component);
                    opened.add(current);
                }
                return new EntryLocation(
                        current,
                        path.fileName(),
                        opened,
                        parent.displayPath(rootName).resolve(relative));
            } catch (IOException failure) {
                closeReverse(opened);
                throw failure;
            }
        }

        private static String sha256(byte[] bytes) {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
            }
            return HexFormat.of().formatHex(digest.digest(bytes));
        }
    }

    private static final class EntryLocation implements AutoCloseable {
        private final IntegrationParentDirectory parent;
        private final String name;
        private final List<IntegrationParentDirectory> opened;
        private final Path displayPath;

        EntryLocation(
                IntegrationParentDirectory parent,
                String name,
                List<IntegrationParentDirectory> opened,
                Path displayPath) {
            this.parent = parent;
            this.name = name;
            this.opened = opened;
            this.displayPath = displayPath;
        }

        IntegrationParentDirectory parent() { return parent; }
        String name() { return name; }
        Path displayPath() { return displayPath; }
        @Override public void close() throws IOException { closeReverse(opened); }
    }
}
