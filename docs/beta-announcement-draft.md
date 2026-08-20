# Beta announcement draft

Long source copy for the forum, Reddit, release editing, and anything else that needs the complete
story. The shorter post is [beta-announcement-leo-draft.md](beta-announcement-leo-draft.md).

Replace every bracketed field before posting.

**The Starsector forum takes BBCode, not Markdown.** Pasting this there renders literal asterisks
and broken link syntax. Ready-to-paste download blocks for the forum, Reddit and README are in
[downloads.md](downloads.md#release-day-link-kit), and the prose needs the same conversion:
`**bold**` becomes `[b]bold[/b]`, `## Heading` becomes `[size=14pt][b]Heading[/b][/size]`, and
`[text](url)` becomes `[url=url]text[/url]`.

---

## Preflight: 101-second launches down to 15.25 seconds on my 83-mod Starsector setup

I have 83 mods installed.

At its worst, Starsector took about 101 seconds to reach the main menu. In the latest controlled
comparison on that same mod profile, five ordinary launches had an **89.00-second median** and five
Preflight launches had a **15.53-second median**. The lowest recorded launch in that comparison was
15.25 seconds.

That is the headline. The final release candidate also gets its own retained benchmark before these
words become release copy: **[CANDIDATE BENCHMARK RESULT]** on **[CANDIDATE GAME / HARDWARE /
RUNTIME]**.

**Download:** [RELEASE URL]

Preflight is a free, open-source performance launcher for Starsector. It prepares repeatable work
before launch, validates that prepared data against the exact game and ordered mod inputs it belongs
to, and reuses it while those inputs still match. Runtime optimizations are checked against the code
they were reviewed against. If a check fails or Preflight does not recognize the current target, the
shortcut declines and the original game path handles the work.

The project started as a performance investigation. It did not stay a performance investigation.

## Measure it on your own installation

The desktop app includes a normal-versus-Preflight startup benchmark. It opens the game once through
the normal path and once through Preflight, waits for the main-menu marker, seals the relevant
installation/profile/launcher/runtime/settings identity, and produces one comparison without
needing Accessibility permission or clicking through the game UI.

That means nobody needs to believe my 83-mod result because it looks impressive. Run the benchmark
on your own setup.

The current result view also has **Copy result**, which copies the two measured main-menu times and
an installation-specific qualifier in a compact form suitable for a forum, Discord, or issue. It
does not copy raw run evidence, paths, profile fingerprints, or logs.

## Somehow, Starsector now has a playtime counter

Starsector itself does not expose a playtime total. The usual workaround has been adding it to Steam
as a non-Steam shortcut. As far as I can find, Preflight is the first dedicated Starsector playtime
tracker.

Preflight keeps a bounded local play-history ledger for sessions it launches and can observe. Recording continues
when the desktop minimizes or exits after starting the game. The Speed page shows the total and
session context, and **Copy playtime** can produce a bounded summary with total recorded time,
session count, longest and average session, and first/latest recorded dates.

The history can also be exported through the engine as versioned JSON with an optional
spreadsheet-safe CSV. The portable projection deliberately leaves out run-directory paths, logs,
command lines, usernames, credentials, and arbitrary diagnostic text.

It cannot reconstruct playtime from before Preflight existed or from sessions it never observed.

## Named mod profiles became a real part of the app

Preflight can save named mod profiles from the current enabled-mod order. Profiles can be created,
searched, switched, renamed, duplicated, and deleted.

Switching previews the exact `enabled_mods.json` change before applying it and saves a backup.
Duplicating a profile does not copy mods, saves, or prepared bytes. Matching prepared data follows
the profile identity automatically, so having multiple named setups does not mean every setup needs
its own duplicate copy of everything.

The saved-profile search is remembered across restarts. The search itself is only presentation; it
does not become part of profile identity or change the saved order.

## The usual launcher settings are beside the launch button

Resolution, fullscreen, sound, antialiasing, UI scale, RAM, and battle size are available without a
separate launcher ritual.

The Home page can use the full Hangar presentation or a compact launch-first presentation. Recorded
playtime visibility is independent. The decorative wireframe hull can rotate or stay still, and its
rotation direction can be changed. Those are display choices. They do not alter game, profile, or
cache behavior.

## Preparation tells you what it will cost before it writes

Preflight trades disk space for less repeated launch work, so disk use is part of the product.

Before preparation, it calculates the current profile's predicted additional data, a more
conservative upper bound, reusable existing artifacts, and free filesystem space. It keeps at least
1 GiB in reserve and refuses before writing if the safety bound will not fit. A new manifest becomes
active only after preparation succeeds.

On the reviewed 83-mod development profile, one cold-preparation measurement produced:

| Mode | Complete prepared-data directory | Observed preparation |
| --- | ---: | ---: |
| **Balanced** (default) | 4.76 GB | **3m21s** |
| **Minimal disk** | 10.9 MB | 5.6s |
| **Fastest** | 10.03 GB | high-disk optional mode |

Balanced needed 12.92 GB free before starting in that measurement because the safety check uses
a larger worst-case preparation bound than the final retained directory. Your profile will have its
own numbers, and the app calculates them locally.

If Balanced does not fit, Preflight can offer **Prepare with minimal disk** instead of discovering
halfway through preparation that the disk is full.

Cleanup is preview-first. Current and readable named profiles remain reachable, shared artifacts
remain reusable, and ambiguity retains data instead of guessing that something is safe to delete.
Removing the application and removing all Preflight-owned data are separate choices.

## Why the launch time changed so much

The short version is caching. The interesting version is that the expensive work was spread across
several unrelated-looking parts of startup, and the largest early cache was initially placed on the
wrong side of the real wait.

### The texture cache had to get in front of the queue

The loading thread could spend roughly 27 seconds waiting behind a one-thread image prefetch queue,
then repeat source hashing, image decoding, pixel conversion, buffer copying, color work, padding,
and upload preparation.

The first prepared-pixel implementation made individual decode work cheap and barely changed the
whole launch because the loading thread still sat behind the serialized queue. Moving the validated
prepared lookup ahead of that wait was what changed the result substantially.

The texture work also removed empty power-of-two upload padding. On the reviewed profile, texture
uploads fell from 3.65 GiB to 2.43 GiB after true-size allocation replaced padding the shaders never
sampled.

### The 0-percent pause was a large amount of stable text-derived data being rebuilt

Once textures became cheaper, the loading screen still sat at 0 percent for roughly 18 to 20
seconds. Much of that time was vanilla `SpecStore` rebuilding variants, weapons, projectiles, hulls,
rules, factions, and related registries from stable JSON and CSV inputs every process.

Preflight prepares tagged input trees and merged-reader results underneath the ordinary game
constructors. Starsector still creates and owns fresh mutable hull, weapon, rule, and registry
objects each run. If the profile/provider identity does not match, the ordinary reader handles the
request.

Representative component replays dropped variant merge/parse from 3.289s to 0.324s, weapons from
3.338s to 0.998s, projectiles from 2.349s to 1.004s, hulls from 2.653s to 0.754s, and rules from
0.959s to 0.166s.

### Making vanilla work cheaper exposed mod callback work

AshLib repeatedly resolved the same hull and variant JSON while populating render information.
GraphicsLib repeated work around generated texture state and settings. Exact input memoization and
compact replay reduced those measured sequences without taking ownership of their live game state.

Janino was another case. Mods were asking it to generate highly overlapping complete class maps. A
complete-map cache reduced an 18.014-second direct aggregate to 2.364 seconds, and content
packing later shrank roughly 145.96 MiB of repeated maps to about 1.13 MiB.

### Audio was expensive in CPU even when wall time overlapped

The game constructs about 1.2 GB of decoded PCM before the main menu. Audio decoding overlapped with
other startup work more than the first hypothesis assumed, but removing repeated Vorbis decode and
hashing still removed a large amount of CPU and later contention. The accepted path can serve exact
prepared PCM to OpenAL when its source identity matches.

## Some of the best-looking ideas were wrong

The repository keeps the failures because the final number is not useful if the route to it is
fiction.

Some early texture-cache pilots reported healthy hit counts while producing cropped, tiled, black,
or displaced visuals. A supposed 18-second timing bimodality turned out to be a stale benchmark
anchor. Java Flight Recorder's clock under one runtime setting ran about 2.49 times away from wall
clock. A GraphicsLib traversal replay expanded a roughly 0.25-second path to around 1.70 seconds and
was removed. AppCDS could not establish a safe win for the shipped obfuscated classes and was
removed too.

The benchmark became part of the product partly because this project repeatedly demonstrated that a
number can be internally consistent and still answer the wrong question.

The readable chronology, including the dead ends, is in
[Optimization history](optimization-history.md) and the [Experiment ledger](experiment-ledger.md).

## There is a mod linter too

The same profiling work turned into a read-only linter for mod authors and curious users:

```text
preflight lint --path ./MyMod
preflight lint --game "/path/to/Starsector"
```

It reads file headers and configuration. It never edits, moves, re-encodes, deletes, or rewrites a
mod, and it does not assign scores or ranks.

On the reviewed profile it found progressive JPEGs that decode about **8.75 times slower** through
the game's ImageIO path, substantial texture padding and decoded-audio costs, shadowed and duplicate
resources, editor source files the game never reads, extension mismatches, and a small number of
released configuration files containing data the game silently never applies.

The thresholds were calibrated by linting 86 installed mod directories individually. The median was
zero findings and **44 of 86 were completely clean**. No rule fired on more than a third of the
sample. Most mods are fine, which is exactly what I want a tool like this to discover.

No automatic fix mode exists. Re-encoding or rewriting somebody else's assets is a separate problem
with a separate safety story, and Preflight does not pretend otherwise.

## Failure and recovery are normal product states

Preparation can be cancelled. Interrupted preparation keeps already completed immutable work
reusable and does not activate an incomplete new manifest. Structurally damaged prepared data can be
repaired for the exact profile. Low disk is detected before writing. Stale operations are reconciled
against native ownership after renderer/event failures.

If a game run fails, Home shows a recovery card with Relaunch, **Copy setup**, Get help, and Dismiss.
Copy setup uses the same bounded support summary as Help, so you can paste the useful facts into a
conversation without dumping arbitrary machine state.

## What gets sent anywhere

There is no account and ordinary game launches upload no logs or telemetry.

Preflight can check GitHub for updates. Support reporting is a separate action. Automatic failed-run
reporting is another separate setting and starts off.

The support ZIP is not a recursive archive of an evidence directory. It considers a fixed list of
small text evidence files. Individual sources have size limits, the complete source set has a fixed
limit, symlinks are refused, changing files are skipped, the user-home path is redacted, and the
result contains a manifest with each included entry's size and SHA-256 plus the enforced exclusions.

The ZIP never considers acceleration caches, Starsector files, mod files, saves, decoded assets,
compiled class bodies, console/game/wrapper logs, crash dumps, JFR recordings, screenshots, or audio
captures.

Before sending, the desktop shows the exact ZIP path, byte count, SHA-256, retention, included
entries, skipped-source count, and exclusions. Sending has progress and cancellation. An accepted
report gets a case receipt with its digest/size and retention/deletion information.

## What Preflight changes

Preflight leaves Starsector's JARs, executables, assets, mods, and saves alone.
Runtime changes live only inside the launched game JVM and disappear when the process exits.
Prepared data lives in Preflight's own directory.

Two explicit backed-up features can update game-owned preferences:

1. switching a named mod profile updates the enabled-mod selection after preview;
2. changing launch/game settings updates the corresponding reviewed settings file.

Everything else stays inside Preflight-owned data or the child process.

## Updates and package trust

The desktop checks a fixed HTTPS release feed and offers an update only when a newer signed release
is available. Installation requires the explicit **Install and restart** action. Before downloading,
Preflight rechecks that the version, target, URL, signature, notes, and date are still the exact offer
the user reviewed. Tauri verifies the downloaded updater signature before installation.

A failed download, signature check, or installation leaves the current version runnable. Linux
`.deb` installations continue through the package manager; the built-in updater covers the other
supported updater package paths.

The release process also checks installation, upgrade, rollback, and removal across macOS, Windows,
and Linux. Each package carries a machine-checked capability receipt listing the native commands,
writes, child processes, links, and network endpoints available to that exact package. Publication
work is being tightened so lifecycle evidence and the final release refer to the same exact package
bytes, not merely the same source revision.

The first beta's macOS and Windows packages do not have paid platform publisher identities, so the
operating systems may show their usual unknown-developer warnings. The release provides SHA-256
manifests, and the updater has its own project signature independently of those platform identities.

## AI assistance

Yes. I used ChatGPT/Codex and Claude Code throughout development.

The repository contains the experiment history, the failed branches, the review notes, the source,
and the tests. When a hypothesis failed, it was removed or narrowed. When a measurement was wrong,
the correction was recorded. When an optimization works only for exact code, that exact identity is
part of the runtime gate.

Judge the result by the product and its evidence.

## Known beta limits

- Real-game testing has been deepest on Apple silicon macOS.
- Windows and Linux have substantial automated package/lifecycle coverage but need broader
  real-machine Starsector evidence during the beta.
- There is no Intel Mac package in the first beta.
- The reviewed game version is **0.98a-RC8**. Other versions can use fewer optimizations until their
  changed targets are reviewed.
- The development performance numbers describe one M5 MacBook Air, one 83-mod profile, and the
  stated runtime conditions. Your result can differ substantially.
- The first preparation can take several minutes and consume gigabytes on a large profile. Preflight
  calculates your actual plan before it starts.

[Known limitations](known-limitations.md) has the rest.

## How to use it

1. Download the package for your system.
2. Open Preflight. If it does not find Starsector, choose the game folder.
3. Press **Prepare and launch**.
4. On later runs, press **Launch Starsector**. Matching prepared work is reused automatically.

That is the normal path. Profiles, benchmarks, support tools, storage controls, and the rest are
there when you want them.

**Download:** [RELEASE URL]

If Preflight helps, saves you a pile of waiting, or you simply want to support this kind of
open-source work:

- GitHub Sponsors: [GITHUB SPONSORS URL]
- Patreon: https://www.patreon.com/cw/teamleaderleo

---

## Short version for Discord / a Reddit comment

> Preflight is a free, open-source performance launcher for Starsector. On my 83-mod setup, a
> controlled five-v-five comparison measured 89.00s median normally and 15.53s with Preflight; the
> lowest recorded launch was 15.25s. The desktop includes the same before/after benchmark for your
> own installation.
>
> It also tracks Starsector playtime, has named/searchable/duplicable mod profiles, puts the normal
> game settings beside Launch, plans preparation disk use before writing, has privacy-conscious Copy
> setup/support tools, and uses signed in-app updates. There is even a read-only mod linter.
>
> It does not rewrite the game, mods, or saves. Runtime shortcuts are exact-code-gated and fall back
> to the original game path when they do not match.
>
> Real-game coverage is deepest on Apple silicon macOS; Windows and Linux package/lifecycle testing
> is substantial and broader real-machine testing is part of the beta. [RELEASE URL]

## Playtime claim note

This note is not part of the public post. Searches found Starsector players asking how to see their
hours in [2021](https://www.reddit.com/r/starsector/comments/l20mae/) and again in
[2026](https://www.reddit.com/r/starsector/comments/1q73sh7/is_there_a_way_to_check_how_many_hours_ive_played/).
The recurring answer is to add Starsector to Steam as a non-Steam game. No dedicated tracker or mod
turned up. Keep "as far as I can find" around any first-dedicated-tracker claim unless stronger
external evidence appears.
