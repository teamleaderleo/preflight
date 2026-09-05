# Normal Windows upload ceiling

Normal Windows launches now default to a 1024-pixel unpadded-upload ceiling. The
prepared-resource prototype independently capped all direct textures at 1024, while
ordinary unpadded uploads had remained unlimited.
The change also admits ceiling-declined prepared NPOT images to the original converter,
avoiding another decode while preserving the original padded layout and GL policy.

Executable source: `a403096aa023f5f2157bb83b6c1e222dec4b4f41`.
Candidate JAR SHA-256: `669e77d9dc52ba295f359fdf78186da125127b7cd79d3f9140bd12353d602ee3`.
Baseline installed JAR: `470437a855aa58ccf0b2dff2be83e4b10f7482fb80154c21a428c3e928933135`.

## Failure and boundary

Session `20260905-211743` used native selection (`GALLIUM_DRIVER` absent), Recommended,
20 GiB configured RAM, 14 CPUs, one worker and 1024x720. Typed prepared resources were off.
Only this failure-discovery run enabled upload checkpoints and timing instrumentation.
The last attempted call remained unchanged across thread snapshots at 148.85 and 159.00 seconds:

- Path: `graphics/illustrations/rat_abyss_wreckage.jpg`.
- Main thread, `glTexImage2D`, target 3553, level 0, internal format 6408, border 0.
- Width 1735, height 1014, RGB format 6407, unsigned-byte type 5121.
- Direct buffer position 0, limit/capacity 5,277,870 bytes, exactly width × height × 3.
- 5,499 uploads completed before that attempt. Main CPU stayed at 10,296.88 ms and both
  stacks remained inside `GL11.nglTexImage2D` through the stock loader/resource loop.
- Loaded modules included system `OPENGL32.dll` and Intel `igxelpgicd64.dll`.

The exact game PID/creation identity was checked before forced retirement. The updated
cleanup operator retained a failed summary and archive. This is native-stall evidence,
not a successful timing or proof of the underlying driver defect.

Earlier diagnostics already identified this size and a usable ceiling; see the
[initial NPOT investigation](2026-09-01-windows-vm-startup-tuning.md) and
[native-driver fixture](2026-09-03-big-red-arc140t-vfio-passthrough.md).
This change closes the ordinary-launch policy gap. Earlier statements about preserving
the 1024 ceiling describe the typed path's independent admission limit; they did not
establish a default ceiling for ordinary unpadded uploads.

## Normal native launches

Both candidate runs used the same JAR, with no upload or phase probes, no Gallium override,
no explicit ceiling, no typed prepared resources and no heap-policy switch. RAM, CPUs,
mod selection, launcher and batch identities matched the failure run. No RAM change or
reboot occurred in this comparison.

| Session | Graphics preload | Interactive menu |
| --- | ---: | ---: |
| `20260905-212643` | 24.818 s | 25.729 s |
| `20260905-212845` | 25.029 s | 26.729 s |

Both runs reported:

- Effective unpadded ceiling 1024; 11,448 true-size textures and 24 ceiling declines.
- 24 coherent original-converter fallbacks and 24 corresponding original-decode bypasses.
- All 102 learned Kaleidoscope results retained and consumed; none removed pending.
- Zero pack failures/fallbacks/disables, zero contained adapter failures, and zero active
  or pending prepared upload buffers.
- Complete shutdown checkpoints, zero surviving game/launcher matches and graceful
  shutdown according to the operator. The archived CLI `run.json` still says `RUNNING`;
  these observations do not establish a corrected terminal CLI-record lifecycle.

The timings are two sequential observations, not a randomized speedup estimate. The
baseline never completed and was instrumented; no percentage speedup is calculated.
This fallback resolves the reproduced launch stall on this fixture. It does not prove
that all Windows graphics drivers need a ceiling or repair their native implementations.

## Contracts and validation

The installed common archive still hashes to
`5a26d047baefc6dcd763121a17d170e3b864bfb19a83d11f645ba8be49f1641b`.
The reviewed loader retains its sampler, automatic-mipmap, image/subimage, buffer cleanup,
handler, path-cache, repository and reload bodies. See the
[installed contract owner](2026-09-05-windows-prepared-resource-contracts.md).
The runtime change neither rewrites those bodies nor changes worker scheduling or GL ownership.
Other platforms retain their previous unlimited default. Explicit diagnostic ceiling
properties retain their behavior. Unknown and transformed resources keep their fallback path.

Boundary tests cover 1024/1025 in both dimensions, the observed RGB dimensions and pixel
orientation, original-converter eligibility with no true-size fold or active buffer,
other operating systems, explicit limits/unlimited settings and malformed-property fallback.
`./mvnw verify` passed in 46.906 seconds with the exact installed common JAR supplied;
all nine installed resource-loader contract tests ran without skips. Full Java CI passed
on Linux, macOS and Windows, plus both operator jobs, in workflow
[`33968865254`](https://github.com/teamleaderleo/preflight/actions/runs/33968865254).

Private archives are under `/home/leo/Windows-Share/Diagnostics/`, named
`SESSION-windows-startup-2x2.zip`. Compact assertions and identities are in
`windows-native-upload-fix/results.json`.

| Session | Archive SHA-256 |
| --- | --- |
| `20260905-211743` | `e77ed7081dd092a4dfdc39e8b9f62319e0d6a67a6f4499e83e1002097a47b802` |
| `20260905-212643` | `5bc55ca2f04f695800772186a36fb06fe5f6d324015e2eda45353397b3079286` |
| `20260905-212845` | `0da26d8ce4d7a4ee491cecd84d28abc8cbe1ce76f99db055b698682b249deb25` |
