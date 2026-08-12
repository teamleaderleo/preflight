package dev.starsector.preflight.core;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Publishes a finished temporary file over its final name.
 *
 * <p>Two things can go wrong that are not the caller's fault.
 *
 * <p>An atomic move is refused when the temporary and its destination are not on one filesystem.
 * The move is then retried without the atomicity request, which is what every caller wants: the
 * content is already complete and fsynced, so a torn destination is not possible.
 *
 * <p>On Windows a rename over an existing file fails while any process holds that file open without
 * delete sharing, and the OS reports that as {@link AccessDeniedException}. A real-time virus
 * scanner opening a file Preflight has just written is the common case, and preparation publishes
 * enough files that a rare per-file collision becomes a likely per-run failure. Those handles are
 * released in milliseconds, so a bounded backoff turns a failed preparation into a slightly slower
 * one. Unix renames over open files, so the retry never fires there.
 *
 * <p>Only {@code AccessDeniedException} is retried. A missing temporary or an unwritable directory
 * is a real failure and is reported immediately.
 */
final class AtomicPublish {
    private static final int ATTEMPTS = 5;
    private static final long FIRST_BACKOFF_MILLIS = 20;

    private AtomicPublish() {
    }

    /** Moves {@code temporary} onto {@code target}, replacing whatever is there. */
    static void replace(Path temporary, Path target) throws IOException {
        long backoff = FIRST_BACKOFF_MILLIS;
        for (int attempt = 1; ; attempt++) {
            try {
                move(temporary, target);
                return;
            } catch (AccessDeniedException contended) {
                if (attempt >= ATTEMPTS || !sleep(backoff)) {
                    throw contended;
                }
                backoff *= 2;
            }
        }
    }

    private static void move(Path temporary, Path target) throws IOException {
        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException separateFilesystem) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Returns false when the wait was interrupted, so the caller reports the original failure. */
    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
