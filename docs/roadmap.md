# Roadmap

Preflight follows a measurement-first sequence. Each optimization keeps the original loader available as a fallback.

> **Historical engineering ledger.** Most of this document records the sequence that produced the
> current implementation. It is retained because rejected experiments and corrected measurements
> are part of the evidence, but it is no longer the best description of what blocks release. Start
> with [Release readiness](release-readiness.md), [Optimization history](optimization-history.md),
> and the [product contract](product-contract.md).

## Current release program (2026-08-08)

Startup began around an 88.13-second controlled median, with early accepted launches reaching
roughly 101 seconds. The current development profile has reached a 15.88-second warm record and
clean 16.66-second cold and 16.28-second warm gates. The next performance publication needs a fresh
controlled release-candidate cohort. The current record remains a single warm result.

Release work now has priority over another narrow startup experiment:

1. written Fractal Softworks authorization for distribution, integration approach, name, and
   disclaimer;
2. provision the free Tauri update key, finish the unsigned-package install guidance, and enable the
   consent-based report intake after its production canary;
3. clean-install and real-game beta evidence on macOS, Windows, and Linux;
4. a controlled before/after release-candidate campaign plus frame-time/FPS evidence for gameplay
   claims; and
5. update-signed, checksum-qualified packages with a tested rollback and support path.

Further startup or gameplay work remains welcome only when it keeps exact identity gates, bounded
diagnostics, an independently disableable plan, and the original behavior on uncertainty.

The first lifecycle slice landed on 2026-08-07: preparation, launch, confirmed profile switches,
launch-setting writes, and confirmed cache pruning now share a durable cross-process lease.
Preparation has structured live phase progress and safe cancellation in the desktop host;
interrupted PID-tagged temporary writes inside Preflight's home are reclaimed by the next owner.
Preview-first cache cleanup is now wired through the CLI and desktop: the plan preserves the current
and readable named profiles, groups every removal reason, caps path samples, and is recalculated
under the lease before applying. A read-only pass over the reviewed install found 5.42 GiB across
31,338 safely removable files while protecting 30,638 texture blobs reachable from the current
profile. The two removal scopes now have the same preview/apply contract in the CLI and desktop:
launch integrations plus the installed command engine can leave prepared data intact, while an
all-data removal also clears caches, profiles, evidence, and backups without targeting the game.
An interrupted all-data removal blocks other mutations until it is explicitly resumed. The
signature-verified update client and fail-closed three-platform feed pipeline are now implemented;
free updater-key provisioning, release-candidate update/rollback verification, and unattended
desktop smoke automation follow. Paid Apple and Windows publisher identities are explicitly outside
the first-beta gate; packages will publish checksums and honest OS-warning instructions instead.
The report receiver is now implemented as a private R2-bound Worker with stateless signed grants,
strict ZIP/manifest validation, immutable upload, signed receipt, deletion authorization, and
Worker-runtime tests. A real ZIP from the Java exporter completed its local lifecycle. The desktop
now reviews the exact entry list, size, digest, and exclusions; the native host revalidates and
streams the file with progress/cancellation; and the accepted receipt can be copied or used for
early deletion. Production deployment and its direct canary are complete; a packaged
release-candidate canary still blocks enabling the compile-time origin.
Production provisioning is now complete: the private bucket has 14-day expiration, the signing key
lives in Cloudflare's secret store, per-client edge brakes run before mutation work, and a
day-sharded SQLite counter imposes an exact 500 MiB grant ceiling per UTC day. A live synthetic
create/upload/finalize/delete canary passed and left the bucket empty. The remaining gate is the
same flow through a packaged release candidate; development and distributed builds still omit the
compile-time intake origin.
The first smoke prerequisite is now in the runtime itself: every injected JVM atomically publishes
its PID, parent PID, available start instant, and lifecycle state in the run directory, allowing a
driver to attach without process-name or Launch Services guesses and to reject PID reuse. A runtime
whose operating system doesn't expose a start instant stays non-attachable.
The driver-neutral evidence sealer is also implemented. It accepts only an ordered scenario result,
turns missing capabilities into a skip rather than a pass, bounds diagnostics and artifact bytes,
confines artifacts to the real run directory, hashes stable files itself, and publishes the accepted
evidence atomically. The driver-neutral runner composes those contracts: it gates capabilities,
validates the live PID before each ordered step, requires a fresh observation after input, enforces
monotonic call deadlines, stops on the first failure, and seals pass/skip/failure output through the
engine. Its mock pass, missing-capability skip, and mid-run failure paths are covered without
launching a game.
Semantic waits now come from a second atomic runtime record bound to the same PID and process start
instant. Exact resource-init, campaign-loop, and combat-loop seams publish only state transitions;
ordinary frames pay one volatile comparison. The runner validates that identity and owns the wait,
so OS adapters don't infer gameplay state from a screenshot or window title.
The remaining work is a separately interruptible platform adapter, starting with isolated macOS
PID-addressed window attachment.

## Measured result (2026-08-01, third campaign)

`benchmarkAccepted: true`, 15 of 15 runs, no exclusions, launch-order drift **-0.04s (0.0% of
variance)**. Unattended, 240s cooldown before every launch, discarded settling launch, quiet
machine.

