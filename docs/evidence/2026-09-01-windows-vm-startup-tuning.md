# Windows VM startup tuning — scheduling fixed, serialized texture loading remains

Date: 2026-09-01 (host) / 2026-09-02 (Windows guest)  
Status: accepted exploratory A-B-A plus same-semantics native confirmation; not a repeated release claim

## Answer first

The Windows fixture was not merely “a slow VM.” It exposed two separable effects.

First, its 16 guest vCPUs included Big Red's two 2.5 GHz low-power cores while QEMU's emulator and
disk I/O threads were pinned onto those same cores. Both host and guest also used balanced power
plans, and Windows Defender had no exclusions for the 89-mod game tree or Preflight cache. After
reserving those two low-power cores for QEMU, giving Windows the fourteen 4.4-5.1 GHz cores, setting
both systems to performance plans, and adding only those two Defender path exclusions, stock
Preflight reached the graphics-preload marker in 111.910 and 115.413 seconds. The earlier accepted
stock result was about 181.313 seconds. This is one tuned A-B-A observation, not attribution of the
gain to any one knob.

Second, Preflight plus Fast Rendering reached the same graphics marker in 38.010 seconds between
those stock runs. That marker was early: its exact semantic `main-menu-interactive` transition was
50.998 seconds after process start. Even on the stricter usable-menu clock, the combined route was
about 2.2 times faster than the stock route's 111-115 second ready interval.

The remaining large target is now specific. Stock Starsector logged 15,458 texture-loader operations
on the main thread across 110.087 seconds. Fast Rendering logged 15,573 texture loads across three
workers plus 542 main-thread operations across 49.467 seconds. Preflight's stock prepared-pixel
adapter served 460 exact hits and 229,314,384 bytes with zero prepared-pixel fallbacks; its own code
spent only 0.763-1.855 seconds inside the intercepted loads while 106.6-108.8 seconds elapsed between
them. Hashing and pixel conversion are no longer the dominant Windows startup cost in this fixture.

After the cohort runner was repaired and pushed to `main` at `1ba9286f`, a native confirmation made
both Preflight-backed conditions wait for the exact interactive title boundary. Stock Preflight took
112.455 seconds to graphics preload and 125.255 seconds to the interactive menu. Preflight plus Fast
Rendering took 37.002 and 49.551 seconds respectively. On the same time-to-play boundary, the
combined route was 60.4% faster, or 2.53 times quicker.

## Exact A-B-A

All three runs used the same Windows 11 installation, 89-mod profile, 1024x720 windowed display,
llvmpipe renderer, current prepared cache, Eclipse Temurin 17.0.10 game runtime, and Preflight JAR:

```text
9b3fedf64716b3a051dfd74d9efa8fc0dcf0efb3e0498a8fea9f4df6654e3e55
```

The prepared-cache check passed before every leg and preparation was not run.

| Order | Condition | Game-log start -> graphics preload | Process start -> semantic menu | Adapter result |
| ---: | --- | ---: | ---: | --- |
| 1 | Preflight | 111.910 s | 111.292 s to `main-menu-ready` | accepted; 23 applied / 24 exact; one bounded decline |
| 2 | Preflight + Fast Rendering | 38.010 s | 50.998 s to `main-menu-interactive` | accepted; 19/19; zero declines |
| 3 | Preflight | 115.413 s | 114.121 s to `main-menu-ready` | accepted; 23 applied / 24 exact; one bounded decline |

The stock decline was the exact combat-runtime-integrity plan declining `CombatState` and retaining
the original bytes. Both stock runs reported zero contained failures and zero source-binding
rejections. The combined run reported zero declines, contained failures, or source-binding
rejections. All three closed the game gracefully.

The stock runner stopped after the historical graphics marker and therefore did not wait long enough
for the title overlay's stricter interactive transition. The Windows cohort runner now waits for and
requires that exact boundary for Preflight-backed conditions and reports it separately from graphics
preload. This preserves the historical clock without presenting it as time-to-play.

## Same-semantics native confirmation

The repaired runner retained the same game/profile/JAR/display identity and automatically recorded
the High Performance power GUID, fourteen guest processors, Defender still enabled, and the two
exact path exclusions.

| Condition | Game-log start -> graphics preload | Process start -> interactive menu | Accepted |
| --- | ---: | ---: | --- |
| Preflight + Fast Rendering | 37.002 s | 49.551 s | yes |
| Preflight | 112.455 s | 125.255 s | yes |

This confirmation is still one run per condition. It establishes the boundary and direction; a
release-performance claim still needs the ordinary shuffled repeated cohort.

## Tuned VM identity

