# Windows memory policy in normal launches

The reviewed initial-heap policy now defaults on for normal Preflight Recommended launches.
It is launcher behavior, not a benchmark-only optimization. The existing exact batch, wrapper,
runtime, platform, preset and explicit-user-heap gates remain unchanged. The game child starts
with 2048 MiB initial heap and retains its 6144 MiB maximum; launcher files are not edited.
`PREFLIGHT_DISABLE_WINDOWS_INITIAL_HEAP_POLICY=1` opts out. The historical
`preflight.windows.initialHeapProbe=false` property remains an explicit opt-out as well.

Executable source: `cd0e4699a4f6729b8a70c33d44668b863c395a0f`.
Packaged JAR SHA-256: `60e1dfb7eed90c65b1e87223898877eb1acd3ac9107852078355725c53dc474d`.
Earlier opt-in comparisons and launcher identities are owned by
[the initial memory investigation](2026-09-05-windows-pack-io-refinement.md).

## Ordinary launch observations

Same Windows fixture, 14 vCPUs, 12,814,041,088 physical bytes, 1024x720, Recommended,
one worker, condition `preflight`. Typed prepared resources, byte barrier, queue claims,
read-ahead and intrusive timing probes were not enabled. Both completed runs used the
existing padded coherent-direct policy selected by `GALLIUM_DRIVER=llvmpipe`.
**This environment value is not renderer proof:** a loaded-module observation during the
enabled run found system `OPENGL32.dll` and Intel `igxelpgicd64.dll`, not a Mesa renderer.
The observation was a bounded module query, not per-upload instrumentation.

| Session | Initial heap policy | Graphics preload | Interactive menu | Result |
| --- | --- | --- | --- | --- |
| `20260905-132424` | automatic; neither heap switch supplied | 44.656 s | 46.415 s | accepted |
| `20260905-132613` | explicitly disabled | 72.442 s | 74.969 s | accepted |

This single B/A pair improved interactive startup by 28.554 s (38.1%). It is not a repeated
campaign or a universal Windows speedup. Do not pool it with earlier typed-resource runs.
Both adapter reports confirm 6,442,450,944 maximum heap bytes, zero active/pending upload
buffers, and typed prepared resources disabled. Both preserve the existing 1024 ceiling.
The enabled run's `run.json` confirms actual heap admission without an opt-in property.

Both runs still record one `read-interrupted` pack failure and pack disable at stock worker
shutdown. Enabled: 15,104 pack hits, 367 pack fallbacks, 78 of 102 seeded Kaleidoscope results
consumed after stop, 24 pending removed. Disabled: 15,044 hits, 427 fallbacks, 18 consumed,
84 pending removed. These counters are not zero-failure claims. The source change does not
alter resources, aliases, handlers, registration, reload, worker scheduling or GL calls.

## Retained native-selection failures

Two earlier normal-path runs with `GALLIUM_DRIVER` absent stalled before graphics completion:
`20260905-131647` with heap policy explicitly disabled and `20260905-132053` with it automatic.
For each, two thread snapshots approximately ten seconds apart showed main in native
`GL11.nglTexImage2D` through the stock TextureLoader/resource path, with unchanged main CPU.
Neither had typed prepared resources enabled. Main-window close returned false; the exact
verified game processes were forcibly retired. The harness archived each failed session and
returned failure. Neither has a successful timing, and no native stall fix is claimed.

This narrows the next investigation to the native unpadded upload path and memory pressure;
two failed runs and one successful padded pair do not establish the underlying driver cause.

## Next performance work supported by this evidence

The ordinary worker shutdown can interrupt the shared pack FileChannel. The current
`TextureCompatibilityRuntime` deliberately disables that pack on the resulting exception,
so subsequent reads fall back. Preserve cancellation and corruption handling while examining
whether the already-reviewed bounded worker drain can serve the normal late-resource path,
or whether reader ownership can isolate worker cancellation from main's later reads.
The typed prototype already drains this seam; it must not be promoted merely to obtain a
timing win without its full admission/lifecycle evidence. Retain the original worker count,
main-thread GL and once-only retirement contracts.

The stock image getter already removes consumed results. Ordinary prepared serving already
avoids exhaustive pixel-layout classification, uses an immutable pixel view for upload copies,
and tracks a 64 MiB active direct-buffer budget. Those are not unimplemented shortcuts.
Prior warm-cache parser measurements and rejected read-ahead experiments do not justify
more speculative I/O or extra workers. Genuine deferred uploads would require coverage of
binding and direct texture-ID consumers; the installed handle's bind method does not lazily load.

## Validation and archive identities

Full local `./mvnw verify` passed in 47.644 s, including automatic admission, environment and
property opt-outs, exact-identity declines, explicit heap precedence, and unchanged options/files.
Three-platform Java verification and both operator jobs passed in workflow `33946768416`.
Final PR checks own desktop package validation; no runtime code changed after the executable
identity above. Raw archives, thread dumps, renderer observation and structured pair data remain
in private Windows-Share Diagnostics, with supporting files under `windows-normal-launch/`.

| Archive (`-windows-startup-2x2.zip`) | SHA-256 |
| --- | --- |
| `20260905-131647` | `9cfb7a78e09997ced08dd597797bff23cba6bab6c8c836bb9930419ee3c52ddb` |
| `20260905-132053` | `ff4d0ea0d97547c37f7752f720cabac54d9c026b5dd035451d77db360a8c0819` |
| `20260905-132424` | `a8e42940c6a79a4be04507b77bc7ad398e7af2f14c68ac93f7d018b48e78239b` |
| `20260905-132613` | `edb83675669d78b44eb82c3dd05a8e6e7a9cddc17399ec20d7f5a5291ea937a3` |