| condition | median | paired result |
| --- | --- | --- |
| `vanilla` | 88.13s | -- |
| `fast` (cache + prefetch bypass) | 72.25s | beats vanilla by 15.88s, 5 rounds of 5 |
| **`prepared`** (pixel bypass + prefetch bypass) | **62.60s** | **beats vanilla by 25.53s, 5 rounds of 5** |

**What a user feels: 25.5s off 88.1s, about 29%.**

Every ordering is unanimous and the three conditions do not overlap: the slowest `prepared` run is
7.8s faster than the fastest `fast` run, which is 15.6s faster than the fastest `vanilla` run.

The control that makes it believable is `vanilla`, measured ten hours earlier on the same install
and profile at **88.49s** -- it moved 0.4%. The other two moved because the code did:

| condition | previous campaign | this campaign |
| --- | --- | --- |
| `vanilla` | 88.49s | 88.13s |
| `fast` | 78.93s | 72.25s |
| `prepared` | 87.89s | **62.60s** |

Three things stack, and none of them is a faster computation:

1. **A wait that stops happening.** The loading thread slept **27 of the load's 96 seconds** polling
   Starsector's one-thread image prefetcher while the decoded pixels sat unread in the cache behind
   it. The bypass takes 50,879 enqueues off the game's queue and moves the cache from 6,651
   textures served to 21,652.
2. **A hash that stops happening.** Up to 1.34 GB of PNGs re-hashed per launch on the loading
   thread at 292 MB/s -- 40.9% of `main`'s on-CPU samples -- because the game ships an x86_64 JRE
   and Rosetta 2 exposes no SHA-NI. Worth **6.68s** on its own.
3. **A decode and conversion that stop happening.** 21,652 pixel conversions bypassed, 3.92 GB
   handed straight to `glTexImage2D`. Worth **9.65s** with everything else held constant.

Item 3 was the condition that lost the last campaign, at 0.60s from vanilla with p = 1.000, because
it was the one mode that could not take item 1. It can now.

Full write-up:
[twenty-nine percent, when they compose](evidence/2026-08-01-twenty-nine-percent-when-they-compose.md).
Prior campaign, for the 27-second wait itself:
[ten percent, by not waiting](evidence/2026-08-01-ten-percent-by-not-waiting.md).

Next, in order: the game's own `File.exists` probe of 77 mod roots per resource, and the untouched
JSON/spec path.

## Landed since that campaign (2026-08-02), correctness-validated, not timed

**The texture padding is gone.** `--prepared-unpadded` was inert because the fold bypass and the
prepared-pixel rewrite both target `com/fs/graphics/TextureLoader` and the registry dispatches one
plan per class. They now compose, and a real full load reports **0 padding bytes against
1,394,162,605** before it -- 3.65 GiB uploaded becomes 2.43 GiB, **1.22 GiB less**, while serving
1,754 *more* textures. Every safety counter stayed at zero and the launcher texture that caused the
July crash now allocates exactly the 668,043 bytes it is given.
[Evidence](evidence/2026-08-02-the-padding-is-gone.md). Still opt-in; no timing claim.

**Preflight now says when another agent owns a target class** instead of reporting a hash mismatch
that reads as a stale cache. [Prior art](prior-art-starsector-render.md).

**Two ideas were measured and rejected before being built**: the save-load reference pre-scan (the
scan costs more than the registrations it avoids) and any binary save format (the whole 103 MB save
pull-parses in 685 ms).
[Save analysis](evidence/2026-08-02-save-loading-is-not-parsing.md).

Context that reframes all remaining CPU work:
[the game runs under Rosetta](evidence/2026-08-01-the-game-runs-under-rosetta.md).

### Superseded: the first accepted campaign (same day, before the bypass)

| condition | median | paired result |
| --- | --- | --- |
| `vanilla` | 95.78s | -- |
| `fast` (cache, compatibility) | 97.22s | loses to vanilla by 1.28s, 4 rounds of 5 |
| `prepared` (cache + pixel bypass) | 94.10s | beats fast by 2.68s, 5 rounds of 5 |

1.5%, and correct for the code as it stood. Absolute times are ~7s higher throughout because that
campaign ran on a loaded machine; the measurement environment moves this profile by about as much as
the old effect size, which is why campaigns now require the machine to themselves.
[Write-up](evidence/2026-08-01-the-first-valid-startup-number.md).

### Why it was only 1.5%, measured -- and what fixing it was worth

**The loading thread spends 27 of 96 seconds asleep**, in a 10ms poll loop, waiting on a single
background thread decoding PNGs. The machine is 27.8% busy -- under three of ten cores -- because
the game's own prefetch pipeline is one thread wide. Stop-the-world GC is 0.00s and GPU upload is
~1% of samples, so neither is the constraint.

Preflight got none of that time back because its cache lookup sat on the wrong side of the wait.
Our hook was reached 6,654 times; the manifest holds 32,917 textures. The difference is the set the
prefetcher answered first, and the 27 seconds is what the loading thread paid to wait for it.

**Fixing that is the first of the three items above.** Taking those paths off the game's queue moved
the cache from 6,654 lookups to 21,656, and the load from 88.49s to 78.93s in the campaign that
isolated it -- and, once the hash and the pixel bypass composed on top, to 62.60s.

