# Beta announcement draft

Long source copy for the forum, Reddit, release editing, and anywhere else that needs more of the
story than the release post. The shorter version is
[beta-announcement-leo-draft.md](beta-announcement-leo-draft.md).

Keep candidate-specific fields bracketed until the retained release candidate has actually produced
them. The Starsector forum takes BBCode rather than Markdown; use the release-day link blocks in
[downloads.md](downloads.md#release-day-link-kit) and convert the prose before posting there.

---

## Preflight: a performance launcher for Starsector

I have 83 mods installed.

At its worst, Starsector took about 101 seconds to reach the main menu on my development setup. In
the latest controlled comparison on that same 83-mod profile, five ordinary launches had an
**89.00-second median** and five Preflight launches had a **15.53-second median**. The lowest launch
in that comparison was **15.25 seconds**.

Those are development numbers, not a promise about every machine. The released package gets its own
benchmark before this post goes public:

**[PACKAGED CANDIDATE BENCHMARK RESULT]** on **[CANDIDATE GAME / HARDWARE / RUNTIME]**.

**Download:** [RELEASE URL]

Preflight is a free, open-source performance launcher and companion app for Starsector. It prepares
work the game and mods would otherwise repeat during startup and reuses it while the relevant inputs
still match. When reviewed code changes, the affected runtime shortcut steps aside and Starsector
handles that work normally.

The project started as a loading-time investigation. It did not stay one.

## Measure it on your own installation

The desktop includes a normal-versus-Preflight startup benchmark, so the useful question is not
whether my 83-mod result sounds impressive. You can measure your own installation.

The benchmark keeps the installation, profile, launcher, runtime and settings consistent across the
pair, waits for the main-menu marker, and produces a compact comparison you can copy into a forum,
Discord message or issue. The candidate result above is filled from the packaged release bytes, not a
checkout build or an earlier rehearsal.

## The rest of the app grew out of the same investigation

Once I had a launcher sitting between a large mod setup and the game, a lot of adjacent problems were
hard to ignore.

Preflight keeps local playtime for sessions it launches and can observe. It can save named mod
profiles, preview a profile switch before changing the enabled-mod selection, and keep the common
game settings beside Launch instead of sending you through a separate launcher ritual. Preparation
shows the current storage plan before it writes anything, can offer a minimal-disk route when the
normal cache is too large, and can stop safely or repair damaged prepared data.

There is also read-only setup analysis and mod linting for people who want to understand a large mod
stack without changing it. The Hangar traces installed ship art locally into Preflight's wireframe
presentation; the final beta UI uses the custom typeable ship selector and instrument controls that
went through the same desktop acceptance pass as the rest of the app.

Support is deliberately separate from ordinary game use. **Copy setup** gives a small summary for a
conversation. Help can create a deeper support ZIP and show what it contains before anything is sent.
There are no accounts or usage telemetry. The first beta sends a support file only when you press
Send.

The desktop packages include the reviewed Preflight Java runtime, so ordinary desktop users do not
need to install a system JDK first. Supported in-app updates use the project's updater signing key;
Linux `.deb` installations remain package-manager-managed.

## What Preflight changes — and what it does not

Preflight leaves Starsector's JARs, executables, assets, mod files and saves unchanged. Prepared data
lives in Preflight's own area, and runtime optimizations exist inside the launched game process.

Two explicit features can update game-owned preferences after showing the relevant change: named
profile switching can change the enabled-mod selection, and the launch-settings editor can change the
settings it owns. Both paths keep backups.

That distinction matters to me. A performance launcher should not require turning the installation
into its private format just to be useful.

## Why startup changed so much

There was no single magic cache.

Textures were an early bottleneck, but the first prepared-pixel implementation barely changed the
whole launch because the loading thread was still waiting behind the game's serialized prefetch
queue. Moving the reusable work in front of that wait was what changed the outcome.

Once textures became cheaper, the visible 0-percent pause exposed stable JSON/CSV-derived game data
being rebuilt every process. Making that cheaper exposed mod callback work, generated class maps and
other repeated startup costs. Audio turned out to be expensive in CPU even when some of its wall time
overlapped with other work.

That sequence is why the project ended up with several kinds of prepared work instead of one giant
cache switch.

## Some attractive ideas were wrong

The repository keeps the dead ends because the final number is not very useful if the story around it
is fiction.

Some early texture-cache pilots reported healthy hit counts while producing broken visuals. A supposed
timing split turned out to be a stale benchmark anchor. Java Flight Recorder's clock was badly wrong
under one runtime configuration. A GraphicsLib replay made the measured path worse and was removed.
AppCDS did not establish a useful shipped win and was removed too.

The built-in benchmark exists partly because this project repeatedly demonstrated that a convincing
number can still answer the wrong question.

The readable chronology is in [Optimization history](optimization-history.md) and the
[Experiment ledger](experiment-ledger.md).

## A few development reference points

On the reviewed 83-mod profile, current preparation left about **2.3 GB** in Balanced, **11 MB** in
Minimal disk before its first launch, and **5.2 GB** in the advanced Uncompressed mode. Minimal later
grew to about 204 MiB when its first launch learned the non-texture runtime caches. The build briefly
needs more room than the finished pack uses, and the app calculates the current installation's own
plan rather than treating those numbers as universal.

The read-only linter was calibrated over 86 installed mod directories. The median was zero findings
and 44 of 86 were completely clean. That is the intended shape of the tool: useful signal when a
measurable problem exists, not a score that assumes every mod needs fixing.

## Package trust and release evidence

The first beta does not use paid Apple Developer ID or Windows Authenticode publisher identities, so
macOS and Windows can show their normal unknown-developer warnings. The release ships checksum and
other package-review material, and the updater signing key is separate from those platform identities.

The final package-dependent claims all come from one retained candidate generation. Fill these only
from that candidate:

- **Accepted packages / checksums:** [ACCEPTED PACKAGE MATRIX / CHECKSUM SUMMARY]
- **Hosted lifecycle and update evidence:** [HOSTED CANDIDATE EVIDENCE SUMMARY]
- **Tagged report-intake canary:** [TAGGED REPORT CANARY RESULT]
- **Hands-on packaged report cancel/retry/delete:** [PACKAGED REPORT CANARY RESULT]

## Windows and Linux are release prerequisites, not later-beta TODOs

Real-game testing has been deepest on Apple silicon macOS. The first public beta GitHub release and
its downloadable packages do **not** go live until the retained candidate has also completed the
required native Windows and native x86-64 Linux real-game installation exercises.

Fill these lines from those runs before posting:

- **Windows real-game exercise:** [WINDOWS NATIVE REAL-GAME RESULT]
- **Linux real-game exercise:** [LINUX NATIVE REAL-GAME RESULT]

There is no Intel Mac package in the first beta. The reviewed game version is **0.98a-RC8**; changed
versions or changed mods can receive fewer optimizations until the affected paths are reviewed.

## AI assistance

Yes. I used ChatGPT/Codex and Claude Code throughout development.

The repository contains the source, tests, experiment history, failed approaches, review notes and
release work. Judge the result by the product and what it actually does.

## How to use it

1. Download the package for your system.
2. Open Preflight. If it does not find Starsector, choose the game folder.
3. Press **Prepare and launch**.
4. On later runs, press **Launch Starsector**. Matching prepared work is reused automatically.

Profiles, benchmarks, storage controls, support tools and the rest are there when you want them.

**Download:** [RELEASE URL]

If Preflight helps, saves you a pile of waiting, or you simply want to support this kind of open-source
work:

- GitHub Sponsors: [GITHUB SPONSORS URL]
- Patreon: https://www.patreon.com/cw/teamleaderleo

Preflight is an independent, unofficial project. It is not affiliated with or endorsed by Fractal
Softworks.

---

## Short version for Discord / a Reddit comment

> Preflight is a free, open-source performance launcher for Starsector. On my reviewed 83-mod
> development setup, a controlled five-v-five comparison measured 89.00s median normally and 15.53s
> with Preflight, with a 15.25s low. The desktop includes the same before/after benchmark for your own
> installation.
>
> It also grew local playtime, named mod profiles, useful launch settings, storage/recovery tools,
> read-only setup analysis and mod linting, support tools, signed updates, and a wireframe Hangar.
> Starsector, mod files and saves remain unchanged.
>
> The public package gets its own benchmark and the required native Windows/Linux real-game exercises
> before the first beta release and downloads go live.
>
> Download: [RELEASE URL]
