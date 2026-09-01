package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExclusiveMoveTest {
    @Test
    void existingFileWins(@TempDir Path tempDir) throws Exception {
        Path source = tempDir.resolve("source");
        Path target = tempDir.resolve("target");
        Files.writeString(source, "preflight\n");
        Files.writeString(target, "external\n");

        assertThrows(FileAlreadyExistsException.class, () -> ExclusiveMove.move(source, target));

        assertEquals("preflight\n", Files.readString(source));
        assertEquals("external\n", Files.readString(target));
    }

    @Test
    void existingDirectoryWins(@TempDir Path tempDir) throws Exception {
        Path source = tempDir.resolve("source");
        Path target = tempDir.resolve("target");
        Files.createDirectory(source);
        Files.writeString(source.resolve("preflight.txt"), "preflight\n");
        Files.createDirectory(target);
        Files.writeString(target.resolve("external.txt"), "external\n");

        assertThrows(FileAlreadyExistsException.class, () -> ExclusiveMove.move(source, target));

        assertEquals("preflight\n", Files.readString(source.resolve("preflight.txt")));
        assertEquals("external\n", Files.readString(target.resolve("external.txt")));
    }

    @Test
    void absentTargetReceivesExactSourceGeneration(@TempDir Path tempDir) throws Exception {
        Path source = tempDir.resolve("source");
        Path target = tempDir.resolve("target");
        Files.writeString(source, "preflight generation\n");

        ExclusiveMove.move(source, target);

        assertFalse(Files.exists(source));
        assertEquals("preflight generation\n", Files.readString(target));
    }

    @Test
    void longPathsRemainMovable(@TempDir Path tempDir) throws Exception {
        Path parent = tempDir;
        for (int depth = 0; parent.toAbsolutePath().toString().length() <= 280; depth++) {
            parent = parent.resolve("exclusive-move-long-path-segment-" + depth);
        }
        Files.createDirectories(parent);
        Path source = parent.resolve("source");
        Path target = parent.resolve("target");
        Files.writeString(source, "preflight generation\n");

        ExclusiveMove.move(source, target);

        assertFalse(Files.exists(source));
        assertEquals("preflight generation\n", Files.readString(target));
    }

    @Test
    void exactlyOneConcurrentWriterCanClaimAbsentTarget(@TempDir Path tempDir) throws Exception {
        int writers = 8;
        List<Path> sources = new ArrayList<>();
        for (int index = 0; index < writers; index++) {
            Path source = tempDir.resolve("source-" + index);
            Files.writeString(source, "generation-" + index + "\n");
            sources.add(source);
        }
        Path target = tempDir.resolve("target");
        CountDownLatch ready = new CountDownLatch(writers);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(writers);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (Path source : sources) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        ExclusiveMove.move(source, target);
                        return true;
                    } catch (FileAlreadyExistsException externalWon) {
                        return false;
                    }
                }));
            }
            ready.await();
            start.countDown();
            int successes = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) successes++;
            }
            assertEquals(1, successes);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        String winner = Files.readString(target);
        assertTrue(winner.startsWith("generation-"), winner);
        assertEquals(writers - 1, sources.stream().filter(Files::exists).count());
    }

    @Test
    void openedParentKeepsOwningRelativeOperationsAfterPathReplacement(@TempDir Path tempDir)
            throws Exception {
        Path parent = Files.createDirectory(tempDir.resolve("mods"));
        Path parked = tempDir.resolve("mods-reviewed-generation");
        byte[] value = "reviewed\n".getBytes(StandardCharsets.UTF_8);

        try (ParentBoundDirectory directory = ParentBoundDirectory.open(parent)) {
            directory.createFile("before-swap", value, 0600);
            Files.move(parent, parked);
            Files.createDirectory(parent);

            assertThrows(java.io.IOException.class, directory::requireCurrent);
            directory.createFile("after-swap", value, 0600);

            assertArrayEquals(value, Files.readAllBytes(parked.resolve("after-swap")));
            assertFalse(Files.exists(parent.resolve("after-swap")));
        }
    }

    @Test
    void activationParentSwapCannotRedirectPublication(@TempDir Path tempDir) throws Exception {
        Path mods = Files.createDirectory(tempDir.resolve("mods"));
        Path enabled = mods.resolve("enabled_mods.json");
        byte[] reviewed = "{\"enabledMods\":[\"beta\"]}".getBytes(StandardCharsets.UTF_8);
        byte[] replacement = "{\"enabledMods\":[\"alpha\"]}".getBytes(StandardCharsets.UTF_8);
        byte[] external = "{\"enabledMods\":[\"zeta\"]}".getBytes(StandardCharsets.UTF_8);
        Files.write(enabled, reviewed);
        Path parked = tempDir.resolve("mods-reviewed-generation");

        assertThrows(EnabledModsActivationTransaction.StaleGenerationException.class, () ->
                EnabledModsActivationTransaction.replace(
                        enabled,
                        reviewed,
                        replacement,
                        new EnabledModsActivationTransaction.Hook() {
                            @Override
                            public void beforeTargetPublication(Path target) throws java.io.IOException {
                                Files.move(mods, parked);
                                Files.createDirectory(mods);
                                Files.write(mods.resolve("enabled_mods.json"), external);
                            }
                        }));

        assertArrayEquals(external, Files.readAllBytes(mods.resolve("enabled_mods.json")));
        assertArrayEquals(reviewed, Files.readAllBytes(parked.resolve("enabled_mods.json")));
    }
}
