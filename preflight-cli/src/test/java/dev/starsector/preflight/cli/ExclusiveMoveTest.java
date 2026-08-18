package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