- Big Red: Intel Core Ultra 7 255H, 30 GiB RAM, NVMe storage.
- Guest: 12 GiB RAM; about 9.2 GiB free after boot, so RAM was not the bottleneck.
- Guest vCPUs: 14 current / 16 maximum, pinned one-to-one to host CPUs 0-13.
- Host CPUs 0-5: 5.1 GHz performance cores; 6-13: 4.4 GHz efficiency cores.
- Host CPUs 14-15: 2.5 GHz low-power cores reserved for QEMU emulator and I/O work.
- Host and guest power plans: performance / High Performance.
- Defender: real-time monitoring remains enabled; exact exclusions are only
  `C:\Games\Starsector` and `C:\Users\Leo\AppData\Local\Starsector Preflight\cache`.
- Disk: virtio-blk on sparse qcow2 over NVMe, `cache=none`, native I/O, discard enabled.
- Display: QXL plus Mesa llvmpipe. These results are startup compatibility evidence, not gameplay
  FPS evidence.

The original libvirt XML is retained on Big Red at
`/home/leo/win11-starsector-before-cpu-partition-20260902-031520.xml`.

## Best and worst retained anchors

These anchors use different sittings and, in some cases, different readiness boundaries. They are a
map of observed behavior, not one controlled leaderboard.

| Platform / condition | Retained observation | Boundary |
| --- | ---: | --- |
| Linux Preflight, best warm learned pack | 18.27 s | game log -> graphics preload |
| Linux Preflight, current-main confirmation | 23.206 s | game log -> main menu |
| Tuned Windows Preflight + Fast Rendering | 37.002 s | game log -> graphics preload |
| Tuned Windows Preflight + Fast Rendering | 49.551 s | process start -> interactive menu |
| Tuned Windows Preflight | 112.455 s | game log -> graphics preload |
| Tuned Windows Preflight | 125.255 s | process start -> interactive menu |
| Earlier Windows Preflight | about 181.313 s | accepted startup route |
| Earlier Windows vanilla | about 369.326 s | accepted startup route |

On the historical anchors, tuned stock Preflight is roughly 3.3 times faster than the retained
Windows vanilla run on the graphics clock. Tuned Preflight plus Fast Rendering is the fastest
Windows configuration seen, but it remains about 2.7 times slower than the best Linux result on the
stricter interactive-menu clock versus Linux's graphics marker.

## Explored questions

### Did the second run merely inherit warm filesystem cache?

No as a complete explanation. The stock A legs bracketed the combined B leg at 111.910 and 115.413
seconds. Stock did not collapse toward 38 seconds after the combined run warmed the same game tree.

### Is Preflight already a full startup superset of Fast Rendering?

It is a healthy additive wrapper: 19 exact Preflight transforms applied under Fast Rendering with no
declines, and the combined route was far faster than the earlier 323.423-second Fast Rendering-only
observation. It is not yet a full texture-path superset. Fast Rendering replaces the stock loader,
so this combined run recorded zero Preflight prepared-pixel attempts or hits. A dedicated exact
Fast Rendering texture seam could potentially combine its parallel scheduling with Preflight's
prepared bytes.

### Can the Windows VM receive Big Red's real GPU?

Not by ordinary full passthrough without taking the host desktop: Big Red has one Intel Arc iGPU and
Linux owns it. The device exposes seven SR-IOV virtual functions, so a future reversible VF lane is
possible in principle. Intel's Windows-guest procedure requires a platform-matched SR-IOV/zero-copy
driver package; that package has not been identified or installed for this Arrow Lake fixture. No VF
was enabled and the host display was not disturbed.

## Open questions / next experiment

1. Inspect the exact installed Fast Rendering texture-loader bytecode outside the repository and
   determine whether a fail-closed prepared-byte bridge can feed its worker loader without changing
   ordering, GL ownership, fallback, or shutdown semantics.
2. If that seam is unsafe, treat Fast Rendering as the supported parallel texture owner and focus
   stock work on the 100+ seconds between prepared-pixel calls rather than further optimizing the
   sub-two-second Preflight bridge.
3. Investigate Intel SR-IOV only after obtaining the exact supported Windows guest driver and a
   recovery plan; do not turn an exposed sysfs capability into a product-performance claim.

## Preserved evidence

The complete 85-file exploratory and confirmation bundle is on Big Red at:

```text
/home/leo/Windows-Share/Diagnostics/20260902-windows-startup-tuned
```

It contains the identity, schedule, cache check, per-run JSONL result, adapter/runtime reports, and
full logs for:

- `20260902-031639-windows-startup-2x2`
- `20260902-032225-windows-startup-2x2`
- `20260902-032437-windows-startup-2x2`
- `20260902-034011-windows-startup-2x2`
- `20260902-034211-windows-startup-2x2`
