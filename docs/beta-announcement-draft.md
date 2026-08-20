# Beta announcement draft

Long source copy for the forum, Reddit, release editing, and other public posts. The shorter version
is [beta-announcement-leo-draft.md](beta-announcement-leo-draft.md), and
[Public writing style](public-writing-style.md) is the voice guide for the final venue-specific pass.

Replace every bracketed field before posting. The Starsector forum uses BBCode; ready-to-paste
download blocks are in [downloads.md](downloads.md#release-day-link-kit).

---

## Preflight: 89 seconds to 15.53 seconds median on my 83-mod Starsector setup

I have 83 mods installed, and at some point launching Starsector had become enough of an interval that
I stopped merely being annoyed by it and started wanting to know where the time was going.

In the latest controlled comparison on that profile, five ordinary Starsector launches had an
**89.00-second median** and five Preflight launches had a **15.53-second median**, with the lowest
Preflight run at **15.25 seconds**. Earlier in development the same installation had reached roughly
101 seconds, which is why you will also see the more theatrical 101 → 15.25 chronology around the
project; the 89.00 → 15.53 pair is the controlled same-session comparison.

The packaged beta candidate gets its own retained benchmark before publication:
**[CANDIDATE BENCHMARK RESULT]** on **[CANDIDATE GAME / HARDWARE / RUNTIME]**.

**Download:** [RELEASE URL]

Preflight is a free, open-source performance launcher for Starsector. It prepares work the game and
mods would otherwise repeat during startup and reuses that work while the relevant inputs still
match, while changed or unusable work simply goes through Starsector's normal path. That was the
original job. The rest of the application is what happened when I kept following the performance
problem into all of the neighboring launcher problems that had started to bother me too.

## Measure it on your own installation

The desktop includes a normal-versus-Preflight startup benchmark that launches the same current setup
through both paths and records the two main-menu times. **Copy result** turns the pair into something
compact enough for a forum, Discord, or issue while leaving out private paths, logs, and the full run
record, because the useful answer to "does this actually help?" is the number from your installation,
not an argument about whether my 83-mod Mac is representative of your machine.

The development comparison used one M5 MacBook Air running Starsector 0.98a-RC8 with the game's
bundled x86-64 Java runtime through Rosetta; conditions were interleaved, the machine cooled for 240
seconds before each launch, and all ten runs were kept. The full campaign and the component work live
behind the evidence links for anybody who wants to spend an evening with them.

## The launcher kept growing

Preflight now keeps local play history for sessions it launches and can observe, including sessions
that continue after the desktop minimizes or exits. The Speed page shows the total and session
context, **Copy playtime** makes a shareable summary, and the engine can export versioned JSON with an
optional spreadsheet-safe CSV view; older usable Preflight launch records can be imported once when
the play-history ledger first becomes available, while sessions from before Preflight or launches it
never observed remain outside the total.

Named mod profiles grew out of the same desire to stop doing launcher chores by hand. Profiles can be
created, searched, switched, renamed, duplicated, and deleted; switching shows which mods will be
enabled and disabled and saves a backup before applying the change, while duplicating a profile copies
the profile definition instead of making another copy of the mods, saves, or prepared data.

Then I put the settings I actually use beside Launch: resolution, fullscreen, sound, antialiasing, UI
scale, RAM, and battle size, with battle-size presets that can extend through **2,000 deployment
points** on a standard installation while still writing Starsector's own `battleSize` preference.
Home can use the full Hangar presentation or a compact launch-first view, and the selected wireframe
ship, its motion, and the app palette are display preferences; featured ships are traced locally from
the installed game into Preflight's own wireframe presentation instead of being bundled as copied
Starsector art.

## Preparation, disk use, and the part where things go wrong

Preflight trades disk space for less repeated launch work, so it tells you the bill before it starts.
It calculates the current profile, reusable data, free space, and a safety margin before preparation,
and on the reviewed 83-mod development profile one measured preparation produced:

| Mode | Prepared data | Observed preparation |
| --- | ---: | ---: |
| **Balanced** (default) | 4.76 GB | **3m21s** |
| **Minimal disk** | 10.9 MB | 5.6s |
| **Fastest** | 10.03 GB | high-disk optional mode |

Balanced needed 12.92 GB free before starting in that measurement because the preflight check kept
extra room for worst-case preparation even though the finished cache was 4.76 GB; each installation
gets its own calculated plan, and if Balanced will not fit Preflight can offer **Prepare with minimal
disk** instead.

I also wanted failure to remain an ordinary product state rather than the point where the user is
expected to go excavating cache directories. Preparation shows progress and can stop safely,
completed reusable work survives an interruption, a new prepared profile becomes active only after
preparation finishes, damaged prepared data can be repaired for the current profile, and Home can
still offer **Launch at normal speed** when acceleration data cannot be used. Failed runs show
Relaunch, **Copy setup**, Get help, and Dismiss; cleanup is preview-first, and removing the desktop app
is a different operation from removing all Preflight-owned data.

## The mod stack became interesting too

`preflight scan` inventories the enabled profile, while `preflight analyze setup` goes deeper and can
report missing enabled mods, invalid metadata, duplicate mod IDs, required dependencies that are
missing or disabled, malformed dependency metadata, and resolved variants whose hull is absent from
the active profile, all without launching or changing Starsector.

The linter came out of the profiling work because once I had built tooling for finding expensive
asset behavior it was difficult to resist turning it around on the mods themselves. `preflight lint`
can inspect one mod or a whole profile for measurable asset and configuration problems without
editing files or assigning a score. On the reviewed set it found progressive JPEGs that decode about
**8.75 times slower** through the game's ImageIO path, large texture and audio costs, shadowed and
duplicate assets, editor source files the game never reads, extension mismatches, and configuration
the game cannot parse or will never apply; the thresholds were calibrated over 86 installed mod
directories, the median was zero findings, and **44 of 86 were completely clean**, which is a more
interesting result than a linter designed to prove that everybody is doing something wrong.

## Where the launch time went

The largest early finding was a one-thread texture prefetch queue. The loading thread could spend
roughly 27 seconds behind it and then repeat hashing, image decoding, pixel conversion, copying, color
work, padding, and upload preparation; moving prepared texture lookup ahead of the actual serialized
wait was the change that produced the big early win.

Once textures became cheap, the visible 0-percent pause stopped being an inscrutable loading-screen
quirk and exposed roughly 18–20 seconds of repeated `SpecStore` work rebuilding variants, weapons,
projectiles, hulls, campaign rules, and related registries from stable JSON and CSV inputs. Once that
large plateau was cheaper too, the tail became more eclectic: repeated work in AshLib and
GraphicsLib callbacks, Janino class-map generation, audio decoding, and a collection of smaller hot
seams that had been invisible while the first two bottlenecks dominated the launch.

And some ideas failed, occasionally with great confidence. Early texture-cache pilots reported
healthy hit counts while producing cropped, tiled, black, or displaced visuals; a timing split turned
out to be a stale benchmark anchor; Java Flight Recorder's clock ran about 2.49 times away from wall
clock under one runtime setting; a GraphicsLib replay made the measured path worse; and AppCDS never
established a useful win for the shipped classes. Those branches were removed or narrowed, and the
built-in benchmark grew partly out of repeatedly learning that a locally convincing measurement can
still be answering the wrong question.

The readable chronology is in [Optimization history](optimization-history.md) and the
[Experiment ledger](experiment-ledger.md).

## Support, privacy, and what Preflight changes

Ordinary game launches upload no logs or telemetry. **Copy setup** creates a small support summary
with useful game, profile, mod, and launch facts while leaving out private paths, credentials, saves,
and arbitrary logs; when deeper evidence is useful, Help can create a support ZIP from a fixed set of
diagnostic files, show what will be sent before sending it, and support cancellation and retry. The
ZIP excludes acceleration caches, Starsector and mod files, saves, screenshots, audio, crash
recordings, arbitrary logs, and credentials, while automatic failed-run reporting is a separate
setting that starts off.

On the game side, Preflight keeps Starsector JARs, mod JARs, executables, assets, and saves outside the
acceleration path, and runtime optimizations live inside the launched game process and disappear when
it exits. Two backed-up features can update game-owned preferences: switching a named mod profile can
change the enabled-mod selection after preview, and the launch-settings editor can change the
corresponding game or launcher preference. Prepared data, benchmarks, play history, and Preflight
settings live in Preflight-owned locations.

## Updates and package trust

Supported desktop packages can show a newer release, its notes, and an **Install and restart** action,
with the downloaded updater verified before installation; Linux `.deb` packages continue through the
package manager. The first beta ships without paid Apple Developer ID or Windows Authenticode
publisher identities, so macOS and Windows can show their standard unknown-developer warnings, while
the release provides SHA-256 manifests and the supported in-app updater uses its own project signing
key.

The release process exercises installation, upgrade, rollback, and removal across macOS, Windows, and
Linux. Each native package also carries a machine-readable capability receipt describing its native
commands, writes, child processes, links, and network endpoints; those package-level details are
there for the people who want to inspect them, rather than as homework before somebody is allowed to
care that the game launches sooner.

The native desktop packages also bring their own minimal Preflight Java runtime, so ordinary desktop
use does not require installing a system JDK. The standalone JAR and CLI remain available for people
who prefer them.

## AI assistance

I used ChatGPT/Codex and Claude Code throughout development. The repository contains the source,
tests, experiment history, failed approaches, review notes, and retained measurements behind the
release claims; the point of keeping all of that is less to perform virtue than to make it possible
to reconstruct why a claim exists after the memory of the implementation has gone fuzzy.

## Known beta limits

- Real-game testing has been deepest on Apple silicon macOS.
- Windows and Linux have substantial automated package and lifecycle coverage; broader real-machine
  Starsector exercise continues through the beta.
- There is no Intel Mac package in the first beta.
- The reviewed game version is **0.98a-RC8**. Other versions can receive fewer speedups until changed
  targets are reviewed.
- The performance numbers above describe one M5 MacBook Air, one 83-mod profile, and the stated
  runtime conditions. Other installations can differ substantially.
- First preparation can take several minutes and gigabytes on a large profile. Preflight calculates
  the local plan before starting.

[Known limitations](known-limitations.md) has the complete list.

## How to use it

1. Download the package for your system.
2. Open Preflight. If it does not find Starsector, choose the game folder.
3. Press **Prepare and launch**.
4. On later runs, press **Launch Starsector**. Matching prepared work is reused automatically.

That is the routine path; profiles, benchmarks, support tools, storage controls, setup analysis, the
linter, and the rest are there when you decide to go wandering.

**Download:** [RELEASE URL]

If Preflight helps, saves you a pile of waiting, or you simply like this kind of overgrown open-source
project:

- GitHub Sponsors: [GITHUB SPONSORS URL]
- Patreon: https://www.patreon.com/cw/teamleaderleo

Preflight is an independent, unofficial project and isn't affiliated with or endorsed by Fractal
Softworks.

---

## Short version for Discord / a Reddit comment

> Preflight is a free, open-source performance launcher for Starsector. On my 83-mod setup, a
> controlled five-v-five comparison measured 89.00s median normally and 15.53s with Preflight, with
> the lowest recorded launch at 15.25s; the desktop includes the same before/after benchmark so you
> can measure your own installation instead of extrapolating from mine.
>
> The loading-time project also grew playtime tracking, named mod profiles, the useful launch
> settings beside Launch (including battle-size presets through 2,000), disk planning and recovery,
> read-only setup analysis, signed updates, a wireframe Hangar, and a mod linter that was calibrated
> across 86 installed mods and found 44 of them completely clean.
>
> Real-game testing has been deepest on Apple silicon macOS. Windows and Linux have substantial
> automated package/lifecycle coverage, with broader real-machine exercise continuing through the
> beta.
>
> Download: [RELEASE URL]
