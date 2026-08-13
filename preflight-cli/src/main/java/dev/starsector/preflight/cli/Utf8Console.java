package dev.starsector.preflight.cli;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * A UTF-8 answer channel, the return half of {@link Utf8Argv}.
 *
 * <p>Java encodes {@code System.out} with {@code stdout.encoding}, which on Windows is the console's
 * code page rather than UTF-8. Everything Preflight reports travels through that stream, and the
 * desktop reads it as UTF-8, so a code page in between corrupts any answer carrying a game folder,
 * profile name, or user directory. The damage is not uniform: a character the code page can
 * represent arrives as a byte that is not valid UTF-8 and reads back as {@code U+FFFD}, while one it
 * cannot represent is replaced by {@code '?'} and is gone for good.
 *
 * <p>Measured on a cp1252 runner, {@code Synthetic Game – path Ω} came back as
 * {@code Synthetic Game � path ?} — the en dash by the first route, the omega by the second.
 * Discovery had already resolved that folder correctly, so this is purely the answer being spoiled
 * on the way out.
 *
 * <p>Installing UTF-8 explicitly makes the stream mean the same thing on every platform, which is
 * also what reproducible evidence requires. The cost is that a Windows console still running a
 * legacy code page renders non-ASCII text as mojibake. That trades a cosmetic problem in an
 * interactive console for a correctness problem in every machine-read answer, which is the right way
 * round: the desktop is the primary reader, and a `?` cannot be recovered by anyone.
 */
final class Utf8Console {
    private Utf8Console() {
    }

    /**
     * Points {@code System.out} and {@code System.err} at UTF-8.
     *
     * <p>Called before any command runs, so nothing has buffered bytes through the streams being
     * replaced.
     */
    static void install() {
        System.setOut(utf8(new FileOutputStream(FileDescriptor.out)));
        System.setErr(utf8(new FileOutputStream(FileDescriptor.err)));
    }

    /**
     * Wraps a sink in an auto-flushing UTF-8 stream.
     *
     * <p>Auto-flush keeps the existing line-at-a-time behaviour, so a command that ends with
     * {@code System.exit} still delivers every line it printed.
     */
    static PrintStream utf8(OutputStream sink) {
        return new PrintStream(sink, true, StandardCharsets.UTF_8);
    }
}
