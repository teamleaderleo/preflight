# Patreon page draft

Working copy for <https://www.patreon.com/cw/teamleaderleo>. This is preparation copy, not a fixed
contract. Revise it whenever the page, the projects, or my taste changes. Preflight stays free and
open source.

## Page line

Preflight and other projects that got out of hand.

## About

I make stuff that tends to become much larger than I expect.

Right now, most of my public work is going into Preflight, a free and open-source performance
launcher for Starsector.

Preflight started because my heavily modded installation could take 101 seconds to reach the main
menu. The latest controlled comparison on that 83-mod setup had an 89.00-second normal-launch median
and a 15.53-second Preflight median, with one launch reaching 15.25 seconds.

That investigation has since turned into a full desktop app with its own before-and-after benchmark,
Starsector playtime tracking, named mod profiles, common and extended game settings, storage planning
and recovery, read-only setup analysis, privacy-conscious support tools, signed updates, a Hangar
that can draw installed ships as local wireframes, and a mod linter. The repository also keeps the
experiments that failed, the measurements that turned out to be wrong, and the regression tests that
came out of both.

Membership helps support development time, testing hardware, hosting, release work, and whatever I
end up working on next. Preflight access does not depend on membership. The app, source, features,
and public support stay available to everyone.

## Membership tiers

The tiers are contribution levels. Pick whichever amount feels right; they do not unlock different
versions of Preflight.

### Supporter

**Price:** $5 per month

Help support Preflight and my other projects. Your membership contributes to development, testing,
release work, hosting, hardware, and keeping the work maintained.

### Backer

**Price:** $10 per month

Give a little more toward ongoing development, compatibility work, testing, and future projects. A
good fit if you use Preflight regularly or enjoy following an investigation that keeps turning into
more software.

**Highlighted tier:** yes.

### Sustainer

**Price:** $20 per month

Help cover a meaningful share of cross-platform testing, release preparation, hardware, hosting,
and continued development.

### Sponsor

**Price:** $50 per month

For people or organizations who want to make a substantial contribution to Preflight and my other
work. Your membership helps support continued development, testing, releases, hardware, hosting,
and future projects.

## Welcome note

Thank you. This helps support the time and costs behind Preflight and whatever I end up working on
next.

Preflight lives at <https://github.com/teamleaderleo/preflight>. If something breaks, use **Copy
setup** or the support file in the app, or open an issue. Membership is never required for support.

## First current public post

### Preflight got out of hand

It has been a while since I posted here.

I make stuff that tends to become much larger than I expect, and Preflight has definitely become one
of those things.

The original problem was simple: I have a ridiculous Starsector installation. On the current setup,
83 mods are enabled. At its worst, the game took about 101 seconds to reach the main menu. I wanted
to know where that time was actually going and whether any of it was work the game did not need to
repeat every single launch.

A lot of it was.

The latest controlled comparison on that same 83-mod profile had five ordinary launches with an
89.00-second median and five Preflight launches with a 15.53-second median. The lowest recorded
launch in that comparison was 15.25 seconds. Results will differ by machine and mod list, which is
why Preflight now includes the same normal-versus-Preflight benchmark in the desktop app.

That was supposed to be the project. It was not the end of the project.

Preflight now tracks Starsector playtime locally. It has named mod profiles that preview the exact
mod-list change and make a backup before switching. It can duplicate and search saved profiles. It
puts resolution, fullscreen, sound, antialiasing, UI scale, RAM, and battle size beside the launch
button. The battle-size shortcuts are not limited to the vanilla settings slider either: on an
ordinary installation they include 600, 1000, 1500, and 2000 deployment-point choices while still
using Starsector's own `battleSize` preference.

It calculates the current profile's storage requirement before preparation, offers a tiny
minimal-disk route when the normal cache will not fit, previews cleanup before deleting anything,
and keeps removal of the application separate from removal of Preflight's data.

