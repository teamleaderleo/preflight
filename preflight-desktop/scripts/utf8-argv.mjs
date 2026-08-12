/**
 * An ASCII-safe transport for the engine's command line.
 *
 * Windows converts a process command line to the active ANSI code page before the Java launcher
 * reads it, so any character outside that page reaches `main` as `?`. No JVM option avoids it:
 * `sun.jnu.encoding` is derived from the System Locale and is not settable with `-D`. Vectors that
 * need more than ASCII are therefore Base64 UTF-8 encoded behind a sentinel, which the engine
 * reverses before parsing. This mirrors `Utf8Argv` in preflight-cli and `encode_argv` in the
 * desktop host; all three must agree.
 */

/** First argument of an encoded vector. Never a valid Preflight command. */
export const UTF8_ARGV_SENTINEL = "--preflight-utf8-argv";

/** True when every argument survives an ANSI code page unchanged. */
export function isAsciiArgv(args) {
  return args.every((argument) => !/[^\x00-\x7f]/u.test(argument));
}

/**
 * Returns `args` unchanged when it is already ASCII, otherwise the sentinel-marked Base64 UTF-8
 * form. ASCII vectors are left alone so ordinary command lines stay readable in process listings.
 */
export function encodeArgv(args) {
  if (isAsciiArgv(args)) return args;
  return [UTF8_ARGV_SENTINEL, ...args.map((argument) => Buffer.from(argument, "utf8").toString("base64url"))];
}

/** Reverses {@link encodeArgv}. Present so tests can check the round trip end to end. */
export function decodeArgv(args) {
  if (args.length === 0 || args[0] !== UTF8_ARGV_SENTINEL) return args;
  return args.slice(1).map((argument) => Buffer.from(argument, "base64url").toString("utf8"));
}
