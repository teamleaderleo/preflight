# Prior art: `starsector-render` (Fast Rendering)

**Repository:** <https://github.com/halke1986/starsector-render> (author: halke1986 / Genir)
**License:** The Unlicense (public domain)
**Reviewed:** 2026-08-01, at a shallow clone of `master`, last updated the same day
**Targets:** Starsector 0.98a-RC8, Windows, quad-core minimum

A parallel project attacking the same game with the same class of tool: a Java agent that rewrites
Starsector's classes before they load. It is worth reading carefully before building anything else,
because it already covers ground this repository has been treating as unexplored.

## What it is for

Primarily frame rate. The core of it is an **asynchronous, multithreaded OpenGL wrapper**: GL
submission is deferred off the render thread. That is why it requires four cores, and why its
changelog is a long sequence of *"asynchronous pipeline stall"* fixes -- every GL call that reads
state back (`glIsEnabled`, `glGetInteger`) has to synchronise with the deferred queue, and each mod
that used one it did not yet implement produced a crash report.

It also does startup work, and the premise that it does not is wrong: `Optimized game startup time`
appears three separate times in the changelog, alongside fixes for startup race conditions and for
XStream freezes during saving. It is simply not what the project advertises.

## The technique, and why it is not ours

`ConstantTransformer` walks the raw class file's **constant pool** and rewrites `CONSTANT_Utf8`
entries in place, adjusting the length prefix. Two passes per entry:

1. exact whole-string match against a substitution map -- `org/lwjgl/opengl/GL11` becomes
   `com/genir/renderer/bridge/commands/GL11`;
2. failing that, parse the entry as a **method descriptor** and substitute type names inside it, so
   `(Lorg/lwjgl/opengl/GL11;)V` is rewritten too.

No ASM. No class model. No stack-map frames. One linear scan.

The consequences are worth stating plainly, because they are the opposite of Preflight's:

| | `starsector-render` | Preflight |
| --- | --- | --- |
| unit of change | every reference to a class, everywhere | one method body |
| mechanism | constant-pool string substitution | ASM instruction splice |
| obfuscation | irrelevant -- names are just bytes | requires exact method identity |
| frames | never recomputed | recomputed and verified |
| can it change behaviour in place? | **no** -- only redirect | yes |
| blast radius | every class that references the name, mods included | the pinned method |
| verification that it landed | none | pinned class hash, plan verification, telemetry |

Because substitution can only redirect, behaviour changes need a whole replacement class -- which is
what the hand-written Jasmin under `assembly/` is for.

The coarseness is the real cost. Substituting `org/lwjgl/opengl/GL11` hits mod code as well as game
code, so the shim must implement every overload any mod ever calls; the changelog records exactly
that failure mode against BoxUtil, Console Commands, Moci Ship Pack, PatchLib and others.

**The idea worth borrowing:** when the goal is to wrap an entire class rather than alter one method,
constant-pool redirection is dramatically more robust than splicing every call site, and it is
immune to obfuscation churn. It fails open naturally -- a renamed class simply stops matching.

**What we should not give up:** it has no gate. Nothing verifies a substitution landed, or notices
when one silently stops matching. `AdapterTargetRegistry`'s pinned class hashes and the circuit
breaker exist for that reason and have already caught real drift.

## Overlapping seams

Classes it rewrites that this repository also touches or has queued:

```
com/fs/graphics/L                              the one-thread image prefetcher we bypassed
com/fs/graphics/TextureLoader                  our texture seam
com/fs/util/C                                  the 77-root File.exists probe, queued here
com/fs/starfarer/loading/ResourceLoaderState   builds the prefetch queue
com/fs/starfarer/loading/SpecStore             the JSON/spec path, untouched here
com/fs/starfarer/loading/scripts/ScriptStore   Janino
com/fs/starfarer/campaign/save/B               XStream save freeze
com/fs/starfarer/combat/{CombatEngine,CombatState,collision,entities/Ship}
```

