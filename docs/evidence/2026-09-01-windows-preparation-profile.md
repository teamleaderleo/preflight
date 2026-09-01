# Windows preparation profile — exact identity work was accidentally serial and repeated

Date: 2026-09-01  
Status: measured candidate; not yet a release claim

## Answer first

The shared exact-content hashing work from #300/#322/#603/#832 was present and working. The
`SpecStore` preparation identity was a missed consumer: it independently resolved and SHA-256
hashed every ordered `data/` provider in a serial loop. Preparation also scanned the complete
resource profile twice and validated the same 61,707-provider index twice.

On the installed Windows profile, a warm same-machine pair changed preparation wall time from
45.828 seconds on main to 25.323 seconds with the candidate: 20.505 seconds saved, or 44.7%.
The candidate ran first and main ran immediately afterward. No game was launched.

## Exact pair

Both runs used Windows 11, the same 12 GiB / 16-vCPU VM, Eclipse Temurin 21.0.12, Starsector
0.98a-RC8, the same installed mods, the same cache, and:

```text
prepare --game C:\Games\Starsector
        --cache-dir C:\Users\Leo\AppData\Local\Starsector Preflight\cache
        --deep --verify-lookups
```

Main was `e827f2fa` and its measured JAR SHA-256 was
`1d9de6b241801fda02449cabdb6b7c4c23612561f20eed9e5e2a242df5c3fca1`. The candidate was
`7641a980`; its JAR SHA-256 was
`f9f4a447206cb31aeb79ba022ce146cdfb93e28f4e505288749bc00349a23205`.

| Clock or stage | Main | Candidate |
| --- | ---: | ---: |
| process wall clock | 45,828.3 ms | 25,323.0 ms |
| report body | 28,448.0 ms | 17,679.0 ms |
| resource-index stage | 18,401.8 ms | 10,843.7 ms |
| fresh resource walk (`buildMs`) | 8,222.7 ms | 6,370.0 ms |
| SpecStore identity | 4,201.8 ms | 892.0 ms |
| census | 9,933.0 ms | 9,707.8 ms |
| classpath index | 2,284.0 ms | 2,272.4 ms |
| cached textures | 1,451.6 ms | 1,485.9 ms |
| lookup verification | 4,384.8 ms | 4,447.5 ms |

The two clocks agree about where the change came from. Census, classpath, texture, and lookup
times remained close. The candidate removed one complete pre-ownership resource walk, one of two
identical resource validations, and the serial SpecStore hashing path.

## Correctness and workload identity

- Resource fingerprint remained
  `cfe95f25f14ce426766539225fd1fdab520d728b117a317413f47d3c40fbae3a`.
- SpecStore identity remained
  `57e7fcae9fba939c7fe8e908d9ed61655ca9f2d35913d6d6f9fb9eaa35b5dcf8`.
- Both runs covered 59,395 resource entries and 61,707 providers.
- Both SpecStore identities covered 17,839 ordered data providers, 55,945,461 provider bytes,
  and 85 classpath archives.
- The candidate used eight bounded content-hash workers and retained request order, stable-file
  before/after checks, containment checks, and exact artifact identity.
- Both texture stages reused all 30,638 prepared blobs and built zero.
- Both lookup-verification stages succeeded.
- The older profile artifact was an exact hit under the candidate, which is direct evidence that
  the identity bytes did not change.
- Focused tests passed for serial/parallel identity equivalence, mutation-during-hash refusal,
  resource mutation after lease acquisition, deletion during the final validation gate, and
  preservation of a previously validated artifact after failure.

## Why Linux and macOS did not look the same

This was not a Windows cache-key format and not missing cross-platform SHA-256 support. Windows
made a portable algorithmic mistake expensive: tens of thousands of serial opens, real-path
resolutions, attribute reads, and content reads have much higher observed latency there. Warm
macOS/Linux page cache hid much of that cost.

The retained Linux preparation report is not a paired comparison. It describes a smaller workload:
16,439 SpecStore providers and 8,919 texture candidates, versus 17,839 and 32,920 on Windows.
Linux completed its recorded preparation in 7.060 seconds, including 6.172 seconds building 8,483
texture blobs. Those numbers should not be used as a platform speed ratio.

Platform selection itself remains explicit. `Platform.current()` derives the host from `os.name`.
Linux's startup-GC policy then fails closed unless the launcher text and bundled runtime release
match the reviewed Zulu x86_64 Java 17 identity. Game-bytecode adapters use exact reviewed class and
source identities because obfuscated names differ between installed builds.

## Cold observation that motivated the fix

One earlier cold Windows preparation spent 95,948.5 ms in SpecStore identity and 113,385.3 ms
building the full texture corpus. It produced the same SpecStore identity and was useful for target
selection, but it is not compared numerically with the warm candidate above.

## Fast Rendering boundary

Fast Rendering 0.8.4 is installed only on the Windows fixture; upstream describes it as
Windows-only. Its archive SHA-256 is
`929e7efacdf77736331eb3f8f44bf75ce47f9cdd7a2a080c0db69276fedbc6e2`.
An explicit `fr.bat` Preflight dry run selected runtime owner `FAST_RENDERING` for the expected
launcher reasons. That proves ownership discovery, not full launch compatibility or performance.

The committed Windows cohort runner owns the next check: shuffled repeated Starsector, Preflight,
Fast Rendering, and Preflight-plus-Fast-Rendering launches with exact hashes, adapter health,
runtime ownership, renderer process proof, and start-marker-to-graphics-preload timing. Gameplay
FPS from this VM is not representative because the guest currently uses llvmpipe software
rendering.

## Retained run names

The reports and logs remain on the Windows fixture under `C:\VM-Setup\Preflight`:

- `windows-prepare-final-candidate.{json,log}`
- `windows-prepare-main-final-pair.{json,log}`
- `windows-prepare-specstore-candidate.{json,log}`
- `windows-prepare-specstore-index-candidate.{json,log}`
- `windows-prepare-warm-baseline.jfr`

The candidate and main pair both exited zero. No Starsector process was left running.