So the load is **not** an irreducibly serial chain, and the opportunity **is** reachable from where
Preflight sits -- both of which this roadmap asserted earlier today on the strength of a report
that printed the eight longest-blocked threads while the loading thread ranked ninth.

Also measured, and unaddressed: **the JSON/spec path is comparable to the texture path** in both
wall time (`LoadingUtils` owns 0-25s and 65-85s; `TextureLoader` owns 25-65s and 85-95s) and
allocation (27% of ~126 GB, the single largest site). The resource index solves *finding* a
resource; nothing caches the parsed result.

Full write-up: [the loading thread waits on a one-thread prefetcher](evidence/2026-08-01-the-loading-thread-waits-on-a-one-thread-prefetcher.md),
reproducible with `scripts/starsector_critical_path.py <recording.jfr>`. It supersedes
[what the load is actually waiting for](evidence/2026-08-01-what-the-load-is-actually-waiting-for.md).

### Standing correction: JFR durations from a Starsector recording are 2.49x short

The game launches with `-XX:+UseFastUnorderedTimeStamps`, under which JFR's tick-to-nanosecond
conversion is wrong by a constant factor. On the reviewed install a `Thread.sleep(10)` records as
4.7ms and a 96-second load records as 38.5 seconds of events, which reads as a truncated recording
and is not one. `scripts/starsector_critical_path.py` now measures the factor against `jdk.CPULoad`'s
fixed 1000ms period and says so at the top of every report.

Shares, ratios and sample counts are unaffected -- they count events, and counting does not depend
on the clock. Durations are not: any wall-clock figure previously read out of a Starsector JFR
recording is short by ~2.49x.

## Standing correction: every startup number before 2026-08-01 is void

The benchmark measured from the first log line that appeared after its own snapshot. Starsector's
launcher writes into the same log the game does, so log4j flush timing decided whether the early
part of loading fell inside the measured interval. That is the whole of what was recorded as an
"unexplained 18s bimodality": every run anchored on the launcher's line measured 92-99s, every
run anchored on a later mid-load line measured 74-78s.

Read straight out of the game's own log, the same launches are unimodal at 89.6-99.1s. **Startup
on the reviewed profile is ~92 seconds, not ~75.** The measurement is fixed
([evidence](evidence/2026-08-01-the-bimodality-was-the-anchor.md)), and
`scripts/starsector_log_load_times.py` is the independent check that the harness now has to
agree with.

What this voids, and what it does not:

- **Void:** every recorded `gameLogStartToGraphicsPreloadMs`, every campaign summary built on
  one, and the prepared-pixels-versus-compatibility pilot — `prepared` was recorded at 99.1s
  against `fast` at 92.2s and 74.9s, and only one of those two was measuring the same quantity.
- **Not void:** everything established by JFR attribution, adapter reports, or the game's own
  behaviour. The profile shares (texture conversion 34-40% of the loading thread, PNG decode
  13-16%, the O(n) LinkedList scan 5-7%) are ratios within a recording and do not depend on the
  harness's wall-clock boundary at all. The correctness work — compatibility-v2 acceptance, the
  prepared-pixel contract check, the padding invariant — is untouched.

The next campaign is the first that can produce a reportable number. Run it unattended, into a
fresh session directory, and check it against `starsector_log_load_times.py` before believing it.

## Current near-term program

The July 2026 unified real-install runs completed the broad discovery gate for texture loading, Janino compilation, and audio decoding.

- [Optimization North Star](optimization-north-star.md) records the real-install evidence, exact reviewed targets, ordered implementation program, benchmark protocol, and release gates.
- [Real texture preparation and compatibility pilot](evidence/2026-07-18-real-texture-preparation-and-compatibility-pilot.md) records the passing full-profile preparation, the title-screen renderer failure, and the bounded launcher-lifecycle reporting fix.
- [Compatibility-v2 acceptance evidence](evidence/2026-07-19-real-texture-compatibility-v2-acceptance.md) records one bounded accepted real-install texture run.
- [Prepared-pixel operator and LLM handoff](prepared-pixels-operator-handoff.md) defines the exact current sequence and stop points.
- [Next LLM Implementation Handoff](next-llm-handoff.md) provides current identities, responsibilities, prohibited shortcuts, and the next implementation decision tree.
- [Verification strategy](verification-strategy.md) records which claims can be proved without the game, which need the reviewed installation, why each machine in the fleet is or is not suitable, and the 2026 tooling survey behind those choices.

The adapter-OFF control reached the main screen and exited normally. Compatibility-v2 preserves Starsector's asynchronous image-preloader handoff, matches the exact installed bytes, and passed bounded real-install behavioral acceptance on 2026-07-19. PR #117 repaired the installed-style prepared-pixel color flow, and PR #119 added an offline exact installed-class contract checker. The immediate sequence is now: run that checker against the reviewed installation, review the report, complete one prepared-pixel lifecycle through campaign/combat/save/clean exit, and only then run repeated OFF-versus-compatibility-versus-prepared-pixel measurements. Audio and Janino remain exact-evidence gated until the texture decision is made.

## M0: Measurement foundation

- Launch-time JFR agent
- Startup trace summarizer
- Repeatable benchmark protocol
- Profile and environment fingerprints

Exit condition: a baseline result bundle explains the dominant startup costs for at least one large mod profile.