There is a built-in startup benchmark because I do not want anyone to take my machine's result on
faith. There is **Copy result** for sharing a compact benchmark comparison. There is **Copy
playtime** for sharing the local playtime summary. There is **Copy setup** for getting the useful
support facts without dumping paths, saves, credentials, or arbitrary logs into a Discord message.
If a run fails, that same action is available directly on the recovery card.

The support ZIP is its own rabbit hole. It uses a fixed allowlist, has hard size limits, excludes
saves, game and mod assets, screenshots, audio, caches, arbitrary logs, and credentials, and tells
you what it contains before you choose whether to send it. Sending has progress, cancellation and
retry. An accepted upload gets a receipt with retention and deletion information, and the supported
intake path can delete the uploaded report through that scoped receipt. Automatic failed-run
reporting is a separate setting and starts off.

The updater became another rabbit hole. Releases use a signed in-app update path, and the release
process exercises installation, upgrade, rollback, and removal across macOS, Windows, and Linux.
Incompatible cache formats are kept in separate namespaces so rolling the application back does not
first destroy the prepared data the older version understands. The packages carry a machine-checked
receipt describing which native commands, writes, child processes, links, and network endpoints
that exact package can use.

The native desktop packages also carry their own minimal Java runtime, so using the desktop app does
not mean asking somebody to install a system JDK first. The standalone JAR remains there for people
who want the terminal version.

And the terminal version is not an afterthought. The desktop and CLI use the same Java engine.
`doctor` can show which launcher Preflight found without starting the game; `scan` can inventory a
large mod profile; `--dry-run` prints the exact selected launcher, command and working directory;
and an optional direct-launch path uses Starsector's own saved launch preferences without clicking
through its launcher UI. Preflight can also wrap an explicitly selected compatible launcher instead
of replacing it on disk.

There is a deeper read-only setup checker as well. Without launching Starsector it can catch things
like an enabled mod with unavailable metadata, two installed mods declaring the same ID, a required
dependency that is missing or merely disabled, or a winning ship variant that references a hull the
resolved profile does not actually contain. It exits after the analysis and changes nothing.

Then the Hangar got out of hand too. Preflight can read the installed hull catalog and let you choose
a ship for the app's wireframe display. For featured ships it can trace the installed sprite locally
into a new wireframe instead of packaging Starsector's artwork. The selected hull persists, and the
wireframe can be tuned per installation and ship for smoothing, outer and interior detail, and depth.
It is completely unnecessary. I like it a lot.

The rest of the desktop got some of that treatment too. There are five palettes — Blueprint, Hangar,
Ultraviolet, Airglow, and Phosphor — on top of System, Light, and Dark themes. Home can be the full
hull-led Hangar or a Compact launch-first view. You can hide the playtime readout without stopping
playtime recording, and the hull can rotate, sit still, or turn the other direction.

And once Preflight has verified that the actual Starsector JVM is alive, it can minimize, stay open,
or quit according to your remembered preference. If it quits, playtime still records. The launcher
can do its work and then get out of the way.

There is also a mod linter now, because apparently this was not enough. It is read-only and can
inspect one mod or a full profile. On the reviewed mod set it found progressive JPEGs that decode
about 8.75 times slower through the game's ImageIO path, large amounts of avoidable texture and audio
allocation, shadowed and duplicate resources, editor source files the game never reads, and a small
number of released configuration files containing data the game silently never applies. It gives no
score and edits nothing.

The performance investigation itself has been the strangest part. The first texture cache produced
great-looking hit counters and then broke actual visuals. A supposed timing split turned out to be a
stale benchmark anchor. Java Flight Recorder's clock was off by about 2.49 times under one runtime
setting. A GraphicsLib replay that sounded promising made the measured path substantially slower and
was deleted. AppCDS did not establish a safe win and was deleted too. Those failures are still in the
repository because they are part of how the current result became trustworthy.

The accepted work goes through texture preparation, a single-threaded prefetch queue, repeated JSON
and CSV reconstruction, generated bytecode, audio, resource indexing, mod-specific callback work,
and a long tail of smaller costs. There is reviewed campaign/runtime work too, but startup is the
part with the huge controlled number and I am not turning exploratory frame-time work into a
universal FPS promise.

