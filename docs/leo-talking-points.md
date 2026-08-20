# Leo's Preflight talking points

Read this before a forum post, Patreon update, video, stream, interview, release conversation, or any
other moment where the project has to fit into human working memory.

The announcement drafts are still the source for finished prose. This page is here because Preflight
now does enough things that any improvised explanation will forget half of them.

## The one-sentence sale

Preflight is a free, open-source performance launcher for Starsector that took the controlled median
on my 83-mod setup from **89.00 seconds normally to 15.53 seconds with Preflight**, then somehow grew
a built-in benchmark, playtime tracking, named mod profiles, storage planning, game settings,
recovery/support tools, signed updates, and a mod linter around that job.

## The personal version

I make stuff that tends to become much larger than I expect. Preflight is the current example.

I started because modded Starsector could take about 101 seconds to reach the main menu on my
machine. I wanted to know where the time was going. The answer turned into a performance
investigation, then a launcher, then a desktop application, then a pile of release/correctness work I
apparently considered necessary before giving it to other people.

That line is useful on Patreon and anywhere the audience is following me as a person. It is less
useful as the first line of a technical Starsector release post, which should lead with Preflight or
the measured result.

## Lead with these numbers

On the 83-mod development installation:

- observed early high: roughly **101 seconds** to the main menu;
- latest same-profile controlled baseline: **89.00-second median** across five ordinary launches;
- same-session Preflight result: **15.53-second median** across five launches;
- lowest recorded launch in that comparison: **15.25 seconds**;
- none of the ten controlled runs were excluded;
- rounds were interleaved and the machine cooled for 240 seconds before each launch.

The exact public claim is the controlled median comparison. The 101s → 15.25s line is the readable
chronological headline.

The first public release also needs the exact packaged-candidate benchmark inserted beside this
development record before publication.

## What makes the number credible

The benchmark is part of the product. People can compare a normal launch and a Preflight launch on
their own machine instead of taking the development numbers on faith. **Copy result** shares the two
measured times and an installation-specific qualifier without copying raw evidence, paths, profile
fingerprints, or logs.

The project history also contains attractive results that turned out to be wrong:

- an early texture cache produced healthy hit counters alongside broken visuals;
- a supposed timing bimodality came from a stale benchmark anchor;
- Java Flight Recorder's clock under one runtime setting ran about **2.49×** away from wall clock;
- a GraphicsLib traversal replay grew a roughly 0.25-second path to about 1.70 seconds and was
  removed;
- AppCDS could not establish a safe win for the shipped obfuscated classes and was removed.

Do not hide those stories. They explain why the current evidence and refusal paths are as elaborate
as they are.

## What actually removed the time

The useful short explanation is not "magic caching." It is repeated deterministic work being moved
out of the launch path while keeping exact identity checks around reuse.

### Textures and the prefetch queue

The loading thread could wait roughly 27 seconds behind a one-thread image prefetch queue, then
repeat hashing, decoding, pixel conversion, buffer work, color calculation, padding, and upload
preparation. The early prepared-pixel cache barely moved the whole launch until the validated lookup
was placed ahead of the real serialized wait.

Empty power-of-two upload padding was removed as part of the texture work. On the reviewed profile,
texture uploads fell from 3.65 GiB to 2.43 GiB.

### The 0-percent `SpecStore` plateau

Once textures were cheaper, the visible 0-percent pause exposed roughly 18 to 20 seconds of stable
JSON/CSV-derived game data being reconstructed every process. Prepared tagged trees and merged reads
supply the stable input while Starsector still creates fresh mutable game-owned specs and
registries.

Representative replay changes include variants 3.289s → 0.324s, weapons 3.338s → 0.998s,
projectiles 2.349s → 1.004s, hulls 2.653s → 0.754s, and rules 0.959s → 0.166s.

### Mod callbacks and generated code

Once vanilla work became cheaper, AshLib and GraphicsLib callback costs became visible. Janino was
generating highly overlapping complete class maps. A complete-map cache cut an 18.014-second direct
aggregate to 2.364 seconds, and content packing later shrank roughly 145.96 MiB of repeated maps to
about 1.13 MiB.

### Audio and the remaining tail

The game constructs about 1.2 GB of decoded PCM before the main menu. Audio decoding overlaps with
other work, so its wall-time contribution needs careful wording, but exact prepared PCM removes a
large amount of repeated CPU and hashing.

The final seconds came from many smaller serial costs: exact transformer targeting, resource
priority, shared readers, path normalization, logging, profile identity, and other hot seams.

## The app that grew around the optimization

These are finished on current `main` unless a note says otherwise.

- **Built-in startup benchmark.** Permission-free normal-versus-Preflight comparison with exact
  identity checks and a copyable public result.
- **Playtime tracking.** Durable local total for sessions Preflight launches and can observe. The
  desktop shows total/session context and **Copy playtime**; the engine can export versioned JSON and
  spreadsheet-safe CSV.
