# Leo's beta announcement draft

This is the shorter technical release post for the Starsector forum and subreddit. It should still
feel like a person explaining the thing he spent an unreasonable amount of time on. Keep
[beta-announcement-draft.md](beta-announcement-draft.md) as the longer source copy.

Replace every bracketed field before posting. Convert the Markdown to BBCode for the Starsector
forum. Read [Leo's talking points](leo-talking-points.md) and
[Public-writing sales inventory](public-writing-sales-inventory.md) before posting so the smaller
finished features, current limits, and claim qualifiers do not disappear during the final edit.

## Title

> Preflight, a performance launcher for Starsector (101s to 15.25s on my 83-mod setup)

---

## Post

I have 83 mods installed.

At its worst, Starsector took about 101 seconds to reach the main menu. In the latest controlled
comparison on that same mod profile, five ordinary launches had an **89.00-second median** and five
Preflight launches had a **15.53-second median**. The lowest recorded launch in the comparison was
**15.25 seconds**.

So, here it is.

**Download Preflight:** [RELEASE URL]

Preflight is a free, open-source performance launcher for Starsector. It prepares work the game and
mods would otherwise repeat on every launch, checks that the exact inputs still match, and reuses the
result. When an exact check does not match, that shortcut declines and the original game code handles
the work.

The app includes the same normal-versus-Preflight startup benchmark I use for the development
comparison, so you can measure your own installation instead of extrapolating from mine. It seals the
installation/profile/launcher/runtime/settings identity around the pair and refuses to call two
different setups a comparison. The result can show cache hits, safe fallbacks, contained failures,
prepared-data size, measurement overhead and available memory alongside the startup time, and
**Copy result** produces a compact forum/Discord-ready summary without raw evidence or private paths.

And because I apparently cannot leave a project at one job, Preflight now does quite a bit more than
launch the game sooner.

- **It tracks Starsector playtime.** The local ledger keeps recording a session launched through
  Preflight even if the desktop minimizes or exits afterward. The Speed page can copy a summary with
  total time, session count, longest and average session, and first/latest recorded dates. The engine
  can also export the history as versioned JSON or spreadsheet-safe CSV.
- **It has named mod profiles.** Switching previews the exact `enabled_mods.json` change and saves a
  backup first. Profiles can be created, searched, renamed, duplicated, switched, and deleted.
  Duplicating a profile does not duplicate your mods, saves, or cache data.
- **The launch settings are actually useful.** Resolution, fullscreen, sound, antialiasing, UI scale,
  RAM, and battle size all live beside Launch. The battle-size controls can extend beyond the vanilla
  slider with presets through 2,000 deployment points while still using Starsector's own preference.
- **It plans storage before preparation.** Preflight calculates the current profile's requirement,
  reuses matching prepared data, and refuses before writing when the safe bound does not fit. If the
  normal preparation is too large, it can offer a much smaller minimal-disk route.
- **Preflight cannot hold the game hostage to its own cache.** If full preparation will not fit, or
  prepared data cannot be trusted enough to use, Home can still offer **Launch at normal speed**.
  Damaged prepared data gets a scoped repair path, and an in-progress preparation has **Stop safely**.
- **It can inspect a broken-looking mod setup without launching the game.** The read-only deep setup
  check can report missing enabled mods, invalid metadata, duplicate mod IDs, required dependencies
  that are missing or disabled, and resolved variants that point at hulls absent from the active
  profile. It changes nothing.
- **Support data is deliberately boring.** Copy setup gives you the useful game/profile/mod/launch
  facts without paths, credentials, saves, or arbitrary logs. A separate support ZIP uses a fixed
  allowlist, tells you what is inside before you send it, and excludes saves, game/mod assets,
  screenshots, audio, caches, arbitrary logs, and credentials. Automatic failed-run reporting is a
  separate setting and starts off.
- **Updates are signed.** The desktop can notify you about a newer release, show its notes, download
  and verify it, then install and restart when you ask. The release process also rehearses install,
  upgrade, rollback, and removal across macOS, Windows, and Linux.
- **There is a read-only mod linter too.** It can inspect one mod or a whole profile for measurable
  asset/config costs without editing anything or assigning a score.

After a Preflight launch, Home does not reduce compatibility to a green checkmark. It can say
**Last run: acceleration active**, **acceleration active, with safe fallback**, or **original game
code used safely**; review-worthy outcomes stay visible and carry a suggested next action.

The native desktop packages bring their own minimal Java runtime. You do not need to install a JDK
to use the desktop app. The standalone JAR and the CLI still exist for people who want them, and they
use the same engine and safety checks as the desktop.

The CLI has some useful power-user tools of its own: `doctor` can show which Starsector launcher
Preflight found without starting anything, `scan` can inventory a huge enabled profile, `--dry-run`
can print the exact launch command without executing it, and the direct path can use Starsector's own
saved launcher preferences when an unattended launch is useful.

The linter came out of the same profiling work. On the reviewed mod set it found progressive JPEGs
that decode about **8.75 times slower** through the game's ImageIO path, large amounts of avoidable
texture and audio allocation, shadowed and duplicate assets, editor source files the game never
reads, and a handful of released config files containing data the game silently never applies. It
was calibrated over 86 installed mod directories; the median was zero findings and 44 of 86 were
completely clean. The point is useful signal, not telling mod authors that their work is bad.

The performance story is much weirder than "cache some files."

The loading thread could spend roughly 27 seconds waiting behind a one-thread texture prefetch
queue, then repeat hashing, image decoding, pixel conversion, copying, color work, and empty
power-of-two padding. Once textures became cheap, the visible 0-percent pause turned out to be
`SpecStore` rebuilding stable JSON/CSV-derived data every process. Then the mod callback tail became
visible: AshLib repeatedly resolved the same hull data, GraphicsLib repeated work around generated
texture state, Janino regenerated highly overlapping class maps, and the game decoded a very large
amount of audio before the menu.

Not every idea survived. Some early texture-cache pilots had excellent hit counters and broken
visuals. A supposed timing split turned out to be a stale benchmark anchor. Java Flight Recorder's
clock was off by about 2.49 times under one runtime setting. A GraphicsLib traversal replay made the
measured path substantially worse and was removed. AppCDS did not establish a safe win and was
removed too. The repository keeps those failures and the evidence that killed them instead of
retelling the history as a straight line of successes.

That is also why the built-in benchmark exists. I spent enough time learning that a convincing
number can still be measuring the wrong thing.

Preflight does **not** rewrite Starsector's JARs, mod JARs, executables, assets, activation data, or
saves. Runtime optimizations exist inside the launched game process and disappear when it exits.
Profile switching and the launch-settings editor are the two explicit, backed-up paths that can
change game-owned preferences. The launch-settings backup contains only the preference keys Preflight
is authorized to change; it deliberately excludes the registration serial and unrelated launcher
preferences.

The desktop host is a fixed set of typed commands, not a generic shell. Every release package also
carries a machine-checked capability receipt describing the native commands, writes, child processes,
links, and network endpoints available to that exact package. The release pipeline is being tightened
so the evidence for install/update/rollback and the final published package all refer to the same
exact bytes.

Yes, I used ChatGPT/Codex and Claude Code throughout development. I stand by the code, the tests, and
the evidence. The repository includes the experiment history, rejected approaches, review notes,
regressions, and current release work. If something fails, I want enough evidence to reproduce it
and enough containment that the original installation stays recoverable.

This is still a beta. Real-game testing has been deepest on Apple silicon macOS. Windows and Linux
packages already go through substantial automated package and lifecycle testing, but broader
real-machine game evidence is one of the reasons to do a beta. The reviewed game version is
0.98a-RC8. Unknown or changed code can mean fewer optimizations until Preflight is updated.

**Download:** [RELEASE URL]

If Preflight helps, saves you a pile of waiting, or you simply like this kind of obsessive
open-source work and want to support it:

- GitHub Sponsors: [GITHUB SPONSORS URL]
- Patreon: https://www.patreon.com/cw/teamleaderleo

## Playtime claim note

This note is not part of the post. Searches found Starsector players asking how to see their hours
in [2021](https://www.reddit.com/r/starsector/comments/l20mae/) and again in
[2026](https://www.reddit.com/r/starsector/comments/1q73sh7/is_there_a_way_to_check_how_many_hours_ive_played/).
The recurring answer is to add Starsector to Steam as a non-Steam game. No dedicated tracker or mod
turned up. Keep "as far as I can find" around any first-dedicated-tracker claim unless stronger
evidence appears.
