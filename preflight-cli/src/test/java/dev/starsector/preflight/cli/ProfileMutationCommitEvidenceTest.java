package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pins recovery to the consumed staged replacement as durable pre-marker publication evidence. */
class ProfileMutationCommitEvidenceTest {
    private static final byte[] REVIEWED = "reviewed-profile".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] REPLACEMENT = "replacement-profile".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] EXTERNAL = "external-profile".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @TempDir
    Path temporaryDirectory;

    @Test
    void updateRecoveryPreservesExternalReplacementAfterPublicCommitBeforeMarker() throws Exception {
        Fixture fixture = fixture();
        assertThrows(IOException.class, () -> ProfileMutationTransaction.update(
                fixture.profiles(), fixture.backups(), fixture.source(), REPLACEMENT,
                this::verifyReviewed,
                replacePublicArtifactThenInterrupt(EXTERNAL)));

        ProfileMutationTransaction.recover(fixture.profiles(), fixture.backups());

        assertArrayEquals(EXTERNAL, Files.readAllBytes(fixture.source()));
        assertNoTransactions(fixture.profiles());
    }

    @Test
    void updateRecoveryDoesNotResurrectReviewedProfileAfterPublicDeletionBeforeMarker() throws Exception {
        Fixture fixture = fixture();
        assertThrows(IOException.class, () -> ProfileMutationTransaction.update(
                fixture.profiles(), fixture.backups(), fixture.source(), REPLACEMENT,
                this::verifyReviewed,
                deletePublicArtifactThenInterrupt()));

        ProfileMutationTransaction.recover(fixture.profiles(), fixture.backups());

        assertFalse(Files.exists(fixture.source()));
        assertNoTransactions(fixture.profiles());
    }

    @Test
    void renameRecoveryPreservesExternalReplacementAfterPublicCommitBeforeMarker() throws Exception {
        Fixture fixture = fixture();
        Path target = fixture.profiles().resolve("cd".repeat(32) + ".json");
        assertThrows(IOException.class, () -> ProfileMutationTransaction.rename(
                fixture.profiles(), fixture.backups(), fixture.source(), target, REPLACEMENT,
                this::verifyReviewed,
                replacePublicArtifactThenInterrupt(EXTERNAL)));

        ProfileMutationTransaction.recover(fixture.profiles(), fixture.backups());

        assertFalse(Files.exists(fixture.source()));
        assertArrayEquals(EXTERNAL, Files.readAllBytes(target));
        assertNoTransactions(fixture.profiles());
    }

    @Test
    void renameRecoveryDoesNotResurrectSourceAfterPublicDeletionBeforeMarker() throws Exception {
        Fixture fixture = fixture();
        Path target = fixture.profiles().resolve("cd".repeat(32) + ".json");
        assertThrows(IOException.class, () -> ProfileMutationTransaction.rename(
                fixture.profiles(), fixture.backups(), fixture.source(), target, REPLACEMENT,
                this::verifyReviewed,
                deletePublicArtifactThenInterrupt()));

        ProfileMutationTransaction.recover(fixture.profiles(), fixture.backups());

        assertFalse(Files.exists(fixture.source()));
        assertFalse(Files.exists(target));
        assertNoTransactions(fixture.profiles());
    }

    private Fixture fixture() throws IOException {
        Path profiles = Files.createDirectories(temporaryDirectory.resolve("profiles"));
        Path backups = Files.createDirectories(temporaryDirectory.resolve("backups"));
        Path source = profiles.resolve("ab".repeat(32) + ".json");
        Files.write(source, REVIEWED, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return new Fixture(profiles, backups, source);
    }

    private void verifyReviewed(byte[] actual) throws IOException {
        if (!Arrays.equals(REVIEWED, actual)) {
            throw new IOException("reviewed profile bytes changed");
        }
    }

    private static ProfileMutationTransaction.Hook replacePublicArtifactThenInterrupt(byte[] external) {
        return new ProfileMutationTransaction.Hook() {
            @Override
            public void afterTargetPublicationBeforeCommit(Path publicArtifact) throws IOException {
                Files.delete(publicArtifact);
                Files.write(publicArtifact, external, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                throw new IOException("simulated interruption after public commit");
            }
        };
    }

    private static ProfileMutationTransaction.Hook deletePublicArtifactThenInterrupt() {
        return new ProfileMutationTransaction.Hook() {
            @Override
            public void afterTargetPublicationBeforeCommit(Path publicArtifact) throws IOException {
                Files.delete(publicArtifact);
                throw new IOException("simulated interruption after public commit");
            }
        };
    }

    private static void assertNoTransactions(Path profiles) throws IOException {
        try (var entries = Files.list(profiles)) {
            assertFalse(entries.anyMatch(path ->
                    path.getFileName().toString().startsWith(".preflight-profile-txn-")));
        }
    }

    private record Fixture(Path profiles, Path backups, Path source) {}
}
