# Windows prepared audio and the startup combination

The final installed artifact reached the interactive menu in **18.953 and 17.620 seconds** on
two ordinary Recommended launches, with no experiment flags. The same fixture's pre-bake baseline
was 25.591 seconds. This establishes below-20 observations on this VM, not a universal guarantee.

Windows can now prepare sound effects with its exact installed Vorbis decoder and serve those
PCM results during ordinary launches. The existing preparation/cache machinery previously bound
the different `sound.J` / `sound.F` decoder/result names. Windows uses `sound.O0oO` / `sound.G`.
This removes repeated decoding; the earlier chunk-copy experiment still decoded every sound.

Windows Recommended launches with a validated prepared-audio manifest also enable the existing
exact prestart texture admission and faction priority cache. Explicit property overrides win.
Disabling the prepared-audio optimization domain prevents this automatic combination. No valid
audio manifest means the earlier startup policy. Conservative and other-platform presets do not
gain the extra prestart/faction defaults. A fresh faction cache learns from original callbacks on
its first launch and replays only validated entries on subsequent launches.

## Native observations

All observations use the same 20 GiB configured Windows VM, 14 CPUs, Recommended preset, native
Intel graphics selection, 1024x720 and one resource worker. There was no RAM change or reboot.
Times use the game-log graphics and interactive-menu clocks, excluding preparation. None of the
timing runs enabled phase, texture-CPU or GL upload instrumentation. These are sequential
observations, not a randomized estimate or a guarantee for all Windows systems.

| Session | Configuration | Graphics | Interactive menu |
| --- | --- | ---: | ---: |
| `20260905-230349` | Candidate A, no audio cache | 23.934 s | 25.591 s |
| `20260905-230638` | Candidate A, prepared audio, ordinary launch | 19.710 s | 21.667 s |
| `20260905-230843` | Candidate A, audio + explicit prestart | 19.427 s | 21.137 s |
| `20260905-231058` | Candidate A, audio + explicit prestart/faction replay | 18.053 s | 19.884 s |
| `20260905-231853` r1 | Candidate B, ordinary Recommended | 18.343 s | 19.417 s |
| `20260905-231853` r2 | Candidate B, ordinary Recommended | 16.911 s | 18.080 s |
| `20260905-232757` r1 | Final C, ordinary Recommended | 17.871 s | 18.953 s |
| `20260905-232757` r2 | Final C, ordinary Recommended | 16.722 s | 17.620 s |

The first below-20 menu was the combined explicit candidate. Audio alone removed 3.924 seconds
in the same-JAR before/after observations. The combination motivated promotion behind the
validated-audio prerequisite. Both default-policy candidates completed two unflagged runs below
20 seconds. Candidate B spent 223.1 and 228.0 ms validating audio source identities before launch;
that cost is outside the game-log clock and much smaller than the observed startup reduction.

Every audio-enabled run served all 2,049 prepared effects and retained one original decode, with
zero audio-cache failures. The combined runs replayed all 944 faction entries / 35,765 IDs, with
zero replay or fingerprint failures. Prestart removed 15,003 jobs, consumed 15,002 identities and
retired one unused identity, with zero pending identities, waits or failures. It committed 14,958
direct and 44 coherent completions under the 1024 ceiling. All runs consumed all 102 late
Kaleidoscope results, with zero pack failures/fallbacks. Shutdown reports were complete with no
survivors; the older CLI terminal `RUNNING` record issue is not claimed fixed.

The bake prepared all 2,049 eligible effects, with zero undecodable files, from 133,339,260 encoded
bytes to 1,226,415,962 PCM bytes in 45.399 seconds. This is a separate one-time cost. The resulting
manifest has 2,049 entries and SHA-256
`5865f084979b57972621a103f0cdadab77d387841953b00928edc6724cc809e5`.
Its decoder-policy identity is
`5ffefaa1bbe6fc541a284d9fb07d6b70aae92e21b3ee9f3cd60ae1ff3089e7f2`.

## Installed contracts

The original Windows method `sound.O0oO.super(InputStream):sound.G` creates `sound.F`, decodes
through its original read/refill loop, releases that stream's pooled buffer, then returns direct
PCM at position zero with channel count and sample rate. `sound.F.close()` releases its internal
buffer; it does not close the caller's input stream. The rewritten entry retains the complete
original method under a new name and supplies its exact method handle to the cache runtime.
Unknown decoder bytes or result shapes decline. Null input retains the original IOException.

