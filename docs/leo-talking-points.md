# Leo's Preflight talking points

Read this before a forum post, Patreon update, video, stream, interview, release conversation, or any
other moment where the project has to fit into human working memory. For cadence and prose habits,
read [Public writing style](public-writing-style.md); for the intentionally excessive pile of hooks,
use [Public-writing sales inventory](public-writing-sales-inventory.md).

The point of this file is recall, not canned copy. Say the true thing in whatever sentence the
conversation wants.

## The one-sentence sale

Preflight is a free, open-source performance launcher for Starsector that took the controlled median
on my 83-mod setup from **89.00 seconds normally to 15.53 seconds with Preflight**, and the original
loading-time investigation kept wandering into useful adjacent problems until the launcher had its
own benchmark, playtime tracking, named profiles, launch settings through 2,000-point battles,
storage and recovery tools, setup analysis, signed updates, a locally drawn wireframe Hangar, and a
mod linter too.

## The personal version

I make stuff that tends to become much larger than I expect, and Preflight is the current specimen.
I started because modded Starsector could take about 101 seconds to reach the main menu on my
machine; I wanted to know where the time was going, which became a performance investigation, then a
launcher, then a desktop application, then enough release and correctness work that I apparently had
to invent several new categories of chores before I was willing to give it to other people.

That version belongs on Patreon, streams, interviews, and anywhere the audience is following the
person as well as the software. A Starsector release post should still lead with Preflight or the
measured result, because that is why the reader opened it.

## The performance record worth remembering

On the 83-mod development installation, the earlier observed high was roughly **101 seconds**. The
latest same-profile controlled session measured a **89.00-second median** across five ordinary
launches and a **15.53-second median** across five Preflight launches, with **15.25 seconds** as the
lowest Preflight run in that comparison. The conditions were interleaved, the machine cooled for 240
seconds before each launch, and all ten results stayed in the set.

The controlled medians are the comparison claim. The 101s → 15.25s line is the development
chronology, which is punchier and useful as long as the surrounding copy says what it means. The
first public release still needs the packaged-candidate benchmark inserted beside this record before
publication.

The desktop benchmark is part of the product because the development machine is one machine. A
player can compare an ordinary launch and a Preflight launch on the installation they actually use,
and **Copy result** turns that into a compact shareable pair without hauling private paths, logs, or
the full run record into the conversation.

## Why I trust the number as much as I do

The project contains some very attractive results that were wrong. An early texture cache had healthy
hit counters and broken visuals; a supposed timing bimodality came from a stale benchmark anchor;
Java Flight Recorder's clock ran about **2.49×** away from wall clock under one runtime setting; a
GraphicsLib replay turned a roughly 0.25-second path into about 1.70 seconds; AppCDS failed to produce
a useful enough win for the shipped obfuscated classes and came back out.

Those are good stories. They explain why the benchmark measures the whole launch and why a local
counter, however gratifying, does not get to overrule the thing the player actually experiences.

## Where the time went

The short explanation is repeated work leaving the launch path. The interesting explanation is that
there was no single cache-shaped dragon waiting to be slain, because each large win exposed a
formerly hidden next layer.

The loading thread could spend roughly 27 seconds behind a one-thread image prefetch queue and then
repeat hashing, decoding, pixel conversion, buffer work, color calculation, padding, and upload
preparation. Putting prepared texture work ahead of the serialized wait produced the largest early
win, and removing empty power-of-two padding also took texture uploads on the reviewed profile from
3.65 GiB to 2.43 GiB.

Once textures became cheap, the visible 0-percent plateau exposed roughly 18 to 20 seconds of stable
JSON/CSV-derived `SpecStore` work being reconstructed every process. Representative component
replays dropped variants from 3.289s to 0.324s, weapons from 3.338s to 0.998s, projectiles from
2.349s to 1.004s, hulls from 2.653s to 0.754s, and rules from 0.959s to 0.166s.

Then the tail got baroque. AshLib and GraphicsLib callback costs became visible, Janino was generating
highly overlapping complete class maps, audio had a large decoded-PCM CPU cost even when some of its
wall time overlapped other work, and a long collection of smaller serial seams became worth caring
about because the formerly enormous things in front of them had disappeared.

## What the application grew around that job

