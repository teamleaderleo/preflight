# The game runs under Rosetta, and it decides what is worth optimizing

**Date:** 2026-08-01
**Install:** Starsector 0.98a-RC8, 77 enabled mods, macOS 15, M5 MacBook Air (10 cores), 24 GB
**Recording:** `~/.starsector-preflight/benchmarks/20260801-222649/runs/profile-1/startup.jfr`
**Status:** measured; the two changes it motivated are shipped and smoke-tested, not yet campaigned

## What the profile says now

The first profile taken after the prefetch bypass landed is a different load from the one before it.

| | before the bypass | after |
| --- | --- | --- |
| `main` blocked | 27.4s | **0.1s** |
| `main` on-CPU share | -- | 54.0% of 4,876 samples |
| top blocking site on `main` | the prefetcher's poll loop | `ZipFile.getEntry`, 0.1s |

The 27 seconds of waiting is gone, confirmed by the instrument rather than by the stopwatch. What is
left is a thread that computes, and one frame dominates what it computes:

| top on-CPU frames on `main` | share |
| --- | --- |
| `VarHandleByteArrayAsInts$ArrayHandle.index` | **40.9%** |
| `com/fs/graphics/TextureLoader.o00000` | 6.5% |
| `TextureCompatibilityRuntime.reconstruct` | 5.4% |
| `SinglePixelPackedSampleModel.getPixel` | 5.1% |
| `ArraysSupport.newLength` | 4.1% |
| `DataBufferByte.getElem` | 2.7% |
| `ComponentSampleModel.getPixel` | 2.2% |

Every one of those 1,076 samples has the same stack:

```
java/lang/invoke/VarHandleByteArrayAsInts$ArrayHandle.index
  <- ArrayHandle.get <- VarHandleGuards.guard_LI_I
  <- sun/security/provider/ByteArrayAccess.b2iBig64
  <- sun/security/provider/SHA2.implCompress0
  <- sun/security/provider/DigestBase.engineUpdate
  <- java/security/MessageDigest.update
```

That is the cache verifying, once per lookup, that each source file still hashes to what the blob
was built from.

## Why it is that slow

`SHA2.implCompress0` has a HotSpot intrinsic. Seeing `b2iBig64` underneath it means the intrinsic
did not apply, and the pure-Java VarHandle implementation ran instead.

The reason is not subtle once you look:

```
$ file /Applications/Starsector.app/Contents/Home/bin/java
Mach-O 64-bit executable x86_64
```

**Starsector ships an x86_64 JRE.** The entire game runs under Rosetta 2 on Apple Silicon, and
Rosetta does not implement Intel's SHA-NI, so the JVM's CPU feature detection finds no SHA extension
and never enables `UseSHA256Intrinsics`. The launch flags corroborate it -- `-XX:UseAVX=3`,
`-XX:UseSSE=4`, `-XX:+UseBMI2Instructions`, `-XX:+UseCLMUL` are an x86 tuning set, and none of them
mean anything on an ARM core.

Measured directly, same bytes, same machine, both JVMs warmed:

| JVM | SHA-256 |
| --- | --- |
| the game's own JRE (x86_64, Rosetta 2) | **292 MB/s** |
| a native arm64 JDK 21 | **3,314 MB/s** |

**11.4x.** The corpus is 1,344,517,311 bytes across 32,917 manifest entries, of which one launch
looks up 21,653.

## What was done about it

Not "make the hash faster" -- there is no version of that available inside the game's JVM. The work
was removed.

`configure()` already runs `ResourceIndexValidator` across every provider in the index before the
first lookup happens, and that validator's staleness test is **size and modified time**, not
content. The index records both per provider. Re-checking those two at lookup costs one
`readAttributes`, which is the same syscall the `isRegularFile` guard was already paying, and it
closes the window between configure and lookup.

What that gives up is real and worth naming: an edit that changes a file's contents while preserving
both its length and its modification time to the millisecond is no longer detected. Every mod update
changes at least one of the two, and it is the staleness contract every build system in common use
accepts. `-Dpreflight.texture.verifySourceHash=true` restores the content hash.

## The wider consequence

This is not a fact about SHA-256. It is a fact about every CPU-bound thing in the load.

Anything Preflight computes inside the game's JVM runs translated, without AES-NI, SHA-NI, or any
AVX-512 path the flags ask for. Work moved **out** of that process -- into `prepare`, which runs on
a native arm64 JVM -- gets roughly an order of magnitude on exactly the operations the cache formats
lean on. Work left **inside** it should be counted at Rosetta prices, and a frame that looks
affordable in a native profile may not be.

It also means the largest single lever on this machine is one Preflight does not hold: a native
arm64 JRE for the game. Starsector's LWJGL 2 native libraries are x86_64, so this is Fractal
Softworks' to pull, not ours, and it is worth reporting for that reason.

## What is next in this profile

With the hash gone and the two texture optimizations composed, the remaining frames on `main` are:

1. **The game's own path resolution.** 148 native samples in `File.exists` under
   `com/fs/util/C.Ô00000` from `LoadingUtils.super` -- the loader probing each of 77 mod roots in
   turn for every resource. Preflight already has an index that knows the winner.
2. **The JSON/spec path**, still untouched: `JSONTokener.nextString`, `Rules.super`, and 153 native
   samples in `LoadingUtils.super` reading through `FileInputStream`. `ArraysSupport.newLength` at
   4.1% suggests collections growing without being presized.
3. **`glTexImage2D`**, 74 native samples. Irreducible; that is the upload actually happening.

Off the critical path but worth knowing: four `Thread-VC-*` threads spend 527 native samples in
`SocketDispatcher.read0`. Mod version checkers, phoning home during the load. `main` never waits on
them.
