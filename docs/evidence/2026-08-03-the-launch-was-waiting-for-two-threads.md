# The launch was waiting for two threads to decode audio

**Date:** 2026-08-03
**Install:** Starsector 0.98a-RC8, 83 enabled mods, macOS 15, M5 MacBook Air (10 cores), 24 GB
**Probe runs:** `~/.starsector-preflight/runs/rootopen-20260803-153548`,
`~/.starsector-preflight/runs/prepared-audio-*`
**Result:** **40.13 s -> 35.93 s** on one probed launch, by not decoding audio during the launch.

## Finding it

After the resolver work, no block above 4.4 s remained and the launch would not go below 40 s. The
phase probe cannot say whether a block is busy or waiting, but the game's own log can: every line
carries a millisecond stamp and a thread name.

Charging each gap between consecutive `[main]` lines to the logger on either side:

| the main thread's 39.01 s | seconds |
| --- | ---: |
| `TextureLoader` | 13.51 |
| `LoadingUtils` | 4.42 |
| `Rules` | 1.72 |
| 21 other loggers | 2.32 |
| **between one logger and the next** | **16.37** |

Only **2.87 s** of the whole launch has no thread logging anything, so the launch is busy rather
than idle. And that last row is not one block:

| | gaps | seconds |
| --- | ---: | ---: |
| over 1 s | 2 | 4.64 |
| 201-1000 ms | 10 | 4.49 |
| 11-200 ms | 121 | 4.86 |
| under 10 ms | 5,072 | 2.39 |

The largest single gap is **3.46 s**, from 24.37 s to 27.83 s. During it `pool-1-thread-1` and
`pool-1-thread-2` log 516 lines of `Loading sound [...]`; the last one lands at 27.66 s and main
resumes 0.17 s later. In `ResourceLoaderState.init`:

```
1989: iconst_2
1990: invokestatic  Executors.newFixedThreadPool:(I)Ljava/util/concurrent/ExecutorService;
...
2410: invokeinterface ExecutorService.shutdown:()V
2423: invokeinterface ExecutorService.awaitTermination:(JLjava/util/concurrent/TimeUnit;)Z
```

Two threads, on a ten-core machine, and `awaitTermination` is what main is sitting on.

## Why widening the pool is the wrong fix

Every decode path in `sound.ooOO` ends like this, with no lock held:

```
36: getfield  OO0000:Ljava/util/HashMap;   -> get(name)
78: invokestatic AL10.alGenBuffers
102: invokestatic AL10.alBufferData
123: invokevirtual HashMap.put(name, id)
```

A plain `HashMap` written from N threads, plus OpenAL calls on a shared context. The game validated
that at two. Turning it up widens a race it currently gets away with, which is a correctness change
wearing a performance change's clothes.

## What the work actually is

The cost scales cleanly with encoded bytes, which is what pure Vorbis decode looks like:

| ogg bytes | sounds | mean ms |
| ---: | ---: | ---: |
| 8,192 | 467 | 3.9 |
| 32,768 | 419 | 10.5 |
| 131,072 | 124 | 43.7 |
| 524,288 | 10 | 233.9 |

**2,099 files, 140.7 MB, 30.5 thread-seconds**, producing 1.23 GB of PCM.

## Decoding it beforehand instead

`sound.J.o00000(InputStream)` is the whole decode -- no OpenAL, no shared state -- returning a
`sound.F` of three public fields: channel count, a direct 16-bit PCM buffer, and the sample rate.
Everything unsafe happens in the caller, after it returns. So that is the seam, and the pool stays
exactly two threads wide doing exactly what it did.

Blobs are keyed by SHA-256 of the encoded bytes the decoder was handed, so a served blob is keyed by
exactly the input it replaces. Hashing the corpus costs 0.52 s across the pool's two threads, and
buys never having to ask whether a path still means what it meant at bake time.

Replaying the launch's own sounds against the real installed classes:

| | |
| --- | ---: |
| decode with the game's decoder | 19.74 s |
| serve from prepared blobs | **5.86 s** |
| equivalence | **2,099 checked, 0 mismatches** |

Every field compared and the PCM byte for byte. 2,049 served; the other 50 do not decode at all --
the game logs an error and carries on with an empty result -- so they are not baked and fall through
to the same failing decode they always had.

Serving was 10.21 s until `PreparedAudioIO` stopped hashing the same bytes twice: it verified a
SHA-256 over the payload and then a second one over the PCM inside it, 1.23 GB each. The payload
checksum already covers every byte the PCM checksum covers, and corruption still fails on it.

## The launch

One probed launch, `--fast`, against the same flags measured at 40.13 s earlier the same day:

| | before | after |
| --- | ---: | ---: |
| game log start to main menu | 40.13 s | **35.93 s** |
| `audio-workers-complete` phase | 3.31 s | **absent** |
| mod callbacks | 8.30 s | 7.88 s |
| spec store | 9.96 s | 9.90 s |

and from `adapter.json`:

```
preparedAudio      2,050 intercepted, 2,049 served, 1 decoded by the game, 0 failures
resourceProbeCache 906,593 probes, 906,464 without a syscall, 4 deferred, 0 failures
loadJsonMemo       39,018 calls, 29,544 from the memo, 7,444 distinct paths, 0 failures
```

Those three runtimes collected counters and never wrote them until now, so a launch could not be
asked afterwards what any of them had done.

## Cost

1.23 GB on disk, baked in 40.5 s by `preflight audio prepare`. The bake runs the installation's own
decoder in a child process on the installation's own Java, because the blobs have to be what *that*
decoder produces; the decoder's identity is hashed into every cache key, so a game update that
changes it simply stops matching and every sound falls through to a real decode.

## Reproduction

```bash
java -jar preflight-cli/target/preflight.jar audio prepare --game /Applications/Starsector.app
```

```bash
scripts/probe-launch.sh --label prepared-audio -- --fast
```
