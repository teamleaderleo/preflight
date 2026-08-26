package dev.starsector.preflight.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Stages and forces a complete cache blob before publishing it under its final name.
 *
 * <p>Every cache Preflight writes is read by a game that is starting up, possibly while another
 * Preflight process is still writing it, and possibly after interruption or power loss. The bytes
 * therefore go to a uniquely named sibling and are forced before publication. When the filesystem
 * supports the atomic replacement requested by {@link AtomicPublish}, readers switch directly from
 * the old blob to the complete new blob. On the documented non-atomic fallback, the cache carries a
 * rebuildable-data contract instead: after interruption the observer may find the old artifact, the
 * new artifact, or no usable artifact, and format validation must reject unusable data and rebuild.
 *
 * <p>Forcing the temporary file makes its contents durable before publication. This helper does not
 * fsync the parent directory, so it does not claim crash-durable directory-entry replacement.
 */
final class AtomicBlobs {
    private AtomicBlobs() {
    }

    static void write(Path target, byte[] bytes) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = absolute.resolveSibling(
                absolute.getFileName() + ".tmp-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            AtomicPublish.replace(temporary, absolute);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    /**
     * Writes a checked build intermediate without forcing every individual file to stable storage.
     *
     * <p>The caller must not make this path active directly. The texture builder uses these files
     * only as resumable inputs to a pack that rechecks every SPFT payload before forcing and
     * atomically publishing the final artifact. A normal failure removes the incomplete file; a
     * process or power interruption may leave one behind, and the next preparation validates it
     * before reuse.
     */
    static void writeIntermediate(Path target, byte[] bytes) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        boolean created = false;
        boolean complete = false;
        try {
            FileChannel opened = FileChannel.open(
                    absolute,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            created = true;
            try (FileChannel channel = opened) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            }
            complete = true;
        } finally {
            if (created && !complete) {
                Files.deleteIfExists(absolute);
            }
        }
    }
}