## M1: Resource index

- Ordered enabled-mod fingerprint
- Winning-provider lookup
- All-provider lookup for mergeable resources
- Negative lookup cache
- Case-collision diagnostics

Exit condition: fixture tests match reference resource resolution and benchmarks show the saved lookup work.

## M2: Prepared textures

- Benchmark current decode and conversion path
- Bulk conversion for common image layouts
- Versioned prepared-texture payload
- Content-addressed cache pack
- Corruption detection and rebuilding
- Exact-gated compatibility and upload-ready runtime consumers

Exit condition: cached and uncached texture data are equivalent and repeat startup improves on image-heavy profiles.

## M3: Script bytecode

- Measure loose-source compilation cost
- Persist generated and transformed bytecode
- Capture complete ordered source/resource dependencies
- Conservative exact-context invalidation

Exit condition: representative source-heavy profiles compile once and safely reuse complete generated class maps.

## M4: Scheduling and integration

- Separate image and script worker pools
- In-flight decoded-byte budget
- Runtime adapter for vanilla Starsector and optional Fast Rendering support
- Cross-platform packaging and diagnostics

## M5: Prepared audio and later experiments

- Prove installed-JOrbis PCM and wrapper-contract equivalence
- Reuse short fully decoded effects with exact keys and untouched fallback
- Preserve streaming music until its policy is proven safe
- Evaluate selective lazy loading only when traces identify a narrow safe target

Exit condition: prepared audio is byte-for-byte and metadata-equivalent, bounded, fail-open, and measurably reduces repeat-launch decoding work.

**Deprioritised on 2026-07-30, on evidence, and not on the evidence below.** The work described here
is real and happens before the menu, but it runs on a worker pool the loading thread never waits for:
**one audio sample out of 4,423 on the loading thread**, which blocks 67 ms in 90 seconds while the
two decode workers sit parked for 22 seconds each. Removing it would remove CPU and energy from a
thread nobody is waiting on, which on a 10-core machine is close to invisible in wall-clock startup.
It may still matter on a core-starved machine or a far larger audio profile, neither measured. See
[the loading thread never waits for the audio](evidence/2026-07-30-the-loading-thread-never-waits-for-the-audio.md);
texture preparation, at 40–53% of the loading thread, is the one of the three domains that is on the
critical path.

**The premise below is measured and holds — as a statement about work performed, not about wall
clock.** A run on 2026-07-29 through
[`audio decode-probe`](audio-decode-probe.md) found the game opening **all 2,050 declared effects
inside a 1.5-second window**, 23 seconds into a 360-second session, from a single caller — **1,169.4
MB of PCM built before the player sees the main menu**, on every launch. The session reached the
campaign map and flew on it, and **no sound file was opened for the first time after the 24.7-second
mark**, so entering the campaign added nothing to that set.

This is the whole declared effect set rather than a floor: combat cannot add to a set the game has
already opened completely, and a sound loaded from outside the census would show as an unmatched read.
**Nothing opens the 220 unreferenced files**, which independently confirms the `audio-unreferenced`
lint rule from the game's own behaviour rather than from config.

Music remains out of scope on the same caution as before, not on this evidence. None of the 156
declared music files were opened, but vanilla music is one container — `sounds/music/music.bin`, read
1,806 times in this run — that the census has no entry for, so the probe says nothing about it.