## Janino: complementary, not competing

`modules/renderer/.../MultiThreadedJaninoClassLoader.java` **parallelises script compilation**, and
the agent substitutes `org/codehaus/janino/JavaSourceClassLoader` wholesale to install it.

This repository's plan was to *cache* compiled bytecode instead. Those are complementary, and
reading the source makes the boundary exact:

`MultiThreadedJaninoClassLoader` holds `private final Map<String, byte[]> bytecodeCache =
new ConcurrentHashMap<>()`, and each thread gets its own `JavaSourceCompiler` via a `ThreadLocal`
writing into that shared map. **So they do cache bytecode -- in memory, for the lifetime of one
class loader, in one launch.** It exists to stop two worker threads compiling the same class, not to
carry anything to the next launch. Nothing is written to disk; the map dies with the process.

- **Parallelising** helps every launch, including the first, and scales with cores -- which is
  exactly what a low-core machine cannot offer.
- **Persisting** that same map is the piece they left on the table, and it is Preflight-shaped:
  CPU-count independent, zero work on repeat launches. On this install the profile shows `Thread-4`
  sleeping 33.7s in `ScriptStore$3.run`.

Their in-memory `Map<String, byte[]>` is, quite literally, the thing we would serialise. Doing both
is coherent, and the interface between them is already the right shape.

One design decision of theirs is worth copying regardless: `ScriptLoader.joinScriptLoadingThread`
calls every script constructor **on the main thread**, because vanilla ran constructors on the
loading thread and scripts that call API methods from their constructors raced. Any parallel or
cached script path we build has to preserve that.

## Two questions answered by reading it

### They did fix the prefetcher, and an earlier version of this document said otherwise

**Correction, 2026-08-02.** This document previously claimed: *"They did not fix the prefetcher
[...] The 27-second wait this repository removed on 2026-08-01 is still paid by every Fast Rendering
user."* **That was wrong.** The observation behind it was right and the conclusion did not follow:
`assembly/com/fs/graphics/L.j` does still contain one `Thread` and the 10ms `Thread.sleep` poll
loops, but **nothing under Fast Rendering ever reaches them**, because they replaced the *caller*
rather than the class. The error was checking a class and not its call sites.

The path, end to end:

```
ResourceLoaderState (methods/)  ->  ResourceLoader.loadResource(flags.name(), path)
  case "TEXTURE" / "TEXTURE_OPTIONAL" / "TEXTURE_ALPHA_ADDER"
      -> TextureLoader.queueImage(type, path)
          -> ResourceLoader.workers.execute(...)          // 4-thread pool
              -> FileRepository.FileRepository_loadImage(path)
```

and `ObfTransformations` maps `FileRepository` = `com/fs/graphics/L` with
`FileRepository_loadImage` = `o00000` -- **the private decode method**, which on the name-divergence
table below is one of the two names that agree across Windows and macOS. So their workers call the
decoder *directly*. The enqueue method and the polling getter are never invoked, the prefetch queue
stays empty, and the single decode thread is never started.

**Both projects removed the same 27 seconds, by opposite means.** They spread the decode across four
workers; Preflight takes 50,879 paths off the queue and serves them from a cache. That is the single
most important consequence of this review, and it is the opposite of what this file said before:
**on textures the two projects overlap rather than stack.** Any claim that 29% and 25% compose is
unfounded until measured together.

**They did not cache resource resolution across launches** -- but they do cache it *within* one, and
that is new since the first pass of this review. See `PathCache` below. `assembly/com/fs/util/C.j`
is untouched because the work moved into `com.genir.renderer.overrides.FileUtils`.

**They did not touch the JSON/spec path at all.** `SpecStore` appears only as a compile-time proxy
stub and as a call site used to sequence the load; the sole mention of JSON anywhere in their 132
source files is a `throws JSONException` clause. There is no `JSONObject`, `JSONTokener`, or
`org.json` reference in their tree. That seam is open ground, and it is the largest one left in our
own profile.