Cache lookup hashes the encoded input and includes the installed decoder-policy identity and
fully-decoded-effect policy. It validates the cached metadata and PCM shape, creates the actual
Windows `sound.G` and fills its original three fields. Missing/corrupt/unsupported entries invoke
the preserved original decoder with the same encoded bytes. The final Windows gate admits only
exact `ByteArrayInputStream` inputs from the reviewed resource batch; custom stream classes retain
their original read/error behavior without interception. The existing caller `sound.C` keeps
its filename repository lookup, `alGenBuffers`, mono/stereo selection, `alBufferData`, repository
registration, error handling and subsequent lifecycle. Music streaming is untouched.

The sound archive SHA-256 is
`d70e2760c9785770818607edd7be502ac75f7b87f8af5770c178a8d723c96dab`.
The Windows decoder class is
`4b28c09ee5004a353ea2f0d61611eb4c7e0504abfc7b1f5328d6a7123f7f72b7`;
its result class is
`c7dbba1261cfba676dba014709c68e10563c3d06b0e8b5e664a5c1d2ee5e6616`.
Preparation identity includes the sound archive and installed JOrbis/Jogg/LWJGL hashes, preventing
cross-decoder reuse. Prepared audio takes precedence over the optional PCM-copy adapter; a miss
uses the original decoder rather than composing that rejected experiment.

Texture registration, aliases, handlers, destruction/replacement/reload, GL ownership and the
1024 ceiling retain the [installed resource contracts](2026-09-05-windows-prepared-resource-contracts.md).
Prestart retains the [reviewed admission and retirement rules](2026-09-05-windows-prestart-and-pcm-copy.md).
The stock worker start/count and byte queue remain unchanged. Faction replay retains its exact
profile/faction JSON/callback/table identities and original-callback fallback; see the
[earlier replay validation](2026-09-01-windows-vm-startup-tuning.md#can-the-faction-priority-table-walk-be-reused-on-an-exact-windows-profile).

## Verification

Full `./mvnw verify` passed for the final executable source in 43.996 seconds with
`-Dpreflight.starsector.common.jar` supplied; all nine installed texture-loader contract tests ran
without skips. Policy tests cover Windows Recommended with/without a validated audio manifest,
other platforms/presets, idempotence and explicit admission/faction opt-outs. Registry tests cover
PCM-copy precedence and unknown Windows decoder rejection.
The desktop capability source lock was reviewed and refreshed for the `RunCommand` change that
passes validated-manifest presence into startup policy; all nine receipt tests passed.
Final executable-source Java CI passed on Linux, macOS and Windows, plus both operator jobs, in
[workflow 33974820622](https://github.com/teamleaderleo/preflight/actions/runs/33974820622).

Private installed-audio tests compared original and prepared output for three real Ogg files:
every PCM byte, channels, sample rate, buffer directness/position, corrupt/missing-cache fallback
and null exceptions. The custom-stream test makes `readAllBytes` throw to prove it is not
intercepted; an explicit disable also retains the original output. Preparation independently
matched the same original output. ASM BasicVerifier
checked the rewritten methods. The private test uses `-DargLine=-noverify` because the installed
original obfuscated stream has invalid field names, matching the game runtime. Ordinary Maven/CI
tests retain JVM verification. No installed game assets are committed.

Candidate A executable source is `94b28b670d18f22d33dd2d9dfb1b4814f9416fb9`, JAR SHA-256
`85b2288c51a6aaa4b55b2836d3ea020f36c6a9dc1e8b8a25f5d22cce26518d6d`.
Candidate B default-policy executable source is `41a8c07721efbdd8c2c85a8fa77f3711ef71c155`, JAR
SHA-256 `be25cdd8220e1a758a3d4b699441c011dba550787a42ceba8ac3ea933b6a3c8f`.
The final stream-gated candidate C executable source is
`d7501b2e8682b2a168a75bba074cc3b028959370`, JAR SHA-256
`1c7405a1adc71849a9c010aaba6eb78bb8a72b4457257baf19bc3ed652121fbc`.
The private diagnostics owner is `/home/leo/Windows-Share/Diagnostics/windows-prepared-audio/`:
`bake.json`, artifact identities and `results.json` retain hashes, timings, resource/audio/faction
counters and shutdown records. Archives use `SESSION-windows-startup-2x2.zip` in its parent.