Preflight does not rewrite Starsector's JARs, mod JARs, assets, or saves. Runtime optimizations are
checked against the installed code before they run. When Preflight cannot establish that something
is the exact thing it reviewed, that shortcut declines and the original code handles the work. If I
need to troubleshoot the optimization layer entirely, the product has a global Off mode without
requiring the launcher, profiles, support tools, and other desktop functionality to disappear with
it.

I am working toward the first public beta now. The desktop product is largely there. The remaining
work is mostly release-candidate evidence: exercising the exact distributed package bytes, finishing
the packaged benchmark and report checks, broadening Windows and Linux real-machine coverage, and
finishing the publication decision.

Preflight will remain free and open source. This Patreon is here for anyone who wants to support the
time, hardware, hosting, and release work behind it, or just enjoys watching me disappear into a
problem and come back with far too much software.

Project: <https://github.com/teamleaderleo/preflight>

## Follow-up posts worth writing

These are deliberately different stories. Do not turn them into one giant announcement.

### How 101 seconds became 15.25

Walk through the accepted performance chronology: the texture prefetch queue, the 0-percent
`SpecStore` plateau, mod callbacks, generated bytecode, audio, and the final serial tail. Include the
wrong measurements and rejected experiments because they are some of the best parts of the story.

### The launcher accidentally became a Starsector companion app

Focus on the product around the optimization: built-in benchmark, playtime tracking, named profiles,
search and duplication, launch settings, extended battle size, storage planning, recovery, Copy
setup, cleanup, updates, removal, and the fact that it can disappear after launch while playtime keeps
recording.

### Yes, the battle-size button goes to 2000

A lighter player-facing post about launch settings: the ordinary settings beside Launch, why the
battle-size range can go beyond the vanilla slider, high-DPI resolution handling, RAM editing, and
why Preflight backs up/refuses ambiguous settings layouts instead of guessing.

### Before you blame the launcher, inspect the mod stack

A support/modder post about `preflight analyze setup`: missing enabled mods, duplicate IDs, disabled
required dependencies, malformed metadata, and resolved variants that point at absent hulls. The
interesting part is that the check can make those deterministic findings without starting the game
or asking for somebody's giant log file.

### How Preflight draws Starsector ships without shipping Starsector art

A design/dev post about the Hangar: installed hull discovery, local sprite tracing for featured
ships, choosing a display hull, per-hull wireframe tuning, palettes/themes, and why the package
contains none of the proprietary source artwork.

### The launcher should disappear when the game starts

A small product post about after-launch behavior, why the choice only applies after the actual game
JVM is confirmed alive, and how playtime recording continues even when the desktop quits.

### The same app has a GUI, a dry-run, a profile census, and a mod linter

Show the power-user side: `doctor`, `scan`, `--dry-run`, explicit launcher selection, direct launch,
standalone JAR, and the fact that desktop and terminal use the same engine.

### I pointed the profiler at 86 mods

Tell the linter story. Lead with the measured progressive-JPEG cost and the calibration result that
44 of 86 inspected mod directories were completely clean. Explain why there is no score, no ranking,
and no automatic fixer.

### When the profiler lies to you

Write about the stale benchmark anchor, the JFR clock error, visually broken texture-cache pilots,
and why every attractive number needs a second way to prove what it means.

### Why a launcher has a signed updater and rollback rehearsal

Explain the release side in human terms: signed updater artifacts, rollback-preserving cache formats,
exact candidate bytes, install/update/rollback/removal checks, checksums/SBOMs, and capability
receipts.

### What I am actually working on now

A lighter development update: exact package evidence, cross-platform release checks, filesystem and
cache correctness work, the remaining desktop polish, and the road to the first public beta.

## Images

- Profile image: use the current creator avatar or the Preflight mark, whichever fits the page at the
  time. This is a creator page, not a permanent Preflight-only identity.
- Header while Preflight is the main public project: the wireframe ship on the drafting grid, with
  the ship kept to the right so Patreon can crop the left side safely.
- Do not put essential text in either image; Patreon crops them differently across devices.
