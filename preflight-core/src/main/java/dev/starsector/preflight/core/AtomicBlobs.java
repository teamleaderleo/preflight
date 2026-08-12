package dev.starsector.preflight.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Writes a cache blob so that a reader never sees a partial one.
 *
 * <p>Every cache preflight writes is read by a game that is starting up, possibly while another
 * preflight process is still writing it, and possibly just after the machine lost power. A plain
 * write fails all three: the reader can observe a file that exists, has a plausible size, and is
 * half-written. So the bytes go to a uniquely named sibling, are forced to disk, and are moved into
 * place atomically — the name either resolves to the old blob or the complete new one.
 *
 * <p>This lives on its own because it is the same procedure for every blob format, and the failure
 * it prevents is silent. A second copy that quietly dropped the {@code force} would look correct in
 * every test and lose data only on a machine that crashed.
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
}
