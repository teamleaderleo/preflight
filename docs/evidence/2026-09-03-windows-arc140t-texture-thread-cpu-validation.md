# Windows Arc 140T texture thread-CPU validation

Date: 2026-09-03 host / 2026-09-04 Windows guest

Status: accepted A/B/A; diagnostic validated; no startup implementation changed

## Result

On the native Intel Arc 140T fixture, the CPU-probe leg remained inside the texture-wall regime
defined by its adjacent controls. The exact 15,002 ordinary TEXTURE calls took **18.399s wall**:

- **11.406s current-thread CPU (61.99%)**;
- **6.993s inferred off-CPU (38.01%)**.

This is not materially different from the validated llvmpipe split of 10.500s CPU / 5.877s
off-CPU across 16.377s wall, or 64.11% / 35.89%. Native Arc moves the proportions by only 2.12
percentage points toward inferred off-CPU. The common ordinary-texture path remains mixed, with a
clear majority on the current game thread; GPU passthrough did not turn it into a predominantly
off-CPU wait.

This cohort does **not** measure upload cost. `TextureUploadProbe` was off in every leg, so no
llvmpipe upload result is carried into this interpretation.

## Accepted A/B/A

All times below are retained aggregate milliseconds converted to seconds. CPU/off-CPU is
unavailable by design in the two control legs because the CPU probe was off.

| Leg | CPU probe | Total TEXTURE wall | Cursor wall / CPU / off-CPU | Other 15,002 wall / CPU / off-CPU | Process -> ready | Process -> v2 usable menu |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| A1 | off | 27.168s | 9.476s / n/a / n/a | 17.692s / n/a / n/a | 50.387s | 54.420s |
| B | on | 27.053s | 8.653s / 0.000s / 8.653s | 18.399s / 11.406s / 6.993s | 50.280s | 53.024s |
| A2 | off | 29.051s | 9.742s / n/a / n/a | 19.309s / n/a / n/a | 51.264s | 54.285s |

The B total is 0.115s below A1 and 1.998s below A2. Its 18.399s ordinary-texture wall is also
between the adjacent control values. Relative to the 28.110s mean of A1/A2 total TEXTURE wall, B
is 3.76% lower. The diagnostic therefore did not inflate the workload and is accepted as usable.

The B CPU clock reported `available`, zero read failures, 379 negative/skew clamps, and 10,000
overhead-calibration samples totaling 3.287ms (328ns average, 20.2us maximum). Cursor CPU rounded
below the retained aggregate's one-millisecond resolution; the result should be read as `<1ms`, not
proof of literally zero executed instructions. The cursor is retained separately from the ordinary
texture conclusion.

## Workload and adapter health

Every accepted leg retained the same resource and prepared-texture workload:

```text
TEXTURE resource calls: 15003
prepared lookups: 15473
prepared hits: 15445
known declines: 27 (entry-missing 3; unsupported-texture 24)
pack hits: 15470
pack failures: 0
packed store active at shutdown: true
prepared internal errors: 0
active/pending buffers at shutdown: 0 / 0
textures served unpadded: 11448
dimension-ceiling declines: 24
padding bytes avoided: 842957275
adapter transformations / exact matches / declines: 28 / 29 / 1
```

All three runner records say `accepted: true`, `adapterHealthy: true`, and
`gracefulShutdown: true`. Each run reached the reviewed v2 usable-menu boundary. No v1 overlay
removal timing is used.

## Exact identity

The three accepted legs held these values constant:

```text
source main: ed98679e094e2f19088b6b2f88a50db9f99331ed
host / CPU: big-red / Intel Core Ultra 7 255H
VM / guest: win11-starsector / STAR-WIN11
guest OS: Microsoft Windows NT 10.0.26200.0
guest vCPU / RAM: 14 / 12814041088 bytes
host profile before/during: performance / performance
guest power GUID: 8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c (High Performance)
preset: Recommended
resolution: 1024x720 windowed
prepared workers: 1 (stock-order path)
maximum unpadded dimension: 1024
resolved mods: 83
enabled_mods.json SHA-256:
  76227ce91333c202271e541774f3e86fd8711c2542d63a81cfd18a4dc0a6997f
resolved profile fingerprint:
  402a6167f341cdaef42e039d23fc3924550b8c75c4a41c23383217dd857f6dad
texture profile fingerprint:
  cfe95f25f14ce426766539225fd1fdab520d728b117a317413f47d3c40fbae3a
texture manifest SHA-256:
  c39e193ca7d8c6784072345c57721c4a09e09ae46138b471c9f35a08172cb850
texture resource index SHA-256:
  b326c99d66910ec526d8f564dcdb8d249ec44214e64ff3041f932e6158292e87
packed texture store SHA-256:
  a97335bda8c44c9c18e5f8f5969071872ac47f67dc81364c988959b946f73a4d
Preflight JAR SHA-256:
  c6e3b88a8823799f17b46538bee9145e5beff689b2d71302c2fb598244ad19af
CLI Java: Eclipse Adoptium 21.0.12.1
Java executable SHA-256:
  82051fdab26319d77d20cc0065045d05ec00b3e3d05f44935d7c06b96b621d55
```

