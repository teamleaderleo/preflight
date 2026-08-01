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

## Open questions before anything is built on this

1. **Agent collision.** Both are `premain` transformers and both rewrite `com/fs/graphics/L` and
   `TextureLoader`. Transformers chain, so an ASM splice may run against a constant pool the other
   agent has already rewritten, or vice versa. Untested. Anyone running both today is in undefined
   territory, and that needs an answer before either is recommended alongside the other.
2. **Whether their `com/fs/util/C` work already solves resource resolution**, which was the next
   queued item here.
3. **Whether their built-in profiler** (CTRL+SHIFT+F8, plus a `modules/jfr/` directory) makes the
   frame-time harness in the roadmap redundant.
4. **macOS.** It targets Windows. How much is portable is unknown.

## Licensing

The Unlicense covers the author's own work, and is as permissive as licences get.

It cannot cover `assembly/*.j`. Those are Jasmin disassemblies of Starsector's obfuscated classes,
which are Fractal Softworks' and not the author's to dedicate to the public domain. The same rule
this repository already follows applies: read locally for interoperability, copy nothing in.
