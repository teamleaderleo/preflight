# The final texture-prefetch wait was one WebP

**Date:** 2026-08-05

**Install:** Starsector 0.98a-RC8, 83 enabled mods, macOS on Apple M5 under Rosetta

## Finding

The first profile of the current `--fast` preset still showed **117 consecutive sleeps** on the
main thread below Starsector's image-prefetch consumer. Correcting the recording's known 0.401x
clock made them about **1.27 seconds** of blocked time. Texture telemetry explained the tail:
50,879 resources were removed from the vanilla queue, but one was retained because the prepared
manifest had no entry for it.

The enabled profile contains three WebP files. Preflight's native arm64 preparation JVM had no
WebP ImageIO reader, so all three remained ordinary fail-open textures. Starsector ships
`webp-imageio-0.1.6.jar`, but its macOS decoder is an x86-64 native library loaded inside the game
under Rosetta. The one retained startup resource was enough to keep the one-thread queue and its
10ms consumer polling loop alive.

## Change and fidelity boundary

The preparation CLI now includes TwelveMonkeys ImageIO WebP 3.13.1, a pure-Java reader. The
dependency is deliberately preparation-only: no WebP class is called by the game agent, and an
unreadable or malformed format still follows the existing unsupported/failure path instead of
changing the game at runtime. The executable-jar shade retains the ImageIO service registry.

Before integration, both readers decoded the two simple lossless VP8L WebPs independently. Hashing
canonical ARGB pixels produced identical results for each file:

| dimensions | TwelveMonkeys | Starsector's native reader |
| --- | --- | --- |
| 40x40 | `8ce23df728d523fe3da0d878587c2007f7aa11bc390d1d8af4c0f149748137ba` | same |
| 380x571 | `369bd184f7d6dd40974a3cb6a325d66309d3f87cc3d39483907a3efeccbedc2d` | same |

On the 40x40 startup icon, fresh-process initialization plus decode took about 27ms in the arm64
pure-Java reader versus 455ms in Starsector's x86 native reader. These timings are explanatory
micro-measurements, not the launch attribution.

The third enabled WebP is an extended lossy-alpha file. Its canonical pixels differed between the
two decoders, so exact preparation permits only the directly encoded lossless `VP8L` form. Extended
or lossy WebP stays on Starsector's authoritative decoder. The eligibility check runs before a
content-addressed cache hit is accepted, so an artifact created under an older policy cannot bypass
a tightened fidelity boundary.

Unit coverage proves a simple lossless WebP becomes a prepared manifest entry, an extended WebP
stays unsupported even if a stale prepared blob already exists, and a genuinely unsupported TGA
still fails open. Full `mvn verify` passes. The final real deep preparation considered **32,920**
candidates, retained **32,919** manifest entries, reused 30,638 valid unique blobs, and reported
one intentional unsupported fallback with zero failures or invalid entries.

## Live gate

The first profiled unattended launch was
`~/.starsector-preflight/benchmarks/20260805-234954`. After adding the exact-fidelity gate, the final
confirmation is `~/.starsector-preflight/benchmarks/20260806-000649`:

| signal | before | after |
| --- | ---: | ---: |
| prefetch resources kept | 1 | **0** |
| main-thread prefetch sleeps | 117 | **0** |
| main-thread blocked time | about 1.4s | **about 0.1s total** |
| prepared texture hits | 15,468 | **15,469** |
| final gated profiled wall time | 24.42s | **24.15s** |

The three remaining `entry-missing` calls are repeated dynamic lookups that were never queued;
they therefore neither block on the prefetcher nor weaken the optimization. The final recording
contains no main-thread sleep events. All 33 exact transformations applied, none declined or
failed, the main menu was reached, and the harness shut the game down.

## Ordinary cohort

The following one-minute-cooled non-profile cohort is
`~/.starsector-preflight/benchmarks/20260805-235131`:

- **23.24, 23.34, 23.03, 22.90, and 22.99 seconds**;
- **23.03-second median**, 0.44-second full range;
- every run served 15,469 prepared textures, skipped 50,880 prefetch enqueues, and retained none;
- every run applied all 33 exact transformations with zero decline/failure and stopped
  automatically.

The adjacent prior cooled cohort measured 23.68 seconds median. The **0.65-second median shift** is
directionally consistent with removing the final wait, while the event count and blocked-time
change provide the exact attribution. The sessions were adjacent rather than a shuffled paired A/B.