## How their startup actually works

Reading `overrides/loading/ResourceLoader.java` answers "what was the 25%". It is one idea applied
four ways: **the load is not serial, so stop running it serially.**

1. **Thread inversion.** `initSpecStore` moves the bulk of resource loading onto a worker and turns
   `main` into a message pump draining `mainThreadQueue` on a 333ms poll, running only the work that
   must touch GL and rendering the progress bar between items. GL stays on the thread that owns the
   context; everything else leaves it.
2. **A 4-worker pool** (`FR-Resource-Loader-Worker`) shared by textures, sounds and scripts, with a
   `mainThreadWaitGroup` counter as the join barrier and a single `AtomicReference<Throwable>`
   funnelling worker failures back to the main thread.
3. **Speculative sprite queueing.** `queueWeaponSprite` / `queueProjectileSprite` /
   `queueShipSprite` queue textures *as each spec is parsed*, so decoding overlaps spec parsing
   rather than following it. `queueShipAndWeaponSprites()` then runs as the authoritative pass --
   "vanilla is the final judge on what should be loaded," in their comment.
4. **Skipping the vanilla epilogue** by throwing a private `SkipVanillaInitEpilogue` out of
   `initSpecStore` to abort the middle of vanilla `init`, then running a hand-written `initEpilogue()`
   that re-does the parts they still want, in order.

Item 4 is worth noting as a technique we should *not* copy: it pins them to the exact statement
order of a specific build's `init`, and it is the kind of thing that breaks silently on a game
update. It is also why their changelog has so many "fixed a race condition in vanilla code" entries
-- parallelising code that was written serially surfaces every latent ordering assumption in it.

**The strategic read: they make one launch cheaper by using more cores; we make the next launch
cheaper by not doing the work at all.** That framing still holds and is still the reason both can
exist. What does *not* hold is the assumption that the two are additive everywhere -- on the texture
path they are two solutions to one problem.

## `PathCache`: their answer to the 77-root probe, and why ours can be better

`overrides/PathCache.java` is 89 lines and directly addresses the seam our roadmap has queued:

- A background thread (`FR-Path-Loader`) recursively enumerates the core directory and the mods
  directory into a `HashSet<String>` of lowercased, prefix-stripped relative paths.
- `exists(File)` becomes a set membership test; if the enumeration has not finished,
  it **falls back to real `File.exists`**, so it fails open the same way we do.
- `FileUtils.findResources` tries the fast path first and, if it finds *nothing*, redoes the entire
  search with real `File.exists` -- a guard against false negatives.
- It is switched off once `GameState.gameInitialized` is set, because a mod deleting a file during
  init would make the set stale, and deallocated by `closeFileRepository()`.

Three things are worth taking, and three are worth improving on:

**Take:** the fail-open-while-warming pattern; the retry-with-real-`exists` guard; and the
observation that this is safe *only* during init, which is precisely the window Preflight cares
about.

**Improve:**

1. **It re-enumerates the whole tree every launch.** Preflight's `ResourceIndex` already holds this,
   built and validated offline on a native JVM -- which the Rosetta finding says is worth roughly an
   order of magnitude on exactly this kind of work. Persisting it is the entire thesis of this
   project.
2. **It answers "does this exist", not "which location wins".** Their `openResource` still walks
   every location in order doing one set lookup each; `ResourceIndex` knows the winning provider
   directly, so the same question is O(1) instead of O(locations).
3. **`normalizePath` lowercases every path.** Their own comment says *"Not sure if this works on
   Linux or MacOS."* It does not, in general: on a case-sensitive filesystem, lowercasing collapses
   distinct files and can produce a **false positive** -- and false positives are exactly what the
   retry-with-real-`exists` guard does *not* catch, since it only fires when the fast path finds
   nothing. Our index stores real paths and does not have this failure mode.

