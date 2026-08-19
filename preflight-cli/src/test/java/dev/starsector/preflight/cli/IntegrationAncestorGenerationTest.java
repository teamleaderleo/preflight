package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IntegrationAncestorGenerationTest {
    @Test
    void preExistingAncestorAliasIsRefusedWithoutExternalWrites(@TempDir Path tempDir) throws Exception {
        Path home = Files.createDirectory(tempDir.resolve("home"));
        Path external = Files.createDirectory(tempDir.resolve("external"));
        Files.createDirectory(external.resolve("bin"));
        Path alias = home.resolve("aliased");
        createDirectoryAlias(alias, external);
        Path target = alias.resolve("bin/preflight");
        PreflightHome.Integration integration = command(target, false);

        assertThrows(IOException.class, () -> IntegrationMutation.reviewForPublication(integration));

        assertDirectoryEmpty(external.resolve("bin"));
    }

    @Test
    void parentReplacementAfterReviewCannotRedirectStaging(@TempDir Path tempDir) throws Exception {
        Path parent = Files.createDirectory(tempDir.resolve("owned-parent"));
        Path reviewedParent = tempDir.resolve("reviewed-parent");
        Path external = Files.createDirectory(tempDir.resolve("external"));
        Path target = parent.resolve("preflight");
        PreflightHome.Integration integration = command(target, false);

        try (IntegrationMutation.Review review = IntegrationMutation.reviewForPublication(integration)) {
            Files.move(parent, reviewedParent);
            createDirectoryAlias(parent, external);

            assertThrows(IOException.class, () -> review.createStagingFile(ownedScript("game"), true));
        }

        assertDirectoryEmpty(external);
        assertDirectoryEmpty(reviewedParent);
    }

    @Test
    void parentReplacementDuringReplacementRestoresOnlyReviewedParent(@TempDir Path tempDir) throws Exception {
        Path parent = Files.createDirectory(tempDir.resolve("owned-parent"));
        Path reviewedParent = tempDir.resolve("reviewed-parent");
        Path external = Files.createDirectory(tempDir.resolve("external"));
        Path target = parent.resolve("preflight");
        String original = ownedScript("old-game");
        Files.writeString(target, original, StandardCharsets.UTF_8);
        AtomicBoolean swapped = new AtomicBoolean();
        PreflightHome.Integration integration = command(target, false);

        try (IntegrationMutation.Review review = IntegrationMutation.reviewForPublication(integration);
                IntegrationMutation.TestHookScope ignored = IntegrationMutation.installTestHook((event, value, path) -> {
                    if (event == IntegrationMutation.Event.AFTER_QUARANTINE
                            && value.id() == PreflightHome.Id.LINUX_COMMAND
                            && swapped.compareAndSet(false, true)) {
                        Files.move(parent, reviewedParent);
                        createDirectoryAlias(parent, external);
                    }
                })) {
            IntegrationMutation.Staging staged = review.createStagingFile(ownedScript("new-game"), true);
            try {
                assertThrows(IOException.class, () -> IntegrationMutation.publish(review, staged));
            } finally {
                IntegrationMutation.deleteStaging(staged);
            }
        }

        assertEquals(original, Files.readString(reviewedParent.resolve("preflight")));
        assertDirectoryEmpty(external);
        assertNoMutationResidue(reviewedParent);
    }

    @Test
    void rollbackAfterParentReplacementStaysInsideReviewedParent(@TempDir Path tempDir) throws Exception {
        Path parent = Files.createDirectory(tempDir.resolve("owned-parent"));
        Path reviewedParent = tempDir.resolve("reviewed-parent");
        Path external = Files.createDirectory(tempDir.resolve("external"));
        Path target = parent.resolve("preflight");
        String original = ownedScript("old-game");
        Files.writeString(target, original, StandardCharsets.UTF_8);
        PreflightHome.Integration integration = command(target, false);

        try (IntegrationMutation.Review review = IntegrationMutation.reviewForPublication(integration)) {
            IntegrationMutation.Staging staged = review.createStagingFile(ownedScript("new-game"), true);
            IntegrationMutation.Publication publication = IntegrationMutation.publish(review, staged);
            Files.move(parent, reviewedParent);
            createDirectoryAlias(parent, external);

            publication.rollback();
        }

        assertEquals(original, Files.readString(reviewedParent.resolve("preflight")));
        assertDirectoryEmpty(external);
        assertNoMutationResidue(reviewedParent);
    }

    @Test
    void parentReplacementDuringRemovalRestoresOnlyReviewedParent(@TempDir Path tempDir) throws Exception {
        Path parent = Files.createDirectory(tempDir.resolve("owned-parent"));
        Path reviewedParent = tempDir.resolve("reviewed-parent");
        Path external = Files.createDirectory(tempDir.resolve("external"));
        Path target = parent.resolve("preflight");
        String original = ownedScript("game");
        Files.writeString(target, original, StandardCharsets.UTF_8);
        AtomicBoolean swapped = new AtomicBoolean();
        PreflightHome.Integration integration = command(target, false);

        try (IntegrationMutation.Review review = IntegrationMutation.reviewForRemoval(integration);
                IntegrationMutation.TestHookScope ignored = IntegrationMutation.installTestHook((event, value, path) -> {
                    if (event == IntegrationMutation.Event.AFTER_REMOVE_QUARANTINE
                            && value.id() == PreflightHome.Id.LINUX_COMMAND
                            && swapped.compareAndSet(false, true)) {
                        Files.move(parent, reviewedParent);
                        createDirectoryAlias(parent, external);
                    }
                })) {
            assertThrows(IOException.class, () -> IntegrationMutation.remove(review));
        }

        assertEquals(original, Files.readString(reviewedParent.resolve("preflight")));
        assertDirectoryEmpty(external);
        assertNoMutationResidue(reviewedParent);
    }

    @Test
    void legacyRetirementUsesReviewedParentGeneration(@TempDir Path tempDir) throws Exception {
        Path parent = Files.createDirectory(tempDir.resolve("legacy-parent"));
        Path reviewedParent = tempDir.resolve("legacy-reviewed-parent");
        Path external = Files.createDirectory(tempDir.resolve("external"));
        Path target = parent.resolve("starsector-preflight");
        String original = ownedScript("game");
        Files.writeString(target, original, StandardCharsets.UTF_8);
        PreflightHome.Integration legacy = command(target, true);
        PreflightHome home = new PreflightHome(tempDir.resolve("state"), List.of(legacy));
        AtomicBoolean swapped = new AtomicBoolean();

        try (IntegrationMutation.TestHookScope ignored = IntegrationMutation.installTestHook((event, value, path) -> {
            if (event == IntegrationMutation.Event.AFTER_REMOVAL_REVIEW
                    && value.id() == PreflightHome.Id.LEGACY_LINUX_COMMAND
                    && swapped.compareAndSet(false, true)) {
                Files.move(parent, reviewedParent);
                createDirectoryAlias(parent, external);
            }
        })) {
            InstallCommand.retireLegacyLaunchers(home);
        }

        assertEquals(original, Files.readString(reviewedParent.resolve("starsector-preflight")));
        assertDirectoryEmpty(external);
        assertNoMutationResidue(reviewedParent);
    }

    @Test
    void oversizedLauncherFileIsRefusedDuringNativeReadAndPreserved(@TempDir Path tempDir) throws Exception {
        Path parent = Files.createDirectory(tempDir.resolve("parent"));
        Path target = parent.resolve("preflight");
        byte[] oversized = new byte[IntegrationParentDirectory.MAX_REVIEW_FILE_BYTES + 1];
        Files.write(target, oversized);
        PreflightHome.Integration integration = command(target, false);

        IOException publicationFailure = assertThrows(
                IOException.class, () -> IntegrationMutation.reviewForPublication(integration));
        IOException removalFailure = assertThrows(
                IOException.class, () -> IntegrationMutation.reviewForRemoval(integration));

        assertTrue(publicationFailure.getMessage().contains("review byte limit"));
        assertTrue(removalFailure.getMessage().contains("review byte limit"));
        assertEquals(oversized.length, Files.size(target));
        assertNoMutationResidue(parent);
    }

    @Test
    void oversizedUnknownDirectoryFileIsRefusedBeforeOwnershipDecision(@TempDir Path tempDir) throws Exception {
        Path parent = Files.createDirectory(tempDir.resolve("parent"));
        Path target = Files.createDirectory(parent.resolve("Preflight"));
        Files.writeString(
                target.resolve("Preflight.cmd"),
                "@echo off\r\n" + IntegrationOwnership.WINDOWS_MARKER + "\r\necho owned\r\n",
                StandardCharsets.UTF_8);
        Path unknown = target.resolve("external.bin");
        byte[] oversized = new byte[IntegrationParentDirectory.MAX_REVIEW_FILE_BYTES + 1];
        Files.write(unknown, oversized);
        PreflightHome.Integration integration = windowsDirectory(target);

        assertThrows(IOException.class, () -> IntegrationMutation.reviewForPublication(integration));
        assertThrows(IOException.class, () -> IntegrationMutation.reviewForRemoval(integration));

        assertEquals(oversized.length, Files.size(unknown));
        assertNoMutationResidue(parent);
    }

    @Test
    void overCountLauncherDirectoryIsRefusedBeforeRetainingUnboundedEntries(@TempDir Path tempDir)
            throws Exception {
        Path parent = Files.createDirectory(tempDir.resolve("parent"));
        Path target = Files.createDirectory(parent.resolve("Preflight"));
        for (int index = 0; index < IntegrationParentDirectory.MAX_REVIEW_TOTAL_ENTRIES; index++) {
            Files.writeString(target.resolve("entry-" + index), "x", StandardCharsets.UTF_8);
        }
        PreflightHome.Integration integration = windowsDirectory(target);

        IOException failure = assertThrows(
                IOException.class, () -> IntegrationMutation.reviewForPublication(integration));

        assertTrue(failure.getMessage().contains("entry"));
        try (var entries = Files.list(target)) {
            assertEquals(IntegrationParentDirectory.MAX_REVIEW_TOTAL_ENTRIES, entries.count());
        }
        assertNoMutationResidue(parent);
    }

    @Test
    void aggregateLauncherSnapshotBytesAreBoundedAcrossSmallFiles(@TempDir Path tempDir) throws Exception {
        Path parent = Files.createDirectory(tempDir.resolve("parent"));
        Path target = Files.createDirectory(parent.resolve("Preflight"));
        int fileBytes = IntegrationParentDirectory.MAX_REVIEW_FILE_BYTES - 1024;
        int fileCount = (IntegrationParentDirectory.MAX_REVIEW_TOTAL_BYTES / fileBytes) + 1;
        for (int index = 0; index < fileCount; index++) {
            Files.write(target.resolve("part-" + index), new byte[fileBytes]);
        }
        PreflightHome.Integration integration = windowsDirectory(target);

        IOException failure = assertThrows(
                IOException.class, () -> IntegrationMutation.reviewForPublication(integration));

        assertTrue(failure.getMessage().contains("review byte limit"));
        for (int index = 0; index < fileCount; index++) {
            assertEquals(fileBytes, Files.size(target.resolve("part-" + index)));
        }
        assertNoMutationResidue(parent);
    }

    private static PreflightHome.Integration command(Path path, boolean legacy) {
        return new PreflightHome.Integration(
                legacy ? PreflightHome.Id.LEGACY_LINUX_COMMAND : PreflightHome.Id.LINUX_COMMAND,
                legacy ? "legacy command" : "command",
                path,
                false,
                legacy);
    }

    private static PreflightHome.Integration windowsDirectory(Path path) {
        return new PreflightHome.Integration(
                PreflightHome.Id.WINDOWS_DIRECTORY,
                "Windows launcher directory",
                path,
                true,
                false);
    }

    private static String ownedScript(String game) {
        return "#!/bin/sh\n" + IntegrationOwnership.POSIX_MARKER
                + "\nexec 'java' -jar 'preflight.jar' run --fast --game '" + game + "' \"$@\"\n";
    }

    private static void createDirectoryAlias(Path alias, Path target) throws IOException {
        if (Platform.current() == Platform.WINDOWS) {
            Process process = new ProcessBuilder(
                    "cmd.exe", "/d", "/c", "mklink", "/J", alias.toString(), target.toString())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit;
            try {
                exit = process.waitFor();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while creating Windows junction for ancestor test", interrupted);
            }
            assertEquals(0, exit, output);
            assertTrue(Files.exists(alias, LinkOption.NOFOLLOW_LINKS), output);
        } else {
            Files.createSymbolicLink(alias, target);
        }
    }

    private static void assertDirectoryEmpty(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            assertTrue(entries.findAny().isEmpty(), "unexpected write beneath " + directory);
        }
    }

    private static void assertNoMutationResidue(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().contains(".preflight-")),
                    "reviewed parent retained mutation residue: " + directory);
        }
    }
}
