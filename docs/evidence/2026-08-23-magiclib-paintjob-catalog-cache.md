# MagicLib paintjob catalogs no longer need to be rebuilt every launch

**Date:** 2026-08-23

**Profile:** reviewed 83-mod installation, exact fingerprint
`2995668308ac3d31d645ccac30fb1a7e644e64fce5609050a1488df4cadc5af6`

**Runtime:** Starsector's bundled Zulu 17.0.10 x86_64 JRE under Rosetta 2

## Why this was the next target

After the vanilla readers and larger AshLib and GraphicsLib paths became cheaper, an exact callback
probe put MagicLib's application plugin at about 0.88 seconds. Its paintjob loader owned 0.65
seconds of that callback. The remaining GraphicsLib, Nexerelin and AshLib work had either more
side effects or a smaller measured ceiling, so the paintjob catalog was the strongest bounded
candidate.

The loader is ordered registry construction. It reads the merged paintjob tables, creates paintjob,
engine, shield and weapon objects, registers them by ID and updates MagicLib's auto-unlock set. The
result is stable for an unchanged ordered `data/` resource corpus, but the objects themselves are
mutable and cannot be retained across game processes.

## Cache boundary

The adapter is limited to MagicLib 1.5.6's reviewed manager class:

- class SHA-256:
  `841f945d675920e0fad9ccf13c7fa3144b6437489b6ba11e121a3267e6b8993c`
- source archive SHA-256:
  `af028fcd67dd537024eab0082d3e78cac8508355dbd5f8731b6c243c60dae0d5`
- class-file Java major: 61
- ordered merged-resource identity:
  `8f57ff075ae38b02bb312b1d6bd7b97fe1a5bda93fc50e84254743b35ae2c447`

The resource identity is the existing merged-read profile identity. It covers the enabled providers
in load order and the game archive, so this cache adds no second profile hash pass. A changed mod,
resource order, game archive, class, loader or method shape declines the adapter and runs MagicLib's
original code.

The artifact stores immutable field data, not MagicLib objects. Every hit constructs fresh mutable
objects, replays the original map and set mutations, and verifies that every map key matches the
embedded spec ID. Duplicate IDs, malformed bounds, checksum failures and mismatched identities are
rejected. The artifact is transactionally published and bounded to 64 MiB on disk and 32 MiB after
decode. Any rejection falls back to the original loader.

## Learning and replay observations

The first diagnostic launch learned the catalog:

`~/.starsector-preflight/runs/magic-paintjob-learn-20260823-160958`

| observation | result |
| --- | ---: |
| MagicLib application plugin | 875 ms |
| `magic.paintjobs` | 650 ms |
| cache outcome | 1 miss, 1 write |
| catalog capture | 109 ms |

The next diagnostic launch replayed it:

`~/.starsector-preflight/runs/magic-paintjob-hit-20260823-161059`

| observation | result |
| --- | ---: |
| MagicLib application plugin | 246 ms |
| `magic.paintjobs` | 52 ms |
| cache outcome | 1 hit, no fallback |
| catalog replay | 35 ms |

That is a 598 ms reduction in the paintjob loader and a 629 ms reduction in the enclosing MagicLib
callback. These are direct measurements of the owned call sites. The diagnostic launch clock moved
from 16.86 to 15.92 seconds, but the probe itself adds instrumentation and is not the public startup
clock.

The first normal exact-marker benchmark after both diagnostic launches was:

`~/.starsector-preflight/benchmarks/20260823-161241`

It recorded 15.96 seconds from `Running with the following mods` to GraphicsLib's
`VRAM after unload/preload`, with one catalog hit, 32 ms of replay, no fallback and no contained
failure. The fanless machine had just completed two instrumented game launches, so this observation
does not replace the cooled 14.49 and 14.84-second current-path results or the earlier 13.686-second
best run. A cooled exact-marker launch is the remaining whole-startup check.

Evidence identities:

- learning adapter report SHA-256:
  `4ebbf839f9e7ee9a568b038ed67aa2add9634cc8d030e5dc9ab06b444a8470e9`
- hit adapter report SHA-256:
  `0a73eb8726f41224d320813582aa2d2c2e1ebd063ec6facf7ddccedbfbaef213`
- exact benchmark summary SHA-256:
  `9c37d602ca5a7bebe52e47af37d2df5398c5ca31f92cdc7eaa04524c0db212e3`

## Process correction

One UI-automation attempt was mistakenly described using wrapper lifetime while it waited for a
mouse action. That was not a startup measurement. Startup timing has one accepted definition here:
the same two exact log markers used by `scripts/run-startup-benchmark.sh`. Missing either marker
means there is no startup number. UI automation remains useful for clicking, screenshots and clean
shutdown; it does not own the clock.

