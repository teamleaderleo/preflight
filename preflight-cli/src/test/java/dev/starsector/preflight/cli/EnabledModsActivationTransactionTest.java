package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EnabledModsActivationTransactionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void refusesAnExternalInPlaceChangeBeforeSourceCapture() throws Exception {
        Path source = source("beta");
        byte[] reviewed = Files.readAllBytes(source);
        byte[] external = json("zeta");

        assertThrows(EnabledModsActivationTransaction.StaleGenerationException.class, () ->
                EnabledModsActivationTransaction.replace(source, reviewed, json("alpha"),
                        new EnabledModsActivationTransaction.Hook() {
                            @Override
                            public void beforeSourceCapture(Path ignored) throws java.io.IOException {
                                Files.write(source, external);
                            }
                        }));

        assertArrayEquals(external, Files.readAllBytes(source));
        assertNoTransactionArtifacts();
    }

    @Test
    void externalWriterWinsThePublicNameAfterCapture() throws Exception {
        Path source = source("beta");
        byte[] reviewed = Files.readAllBytes(source);
        byte[] external = json("zeta");

        assertThrows(EnabledModsActivationTransaction.StaleGenerationException.class, () ->
                EnabledModsActivationTransaction.replace(source, reviewed, json("alpha"),
                        new EnabledModsActivationTransaction.Hook() {
                            @Override
                            public void beforeTargetPublication(Path target) throws java.io.IOException {
                                Files.write(target, external);
                            }
                        }));

        assertArrayEquals(external, Files.readAllBytes(source));
        assertNoTransactionArtifacts();
    }

    @Test
    void sameSizeSameMtimeExternalReplacementStillWinsTheFinalRace() throws Exception {
        Path source = source("beta");
        byte[] reviewed = Files.readAllBytes(source);
        byte[] external = json("zeta");
        assertEquals(reviewed.length, external.length);
        FileTime reviewedMtime = Files.getLastModifiedTime(source);

        assertThrows(EnabledModsActivationTransaction.StaleGenerationException.class, () ->
                EnabledModsActivationTransaction.replace(source, reviewed, json("alpha"),
                        new EnabledModsActivationTransaction.Hook() {
                            @Override
                            public void beforeTargetPublication(Path target) throws java.io.IOException {
                                Files.write(target, external);
                                Files.setLastModifiedTime(target, reviewedMtime);
                            }
                        }));

        assertArrayEquals(external, Files.readAllBytes(source));
        assertEquals(reviewedMtime, Files.getLastModifiedTime(source));
        assertNoTransactionArtifacts();
    }

    @Test
    void successfulPublicationKeepsTheReviewedPosixPermissions() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path source = source("beta");
        Set<PosixFilePermission> permissions = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ);
        Files.setPosixFilePermissions(source, permissions);
        byte[] reviewed = Files.readAllBytes(source);

        EnabledModsActivationTransaction.Result result =
                EnabledModsActivationTransaction.replace(source, reviewed, json("alpha"));

        assertFalse(result.cleanupPending());
        assertArrayEquals(json("alpha"), Files.readAllBytes(source));
        assertEquals(permissions, Files.getPosixFilePermissions(source));
        assertNoTransactionArtifacts();
    }

    @Test
    void restartRestoresAReviewedGenerationCapturedBeforePublication() throws Exception {
        Path source = source("beta");
        byte[] reviewed = Files.readAllBytes(source);

        assertThrows(SimulatedCrash.class, () ->
                EnabledModsActivationTransaction.replace(source, reviewed, json("alpha"),
                        new EnabledModsActivationTransaction.Hook() {
                            @Override
                            public void afterSourceCapture(Path captured) {
                                throw new SimulatedCrash();
                            }
                        }));
        assertFalse(Files.exists(source));

        assertThrows(EnabledModsActivationTransaction.StaleGenerationException.class,
                () -> EnabledModsActivationTransaction.recover(source));

        assertArrayEquals(reviewed, Files.readAllBytes(source));
        assertNoTransactionArtifacts();
    }

    @Test
    void restartNeverPublishesAnUnprovenCapturedGeneration() throws Exception {
        Path source = source("beta");
        byte[] reviewed = Files.readAllBytes(source);

        assertThrows(SimulatedCrash.class, () ->
                EnabledModsActivationTransaction.replace(source, reviewed, json("alpha"),
                        new EnabledModsActivationTransaction.Hook() {
                            @Override
                            public void afterSourceCapture(Path captured) {
                                throw new SimulatedCrash();
                            }
                        }));
        assertFalse(Files.exists(source));

        Path captured;
        try (var entries = Files.list(temporaryDirectory)) {
            captured = entries
                    .filter(candidate -> candidate.getFileName().toString()
                            .startsWith(".preflight-enabled-mods-txn-"))
                    .filter(candidate -> candidate.getFileName().toString().endsWith("-captured"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("captured activation generation is missing"));
        }
        byte[] corrupted = json("zeta");
        Files.write(captured, corrupted);

        assertThrows(EnabledModsActivationTransaction.StaleGenerationException.class,
                () -> EnabledModsActivationTransaction.recover(source));

        assertFalse(Files.exists(source), "unproven transaction residue must never become public state");
        assertArrayEquals(corrupted, Files.readAllBytes(captured),
                "ambiguous captured evidence must be preserved for inspection/recovery");
        try (var entries = Files.list(temporaryDirectory)) {
            assertTrue(entries.anyMatch(candidate -> candidate.getFileName().toString()
                            .startsWith(".preflight-enabled-mods-txn-")),
                    "ambiguous transaction evidence must remain available");
        }
    }

    @Test
    void restartKeepsAReplacementPublishedBeforeCleanup() throws Exception {
        Path source = source("beta");
        byte[] reviewed = Files.readAllBytes(source);
        byte[] replacement = json("alpha");

        assertThrows(SimulatedCrash.class, () ->
                EnabledModsActivationTransaction.replace(source, reviewed, replacement,
                        new EnabledModsActivationTransaction.Hook() {
                            @Override
                            public void afterTargetPublicationBeforeCleanup(Path target) {
                                throw new SimulatedCrash();
                            }
                        }));
        assertArrayEquals(replacement, Files.readAllBytes(source));

        EnabledModsActivationTransaction.recover(source);

        assertArrayEquals(replacement, Files.readAllBytes(source));
        assertNoTransactionArtifacts();
    }

    private Path source(String mod) throws Exception {
        Path source = temporaryDirectory.resolve("enabled_mods.json");
        Files.write(source, json(mod));
        return source;
    }

    private static byte[] json(String mod) {
        return ("{\"enabledMods\":[\"" + mod + "\"]}\n").getBytes(StandardCharsets.UTF_8);
    }

    private void assertNoTransactionArtifacts() throws Exception {
        try (var entries = Files.list(temporaryDirectory)) {
            assertTrue(entries.noneMatch(path ->
                    path.getFileName().toString().startsWith(".preflight-enabled-mods-txn-")));
        }
    }

    private static final class SimulatedCrash extends Error {
    }
}
