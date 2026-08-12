package dev.starsector.preflight.cli;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * An ASCII-safe transport for the command line.
 *
 * <p>Windows converts a process command line to the active ANSI code page before Java sees it. Any
 * character outside that page arrives as {@code '?'}, so a game folder, profile name, or user
 * directory containing non-ASCII text is destroyed before argument parsing begins. No JVM option
 * changes this: {@code sun.jnu.encoding} is derived from the System Locale and is documented as not
 * settable with {@code -D}, and the launcher still converts through the ANSI code page on every
 * released JDK. Recent launchers only stopped substituting look-alike characters; unmappable ones
 * are still lost.
 *
 * <p>Callers that can carry non-ASCII text therefore encode the whole argument vector as Base64
 * UTF-8 and mark it with {@link #SENTINEL}. Only ASCII crosses the process boundary, which every
 * code page reproduces exactly. ASCII-only vectors are passed through untouched so ordinary command
 * lines stay readable in process listings and logs.
 */
final class Utf8Argv {
    /** First argument of an encoded vector. Never a valid Preflight command. */
    static final String SENTINEL = "--preflight-utf8-argv";

    private Utf8Argv() {
    }

    /** Returns true when every argument survives an ANSI code page unchanged. */
    static boolean isAscii(String[] args) {
        for (String argument : args) {
            for (int index = 0; index < argument.length(); index++) {
                if (argument.charAt(index) > 0x7f) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Returns {@code args} unchanged when it is already ASCII, otherwise a sentinel-marked vector
     * whose arguments are Base64 UTF-8.
     */
    static String[] encode(String[] args) {
        if (isAscii(args)) {
            return args;
        }
        String[] encoded = new String[args.length + 1];
        encoded[0] = SENTINEL;
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        for (int index = 0; index < args.length; index++) {
            encoded[index + 1] = encoder.encodeToString(args[index].getBytes(StandardCharsets.UTF_8));
        }
        return encoded;
    }

    /** Reverses {@link #encode}. Vectors without the sentinel are returned unchanged. */
    static String[] decode(String[] args) {
        if (args.length == 0 || !SENTINEL.equals(args[0])) {
            return args;
        }
        String[] decoded = new String[args.length - 1];
        Base64.Decoder decoder = Base64.getUrlDecoder();
        for (int index = 1; index < args.length; index++) {
            byte[] bytes;
            try {
                bytes = decoder.decode(args[index]);
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException(
                        "argument " + index + " of an encoded command line is not valid Base64", error);
            }
            decoded[index - 1] = new String(bytes, StandardCharsets.UTF_8);
        }
        return decoded;
    }
}
