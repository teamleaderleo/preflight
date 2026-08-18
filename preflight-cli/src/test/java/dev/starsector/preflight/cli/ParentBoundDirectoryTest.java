package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ParentBoundDirectoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void operationsStayBoundToTheOpenedParentGeneration() throws Exception {
        Path parent = Files.createDirectory(temporaryDirectory.resolve("mods"));
        Path parked = temporaryDirectory.resolve("mods-reviewed-generation");
        byte[] reviewed = "reviewed".getBytes(StandardCharsets.UTF_8);

        try (ParentBoundDirectory directory = ParentBoundDirectory.open(parent)) {
            directory.createFile("reviewed", reviewed, 0600);
            directory.createDirectory("txn");
            directory.createFile("txn-snapshot", reviewed, 0600);
            assertArrayEquals(reviewed, directory.readFile("txn-snapshot"));
            directory.deleteFile("txn-snapshot");
            directory.deleteDirectory("txn");
            assertFalse(Files.exists(parent.resolve("txn")));

            Files.move(parent, parked);
            Files.createDirectory(parent);
            assertThrows(IOException.class, directory::requireCurrent);

            directory.createFile("still-reviewed-parent", reviewed, 0600);
            assertTrue(Files.exists(parked.resolve("still-reviewed-parent")));
            assertFalse(Files.exists(parent.resolve("still-reviewed-parent")));
        }
    }

    @Test
    void nestedRelativeNamesAreRejectedByThePinnedParentContract() throws Exception {
        Path parent = Files.createDirectory(temporaryDirectory.resolve("mods"));
        try (ParentBoundDirectory directory = ParentBoundDirectory.open(parent)) {
            assertThrows(IOException.class, () -> directory.createFile("child/value", new byte[] {1}, 0600));
        }
    }
}
