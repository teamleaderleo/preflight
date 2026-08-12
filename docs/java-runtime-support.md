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

## Non-ASCII paths

A JVM decodes the arguments it is given, and on Unix it also encodes the paths it hands the
filesystem. Both use `sun.jnu.encoding`, which the JVM derives from the platform. When that charset
cannot represent a character, the path is lost. Windows and Unix lose it in different places, so
they need different repairs.

No JVM version or option changes the charset:

- `sun.jnu.encoding` comes from the platform and is documented as not settable with `-D`. Measured:
  passing `-Dsun.jnu.encoding=UTF-8` leaves it at `ANSI_X3.4-1968` and the argument still arrives
  corrupted.
- JEP 400 made `file.encoding` UTF-8 in JDK 18. It does not touch `sun.jnu.encoding`.
- [JDK-8337506](https://github.com/openjdk/jdk/pull/20428) (JDK 25) only stops the Windows launcher
  substituting look-alike characters. It still converts through the ANSI code page, and characters
  that code page cannot represent are still lost.
- `java.exe` carries no `activeCodePage=UTF-8` application manifest on any released JDK.

A newer bundled runtime would not have helped.

### Windows: the command line

Windows converts a process command line to the active ANSI code page before the launcher reads it.
Characters outside that page arrive as `?`, and the packaged contract's `Synthetic Game – path Ω`
fixture fails on a cp1252 runner with `Illegal char <?> at index 95`. That error comes from the
Windows path parser rejecting a literal `?`, which is not a legal filename character — the path
layer itself is UTF-16 and lossless, so the command line is the only lossy step.

The scope is narrower than it first looks. The ANSI code page is chosen to cover the system's own
language, so a Cyrillic account name on a Russian Windows round-trips fine. What breaks is text
outside the machine's own code page: mixed scripts, emoji, or a folder named in a different language
from the system.

Argument vectors needing more than ASCII are therefore Base64 UTF-8 encoded behind a
`--preflight-utf8-argv` sentinel, and the engine reverses them before parsing. Only ASCII crosses
the boundary, which every code page reproduces exactly. ASCII-only vectors are untouched so ordinary
command lines stay readable. Three implementations must agree, and each pins the same vectors:

| Side | Implementation |
| --- | --- |
| Engine | `Utf8Argv` in preflight-cli |
| Desktop host | `EngineCommand` and `encode_argv` in `engine.rs` |
| Package scripts | `scripts/utf8-argv.mjs` |

The desktop host collects arguments rather than handing them to the process builder as they arrive,
so a caller cannot add one that skips the encoding.

### Windows: the answer coming back, measured on a cp1252 runner

Encoding the argument vector fixes the way in. The way out has the same problem in reverse: Java
encodes `System.out` with `stdout.encoding`, which on Windows is the console's code page, and the
desktop reads that stream as UTF-8.

The packaged contract demonstrated it. Discovery resolved `Synthetic Game – path Ω` correctly —
`ready: true`, with `starsector.bat` found inside it, which is only possible if the path arrived
intact — and then reported it back as `Synthetic Game � path ?`. Two different failures in one
string, which is what identifies the mechanism:

| Character | In cp1252 | Arrives as | Why |
| --- | --- | --- | --- |
| `–` U+2013 | representable, byte `0x96` | `�` | `0x96` alone is not valid UTF-8 |
| `Ω` U+03A9 | not representable | `?` | substituted at encoding, unrecoverable |

Reproduced away from Windows by forcing the same charset, which emits the identical bytes:

```bash
java -Dstdout.encoding=windows-1252 Enc | python3 -c "import sys; print(sys.stdin.buffer.read().decode('utf-8', errors='replace'))"
```

`Utf8Console` therefore installs UTF-8 on `System.out` and `System.err` at the single entry point,
before any command runs — the same chokepoint approach as argument decoding, and necessary because
the CLI has over two hundred print sites. The trade is that a Windows console still on a legacy code
page renders non-ASCII as mojibake, which is cosmetic and visible, against machine-read answers
being silently wrong, which is neither.

### Unix: the locale, measured on Debian with OpenJDK 21

Under `LC_ALL=C` or `POSIX` the charset is US-ASCII, and encoding the argument vector is **not
enough**. The decoded string is correct, but the file layer then cannot express it and the engine
reports `Malformed input or input contains unmappable characters`. The engine jar's own path fails
the same way, before any Preflight code runs, and a relative jar path does not help because the JVM
resolves it against a `user.dir` decoded with the same charset.

| Parent locale | Argument encoding | Locale rescue | Result |
| --- | --- | --- | --- |
| `C` | no | no | fails |
| `C` | yes | no | fails |
| `C` | no | yes | works |
| `C` | yes | yes | works |
| `en_US.UTF-8` | no | no | works |

So the desktop gives an ASCII-only child `LC_ALL=C.UTF-8`. Only `C`, `POSIX`, and an empty locale
are rescued. A real 8-bit locale such as `de_DE.ISO-8859-1` is left alone: its filenames are that
charset's bytes, and reading them as UTF-8 would corrupt paths that work today. Windows is excluded
because its charset comes from the system code page, which no locale variable changes.

Desktop Linux installs are UTF-8 in practice, so this is insurance rather than a common path.

## The default locale changes what strings mean

A JVM's default locale is the player's, and two of Java's most ordinary operations follow it.

**Case folding.** Under a Turkish locale `"GIF".toLowerCase()` is `gıf` and `"ID".toLowerCase()`
does not equal `"id"`. The codebase folds with `Locale.ROOT` in 201 places; the stragglers that did
not have been corrected.

### The campaign entity lookup folds with the player's locale, because the game does

`EntityLookupRuntime` folds with `Locale.getDefault()` in all three places that matter — building
the index, looking up in it, and validating a candidate. The behavioural model of the shipped method
in `BaseLocation` used to fold with `Locale.ROOT`, and under Turkish the two gave different answers.

The disassembly settles it: the shipped fallback folds **both** sides with the no-argument
`String.toLowerCase()`, which is the default locale. The model was the side that was wrong and has
been corrected. Nothing was copied out of the game — this is a statement about which overload the
method calls, checked once with `javap` against a local install.

The consequence is worth stating plainly, because it is a property of Starsector rather than of
Preflight. Turkish and Azeri lowercase `I` to dotless `ı`, so `"ENTITY_3"` folds to `"entıty_3"` and
never meets `"entity_3"`. **For a Turkish player the shipped case-insensitive fallback already does
not match ids containing an `I`**, with or without Preflight. Exact matches are unaffected, which is
the overwhelming majority of lookups, and the game's own id map is keyed unfolded.

So the index reproduces that, including the part that looks like a bug. Pinning `Locale.ROOT` in the
index would have made it answer for ids the game itself declines — the one failure mode this
project cannot accept, arrived at by way of a fix. `EntityLookupPlanTest` now pins a locale rather
than inheriting the operator's, and
`theIndexTracksTheShippedFallbackEvenWhereTurkishBreaksIt` covers the divergence directly, so the
coverage no longer depends on remembering to pass a flag.

**Number formatting.** `String.format("%.1f", 1.5)` is `1,5` under Turkish and German, `١٫٥` under
Arabic, and `১.৫` under Bengali. The probe reports that printed durations and sizes now format with
`Locale.ROOT` so evidence is reproducible whoever runs it. Integer conversions such as `%04x`, which
the JSON writer uses for escapes, are not localized — that was checked rather than assumed.

The full suite passes under `-Duser.language=tr -Duser.country=TR`, which is worth re-running after
any change to string comparison:

```bash
mvn verify -DargLine="-Duser.language=tr -Duser.country=TR"
```

## Publishing a file while something else holds it

Every prepared artifact is written to a temporary and then renamed over its final name. Unix renames
over an open file; Windows refuses while any process holds that file open without delete sharing,
and reports `AccessDeniedException`. A real-time virus scanner opening a file Preflight has just
written is the ordinary case, and preparation publishes enough files that a rare per-file collision
becomes a likely per-run failure — the classic "preparation randomly fails on Windows" report.

The seventeen publication sites now share one `AtomicPublish.replace`, which keeps the existing
fallback for a cross-filesystem move and adds a bounded backoff for contention: five attempts over
roughly 300ms, then the original failure. Only `AccessDeniedException` is retried, so a missing
temporary or an unwritable directory is still reported immediately. The retry never fires on Unix.

This is reasoned from documented Windows semantics and the retry logic every major build tool
carries for it. It has not been reproduced on Windows, but it costs nothing where the problem does
not exist.

### The agent jar's own path, staged when the encoding would lose it

The agent reaches the game through `JAVA_TOOL_OPTIONS=-javaagent:<jar>=<options>`. Its options are
already Base64, but the jar path is read by the JVM itself and cannot be encoded: HotSpot reads that
variable through the narrow `getenv`, so Windows converts the value to the active ANSI code page and
replaces anything the page cannot represent with `?`. Preflight's home sits under `%LOCALAPPDATA%`,
so an account name outside the system code page gives that jar an unreproducible path. This one does
not degrade — a `-javaagent` the JVM cannot open aborts VM initialization, so the game would not
start through Preflight at all while launching normally without it.

`AgentJarStaging` asks whether the wrapper's own `sun.jnu.encoding` can carry the jar's path. The
wrapper and the game share an environment and a system locale, so that is the encoding the child will
use. When it can, the path is passed through and nothing is copied, which is every ordinary
installation and every localized account name whose own code page covers it. When it cannot, the jar
is copied once into `preflight-agent/` under the first candidate root whose path does survive —
the temporary directory, then `%PUBLIC%`, then `%ProgramData%`, then `/tmp` and `/var/tmp` — and that
copy's path is what `JAVA_TOOL_OPTIONS` carries. The copy is named for its SHA-256, so a rebuilt jar
stages under a new name and a stale copy can never be served in place of the current one. Having
nowhere to stage is reported as a Preflight error rather than left to surface as a JVM that will not
initialize.

The Windows conversion itself has not been reproduced for this variable; it follows from the same
narrow-`getenv` path that the command line demonstrates, and `AgentJarStagingTest` pins the cp1252
behaviour that makes it matter. The staging is exercised on every platform by posing US-ASCII as the
encoding, which is what a `C` locale would give a Unix session.

### Still open: the child JVMs Preflight spawns itself

`prepare audio` runs its decode in a child JVM, and the audio verification commands do the same. Only
`PreflightCli` decodes a Base64 argument vector; those children receive theirs raw, and their game
jars travel on `-cp`, which the launcher consumes before any Preflight code runs. Staging does not
reach that — the game cannot be copied. Closing it means launching the child on the staged jar alone
and handing it the game classpath as encoded arguments to load itself. That is a separate change and
is not in the current candidate.