Also, if the enumeration throws, the `RuntimeException` dies inside the executor lambda,
`filesReference` stays null, and the optimization silently never applies. Fail-open, but silent --
the same class of gap as their missing verification gate.

## Their artifacts are build-specific

Their `L.j` and the local `fs.common_obf.jar` agree exactly on **fields** -- same names, order and
types, including `ô00000 = 10485760` -- and on the two private decode methods. The public method
names differ:

| role | this install (macOS RC8) | `L.j` (Windows RC8) |
| --- | --- | --- |
| enqueue image | `Ö00000` | `return` |
| enqueue bytes | `Ó00000` | `Object` |
| get bytes | `new` | `Ò00000` |
| get image | `class` | `Õ00000` |
| reset | `new()` | `Ò00000()` |
| decode image (private) | `o00000` | `o00000` |
| decode bytes (private) | `Ô00000` | `Ô00000` |

Same class, different name assignment. The cause is not established here; the consequence is, and it
cuts both ways. Their name map and assembly cannot be dropped onto this install unverified, and the
reason `AdapterTargetRegistry` pins a class hash rather than trusting a name is exactly this.

The *semantic* map still transfers by signature and position, which is worth a great deal for the
two biggest untouched seams: `SpecStore_init`, `ScriptStore_getScriptList`,
`ScriptStore_javaSourceClassLoader`, `ResourceLoader_getResourceList`, and the whole `TextureHandler`
setter family this repository's prepared-pixel path already writes through.

## The collision is real, predictable, and silent

`fr.vmparams` puts **`fr.jar` first on the classpath**, ahead of `starfarer_obf.jar` and
`fs.common_obf.jar`, and adds `-javaagent:fr.agent.jar`. So their assembled `com/fs/graphics/L`,
`com/fs/util/C`, `TextureLoader`, `ResourceLoaderState`, `SpecStore` and `ScriptStore` **shadow** the
game's classes outright.

`AdapterProbeTransformer` hashes `classfileBuffer` -- the bytes delivered to `transform()`, not the
jar entry. Under Fast Rendering those bytes are theirs, so the pinned
`229d05ef109d56913b2c04263839088aa2719d31bc5fd3d58af6bc2415b84cd2` will not match and the target is
declined.

**Preflight therefore becomes a silent no-op for every Fast Rendering user**, and this is
classpath-level so it does not depend on `-javaagent:` ordering. It fails open, which is correct, but
the telemetry would report a declined target rather than "another agent owns this class", which is
the wrong diagnosis for anyone reading it.

That last part is worth fixing on its own merits, whatever else happens: detect a shadowed target and
say so.

## Getting both

The two projects optimise on different axes: they make a single launch cheaper (defer GL submission,
parallelise the load, thread the renderer), and this repository makes the *next* launch cheaper
(precompute, cache, skip the work entirely). The mechanism collides -- and, per the correction
above, **on the texture path the results overlap too**, since both remove the same 27-second serial
decode. Where they genuinely stack is everything neither has taken: the JSON/spec path, persisted
Janino bytecode, and resolution answered from a persisted index rather than a per-launch scan.

Three ways out, in increasing order of ambition:

1. **Report it.** Detect a shadowed class and emit a specific disable reason. Cheap, honest, and
   stops a user from concluding Preflight is broken.
2. **Pin their identity too.** Register a second target set keyed to their class hashes. Safe and
   exact, but it means tracking their releases.
3. **Change our mechanism where a wrapper is enough.** Constant-pool redirection composes with
   classpath shadowing: redirecting references to `com/fs/graphics/L` intercepts whoever's
   implementation is underneath, theirs or the game's, without needing to know either one's method
   names. That would make the two agents genuinely stackable, and it drops the requirement for exact
   method identity on those seams.

   The cost is the verification gate. Splicing a pinned method lets us prove what we changed;
   redirecting a class name proves nothing about what we wrapped, so the safety story would have to
   move to verifying the wrapper's delegation instead of the target's bytes.

