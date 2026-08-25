package dev.starsector.preflight.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedCacheWriteBoundaryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void everyPreparedDataWriterRefusesASymlinkedCacheRootWithoutTouchingItsTarget()
            throws Exception {
        Path game = Files.createDirectory(temporaryDirectory.resolve("game"));
        List<List<String>> commands = List.of(
                List.of("prepare", "--game", game.toString(), "--cache-dir"),
                List.of("texture", "build", "--game", game.toString(), "--cache-dir"),
                List.of("classpath", "index", "build", "--game", game.toString(), "--cache-dir"),
                List.of("audio", "prepare", "--game", game.toString(), "--cache"));

        for (int index = 0; index < commands.size(); index++) {
            Path outside = Files.createDirectory(temporaryDirectory.resolve("outside-" + index));
            Path sentinel = outside.resolve("keep.bin");
            byte[] expected = new byte[] {(byte) (index + 1), 42, 99};
            Files.write(sentinel, expected);
            Path link = symlinkOrSkip(temporaryDirectory.resolve("cache-link-" + index), outside);
            String[] command = append(commands.get(index), link.toString());

            IOException error = assertThrows(IOException.class, () -> PreflightCli.run(command));

            assertTrue(error.getMessage().contains("cache root isn't a real directory"),
                    String.join(" ", command) + ": " + error.getMessage());
            assertEquals(List.of("keep.bin"), childNames(outside), String.join(" ", command));
            assertArrayEquals(expected, Files.readAllBytes(sentinel), String.join(" ", command));
        }
    }

    private static String[] append(List<String> command, String value) {
        String[] result = new String[command.size() + 1];
        for (int index = 0; index < command.size(); index++) {
            result[index] = command.get(index);
        }
        result[command.size()] = value;
        return result;
    }

    private static List<String> childNames(Path directory) throws IOException {
        try (var children = Files.list(directory)) {
            return children.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static Path symlinkOrSkip(Path link, Path target) throws IOException {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | SecurityException | IOException error) {
            Assumptions.assumeTrue(false, "Symbolic links aren't available: " + error);
            throw error;
        }
    }
}
