# Starsector runs under Rosetta 2 on Apple Silicon (2026-07-25)

Found while trying to make the GPU capability probe portable. The LWJGL probe failed to load the
game's native library with an architecture mismatch, and the mismatch turned out to be the finding.

## What the installation actually contains

```
$ lipo -archs /Applications/Starsector.app/Contents/Resources/Java/native/macosx/liblwjgl.jnilib
x86_64

$ lipo -archs /Applications/Starsector.app/Contents/Home/bin/java
x86_64

$ /Applications/Starsector.app/Contents/Home/bin/java -XshowSettings:properties -version
    java.version = 17.0.10
    os.arch = x86_64
OpenJDK Runtime Environment Zulu17.48+15-CA (build 17.0.10+7-LTS)
```

The macOS build ships an **x86_64 JVM (Azul Zulu 17.0.10) and x86_64 LWJGL 2 natives**. There is no
arm64 slice in either. On the reviewed machine — an Apple M5 — the entire game therefore runs under
**Rosetta 2 translation**: the JVM itself, the JIT it produces, and every native call.

This is not a macOS-only curiosity dressed up as a finding. It means every CPU-side cost this
project measures on macOS — the ~24–28% of samples in audio decoding, the ~25–27% in Janino, the
texture preparation work — was measured through binary translation. The proportions between
subsystems are probably not distorted much, since all of them are translated alike, but the absolute
figures are not native figures and should never be quoted as if they were.

## Whether this invalidated the GPU evidence

It could have. [The capability probe](2026-07-25-macos-gl-capability-probe.md) was originally
compiled natively for arm64, while the game's GL calls arrive through the x86_64 translated path. If
those two saw different drivers, the evidence would have been about the wrong one.

Checked rather than assumed, by re-running the same probe three ways:

| path | BC1/BC3 | BC7 | NPOT native |
|---|---|---|---|
| arm64 CGL (native) | yes | `GL_INVALID_ENUM` | yes |
| x86_64 CGL (Rosetta) | yes | `GL_INVALID_ENUM` | yes |
| LWJGL 2 in the game's own x86_64 JVM | yes | `GL_INVALID_ENUM` | yes |

Identical. Rosetta translates CPU instructions; the GL calls reach the same Apple driver either way.
The capability evidence stands unchanged.

## Why a native build is not simply available

Java bytecode is architecture-independent, so the game's own code would run on an arm64 JVM
untouched. The blocker is one native library: **LWJGL 2 has no arm64 macOS build**. LWJGL 2's last
release predates Apple Silicon by years, and arm64 macOS support arrived in LWJGL 3
([LWJGL/lwjgl3#601](https://github.com/LWJGL/lwjgl3/issues/601)). Running natively would require
either an arm64 rebuild of LWJGL 2 or an LWJGL 2 → 3 shim behind the same API surface, plus the
same treatment for `jinput`.

That is well outside this project's scope and squarely in the game's. It is recorded because it is
almost certainly a larger lever than anything preflight does on this hardware, and because anyone
reading preflight's macOS numbers needs to know what they were measured through.

## Related: what the OpenJ9 community result does and does not say

A [May 2025 thread](https://fractalsoftworks.com/forum/index.php?topic=32926.0) reports swapping the
JRE for Eclipse OpenJ9 on 0.98a: **42.5 vs 32.5 fps** on the GraphicsLib benchmark, and **1205 vs
1313 MB** of RAM. A second reporter measured a larger gain (70–90 vs 50–70 fps) but abandoned it over
intermittent stalls that `-Xjit:optlevel=veryHot` removed only by erasing the gain — the signature of
JIT recompilation, not of anything preflight touches.

Two things follow, and the second is the useful one.

**It does not overlap this project.** Every number in that thread is steady-state frame rate and
resident memory. Nothing in it measures startup or load time. Preflight's territory is untouched by
it, and the two are stackable rather than competing.

**It contains a rejected lever that was rejected on the wrong benchmark.** The installation notes
say to delete `AppData\Local\javasharedresources` and run with `-Xshareclasses:none`, because the
caching feature was on by default, "provided questionable performance improvements", and cost 300 MB.
That feature is OpenJ9's **shared class cache** — its counterpart to the AppCDS archive preflight
builds. A class cache does its work during class loading, which happens before the first frame; an
FPS benchmark cannot see it by construction. So the community's one data point against JVM-level
class caching in Starsector was collected with an instrument that could not detect the effect, and
should not be read as evidence against preflight's AppCDS work.

The 300 MB figure is worth keeping regardless — it is a real cost, and preflight's own archive should
be reported against it.

## Incidental: the shipped classpath contains a WebP decoder

The same thread quotes the full launch classpath, and the reviewed macOS installation confirms it:
`webp-imageio-0.1.6.jar` is present alongside the LWJGL and Janino jars. The engine can therefore
decode WebP images.

This is a disk-size lever only, not a video-memory one — WebP decodes to RGBA like PNG, so resident
cost is unchanged and decode is likely slower, which is the wrong direction for the speed track. It
is noted so the option is known to have been considered and set aside, rather than discovered later
as an oversight.