The features worth remembering as a player are the ones that make a giant installation less tedious
to live with: the built-in benchmark; local Starsector playtime that keeps following a launched
session when the desktop minimizes or exits; named profiles with preview and backup before switching;
resolution, fullscreen, sound, antialiasing, UI scale, RAM, and battle size beside Launch, including
2,000-deployment-point presets on a standard setup; storage planning before preparation; repair,
normal-speed fallback, and previewed cleanup; read-only setup analysis; **Copy setup** and deeper
support reports; signed updates; and a Hangar that can trace installed ship art locally into a new
wireframe instead of shipping the source artwork.

The CLI is the same Java engine with a different front door. `doctor` shows launcher discovery,
`scan` inventories the enabled profile, `--dry-run` shows the selected launch without executing it,
`analyze setup` finds deterministic setup problems before the game starts, and `lint` inspects one
mod or the complete resolved profile.

The native desktop packages carry their own minimal Preflight Java runtime, so ordinary desktop use
does not ask the player to install a system JDK first.

## The linter deserves an actual story

I pointed the same profiling work at the mods themselves. The useful result was not that everything
was terrible; calibration across 86 installed mod directories produced a **median of zero findings**
and **44 of 86 completely clean**, which is exactly the kind of result a tool aimed at somebody
else's work should be willing to produce.

Progressive JPEGs were one of the clearest measured cases, decoding about **8.75× slower** through the
game's ImageIO path than equivalent baseline encoding. The linter also reports large NPOT padding,
selected audio costs, shadowed or duplicate resources, editor source files, extension mismatches,
and a small number of configuration cases the game cannot parse or never reads. It edits nothing,
ranks nobody, and has no score or automatic fixer.

A good hook is: "I pointed the profiler at 86 mods. Most of them were fine. The interesting part was
what the expensive minority had in common."

## Support and privacy, in ordinary language

Ordinary game launches upload no logs or telemetry. **Copy setup** is the easy public-support path:
it collects useful game, profile, mod, and launch facts while leaving out paths, credentials, saves,
and arbitrary logs.

The deeper support ZIP is a separate action with a fixed set of diagnostic inputs, size limits, and
explicit exclusions. The desktop shows the archive before sending, supports cancellation and retry,
and an accepted report carries retention and deletion information. Automatic failed-run reporting is
a separate remembered setting and starts off.

The useful public story is behavioral. Say what goes into the support material, what stays out, and
what the player chooses. Leave the byte ceilings, symlink policy, request validation, and intake
internals behind the evidence links unless somebody actually asks.

## Updates and package trust

Supported desktop packages use a signed updater and wait for **Install and restart**. A failed
download, verification, or installation leaves the current version available, and Linux `.deb`
installs continue through the package manager.

The release process exercises install, update, rollback, and removal across macOS, Windows, and
Linux. Incompatible cache formats can coexist so rolling the app back does not first destroy the data
an older version understands, and every native package carries a capability receipt for people who
want the package-level command, write, child-process, link, and network inventory.

This is good public material when it answers the ordinary question underneath it: if I trust this
launcher today, what happens when the game, a mod, or Preflight itself changes tomorrow?

## What happens after Starsector or a mod updates?

Prepared work and runtime shortcuts each check the inputs they depend on. A mod update can therefore
make one shortcut step aside while unrelated prepared work keeps working, and a sufficiently large
game, launcher, preference, classloading, or runtime change can still require a Preflight update.

That is the useful player-level idea. The deeper class/archive/source fingerprints exist to make that
behavior real; they rarely belong in the first explanation.

## What Preflight changes

Runtime optimizations live inside the launched game process and disappear when the game exits.
Starsector JARs, mod JARs, executables, assets, and saves stay outside the acceleration path.

Two backed-up features can change game-owned preferences: named-profile activation changes the
enabled-mod selection after preview, and the launch-settings editor changes the corresponding game
or launcher settings. The rest of the persistent application state lives under Preflight-owned
locations.

## Strong story angles for future posts

Use these as starting points, not headline quotas:

- **I tried to reduce one loading screen and accidentally made a Starsector companion app.** Good for
  Patreon and general development audiences; let the product accrete as the story goes.
- **How 101 seconds became 15.25.** The engineering chronology, including the ideas that looked good
  and lost when measured end to end.
- **The loading bar said 0%. The game was still doing 20 seconds of work.** The `SpecStore` story.
- **The first cache had perfect hit counters and broke the screen.** Measurement, correctness, and
  why counters can lie beautifully.
