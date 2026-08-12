# Java runtime support

Three separate JVMs matter, and they don't have to be the same version.

| JVM | What it runs | Requirement |
| --- | --- | --- |
| Build JDK | Compiles the project | 17 or newer |
| Engine runtime | `preflight.jar`: discovery, preparation, caches, reports | 17 or newer; packages bundle their own |
| Game runtime | Starsector, with the Preflight agent injected | 17 or newer, chosen by the player |

Everything ships as class file major version 61, so any JVM from 17 up loads it. Nothing in the
project asks for a language or API level above 17.

## Measured

Full `mvn verify`, including the failsafe integration tests, on an M5 MacBook Air:

| JDK | Result |
| --- | --- |
| Oracle 17.0.12 | pass |
| Oracle 21.0.10 | pass |
| Homebrew 26.0.1 | pass |

17 and 21 are the versions players actually run Starsector on, and both are covered. 26 passing
means nothing between here and the current release breaks the build, but it is one machine and one
vendor, so it is a data point rather than a support claim.

## What the optimizations depend on

Almost nothing version-specific. The work is caching answers along Starsector's own loading paths —
merged JSON, prepared textures, generated mod bytecode, decoded audio — keyed by exact content
identities. That reasoning is about game and mod files, not about JVM internals.

The runtime adapters rewrite game and mod classes, which live on the classpath in the unnamed
module. Strong encapsulation applies to JDK modules, not to those, so `setAccessible` on a game or
mod field keeps working as versions advance without needing `--add-opens`.

Two evidence-only commands compare an observed class loader against
`jdk.internal.loader.ClassLoaders$AppClassLoader` by name. If a future JDK renames it, the identity
check returns false and the evidence run declines. It stops producing that evidence rather than
producing wrong evidence.

`-noverify` is passed by the audio preparation and sound-wrapper probes. It has been deprecated
since JDK 13 and every JDK through 26 still accepts it with a warning. When it is finally removed
those probes lose an optimization, not their correctness.

## Non-ASCII paths on Windows

Windows converts a process command line to the active ANSI code page before the Java launcher reads
it. Characters outside that code page arrive as `?`, so a game folder, profile name, or user
directory containing them is destroyed before argument parsing begins.

No JVM version or option fixes this:

- `sun.jnu.encoding` is derived from the System Locale and is documented as not settable with `-D`.
- JEP 400 made `file.encoding` UTF-8 in JDK 18. It does not touch the launcher's command line.
- [JDK-8337506](https://github.com/openjdk/jdk/pull/20428) (JDK 25) only stops the launcher
  substituting look-alike characters. It still converts through the ANSI code page, and characters
  the code page cannot represent are still lost.
- `java.exe` carries no `activeCodePage=UTF-8` application manifest on any released JDK.

So a newer bundled runtime would not have helped. Instead, argument vectors that need more than
ASCII are Base64 UTF-8 encoded behind a `--preflight-utf8-argv` sentinel, and the engine reverses
them before parsing. Only ASCII crosses the process boundary, which every code page reproduces
exactly. ASCII-only vectors are passed through untouched so ordinary command lines stay readable.

Three implementations have to agree, and each pins the same vectors in its own tests:

| Side | Implementation |
| --- | --- |
| Engine | `Utf8Argv` in preflight-cli |
| Desktop host | `EngineCommand` and `encode_argv` in `engine.rs` |
| Package scripts | `scripts/utf8-argv.mjs` |

The desktop host collects arguments rather than handing them to the process builder as they arrive,
so a caller cannot add one that skips the encoding.

### Still open: the agent jar's own path

The agent reaches the game through `JAVA_TOOL_OPTIONS=-javaagent:<jar>=<options>`. Its options are
already Base64, but the jar path is read by the JVM itself and cannot be encoded. On Windows
Preflight's home sits under `%LOCALAPPDATA%`, so a player whose Windows account name is not ASCII
gives that jar a path the JVM cannot reproduce. A `-javaagent` path that does not resolve is a fatal
startup error, so this fails closed: the game would not start through Preflight at all.

Staging the agent jar at an ASCII path before launch would close it. That is a separate change and
is not in the current candidate.