- **Named mod profiles.** Create, search, switch, rename, duplicate, and delete. Switching previews
  the exact `enabled_mods.json` change and saves a backup. Duplicate profiles do not duplicate mods,
  saves, or prepared bytes.
- **Game settings beside Launch.** Resolution, fullscreen, sound, antialiasing, UI scale, RAM, and
  battle size.
- **Storage planning.** The app calculates the current profile before writing, shows predicted and
  conservative disk requirements, accounts for reuse, keeps a reserve, and can offer a minimal-disk
  path when normal preparation will not fit.
- **Preparation/recovery.** Progress, safe stop, interrupted-operation recovery, exact-profile repair
  for damaged prepared data, and fail-closed low-disk behavior.
- **Failed-run recovery.** Relaunch, **Copy setup**, Get help, and Dismiss on the Home recovery card.
- **Cleanup and removal.** Preview-first cleanup, app-only removal, and separately reviewed removal
  of Preflight-owned data. Starsector, mods, and saves are outside both scopes.
- **Home presentation.** Full Hangar or Compact launch-first view; independent playtime visibility;
  decorative hull motion and direction preferences shared across Home/Speed/Hangar.
- **Signed updates.** Background check, explicit review, **Install and restart**, exact-offer recheck,
  Tauri signature verification, progress/reconciliation, and current-version survival on failure.
- **Support flow.** Copy setup plus a separate bounded/disclosed ZIP, explicit send consent,
  progress/cancel/retry, accepted receipt, retention/deletion information, and default-off automatic
  failed-run reporting.
- **Read-only mod linter.** One-mod and whole-profile modes with measurable asset/config findings and
  no score, ranking, or automatic fixer.

Open desktop polish PRs #855, #856, and #857 should not be described as shipped until they merge.
They cover paused hull-direction editing, keyboard focus transfer into profile review panels, and
immediate pressed feedback for ordinary controls.

## The linter deserves its own story

The linter is not a throwaway CLI command. It is a second public-facing reason for mod authors to care
about the project.

Useful numbers from the reviewed corpus:

- progressive JPEGs decoded about **8.75× slower** through the game's ImageIO path than equivalent
  baseline encoding;
- the whole reviewed profile produced 1,392 findings across 84 resource roots, including 771.9 MB of
  VRAM padding cost, 687.9 MB decoded-at-load audio cost, and 100.8 MB of disk findings, kept as
  separate resource categories instead of summed into nonsense;
- only a handful of findings were actual broken configuration, which is the desired signal level;
- calibration across 86 individual mod directories produced a **median of zero findings** and
  **44/86 completely clean**;
- no rule fired on more than a third of the sample.

The tone is part of the design. The tool says what it measured and why it costs something. It does
not call things mistakes, does not rank mods, and does not rewrite other people's assets.

Good linter-post hook: "I pointed the profiler at 86 mods. Most of them were fine. The interesting
part was what the expensive minority had in common."

## The support/privacy story is unusually strong

Ordinary game launches upload no logs or telemetry.

**Copy setup** is for ordinary public support. It includes the useful setup facts and excludes paths,
credentials, save contents, and arbitrary logs.

The support ZIP is a separate, fixed allowlist of small text evidence. It has per-source and total
size limits, refuses symlinks, skips changing/unreadable files, redacts the home path, and carries its
own manifest of included/skipped entries, enforced limits, sizes, and SHA-256 values.

It never considers prepared caches, Starsector or mod files, saves, decoded assets, class bodies,
console/game/wrapper logs, crash dumps, JFR recordings, screenshots, or audio captures.

Sending is another separate action after disclosure. Automatic failed-run reporting is another
separate setting and starts off.

Do not summarize this as "we care about privacy." Describe what the product actually excludes and
requires. That is much stronger.

## The release/update story is part of the product

The desktop checks one fixed HTTPS release feed. A newer version appears on Home and Settings.
Installation requires **Install and restart**. Before downloading, Preflight rechecks that the exact
version, target, URL, signature, notes, and date still match the offer the user reviewed. Tauri then
verifies the updater signature.

A failed update leaves the current version runnable. Linux `.deb` packages stay with the package
manager.

Release packages go through native install/remove checks and package lifecycle rehearsals across
Linux, Windows, and macOS. Tagged lifecycle evidence is being bound to the exact package bytes that
publication would ship, not just a matching source revision.

Every package carries a capability receipt listing native commands, writes, child processes, links,
and network endpoints available to that exact package.

This is good public material because it answers a very ordinary user question: "If I trust version
0.1.0, what happens when 0.1.1 appears?"

## The trust explanation

Preflight does not rewrite Starsector or mod JARs, executables, assets, activation data, or saves.
Runtime optimizations live inside the launched game JVM and disappear when it exits.

If installed code or prepared evidence differs from what Preflight recognizes, the affected shortcut
declines and the original game path handles that work.

Two explicit backed-up features can change game-owned configuration:

1. named-profile activation writes the reviewed enabled-mod selection;
2. the launch-settings editor writes the reviewed launch/game settings.

Both are explicit user actions with review/backup behavior.