- **I pointed a profiler at 86 mods.** Lead with 44/86 completely clean, then explain what the
  expensive minority had in common.
- **Yes, the battle-size button goes to 2000.** A lighter settings post with high-DPI resolution and
  RAM editing along for the ride.
- **How Preflight draws Starsector ships without shipping Starsector art.** Installed hull discovery
  and local wireframe tracing.
- **Why does a game launcher rehearse rollback?** Signed updates, package lifecycle, cache evolution,
  and capability receipts, explained through the user problem they solve.
- **What happens when a mod updates?** Granular compatibility without turning the answer into a
  fingerprint lecture.
- **Five gigabytes for 446 milliseconds?** Balanced versus Fastest and the virtue of measuring the
  marginal win before spending the disk.

## A spoken version

"Preflight is a performance launcher for Starsector. On my 83-mod setup, a controlled comparison
measured 89 seconds normally and 15.53 seconds with Preflight, and the app includes the same
before-and-after benchmark so you can measure your own setup. It prepares work the game and mods
would otherwise repeat during startup, then the project got a little out of hand and accumulated
playtime tracking, named mod profiles, launch settings through 2,000-point battles, disk planning and
recovery, read-only setup analysis, signed updates, a wireframe Hangar, and a mod linter. Runtime
optimizations stay inside the launched game process, and changed game or mod code simply means the
relevant shortcut steps aside until Preflight recognizes it again."

## Questions people will ask

**"Will I get the same launch time?"** Give the measured comparison and its machine/profile context,
then point them at the built-in benchmark.

**"Does this modify my saves or mods?"** Saves and mod files stay outside the acceleration path.
Profile activation and launch settings are the two backed-up game-owned preference changes.

**"Is this a mod manager?"** It is primarily a performance launcher. Profiles, setup analysis,
storage planning, support tools, and game settings grew around that job because a large modded
installation is easier to live with when those things are nearby. It does not install or update
mods.

**"What happens when one of my mods updates?"** Preflight checks the work that depended on it again.
A changed runtime shortcut steps aside; unrelated prepared work can keep working; a larger game or
launcher change can require a Preflight update.

**"Do I need to install Java?"** The native desktop packages carry their own minimal Preflight Java
runtime. Java 17 or newer is for the standalone JAR and development path.

**"Why can battle size go past the vanilla slider?"** The desktop still writes Starsector's own
`battleSize` preference. On a standard setup Preflight offers presets through 2,000 deployment
points; opening the vanilla slider later can reset a value above that slider's installed maximum.

**"What gets sent anywhere?"** Ordinary launches upload no logs or telemetry. Update checks and
support reporting are separate functions, and automatic failed-run reporting starts off.

**"Why is the cache so large?"** On the reviewed 83-mod profile Balanced retained 4.76 GB, while
Minimal disk retained 10.9 MB and Fastest retained 10.03 GB. The desktop calculates the current
profile before preparation, because the useful answer is the one for the installation in front of
you.

**"Was this written with AI?"** Yes. ChatGPT/Codex and Claude Code were used throughout development;
the repository keeps source, tests, failed experiments, corrected measurements, and the release
evidence behind the public claims.

## Claims that always carry context

The 89.00s → 15.53s comparison is one machine, one 83-mod setup, and one controlled session; 15.25s
is one run from that session; roughly 101s is the earlier observed high. Startup is the strongest
controlled performance claim, while campaign and combat work is more workload-dependent. Real-game
testing has been deepest on Apple silicon macOS, with Windows and Linux package automation ahead of
their broader real-machine game evidence. Keep "as far as I can find" around any first-dedicated-
Starsector-playtime-tracker claim unless stronger external evidence appears.

## What is still happening before release

The desktop product is largely there. Four candidate/platform checks own the beta gate: real-game
Windows and Linux exercise, the complete hosted three-platform candidate, the startup benchmark on
the packaged candidate, and the final packaged support-intake cancel/retry/delete canary.

The Fractal Softworks request remains courtesy correspondence and is outside the publication gate.
#833 generation-authority work and the other continuing hardening/research lanes are post-RC unless a
concrete candidate failure promotes one. Keep public release copy pointed at the candidate work in
[#652](https://github.com/teamleaderleo/preflight/issues/652) instead of letting interesting
post-RC engineering wander back into the beta blocker list.