Each accepted leg set `StartupPhaseProbe=true`. Only B set
`StartupTextureCpuProbe=true`. `TextureUploadProbe=false`, `GALLIUM_DRIVER` was unset, and every
other named intrusive/candidate probe in the cohort identity was false. Game and cache Defender
exclusions were present; real-time monitoring remained enabled.

The 2.259GB packed store was read completely for SHA-256 before each accepted leg. This both
verified byte identity and gave all three legs the same warm-pack precondition. The canonical file
was byte-identically rewritten from about 1,200 NTFS extents to one extent while diagnosing an
intermittent late pack-read failure; its size and SHA-256 did not change. The temporary runner also
waited 15 seconds after the usable-menu timestamp was captured before requesting normal window
shutdown. Neither action changes the retained ready or usable timestamps.

## Renderer identity

The VM stayed on the already-verified whole-iGPU VFIO configuration throughout the cohort. The
same installed Starsector LWJGL 2 context reports:

```text
GL_VENDOR=Intel
GL_RENDERER=Intel(R) Arc(TM) 140T GPU (6GB)
GL_VERSION=4.6.0 - Build 32.0.101.8991
```

Every leg recorded `galliumDriver: null`. A post-cohort live check retained Arc status `OK`,
`ConfigManagerErrorCode=0`, driver `32.0.101.8991`, no local `opengl32.dll` Mesa interception, and
no surviving game process. Thus the renderer identity for A1, B, and A2 is the physical Arc 140T,
not llvmpipe. The configuration and original Java/LWJGL context proof are documented in
[Big Red Arc 140T VFIO passthrough](2026-09-03-big-red-arc140t-vfio-passthrough.md).

## Run identities and retained archives

| Leg | Launch ID | Guest run directory suffix | Host archive | Archive SHA-256 |
| --- | --- | --- | --- | --- |
| A1 | `2620fb9f-03f9-4a90-8429-5ee84319d57e` | `20260904-053437-windows-startup-2x2\\01-preflight-r1` | `/home/leo/Windows-Share/Diagnostics/20260904-053437-windows-startup-2x2.zip` | `df65d1908eb104c36a58f21f6d248e64645ce8668bbdebe5160d08b83d8ece4c` |
| B | `5ab933dc-6f1c-472b-9043-83412a607da3` | `20260904-053700-windows-startup-2x2\\01-preflight-r1` | `/home/leo/Windows-Share/Diagnostics/20260904-053700-windows-startup-2x2.zip` | `a3c1189892461bf1fca684009b9b0562843906959f6bdf1cf63700f4c4a3e967` |
| A2 | `7f0330fe-f78c-48c0-b594-1fd20859dfc3` | `20260904-054811-windows-startup-2x2\\01-preflight-r1` | `/home/leo/Windows-Share/Diagnostics/20260904-054811-windows-startup-2x2.zip` | `6c622bee290f0036360c94d92d62214dcfbda99e9190eb7f568732384a3de813` |

The matching host fingerprints are adjacent `*-host.json` files with the same timestamps.

## Exclusions and interpretation boundary

Several setup/retry runs were retained but excluded. Some observed one fail-open pack read after
more than 15,000 successful reads and therefore accumulated hundreds of later loose-blob misses;
one full-reboot run also had a deliberately cold broader Windows state. They do not enter the
accepted table. The accepted A1/B/A2 are the three clean runs with zero pack failures and identical
resource/cache counters.

The accepted conclusion is narrow: the texture CPU clock is non-perturbing on this native-Arc
fixture, and ordinary texture wall remains about 62% current-thread CPU / 38% inferred off-CPU.
It does not identify which Java operation owns the CPU portion or which wait/driver operation owns
the off-CPU portion, and it makes no native-upload claim.