Option 3 is the interesting one and is unproven. Option 1 should happen regardless.

## The macOS fork, and what it proves about names

The version-specific findings below describe the earlier review. See the dated follow-up
[Mac/Linux renderer port review](evidence/2026-09-06-mac-linux-renderer-port-review.md)
for the newer agent-based port and the distinction between source review and native evidence.

<https://github.com/jontyab/starsector-render> is a fork at v0.7.7 (upstream is past v0.8.0) that
ports the project to macOS and Linux. It carries one artifact worth more to us than the port itself:

```
modules/renderer/src/mappings/obf_windows.tsv
modules/renderer/src/mappings/obf_macos.tsv
modules/renderer/src/mappings/obf_linux.tsv
```

85 symbols each, applied to the Jasmin sources at build time by `scripts/rewrite_j_files.py`.

**49 of the 85 diverge across the three platforms -- 58%.** Only 36 are stable. And it is not only
methods: whole class names move, so `AlphaAdder` is `com/fs/graphics/do` on Windows,
`com/fs/graphics/oO0O` on macOS and `com/fs/graphics/M` on Linux.

Two conclusions.

**First, this settles the name-divergence question that section opened.** The divergence is not an
artifact of one class or one build -- it is the majority of every symbol, on every platform, and
anyone doing name-based interception needs a per-platform table. Preflight pins a class hash instead,
and this is the strongest evidence yet that the choice was right: a hash needs no table, and it
cannot silently bind to the wrong member when a platform reshuffles names.

**Second, it independently confirms our own reverse-engineering at the exact point it mattered.**
Their macOS table gives:

| our finding | their macOS mapping |
| --- | --- |
| `com/fs/graphics/L.o00000` is the private image decoder | `FileRepository_loadImage` = `o00000` |
| `com/fs/graphics/L.Ô00000` is the private byte decoder | `FileRepository_loadSound` = `Ô00000` |
| `com.fs.graphics.oO0O` is "a greyscale-to-alpha mask converter that walks the raster" | `AlphaAdder` = `com/fs/graphics/oO0O` |

That third row is the class whose raster walk crashed the load at 23.6s when prepared-pixel mode
served it a 1x1 token carrier. We identified it from a stack trace and a crash; their table names it
`AlphaAdder` and files it under `TextureTransformer`. It walks the raster because adding an alpha
channel is precisely what it is for -- which means the coherent-carrier fix was not a workaround for
one awkward consumer, it was the only correct answer.

**On `PathCache`, the fork did not fix the lowercasing.** It repairs leading-separator handling for
POSIX paths and swaps `path.toFile().isDirectory()` for `Files.isDirectory(path)`, but the
`toLowerCase(Locale.ROOT)` remains -- and the upstream comment admitting *"Not sure if this works on
Linux or MacOS"* was deleted rather than acted on. The hazard is now shipped to Linux, where
case-sensitive filesystems are the norm and two files differing only in case collapse to one set
entry. It is a false positive, which their retry-with-real-`exists` guard does not catch.

## Still open

1. **Whether their built-in profiler** (CTRL+SHIFT+F8, plus a `modules/jfr/` directory) makes the
   frame-time harness in the roadmap redundant.
2. **macOS.** It targets Windows, and the name divergence above means porting is not a repackaging
   job.
3. **Janino.** They parallelise; caching and parallelising compose, and neither has been measured
   here against a corrected clock.

## Licensing

The Unlicense covers the author's own work, and is as permissive as licences get.

It cannot cover `assembly/*.j`. Those are Jasmin disassemblies of Starsector's obfuscated classes,
which are Fractal Softworks' and not the author's to dedicate to the public domain. The same rule
this repository already follows applies: read locally for interoperability, copy nothing in.
