# Windows TEXTURE thread-CPU probe validation

Date: 2026-09-02 (host) / 2026-09-03 (Windows guest)
Status: accepted diagnostic validation; no optimization or startup-performance claim

## Decision

An adjacent A/B/A on current `main@ed5965a8` validates the opt-in TEXTURE current-thread CPU probe
as non-perturbing for this fixture. The B leg's 15,002 ordinary texture calls took 16.377 seconds of
probe-group wall time, beside 16.431 seconds in A1 and 19.731 seconds in A2. B therefore did not
reproduce the prior 50.575-second ordinary-texture wall anomaly and did not inflate relative to
either adjacent control.

The validated B result assigns 10.500 seconds (64.11%) of ordinary-texture wall to current-thread
CPU and infers 5.877 seconds (35.89%) outside current-thread CPU. This replaces the prior run's
unvalidated 22.9%/77.1% split. The next separate discovery run, if requested, should use the already
existing TextureUploadProbe to divide native GL upload from surrounding Java work. This validation
does not run that probe and does not optimize anything.

## Exact identity

All three legs used the same Windows-built Preflight JAR, SHA-256
`c6e3b88a8823799f17b46538bee9145e5beff689b2d71302c2fb598244ad19af`, from
`main@ed5965a8361261e1aabc7f43d4d5a30d9f1632ed`. The fixture was Windows
`10.0.26200.0`, Eclipse Adoptium Java `21.0.12.1`, llvmpipe, Recommended, 1024x720 windowed, and
the stock one-worker prepared path. The host was an Intel Core Ultra 7 255H; each wrapper invocation
changed its recorded host profile from balanced to performance for the run. The guest exposed 14
processors, kept SysMain running, and used power-scheme GUID
`8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c`.

Exact shared identities:

- enabled mods: `76227ce91333c202271e541774f3e86fd8711c2542d63a81cfd18a4dc0a6997f`;
- prepared profile: `cfe95f25f14ce426766539225fd1fdab520d728b117a317413f47d3c40fbae3a`;
- manifest: `c39e193ca7d8c6784072345c57721c4a09e09ae46138b471c9f35a08172cb850`;
- index: `b326c99d66910ec526d8f564dcdb8d249ec44214e64ff3041f932e6158292e87`;
- Java executable: `82051fdab26319d77d20cc0065045d05ec00b3e3d05f44935d7c06b96b621d55`.

Every leg enabled StartupPhaseProbe. Only B enabled StartupTextureCpuProbe. TextureUploadProbe and
all other candidate/intrusive probes were off. Every leg retained 17,124 resource calls, including
15,003 TEXTURE calls with total weight 16,338. Every leg was accepted, adapter-healthy, free of
contained adapter failures, and shut down gracefully.

## Adjacent A/B/A

All durations are process-relative or resource-call wall time. `mainMenuInteractiveAt` is runtime-
state v2's reviewed usable-menu boundary. `mainMenuOverlayRemovedAt` is diagnostic only and was not
present because the runner accepted and stopped each launch at the earlier v2 usable-menu boundary.

| leg | texture CPU probe | total TEXTURE wall | cursor wall | other 15,002 wall | menu ready | v2 usable menu | overlay removed |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| A1 | off | 23.598 s | 7.167 s | 16.431 s | 42.286 s | 45.938 s | not observed |
| B | on | 22.778 s | 6.400 s | 16.377 s | 37.799 s | 40.272 s | not observed |
| A2 | off | 26.444 s | 6.713 s | 19.731 s | 43.319 s | 46.317 s | not observed |

The B probe aggregate floors each group's nanoseconds independently, while the existing by-type wall
timer floors the combined TEXTURE nanoseconds. That produces a one-millisecond rounding difference:
B cursor plus ordinary group wall is 22.777 seconds, while the unchanged TEXTURE by-type timer is
22.778 seconds.

## B CPU-clock result

| group | calls | wall | thread CPU | inferred off-CPU |
| --- | ---: | ---: | ---: | ---: |
| cursor | 1 | 6.400 s | 0.031 s | 6.368 s |
| other TEXTURE | 15,002 | 16.377 s | 10.500 s | 5.877 s |

The current-thread CPU clock reported `available`, zero read failures, and 644 negative/skew events
at individual-call resolution. Its 10,000-read calibration cost 4.4838 milliseconds total: 448 ns
average and 25.2 microseconds maximum. Group inference remains aggregate wall minus aggregate CPU;
the skew count warns against interpreting individual sub-millisecond calls, but it does not create
the earlier impossible sum because inference is no longer clamped per call.

## Retained artifacts

| leg | launch ID | archive | archive SHA-256 |
| --- | --- | --- | --- |
| A1 | `6c4e2f8a-37d0-4db1-a4d2-6b7ab76295c1` | `/home/leo/Windows-Share/Diagnostics/20260903-093809-windows-startup-2x2.zip` | `ef5a7520a151f24834503469b5ce412f314e076a6d3464c312275693be47eef7` |
| B | `fedd4c6a-86bc-4040-9dcc-3abc0538ba07` | `/home/leo/Windows-Share/Diagnostics/20260903-093938-windows-startup-2x2.zip` | `fa3b064bc9f326f0f6ed324bd25f39836655541e658721f1194cba626829b7e8` |
| A2 | `86dfb5c0-00ec-42bc-aa97-0c327119e90c` | `/home/leo/Windows-Share/Diagnostics/20260903-094055-windows-startup-2x2.zip` | `e0c46096bddc2072719e9ac2eac8c030cc4387ae9a58283cf2049ed3b2dacdd2` |

Each archive has an adjacent `-host.json` file carrying host profile, CPU/vCPU mapping, guest
pre-run state, host samples, guest post-run state, and the retained archive identity.
