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

This repository's plan was to *cache* compiled bytecode instead. Those are complementary:

- **Parallelising** helps every launch, including the first, and scales with cores -- which is
  exactly what a low-core machine cannot offer.
- **Caching** does no work at all on repeat launches, and is CPU-count independent. On this
  install the profile shows `Thread-4` sleeping 33.7s in `ScriptStore$3.run`.

Caching is the better fit for Preflight's thesis and for the long tail. Doing both is coherent.

## Two questions answered by reading it

**They did not fix the prefetcher.** Their `assembly/com/fs/graphics/L.j` still starts exactly one
`Thread` and still contains the 10ms `Thread.sleep` poll loops in both the image and byte getters.
The 27-second wait this repository removed on 2026-08-01 is still paid by every Fast Rendering user.
Our result is not duplicated there.

**They did not cache resource resolution.** `assembly/com/fs/util/C.j` contains zero `HashMap`,
zero cache of any kind, and the same `File.exists` / `lastModified` / `listFiles` probing across
every mod root. That seam is still open ground.

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

The two projects optimise on different axes and compose in principle: they make a single launch
cheaper (defer GL submission, parallelise Janino, thread the renderer), and this repository makes the
*next* launch cheaper (precompute, cache, skip the work entirely). Nothing about that is in conflict.
Only the mechanism collides.

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
