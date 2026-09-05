# Windows prestart admission and PCM copy prototype

Neither candidate establishes a whole-launch speedup or reaches the requested sub-20-second
interactive menu. Both remain explicit opt-ins. Ordinary Recommended launch behavior is unchanged.
The VM retained 20 GiB configured RAM and 14 CPUs throughout.

## Measurements and decision

All runs used Recommended, native graphics selection (no Gallium override), 1024x720, the same
installed game/mod profile and one resource worker. Graphics and interactive-menu measurements
use the game-log clocks. These are sequential individual observations, not randomized estimates.

| Session | Candidate | Graphics | Interactive menu | JAR |
| --- | --- | ---: | ---: | --- |
| `20260905-214321` | Existing typed resources only | 25.221 s | 26.220 s | A |
| `20260905-215207` | Prestart admission | 23.720 s | 25.447 s | B |
| `20260905-222628` | Prestart admission + PCM copy | 23.468 s | 25.229 s | C |
| `20260905-223729` | PCM copy only | 32.066 s | 33.739 s | C |
| `20260905-223845` | Ordinary launch, both off | 24.374 s | 25.359 s | C |

The same-JAR combined result is only 0.130 seconds below ordinary launch; it does not justify
promotion. The PCM-only observation is substantially slower, with causality unresolved.
The earlier [ordinary native observations](2026-09-05-windows-native-upload-ceiling.md) were
25.729 and 26.729 seconds to the menu. No sub-20 result was observed.

Separate intrusive phase/texture-CPU diagnostics explain why removing the first wait is insufficient:

- Baseline `20260905-214127`: the first cursor took 8.292 seconds, including 8.276 seconds off CPU.
  The other texture calls took 5.045 seconds. SpecStore took 4.721 seconds.
- Prestart `20260905-215405`: the cursor took 1 ms, but the other textures took 9.920 seconds.
  Texture progress reached 100% at 17.788 seconds; audio workers completed at 21.025 seconds.
  Mod callbacks then took 3.143 seconds. Its 26.064-second menu is a diagnostic observation only.

Moving prepared loads onto main removes a queue dependency but also moves work. The audio copy
candidate still performs every original Vorbis decode. Prepared-audio telemetry was disabled with
zero cache hits on Windows; existing preparation and serving code binds the different `sound.J` /
`sound.F` decoder/result shape. Porting that cache requires its own Windows decoder identity,
result-shape, stream-lifecycle and installed-output validation. This prototype does not port it.

## Exact resource admission

The hook runs immediately before the reviewed original `Thread.start`. It requires the exact
unstarted `Thread`, main-thread ownership, an admitted current obligation and matching prepared
identity, and no conflicting result or sentinel. It removes only those image jobs, keeping byte
jobs, unknown resources and the original worker count/start intact. Running workers and subclasses
decline. Every removed duplicate job is counted; each distinct identity is consumed or retired once.
The existing typed completion and main-thread commit path constructs actual handlers and retains
the [installed cache, repository, replacement, destruction and reload contracts](2026-09-05-windows-prepared-resource-contracts.md).
The direct ceiling remains 1024. Declined large textures retain coherent prepared images followed
by the original converter/layout/GL path; GL ownership stays on main.

Successful prestart runs removed 15,003 stock jobs, consumed 15,002 identities and retired one
unused identity, with zero pending identities, waits, failures or drain timeouts. They committed
14,958 direct and 44 coherent completions. All measured runs consumed all 102 retained late
Kaleidoscope results and reported zero pack failures/fallbacks. Existing bounded pack-failure
reason and lifecycle telemetry remains present. Shutdown reports were complete with no survivors;
the separate archived CLI `RUNNING` terminal-record issue is not claimed fixed.

## Exact PCM copy contract

The optional Windows plan adds a branch around the byte-at-a-time loop in
`sound.O0oO.super(InputStream):sound.G`. It retains the original decoder/refill, stream close,
direct-buffer construction, channel/rate metadata and downstream OpenAL registration bodies.
The original loop remains the fallback. A separately hash-checked `sound.F` stream exposes a
producer buffer position and a distinct consumer cursor; absolute 8 KiB copies preserve both
buffer position and limit. The original single-byte read handles refills, including the stock
final `write(-1)` behavior. Unknown stream shapes decline before consuming any input.

Both PCM-enabled live runs completed 2,050 copies, with 1,226,387,114 bulk bytes and 28,848 original
read calls, and zero shape declines. These work counters establish execution, not a timing win.

Installed SHA-256 gates:

- Common archive: `5a26d047baefc6dcd763121a17d170e3b864bfb19a83d11f645ba8be49f1641b`.
- Sound archive: `d70e2760c9785770818607edd7be502ac75f7b87f8af5770c178a8d723c96dab`.
- Decoder `sound/O0oO`: `4b28c09ee5004a353ea2f0d61611eb4c7e0504abfc7b1f5328d6a7123f7f72b7`.
- Stream `sound/F`: `d5f2b86bab84ec3a40945ebd488c20ab9401f590f2b4963f358cca4c98757754`.

## Validation and artifact identity

Full Maven verification passed in 45.150 seconds with the installed common JAR contracts enabled.
Python operator checks passed 380 tests with five skips. Three-platform Java CI and operator jobs
passed in [workflow 33971694060](https://github.com/teamleaderleo/preflight/actions/runs/33971694060).
Runtime tests cover boundary lengths, every PCM byte, refill/EOF/exception parity, cursor state,
unknown-shape fallback, exact prestart gates, duplicate retirement and conflicting results.

The private installed-audio test compared stock and rewritten output on three real Ogg files,
including channels, sample rate, directness, position, null failure, explicit opt-out and duplicate
weave refusal. ASM BasicVerifier checked rewritten methods. This test requires `-DargLine=-noverify`
because the installed original stream has JVM-invalid obfuscated field names, as does the real
game launch; it is not proof of JVM verification of those original classes. Normal Maven/CI runs
retain normal verification and skip the private fixture test.

| JAR | Executable source | SHA-256 |
| --- | --- | --- |
| A | `a403096aa023f5f2157bb83b6c1e222dec4b4f41` | `669e77d9dc52ba295f359fdf78186da125127b7cd79d3f9140bd12353d602ee3` |
| B | `81df1495267dba3ee3741d72bc95d3930ba1a9f7` | `f194fa26047b5bd805d0fb93478a6ad0ab3b8d1b2b0dcab0df83eb16a9c24b39` |
| C | `ce3e2c4966376b036b8b44bc333d7f863e6f1a48` | `b396ed068cd04f305823f135ec9b64dbf2afae14966488721b87f3e9abd970e7` |

Private archives remain in `/home/leo/Windows-Share/Diagnostics/SESSION-windows-startup-2x2.zip`.
`windows-sub20/results.json` retains archive/JAR hashes, timings, resource/audio/pack counters and
shutdown records. No installed game assets are committed.
