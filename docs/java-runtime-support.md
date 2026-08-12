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

### Open: the campaign entity index disagrees with its own model under Turkish

`EntityLookupRuntime` folds with `Locale.getDefault()` in all three places that matter — building
the index, looking up in it, and validating a candidate. The behavioural model of the shipped method
in `BaseLocation` folds with `Locale.ROOT`. Both look deliberate and they cannot both be right.

They agree in every locale except Turkish and Azeri, where `I` lowercases to `ı`. There
`EntityLookupPlanTest.theCaseInsensitiveFallbackIsPreserved` and
`aDuplicatedIdResolvesTheSameWayItDidBefore` fail: the index answers `null` for an id the model
resolves to an entity. That is not a slow path, it is a different answer, and a Turkish player with
the campaign entity index enabled would get it.

Settling this needs the shipped method's own bytecode, which is not in this repository. Until then
neither side should be changed to match the other, because aligning them the wrong way makes the
cache authoritative for answers the original code would have refused. Reproduce with:

```bash
mvn -pl preflight-agent -am test -Dtest=EntityLookupPlanTest -DargLine="-Duser.language=tr -Duser.country=TR"
```

**Number formatting.** `String.format("%.1f", 1.5)` is `1,5` under Turkish and German, `١٫٥` under
Arabic, and `১.৫` under Bengali. The probe reports that printed durations and sizes now format with
`Locale.ROOT` so evidence is reproducible whoever runs it. Integer conversions such as `%04x`, which
the JSON writer uses for escapes, are not localized — that was checked rather than assumed.

The full suite passes under `-Duser.language=tr -Duser.country=TR`.

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

### Still open: the agent jar's own path

The agent reaches the game through `JAVA_TOOL_OPTIONS=-javaagent:<jar>=<options>`. Its options are
already Base64, but the jar path is read by the JVM itself and cannot be encoded. On Unix the locale
rescue covers it. On Windows nothing does: Preflight's home sits under `%LOCALAPPDATA%`, so an
account name outside the system code page gives that jar an unreproducible path, and a `-javaagent`
path that does not resolve aborts JVM startup. It fails closed — the game would not start through
Preflight at all.

This has not been reproduced on Windows; it follows from the same command-line conversion that the
contract fixture demonstrates. Staging the agent jar at an ASCII path before launch would close it.
That is a separate change and is not in the current candidate.
