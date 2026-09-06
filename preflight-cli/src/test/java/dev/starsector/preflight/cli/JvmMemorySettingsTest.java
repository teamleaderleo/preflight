package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JvmMemorySettingsTest {
    @TempDir
    Path temporary;

    @Test
    void readsHeapFlagsFromTheSelectedMacLauncher() throws Exception {
        Path root = temporary.resolve("Starsector.app");
        Path launcher = root.resolve("Contents/MacOS/starsector_mac.sh");
        Files.createDirectories(launcher.getParent());
        Files.writeString(launcher, "#!/bin/sh\njava -Xms2g -Xmx6144m game.Main\n");

        JvmMemorySettings.Snapshot snapshot = JvmMemorySettings.inspect(root, target(root, launcher));

        assertTrue(snapshot.available());
        assertTrue(snapshot.editable());
        assertEquals(6144, snapshot.maxHeapMiB());
        assertEquals(2048, snapshot.initialHeapMiB());
        assertEquals(launcher.toRealPath(), snapshot.source());
    }

    @Test
    void followsAFastRenderingResponseFile() throws Exception {
        Path root = temporary.resolve("game");
        Path launcher = root.resolve("fr.sh");
        Path parameters = root.resolve("fr.vmparams");
        Files.createDirectories(root);
        Files.writeString(launcher, "#!/bin/sh\njava @fr.vmparams\n");
        Files.writeString(parameters, "-Xms4096m\n-Xmx6g\n-Djava.system.class.loader=com.genir.renderer\n");

        JvmMemorySettings.Snapshot snapshot = JvmMemorySettings.inspect(root, target(root, launcher));

        assertEquals(6144, snapshot.maxHeapMiB());
        assertEquals(parameters.toRealPath(), snapshot.source());
        assertEquals("launcher response file", snapshot.sourceKind());
    }

    @Test
    void repeatedBranchReferencesUpdateOnlyTheSelectedLaunchersResponseFile() throws Exception {
        Path root = temporary.resolve("branched/game");
        Path launcher = root.resolve("starsector-core/fr.bat");
        Path parameters = launcher.resolveSibling("fr.vmparams");
        Path unrelated = root.resolve("vmparams");
        Files.createDirectories(launcher.getParent());
        Files.writeString(launcher, "if exist PatchLibAgent.jar (\njava -javaagent:PatchLibAgent.jar @fr.vmparams\n"
                + ") else (\njava @./fr.vmparams\n)\n");
        Files.writeString(parameters, "-Xms4g\n-Xmx4g\n");
        Files.writeString(unrelated, "-Xms2g\n-Xmx2g\n");

        var snapshot = JvmMemorySettings.inspect(root, target(root, launcher));
        assertTrue(snapshot.editable());
        assertEquals(4096, snapshot.maxHeapMiB());
        assertEquals(parameters.toRealPath(), snapshot.source());
        JvmMemorySettings.update(root, target(root, launcher), 6144, temporary.resolve("branch-backups"));
        assertEquals(6144, JvmMemorySettings.inspect(root, target(root, launcher)).maxHeapMiB());
        assertEquals("-Xms2g\n-Xmx2g\n", Files.readString(unrelated));
    }

    @Test
    void distinctReferencedHeapsCannotFallBackToOneKnownFilename() throws Exception {
        Path root = temporary.resolve("conflicting/game");
        Path launcher = root.resolve("fr.bat");
        Files.createDirectories(root);
        Files.writeString(launcher, "java @fr.vmparams @alternate.params\n");
        Files.writeString(root.resolve("fr.vmparams"), "-Xmx4g\n");
        Files.writeString(root.resolve("alternate.params"), "-Xmx6g\n");
        var snapshot = JvmMemorySettings.inspect(root, target(root, launcher));
        assertFalse(snapshot.editable());
        assertThrows(IOException.class, () -> JvmMemorySettings.update(
                root, target(root, launcher), 8192, temporary.resolve("conflict-backups")));
        assertEquals("-Xmx4g\n", Files.readString(root.resolve("fr.vmparams")));
        assertEquals("-Xmx6g\n", Files.readString(root.resolve("alternate.params")));
    }

    @Test
    void rejectsLauncherSymlinkOutsideBeforeReadingTargetBytes() throws Exception {
        Path root = temporary.resolve("launcher-link/game");
        Path launcher = root.resolve("starsector.sh");
        Path outside = temporary.resolve("launcher-link/outside.sh");
        Files.createDirectories(root);
        Files.createDirectories(outside.getParent());
        Files.writeString(outside, "#!/bin/sh\njava -Xmx31g game.Main\n");
        if (!symlink(launcher, outside)) return;
        List<Path> opened = new ArrayList<>();

        JvmMemorySettings.Snapshot snapshot = JvmMemorySettings.inspect(
                root,
                target(root, launcher),
                path -> {
                    opened.add(path);
                    return Files.readString(path);
                });

        assertFalse(snapshot.available());
        assertTrue(opened.isEmpty(), "an outside launcher target must never reach the content reader");
    }

    @Test
    void rejectsLexicalResponseFileEscapeBeforeReadingTargetBytes() throws Exception {
        Path root = temporary.resolve("response-escape/game");
        Path launcher = root.resolve("starsector.sh");
        Path outside = temporary.resolve("outside.vmparams");
        Files.createDirectories(root);
        Files.writeString(launcher, "#!/bin/sh\njava @../../outside.vmparams game.Main\n");
        Files.writeString(outside, "-Xmx31g\n");
        List<Path> opened = new ArrayList<>();

        JvmMemorySettings.Snapshot snapshot = JvmMemorySettings.inspect(
                root,
                target(root, launcher),
                path -> {
                    opened.add(path);
                    return Files.readString(path);
                });

        assertFalse(snapshot.available());
        assertEquals(List.of(launcher.toRealPath()), opened);
        assertTrue(snapshot.diagnostics().stream().anyMatch(value -> value.contains("outside")));
    }

    @Test
    void rejectsVmparamsSymlinkOutsideBeforeReadingTargetBytes() throws Exception {
        Path root = temporary.resolve("vmparams-link/game");
        Path launcher = root.resolve("starsector.exe");
        Path parameters = root.resolve("starsector-core/starsector.vmparams");
        Path outside = temporary.resolve("vmparams-link/outside.vmparams");
        Files.createDirectories(parameters.getParent());
        Files.createDirectories(outside.getParent());
        Files.write(launcher, new byte[] {0x4d, 0x5a});
        Files.writeString(outside, "-Xmx31g\n");
        if (!symlink(parameters, outside)) return;
        List<Path> opened = new ArrayList<>();

        JvmMemorySettings.Snapshot snapshot = JvmMemorySettings.inspect(
                root,
                target(root, launcher),
                path -> {
                    opened.add(path);
                    return Files.readString(path);
                });

        assertFalse(snapshot.available());
        assertTrue(opened.isEmpty(), "an outside vmparams target must never reach the content reader");
    }

    @Test
    void findsTheWindowsVmparamsBesideTheGameCore() throws Exception {
        Path root = temporary.resolve("game");
        Path launcher = root.resolve("starsector.exe");
        Path parameters = root.resolve("starsector-core/starsector.vmparams");
        Files.createDirectories(parameters.getParent());
        Files.write(launcher, new byte[] {0x4d, 0x5a});
        Files.writeString(parameters, "-Xms4096m\r\n-Xmx8192m\r\n");

        JvmMemorySettings.Snapshot snapshot = JvmMemorySettings.inspect(root, target(root, launcher));

        assertTrue(snapshot.available());
        assertTrue(snapshot.editable());
        assertEquals(8192, snapshot.maxHeapMiB());
        assertEquals(parameters.toRealPath(), snapshot.source());
        assertEquals("VM-parameter file", snapshot.sourceKind());
    }

    @Test
    void refusesAnAmbiguousLauncherRatherThanGuessingPrecedence() throws Exception {
        Path root = temporary.resolve("game");
        Path launcher = root.resolve("starsector.sh");
        Files.createDirectories(root);
        Files.writeString(launcher, "#!/bin/sh\njava -Xmx4g -Xmx6g game.Main\n");

        JvmMemorySettings.Snapshot snapshot = JvmMemorySettings.inspect(root, target(root, launcher));

        assertTrue(snapshot.available());
        assertFalse(snapshot.editable());
        assertTrue(snapshot.reason().contains("more than one -Xmx"));
    }

    @Test
    void updatesBothHeapBoundsAndKeepsAnExactBackup() throws Exception {
        Path root = temporary.resolve("game");
        Path launcher = root.resolve("starsector.sh");
        Path backups = temporary.resolve("backups");
        Files.createDirectories(root);
        String original = "#!/bin/sh\nexec java -Xms4g -Xmx4g -Xss2m game.Main\n";
        Files.writeString(launcher, original);

        JvmMemorySettings.UpdateResult result = JvmMemorySettings.update(
                root, target(root, launcher), 6144, backups);

        assertTrue(result.changed());
        assertEquals(6144, result.snapshot().maxHeapMiB());
        assertEquals(6144, result.snapshot().initialHeapMiB());
        assertTrue(Files.readString(launcher).contains("-Xms6144m -Xmx6144m"));
        assertNotNull(result.backup());
        assertEquals(original, Files.readString(result.backup()));
    }

    @Test
    void refusesSameSizeExternalEditBeforePublishing() throws Exception {
        Path root = temporary.resolve("external-edit/game");
        Path launcher = root.resolve("starsector.sh");
        Path backups = temporary.resolve("external-edit/backups");
        Files.createDirectories(root);
        String original = "#!/bin/sh\nexec java -Xmx4g game.Main\n";
        String external = "#!/bin/sh\nexec java -Xmx5g game.Main\n";
        assertEquals(original.length(), external.length());
        Files.writeString(launcher, original);

        IOException failure = assertThrows(IOException.class, () -> JvmMemorySettings.update(
                root,
                target(root, launcher),
                6144,
                backups,
                source -> Files.writeString(source, external),
                source -> {}));

        assertTrue(failure.getMessage().contains("changed while they were being reviewed"), failure::getMessage);
        assertEquals(external, Files.readString(launcher));
        assertFalse(Files.exists(backups), "a refused pre-publication update should not retain a backup");
    }

    @Test
    void finalRereadRefusesAContentChangeWithTheSameSizeAndTimestamp() throws Exception {
        Path root = temporary.resolve("final-reread/game");
        Path launcher = root.resolve("starsector.sh");
        Path backups = temporary.resolve("final-reread/backups");
        Files.createDirectories(root);
        String original = "#!/bin/sh\nexec java -Xmx4g game.Main\n";
        String external = "#!/bin/sh\nexec java -Xmx5g game.Main\n";
        assertEquals(original.length(), external.length());
        Files.writeString(launcher, original);
        FileTime originalTime = Files.getLastModifiedTime(launcher);

        IOException failure = assertThrows(IOException.class, () -> JvmMemorySettings.update(
                root,
                target(root, launcher),
                6144,
                backups,
                source -> {},
                source -> {
                    Files.delete(source);
                    Files.writeString(source, external);
                    Files.setLastModifiedTime(source, originalTime);
                },
                source -> {},
                false));

        assertTrue(failure.getMessage().contains("changed while they were being reviewed"), failure::getMessage);
        assertEquals(external, Files.readString(launcher));
        try (var files = Files.list(backups)) {
            assertEquals(0, files.count(), "a final-check refusal should delete its unused backup");
        }
    }

    @Test
    void fallbackRereadsAgainImmediatelyBeforeReplacement() throws Exception {
        Path root = temporary.resolve("fallback-reread/game");
        Path launcher = root.resolve("starsector.sh");
        Path backups = temporary.resolve("fallback-reread/backups");
        Files.createDirectories(root);
        String original = String.join(System.lineSeparator(),
                "#!/bin/sh", "exec java -Xmx4g game.Main", "");
        String external = String.join(System.lineSeparator(),
                "#!/bin/sh", "exec java -Xmx5g game.Main", "");
        Files.writeString(launcher, original);
        AtomicInteger checks = new AtomicInteger();

        IOException failure = assertThrows(IOException.class, () -> JvmMemorySettings.update(
                root,
                target(root, launcher),
                6144,
                backups,
                source -> {},
                source -> {
                    if (checks.incrementAndGet() == 2) {
                        Files.writeString(source, external);
                    }
                },
                source -> {},
                true));

        assertEquals(2, checks.get());
        assertTrue(
                failure.getMessage().contains("changed while they were being reviewed"),
                failure::getMessage);
        assertEquals(external, Files.readString(launcher));
        try (var files = Files.list(backups)) {
            assertEquals(0, files.count(),
                    "a fallback-check refusal should delete its unused backup");
        }
    }
    @Test
    void publishesAndVerifiesThroughTheReplaceFallback() throws Exception {
        Path root = temporary.resolve("replace-fallback/game");
        Path launcher = root.resolve("starsector.sh");
        Path backups = temporary.resolve("replace-fallback/backups");
        Files.createDirectories(root);
        String original = "#!/bin/sh\nexec java -Xms4g -Xmx4g game.Main\n";
        Files.writeString(launcher, original);

        JvmMemorySettings.UpdateResult result = JvmMemorySettings.update(
                root,
                target(root, launcher),
                6144,
                backups,
                source -> {},
                source -> {},
                source -> {},
                true);

        assertTrue(result.changed());
        assertEquals(6144, result.snapshot().maxHeapMiB());
        assertEquals(6144, result.snapshot().initialHeapMiB());
        assertTrue(Files.readString(launcher).contains("-Xms6144m -Xmx6144m"));
        assertEquals(original, Files.readString(result.backup()));
    }

    @Test
    void resultingStateVerificationPreservesAnExternalPostPublicationEdit() throws Exception {
        Path root = temporary.resolve("verify-result/game");
        Path launcher = root.resolve("starsector.sh");
        Path backups = temporary.resolve("verify-result/backups");
        Files.createDirectories(root);
        String original = "#!/bin/sh\nexec java -Xms4g -Xmx4g game.Main\n";
        String external = "#!/bin/sh\nexec java -Xms7g -Xmx7g game.Main\n";
        Files.writeString(launcher, original);

        IOException failure = assertThrows(IOException.class, () -> JvmMemorySettings.update(
                root,
                target(root, launcher),
                6144,
                backups,
                source -> {},
                source -> {},
                source -> Files.writeString(source, external),
                false));

        assertTrue(failure.getMessage().contains("didn't report the requested heap"), failure::getMessage);
        assertTrue(failure.getSuppressed().length >= 1, "guarded rollback should record its refusal");
        assertEquals(external, Files.readString(launcher));
        assertEquals(original, Files.readString(onlyFile(backups)));
    }

    @Test
    void refusesSymlinkEntrySwapBeforePublishingWhenSupported() throws Exception {
        Path root = temporary.resolve("entry-swap/game");
        Path launcher = root.resolve("starsector.sh");
        Path alternate = root.resolve("alternate.vmparams");
        Path backups = temporary.resolve("entry-swap/backups");
        Files.createDirectories(root);
        String original = "#!/bin/sh\nexec java -Xmx4g game.Main\n";
        Files.writeString(launcher, original);
        Files.writeString(alternate, original);
        Path probe = root.resolve("symlink-probe");
        try {
            Files.createSymbolicLink(probe, alternate.getFileName());
            Files.delete(probe);
        } catch (UnsupportedOperationException | IOException unsupported) {
            return;
        }

        assertThrows(IOException.class, () -> JvmMemorySettings.update(
                root,
                target(root, launcher),
                6144,
                backups,
                source -> {
                    Files.delete(source);
                    Files.createSymbolicLink(source, alternate.getFileName());
                },
                source -> {}));

        assertTrue(Files.isSymbolicLink(launcher));
        assertEquals(original, Files.readString(alternate));
        assertFalse(Files.exists(backups), "a refused entry replacement should not retain a backup");
    }

    @Test
    void postPublicationFailureRollsBackOnlyPreflightsPublishedBytes() throws Exception {
        Path root = temporary.resolve("rollback/game");
        Path launcher = root.resolve("starsector.sh");
        Path backups = temporary.resolve("rollback/backups");
        Files.createDirectories(root);
        String original = "#!/bin/sh\nexec java -Xms4g -Xmx4g game.Main\n";
        Files.writeString(launcher, original);

        IOException failure = assertThrows(IOException.class, () -> JvmMemorySettings.update(
                root,
                target(root, launcher),
                6144,
                backups,
                source -> {},
                source -> { throw new IOException("synthetic post-publication failure"); }));

        assertEquals("synthetic post-publication failure", failure.getMessage());
        assertEquals(original, Files.readString(launcher));
        Path backup = onlyFile(backups);
        assertEquals(original, Files.readString(backup));
    }

    @Test
    void externalPostPublicationEditSurvivesFailedRollback() throws Exception {
        Path root = temporary.resolve("rollback-race/game");
        Path launcher = root.resolve("starsector.sh");
        Path backups = temporary.resolve("rollback-race/backups");
        Files.createDirectories(root);
        String original = "#!/bin/sh\nexec java -Xms4g -Xmx4g game.Main\n";
        String external = "#!/bin/sh\nexec java -Xms7g -Xmx7g game.Main\n";
        Files.writeString(launcher, original);

        IOException failure = assertThrows(IOException.class, () -> JvmMemorySettings.update(
                root,
                target(root, launcher),
                6144,
                backups,
                source -> {},
                source -> {
                    Files.writeString(source, external);
                    throw new IOException("synthetic external edit");
                }));

        assertEquals("synthetic external edit", failure.getMessage());
        assertTrue(failure.getSuppressed().length >= 1, "rollback refusal should be retained as suppressed evidence");
        assertEquals(external, Files.readString(launcher));
        assertEquals(original, Files.readString(onlyFile(backups)));
    }

    @Test
    void boundsAutomaticLauncherFileBackups() throws Exception {
        Path root = temporary.resolve("bounded-game");
        Path launcher = root.resolve("starsector.sh");
        Path backups = temporary.resolve("bounded-backups");
        Files.createDirectories(root);
        Files.writeString(launcher, "#!/bin/sh\nexec java -Xms4g -Xmx4g game.Main\n");
        LaunchTarget target = target(root, launcher);

        for (int index = 0; index < SafetyArtifactRetention.MAX_BACKUPS_PER_DIRECTORY + 5; index++) {
            int memoryMiB = index % 2 == 0 ? 6144 : 4096;
            assertTrue(JvmMemorySettings.update(root, target, memoryMiB, backups).changed());
        }

        try (var files = Files.list(backups)) {
            assertEquals(SafetyArtifactRetention.MAX_BACKUPS_PER_DIRECTORY, files.count());
        }
    }

    @Test
    void rejectsOutOfRangeAndUnalignedHeapRequests() throws Exception {
        Path root = temporary.resolve("game");
        Path launcher = root.resolve("starsector.sh");
        Files.createDirectories(root);
        Files.writeString(launcher, "java -Xmx4g game.Main\n");
        LaunchTarget target = target(root, launcher);

        assertThrows(IllegalArgumentException.class,
                () -> JvmMemorySettings.update(root, target, 256, temporary.resolve("backups")));
        assertThrows(IllegalArgumentException.class,
                () -> JvmMemorySettings.update(root, target, 5000, temporary.resolve("backups")));
    }

    /**
     * With no install root there is nothing to resolve a launcher against, and this used to
     * dereference the null and hand the user the NullPointerException's own message:
     * {@code Cannot invoke "java.nio.file.Path.toAbsolutePath()" because "installRoot" is null}.
     */
    @Test
    void saysWhatIsMissingRatherThanReportingItsOwnNullPointer() {
        JvmMemorySettings.Snapshot snapshot = JvmMemorySettings.inspect(null);

        assertFalse(snapshot.available());
        assertEquals(
                "No Starsector installation was found. Set STARSECTOR_HOME or use --game.",
                snapshot.reason());
    }

    private static Path onlyFile(Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            List<Path> found = files.toList();
            assertEquals(1, found.size());
            return found.get(0);
        }
    }

    private static boolean symlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | IOException unsupported) {
            return false;
        }
    }

    private static LaunchTarget target(Path root, Path launcher) {
        return new LaunchTarget(
                root.toAbsolutePath().normalize(),
                launcher.toAbsolutePath().normalize(),
                launcher.getParent().toAbsolutePath().normalize(),
                List.of(launcher.toString()),
                "shell-script",
                100,
                "test");
    }
}