The first published version of this said 1,278 effects and 940.3 MB. The probe was resolving the
recording's relative paths against its own working directory instead of the game's, so every resource
the game opened by relative path looked unopened ([#232](https://github.com/teamleaderleo/preflight/issues/232)).

Reads are still not decodes; the equivalence work remains what proves what the decoder does with
them. [Evidence](evidence/2026-07-29-the-game-builds-1-2-gb-of-pcm-before-the-main-menu.md).

**Equivalence is owed to the installed decoder, not to a reference decoder.** The first version of the
gate decoded through `com.jcraft.jorbis.VorbisFile`, which no shipped Starsector code path calls, and
compared against libvorbis output. Rebuilt in #207 and #208 onto the low-level sequence `sound/void`
drives, it **passes against the reviewed installation** (2026-07-26). The wrapper observation carried the same defect and was
corrected the same day: four of five wrapper payloads match the installed decode byte for byte, and the
fifth is the wrapper's one-byte sentinel for silent streams, which prepared audio must reproduce or
exclude ([follow-up](evidence/2026-07-26-the-wrapper-payload-was-never-the-problem.md)).
See [the evidence](evidence/2026-07-26-the-audio-gate-decodes-an-api-the-game-never-calls.md).

**"Short fully decoded effects" now has numbers behind it.** The
[audio census](audio-census.md) sizes the reviewed profile without decoding it: 1,803 declared
effects hold **1.17 GB** of PCM, declared music holds another 2.86 GB, and 197 files are declared
nowhere. Three facts shape the policy the second bullet still needs
([evidence](evidence/2026-07-26-what-prepared-audio-would-have-to-hold.md)):

- 17 effects over a minute long hold 313 MB — 27% of the eligible bytes in under 1% of the files —
  so a duration bound is worth more than any other knob. The 1,511 effects under five seconds total
  403 MB.
- 195 effects recorded at 96 kHz or above hold 33% of the eligible bytes, and cost proportionally
  more MDCT work to decode. Resampling them is a transformation, not a cache, and stays out of scope.
- Effect versus music is decided by `sounds.json`, never by directory naming, which is wrong in both
  directions in this profile.

## Prior art on the same seams

`starsector-render` is a Java agent for the same game version that already rewrites
`com/fs/graphics/L`, `TextureLoader`, `com/fs/util/C`, `ResourceLoaderState`, `SpecStore` and
`ScriptStore`, parallelises Janino compilation, and ships a frame profiler. Read
[the review](prior-art-starsector-render.md) before starting anything below it -- two queued items
here may already be solved there, its constant-pool substitution technique is worth borrowing, and
the question of whether the two agents can coexist is open and unanswered.

## Runtime performance: a second axis, and four leads

Everything above measures **time to main menu**. That is not the only thing a player feels, and on
the hardware most players own it may not be the thing they feel most: general snappiness, battle
entry and exit, and frame pacing when a high-DP fight fills the screen with projectiles.

Nothing in this section is a result. They are leads, recorded so they are not rediscovered, ordered
by expected value over cost. All four were found on 2026-08-01 while profiling the load; none has
been measured.

### 1. The combat package may be excluded from the optimizing JIT

Starsector launches with `-XX:CompilerDirectivesFile=../../MacOS/compiler_directives.txt`. That file
contains two blocks:

```
match: ["com/fs/starfarer/combat/*.*", "org/dark/shaders/*.*"]
  c1: { Enable: true, Exclude: false }
  c2: { Enable: true, Exclude: true  }      <-- combat excluded from C2

match: ["com/fs/graphics/*.*", "com/fs/state/*.*", "com/fs/util/*.*"]
  c1: { Enable: true, Exclude: true  }
  c2: { Enable: true, Exclude: false, Vectorize: true }
```

Read plainly, the combat package never reaches C2 and runs C1-compiled forever, while graphics skips
C1 and goes straight to a vectorizing C2. That asymmetry points at exactly the code that governs
frame time in large battles.

Two unknowns, neither guessed at here:

- whether `com/fs/starfarer/combat/*.*` matches sub-packages, since the AI code likely lives under
  `combat/ai/` and may fall outside the pattern;
- **why it exists.** A C2 miscompilation workaround or a defence against compile-pause stutter are
  both plausible, and in either case removing it makes things worse.

It is a text file, so the experiment is one line and instantly reversible. Verify first with
`-XX:+PrintCompilation`, or JFR compilation events, and check whether combat methods ever reach
level 4.

### 2. The game ships with VBOs forced off

`data/config/settings.json` line 76: `"forceNoVBO": true`. Vertex buffer objects are disabled
globally, so geometry goes through immediate mode or client-side arrays -- the most expensive way to
push it, and expensive in proportion to how much is on screen. Almost certainly a legacy guard
against driver bugs; two lines below, `"slipstreamUseGLLines": true # a bit faster but may have some
rendering issues depending on drivers` makes the same safety-over-speed tradeoff explicit.

Same shape as the JIT directive: conservative default, one-line experiment, reversible, and the
correctness risk is visual and immediately obvious rather than silent.

### 3. Rosetta is a frame-time cost, not just a load cost

The bundled JRE is x86_64, so the combat loop -- projectile physics, collision, AI over hundreds of
entities -- runs translated. The 11.4x measured for SHA-256 is the *worst* case, since hashing is
exactly what hardware intrinsics target, and ordinary branch-heavy Java is penalised far less. No
number is claimed for the combat loop. But on a CPU-bound frame budget any penalty comes straight
out of frames, and this is plausibly the largest single lever on battle performance for every Apple
Silicon player. It needs arm64 LWJGL 2 natives, so it is Fractal's to pull, not ours. See
[the game runs under Rosetta](evidence/2026-08-01-the-game-runs-under-rosetta.md).

### 4. A frame-time harness, which the project does not have

The existing gameplay script is human-observation correctness only ("did the projectiles look
normal?"). There is no timing. JFR sampling at ~10-20ms cannot resolve a 16ms frame, so sampling
alone will not do it. What would:

1. Hook `org/lwjgl/opengl/Display.update`/`swapBuffers` and write a timestamp to a ring buffer. Pure
   observation, no behaviour change, negligible cost, and the class rewriting machinery already
   exists.
2. Report **99th and 99.9th percentile frame times**, never averages. Stutter is a tail phenomenon
   and means are blind to it.
3. Correlate the worst 1% of frames against JFR windows -- what was on-CPU, what was allocating,
   what Shenandoah was doing -- rather than against the whole run.
4. Fix the scenario using the simulator or a mission, so runs compare.

This is what would let lead 5 below be argued in frame times instead of VRAM accounting.

### 5. What the padding fold is actually for

`TexturePaddingRuntime` serves textures at their true size instead of padded up to a power of two.
Measured on the composed run of 2026-08-01:

```
uploadBytesSupplied  3,923,988,688     handed to the GPU
paddingBytes         1,394,162,605     of which this is zeroes (35.5%)
paddedUploads               17,525     of 21,653 textures (81%)
```

**1.39 GB of VRAM is padding**, and unlike every timing number in this repository that figure is
machine-independent: it is 1.39 GB on a 4 GB integrated GPU exactly as it is on a fast discrete one.
VRAM oversubscription does not show up as a lower average framerate; it shows up as hitching when a
battle pulls in a texture set the driver has evicted.

It is currently switched off, because the padding fold and the pixel plan target the same class and
have not been composed -- the same problem solved for the prefetch bypass on 2026-08-01. See
[half an invariant kills the launcher](evidence/2026-07-31-half-an-invariant-kills-the-launcher.md).

### The long tail, and how to measure it without owning the hardware

This machine (M5 MacBook Air, 24 GB) is well above the median player's. Reports of five-, ten- and
twenty-minute loads come from hardware nobody here has, and cannot be measured here. Two rules keep
that from becoming speculation:

- **Prefer counts over times.** Bytes uploaded, padding bytes, peak heap, allocation rate, GC
  counts. They transfer across machines; seconds do not. The 1.39 GB above is the model.
- **Constrain this machine rather than hunt for a worse one.** The interesting question is not "how
  much slower is a small machine" but **"what is the lowest `-Xmx` at which this modlist loads at
  all, with and without Preflight?"** That is binary, machine-independent, and answerable here.

  Predicting against ourselves: texture work probably does *not* move that floor much. Decoded
  textures leave the heap for VRAM; what prepared-pixel mode improves is allocation rate and peak
  transient (`peakDirectBytes` 25 MB, against a 16 MB `int[]` per 2048x2048 texture in compatibility
  mode). The floor is more likely set by retained spec/JSON/rules data -- the path still untouched.
  A boring answer here is worth having, because it redirects effort there.

One correction worth recording, since it was made in the other direction first: the `-Xms6144m
-Xmx6144m -XX:+AlwaysPreTouch` in `starsector_mac.sh` is an **operator setting, not a shipped
default**. The stock heap is far smaller. The squeeze on low-RAM players is therefore worse than
"the game wants 6 GB": a heavy modlist *forces* the heap up to avoid OOM, and that is exactly the
player who cannot afford it. They choose between crashing and swapping.

## Exploratory tracks (not yet evidence-gated)

Separate from the speed-first milestone program above:

- [Asset Lint](asset-lint.md) — `preflight lint`, a read-only report of asset problems attributed to
  the mod that ships them. Same analysis as the speed work, pointed at the source instead of routed
  around it: a mod author who fixes an asset helps every user of that mod with no cache, no adapter,
  and no equivalence gate. Twelve rules over sound, textures, config and shipped files, runnable
  against a whole profile or a single mod directory. Against the reviewed 84-root profile it finds
  1,392 issues — 285 progressively-encoded images that ImageIO decodes
  [about 8.75x slower](evidence/2026-07-28-progressive-jpeg-costs-nine-times-the-decode.md) than the
  identical image stored normally, 771.9 MB of video memory lost to non-power-of-two padding,
  687.9 MB of avoidable audio decode, 100.8 MB of disk in shadowed copies, duplicates and editor
  project files, and two files the game cannot decode at all.
  [Calibrated across 86 mods](evidence/2026-07-28-what-eighty-six-mods-ship.md) as independent
  samples: median 0 findings, 44 of 86 completely clean.

  The two config rules are the only ones that report something *broken* rather than something
  expensive. They read the 15,353 JSON-shaped files the profile ships and find
  [five](evidence/2026-07-28-config-the-game-silently-never-reads.md), four of them real defects in
  released mods — including a missile whose `PROXIMITY_FUSE` block sits outside the top-level object
  and so does nothing in game. Getting there required discarding a first version that flagged 27
  working files, because the dialect accepts far more than JSON does and a stray trailing brace is
  invisible to the game.

  Applies no fixes; a transform mode would touch other people's assets and has no safety story yet.

- **Desktop GUI (unreviewed exploration).** `preflight-desktop/` is a Vite/React shell, and
  `preflight desktop` is a read-only bridge command that discovers the installation and prints one
  JSON snapshot. It is hidden from CLI help, `preflight-desktop` is not a Maven module, and its build
  output is gitignored, so neither affects the Java build. Notes in
  [desktop app research](desktop-app-research.md).

  This arrived on main inside #216, a commit about asset-lint calibration, because that commit staged
  every modified file rather than the ones it touched. The code was exploration in progress and was
  never reviewed as part of that PR. It is recorded here rather than reverted — it is inert and
  works — but nothing here endorses it as a direction, and it needs its own review before anything
  depends on it.

- [Asset Quality Track](asset-quality-track.md) — proposed opt-in visual-fidelity track
  (crisper BMFont atlases as a candidate standalone mod; offline texture super-resolution
  behind a faithfulness gate). Records concrete font-asset facts, the bigger-vs-sharper
  design question, the gameplay-coordinate gotcha for hull sprites, the VRAM estimator /
  Asset Lab budget, why in-game FPS is out of scope, and external references. Must not
  regress speed-track measurements.
- [Community Evidence and Benchmark Additions](community-evidence.md) — Reddit/forum sweep
  (tier-4/5) that justifies keeping the identity-heavy benchmark design and adds two
  concrete items: a VRAM/decoded-texture estimator and separate runtime/launcher campaign
  orchestration (vanilla+bundled-Java, vanilla+alternate-Java, FR — each OFF and warm).

### Revised near-term priorities (speed track first, quality track opt-in)

1. Complete the prepared-texture lifecycle and controlled timing campaign.
2. Benchmark Starsector's built-in script cache against Preflight Scripts.
3. Run separate bundled-Java, alternate-Java, and FR campaigns (identities never merged).
4. Add VRAM and decoded-texture estimates to `doctor` and profile reports.
   *(In progress: `TextureMemoryEstimator` core + tests landed; `prepare` emits
   `.stages.textures.details.memoryEstimate` and `texture manifest inspect` prints it.
   Census working-set breakdowns landed: `ImageHeaderReader` (exact PNG/JPEG dimensions,
   header-only) feeds a per-mod decoded-VRAM breakdown in `scan` — `decodedWorkingSet`,
   per-mod `decodedImageBytes`, and `largestDecodedMods`. On a real ~70-mod profile this
   surfaced a 4.7 GB decoded floor from 1.1 GB on disk, and a decoded ranking that inverts
   the on-disk one (e.g. a 47 MB mod = 986 MB VRAM). `scan --vram-budget <size>` now grades
   that floor with a three-way advisory verdict (`over` / `at-risk` / `under`), where `at-risk`
   means the base levels fit but a full mip chain (floor + floor/3) would not. The verdict is
   graded against the override-resolved `winnerDecodedImageBytes` (only the loaded provider at
   each logical path), not the all-providers total, so texture-replacer overlap can't inflate it
   into a false `over` — on the real profile a 4G budget reads `over` by 388 MB. `doctor` now
   prints a compact decoded-working-set summary (override-resolved floor, loudest decoded mods,
   pointer to the budget verdict) so the estimate is visible from the command users actually run,
   not just `scan --json`. That closes roadmap #4. `scan --max-texture-size <pixels>` then answers
   the follow-up question a verdict alone cannot — *what would I actually cut* — by projecting the
   floor after capping every override-winning texture's long edge, using exact repeated halving
   (a 2x2 box reduction divides decoded cost by exactly 4 and keeps power-of-two sizes), and
   re-grading the budget against that projection. On the real profile this refuted the obvious
   guess: a 2048 cap touches only 5 textures and saves 74 MiB, still `over`; the cost is a long
   tail, and a 1024 cap (211 textures) takes 4.36 GiB to 2.76 GiB and clears 4G. See roadmap #7.)*
5. Add save/load and clean-exit outcomes to launcher-compatibility campaigns.
6. Font quality: **mechanism confirmed in-game** — mod override of core fonts works, and the
   core UI renders at declared `.fnt` metrics (so an `N×` pack is *bigger* text, not
   same-size-sharper; residual graininess is display/UI-scale). **Landed**: `BitmapFont` codec,
   `FontAtlasGenerator` (AWT rasterizer), `preflight font generate`, and `font generate-pack`
   (whole-UI bring-your-own-font mod generator). Remaining: polish the readable-font mod
   (font picker / packaging), optional kerning, and a fits-in-layout larger-text option.
7. Build the texture Asset Lab as an offline, opt-in overlay generator with a budget gate.
   *(Landed, gate first then generator. The gate: `scan --max-texture-size <pixels>` projects the
   override-resolved floor after capping oversized textures, ranks the largest individual savings,
   and re-grades the VRAM budget against the projection — pure arithmetic over image headers,
   rewriting nothing. The generator: `preflight assets shrink --max-texture-size <pixels> --out-dir
   <mod-dir>` writes the capped textures as a drop-in override mod, so the projection can actually
   be taken. It never touches the installation; undoing it is disabling one mod. On the real
   ~70-mod profile a 1024 cap wrote all 211 oversized textures in 30 s (44 MB on disk) and the
   written pack re-measures to **exactly** the projected 521.85 MiB — 1.60 GiB saved, projection
   and delivery byte-identical. The overlay direction is reduction, not super-resolution: see
   [asset-quality-track.md](asset-quality-track.md) for why that is the honest win.)*
8. Keep enhanced assets in a separate cache namespace and manifest.
   *(Prerequisite measurement done. `assets compression-probe` scored real profile art through a real
   BC1/BC3 encoder in CIELAB ΔE and inverted the standing 2019 objection: the large smooth textures
   holding **55% of art VRAM** round-trip at median mean ΔE **0.80**, below human perceptibility,
   while the small sprites that genuinely mangle are under 1% of VRAM. Then
   [`probe-kits/gpu-capability`](../probe-kits/gpu-capability/README.txt) asked the driver what it
   will accept — see
   [2026-07-25-macos-gl-capability-probe.md](evidence/2026-07-25-macos-gl-capability-probe.md).
   BC1–BC5 upload by both routes; **BC7 and ASTC return `GL_INVALID_ENUM`** on Apple's GL, so the
   macOS choice is BC1/BC3 or nothing. Two further facts fell out: non-power-of-two textures upload
   natively, so the loader's `get2Fold` padding — **1.86 GiB, 27% of resident VRAM** — is inherited
   Slick2D behaviour rather than a hardware requirement; and the engine's hardcoded `GL_RGBA`
   internal format could be swapped for a compressed one at the same call site, one constant for a
   4× cut. The offline-encoder path is preferred anyway: `BlockCompressor` beats the driver's encoder
   by 1.6–2.0×, it allows a per-texture selective policy, and a pre-encoded block texture removes the
   PNG decode stage entirely — putting block compression on the speed track rather than opposite it.
   **That last claim is now measured** — see
   [2026-07-26-texture-load-pipeline-decomposition.md](evidence/2026-07-26-texture-load-pipeline-decomposition.md).
   On the real profile, ImageIO decode is 67–70% of texture load and the raster walk plus power-of-two
   padding another 25–28%, against **under 3% for the GPU upload** and about 3% for disk: CPU work is
   **94.6%** of a texture load. A block cache is therefore **61–74× faster than vanilla and ~4× faster
   than the existing prepared-pixels cache**, while reading a quarter of the bytes and leaving a
   quarter of the VRAM resident — it dominates the incumbent on every axis simultaneously. This
   reorders the program: decode elimination is worth 16–74×, every footprint lever is worth a few
   percent of load time, and the block cache is the best available answer to both, so it should come
   before further shrink work.
   **The encoder underneath that estimate is now verified against real hardware** — see
   [2026-07-26-encoder-driver-byte-agreement.md](evidence/2026-07-26-encoder-driver-byte-agreement.md).
   `BlockCompressor` previously round-tripped pixels without ever forming a block; it now emits the
   exact byte layout `glCompressedTexImage2D` expects, and `roundTrip` is defined as encode-then-decode
   so published fidelity numbers describe the file rather than the palette the encoder had in mind.
   Serialising surfaced a defect that could not have been seen before: **BC1 reads its endpoint order
   as a mode bit**, nothing upstream constrained it, and leaving it unordered would have taken mean ΔE
   from 1.69 to 18.44 on half of all blocks. Ordering costs nothing. `BlockUploadProbe` then had the
   driver arbitrate rather than trusting encoder-agrees-with-decoder: **BC1 and BC3 are now bit-exact
   against Apple's decoder over 65,536 pixels each**, after matching one measured quirk — the colour
   blends truncate while the alpha blends round, in the same block.
   **The blocks now have somewhere to live.** `BlockTexture` / `BlockTextureIO` (magic `SPFB`) and
   `BlockTextureBaker` are the block cache's blob format and bake path. It is a separate type from
   `PreparedTexture` on purpose: that cache promises pixels identical to the loader's, block data
   breaks that promise by design, and collapsing both into one codec field would leave the difference
   to a check any consumer could omit — with no exception and no log line when they do. The blob
   carries the encoder's `CODEC_VERSION` (a blob from an older encoder is not corrupt and cannot be
   recognised by inspection), the GL internal format, and the bake-time `TextureFidelity.Report`, which
   is required rather than optional so a lossy texture cannot enter the cache without its loss having
   been measured. The baker picks BC1 for opaque art and BC3 for anything with alpha, and can bake a
   full mip chain. Writing that chain exposed a defect in the shipped `assets shrink` downsampler: a
   2x2 box filter is only correct on even dimensions, so a 5-pixel row halving to 2 never reads the
   fifth pixel at all. Both now area-average through a shared `ImageResampler` — identical to the 2x2
   box on power-of-two art, so nothing a released `assets shrink` has written changes, and correct off
   it.
   **A profile can now be baked.** `BlockCacheManifest` / `BlockCacheManifestIO` (magic `SPFC`) hold the
   separate namespace this item asks for, and `preflight assets bake-blocks --out-dir <cache-dir>`
   fills it. The interesting behaviour is the refusal to encode: every texture is baked, measured, and
   kept only if p99 ΔE came in under a stated gate (default 1.0, the just-noticeable threshold), so a
   texture with no entry simply keeps the ordinary decode path. That per-texture policy is the reason
   an offline encoder beats flipping the engine's internal-format constant. Shader maps are excluded on
   principle rather than on their number — ΔE does not describe a normal map. Blobs are
   content-addressed, so duplicated art across mods is encoded once. The manifest holds the encoder
   version once for the whole cache, so a stale cache is one integer compare at startup rather than ten
   thousand. **Nothing reads it yet** — the cache is inert until a runtime adapter exists, which is
   deliberate: it can be baked, inspected and argued about before anything touches a loading game.
   **The cache is now driver-checked, not just self-checked.** `preflight assets cache-conformance`
   exports a sample of a baked cache in the same `SPFV` format `block-conformance-probe` already reads,
   so the real driver arbitrates the real cache — the one part of this a synthetic harness structurally
   cannot reach, since every existing harness stops at the byte level and a wrong internal-format
   constant or mip-level order would pass all of them. Confirmed bit-exact on Apple M5 hardware. The
   consumer is the remaining piece.)*
9. Turn community reports into regression cases.
10. Reserve performance claims for repeatable runs with exact identities.

## Milestone numbering in the issue tracker

M0–M5 above are the original document milestones. Later work continued the numbering in the issue tracker rather than here:

- M6 — synthetic production-cache workload proofs (PRs #67–#68).
- M7 — self-contained real-install probe kits (PR #72).
- M8 — exact real-install identity and equivalence gates before live reuse: issues #75 (audio), #77 (Janino), #78 (texture shape).
- M9 — one exact-profile pre-launch build and launch context: issue #76.
- M10 — repeated real OFF-versus-ENABLED startup benchmarks: issue #80.