## Strong story angles for future posts

### "I tried to reduce one loading screen and accidentally made a Starsector companion app"

Use for Patreon or general dev audiences. Start with the performance investigation and then reveal
benchmark, playtime, profiles, settings, support, updates, storage, and linter.

### "How 101 seconds became 15.25"

Use for technical readers. Walk the accepted chronology and include the dead ends. This is the
project's main engineering story.

### "The loading bar said 0%. The game was doing 20 seconds of work."

Focus on `SpecStore`, tagged prepared trees, stable inputs versus fresh mutable game objects, and why
optimizing the obvious reader first did not explain the plateau.

### "The first cache worked perfectly according to the counter and broke the screen"

Use for a post about measurement and correctness. Follow with the stale anchor and JFR clock error.
The point is how many ways optimization work can lie to you.

### "I pointed a profiler at 86 mods"

Use for the linter. Lead with 44/86 completely clean, then progressive JPEG cost, audio, padding,
shadowing, and the handful of real config defects.

### "Why does a game launcher have rollback rehearsals?"

Use for release engineering. Explain signed updater artifacts, exact candidate bytes, install/update/
rollback/removal checks, and capability receipts in user terms.

### "What does a privacy-conscious bug report actually contain?"

Do not write vague values language. Walk through Copy setup versus the ZIP, the fixed exclusions,
digest review, consent, cancellation, receipt, retention, and deletion.

### "Five gigabytes for 446 milliseconds?"

Use for storage tradeoffs. Balanced versus Fastest is a good example of why measured local choices
are better than treating more cache as automatically better.

## A short spoken version

"Preflight is a performance launcher for Starsector. On my 83-mod setup, a controlled comparison
measured 89 seconds normally and 15.53 seconds with Preflight. It prepares repeated startup work,
checks that the exact game and mod inputs still match, and reuses it. The app also has its own
before/after benchmark, playtime tracking, named mod profiles, the usual launch settings, disk
planning, recovery and support tools, signed updates, and a read-only mod linter. It does not rewrite
saves or mod files, and when it cannot prove that a shortcut applies, the original game path handles
the work."

## Questions people will ask

**"Will I get the same launch time?"**

Give the exact measured comparison first, then say results depend on hardware and mods. Point them at
the built-in benchmark.

**"Does this modify my saves or mods?"**

No. Runtime changes stay in the child game JVM. Profile activation and launch settings are the two
explicit backed-up game-owned preference changes.

**"Is this a mod manager?"**

It is primarily a performance launcher. Named profiles, duplication, search, setup analysis,
storage planning, support tools, and game settings grew around that job because they make a large
modded installation easier to launch and reason about. It does not install, update, or rewrite mods.

**"What gets sent anywhere?"**

Ordinary game launches upload no logs or telemetry. The app can check for updates. Support-report
sending is separate and disclosed; automatic failed-run reporting is opt-in and starts off.

**"Why is the cache so large?"**

The default Balanced preparation kept 4.76 GB on the reviewed 83-mod profile. It is trading disk for
work the game otherwise repeats. Preflight calculates the local requirement before writing and can
offer a 10.9 MB minimal-disk route on that reference profile when the default cannot fit.

**"Was this written with AI?"**

Yes. ChatGPT/Codex and Claude Code were used throughout development. The repository keeps the
experiments, failures, fixes, review notes, and regression tests. Judge the claims by the tested
product and its evidence.

## Claims to qualify every time

- The 89.00s → 15.53s comparison is one machine, one 83-mod setup, one controlled session.
- The 15.25s result is one recorded run from that comparison, not a universal expectation.
- The 101s figure is the observed early high, not the controlled baseline median.
- The final release candidate still needs its own retained packaged benchmark before publication.
- Gameplay improvements are more workload-dependent than startup improvements.
- Real-game testing has been deepest on Apple silicon macOS; Windows and Linux have substantial
  package/automated coverage and need broader beta-machine evidence.
- Keep "as far as I can find" around any claim that Preflight is the first dedicated Starsector
  playtime tracker unless stronger external evidence is collected.
- Call it a beta until the release evidence says otherwise.

## What is still happening before release

Do not make the project sound unfinished in the sense of "the launcher does not exist." The desktop
product is largely there. The remaining work is release-candidate evidence and selected polish.

- freeze the exact candidate and bind publication evidence to those exact package bytes;
- run the packaged startup comparison and retain its receipt;
- finish the final packaged report/cancel/retry/delete evidence;
- broaden Windows/Linux real-machine Starsector coverage;
- resolve the Fractal Softworks publication/trademark decision;
- merge or reject the remaining bounded desktop polish PRs after review;
- continue correctness hardening where concrete adversarial cases still exist.

Current deep hardening includes the draft #833 work to bind exact content hashes to strong file
filesystem-generation evidence across Linux, macOS, and Windows, plus one-format persistence audits
that close actual read bounds and canonical-text ambiguities. This is excellent engineering-update
material, but it should not be described as a finished release feature until the relevant PRs merge.
