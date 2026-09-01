package dev.starsector.preflight.core.checkpoints;

import dev.starsector.preflight.core.Hashes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Storage manager for checkpoint files.
 *
 * <p>Ensures atomic writes, deterministic naming via SHA-256 digests, backup preservation,
 * and resilient directory listing.</p>
 */
public final class CheckpointStore {

    public record LoadedCheckpoints(List<Checkpoint> checkpoints, List<String> diagnostics) {}

    private CheckpointStore() {}

    public static String validateName(String name) {
        return Checkpoint.validateName(name);
    }

    public static Path checkpointPath(Path checkpointsDir, String name) {
        String validName = validateName(name);
        String digest = Hashes.sha256(validName.getBytes(StandardCharsets.UTF_8));
        return checkpointsDir.resolve(digest + ".json").toAbsolutePath().normalize();
    }

    public static Path save(Path checkpointsDir, Checkpoint checkpoint) throws IOException {
        Files.createDirectories(checkpointsDir);
        Path target = checkpointPath(checkpointsDir, checkpoint.name());
        atomicWrite(target, checkpoint.toJson() + System.lineSeparator());
        return target;
    }

    public static Checkpoint load(Path checkpointsDir, String name) throws IOException {
        Path file = checkpointPath(checkpointsDir, name);
        if (!Files.isRegularFile(file)) {
            throw new IOException("Checkpoint not found: " + name);
        }
        return readCheckpoint(file);
    }

    public static Checkpoint readCheckpoint(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        return Checkpoint.fromJson(text, file);
    }

    public static LoadedCheckpoints listAll(Path checkpointsDir) throws IOException {
        if (!Files.isDirectory(checkpointsDir)) {
            return new LoadedCheckpoints(List.of(), List.of());
        }
        List<Checkpoint> list = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        try (var stream = Files.list(checkpointsDir)) {
            for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                try {
                    list.add(readCheckpoint(file));
                } catch (Exception ex) {
                    diagnostics.add("Could not read checkpoint " + file.getFileName() + ": " + ex.getMessage());
                }
            }
        }
        list.sort(Comparator.comparing(Checkpoint::name, String.CASE_INSENSITIVE_ORDER));
        return new LoadedCheckpoints(List.copyOf(list), List.copyOf(diagnostics));
    }

    public static Path backup(Path backupsDir, Checkpoint checkpoint) throws IOException {
        if (checkpoint.file() != null && Files.isRegularFile(checkpoint.file())) {
            return backupFile(backupsDir, checkpoint.file());
        }
        Files.createDirectories(backupsDir);
        Path backup = Files.createTempFile(
                backupsDir,
                "deleted-checkpoint-" + Instant.now().toEpochMilli() + "-",
                ".json");
        Files.writeString(backup, checkpoint.toJson() + System.lineSeparator(), StandardCharsets.UTF_8);
        return backup.toAbsolutePath().normalize();
    }

    public static Path backupFile(Path backupsDir, Path checkpointFile) throws IOException {
        Files.createDirectories(backupsDir);
        Path backup = Files.createTempFile(
                backupsDir,
                "deleted-checkpoint-" + Instant.now().toEpochMilli() + "-",
                ".json");
        Files.copy(checkpointFile, backup, StandardCopyOption.REPLACE_EXISTING);
        return backup.toAbsolutePath().normalize();
    }

    public static void delete(Path checkpointsDir, Path backupsDir, Checkpoint checkpoint) throws IOException {
        if (backupsDir != null) {
            backup(backupsDir, checkpoint);
        }
        Path path = checkpoint.file() != null ? checkpoint.file() : checkpointPath(checkpointsDir, checkpoint.name());
        Files.deleteIfExists(path);
    }

    private static void atomicWrite(Path target, String content) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path staged = Files.createTempFile(parent, ".preflight-checkpoint-", ".tmp");
        try {
            Files.writeString(staged, content, StandardCharsets.UTF_8);
            try {
                Files.move(staged, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(staged, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
    }
}
