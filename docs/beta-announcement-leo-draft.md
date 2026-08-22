# Leo's beta announcement draft

Shorter technical release post for the Starsector forum and subreddit. Keep
[beta-announcement-draft.md](beta-announcement-draft.md) as the longer source when a venue needs more
detail.

Keep candidate-specific fields bracketed until the retained release candidate produces them. Convert
this Markdown to BBCode before posting on the Starsector forum.

## Title

> Preflight, a performance launcher for Starsector — 89s to 15.53s median on my 83-mod setup

---

## Post

I have 83 mods installed.

At its worst, Starsector took about 101 seconds to reach the main menu on my development setup. In the
latest controlled comparison on that same profile, five ordinary launches had an **89.00-second
median** and five Preflight launches had a **15.53-second median**. The lowest launch in the comparison
was **15.25 seconds**.

So, here it is.

**Download Preflight:** [RELEASE URL]

Preflight is a free, open-source performance launcher and companion app for Starsector. It prepares
work the game and mods would otherwise repeat during startup and reuses it while the relevant inputs
still match. When reviewed code changes, the affected runtime shortcut steps aside and Starsector
handles that work normally.

The desktop includes the same normal-versus-Preflight benchmark I use for development, so you can
measure your own installation instead of extrapolating from mine. The public package also gets its
own retained benchmark before this post goes live:

**[PACKAGED CANDIDATE BENCHMARK RESULT]** on **[CANDIDATE GAME / HARDWARE / RUNTIME]**.

The project did not stay a loading-time project. Preflight now keeps local playtime for sessions it
launches and can observe, remembers named mod profiles, puts the useful game settings beside Launch,
and shows the preparation storage plan before it writes. If the normal preparation is too large it
can offer a minimal-disk route; preparation can stop safely, and damaged prepared data can be
repaired without treating Starsector or mod files as repair targets.

There is also read-only setup analysis and mod linting for large mod stacks, support tools that keep
sending separate from ordinary game use, signed desktop updates where the package format supports
them, and a wireframe Hangar built from locally traced installed ship art. The final Hangar uses a
custom typeable ship selector rather than a separate generic settings picker.

Preflight leaves Starsector's JARs, executables, assets, mod files and saves unchanged. Runtime
optimizations live inside the launched game process, and prepared data lives in Preflight's own area.
Named profile switching and the launch-settings editor are the two explicit features that can change
game-owned preferences, and both keep backups.

The desktop packages include the reviewed Preflight Java runtime, so ordinary desktop use does not
require installing a system JDK first. The first beta does not have paid Apple Developer ID or Windows
Authenticode publisher identities, so macOS and Windows may show their normal unknown-developer
warnings. The release includes checksum/package-review material, and supported in-app updates use a
separate updater signing key.

The performance story was less tidy than “cache some files.” The loading thread spent a lot of time
waiting behind texture work; once that got cheaper, stable JSON/CSV-derived game data became the next
visible pause, followed by mod callback work, generated class maps and other repeated startup costs.
Some promising ideas were simply wrong: early texture pilots had great hit counters and broken
visuals, one timing split was a stale benchmark anchor, a GraphicsLib replay made the measured path
worse, and AppCDS did not earn a place in the shipped path. Those failures are still in the repository
history.

That is also why the benchmark became part of the product. A convincing number can still be measuring
the wrong thing.

On the reviewed 83-mod development profile, one preparation measurement left **4.76 GB** in
Balanced, **10.9 MB** in Minimal disk, and **10.03 GB** in Fastest immediately after preparation.
Minimal later grew to about 204 MiB when its first launch learned the non-texture runtime caches.
Those are examples from one setup, not requirements; the desktop calculates the current
installation's own plan before starting.

The read-only linter was calibrated over 86 installed mod directories. The median was zero findings
and 44 of 86 were completely clean. The point is useful signal when a measurable problem exists, not
a score that assumes every mod needs fixing.

Yes, I used ChatGPT/Codex and Claude Code throughout development. The repository contains the source,
tests, experiment history, failed approaches, review notes and current release work. Judge the result
by the product and what it actually does.

One important release detail: real-game testing has been deepest on Apple silicon macOS, but Windows
and Linux are **not** being left as “we'll test those during beta.” The first public beta GitHub release
and downloadable packages do not go live until the retained candidate completes the required native
Windows and native x86-64 Linux real-game installation exercises.

- **Windows candidate exercise:** [WINDOWS NATIVE REAL-GAME RESULT]
- **Linux candidate exercise:** [LINUX NATIVE REAL-GAME RESULT]
- **Tagged/report/package evidence:** [FINAL CANDIDATE EVIDENCE SUMMARY]

There is no Intel Mac package in the first beta. The reviewed game version is **0.98a-RC8**; changed
versions or changed mods can receive fewer optimizations until the affected paths are reviewed.

**Download:** [RELEASE URL]

If Preflight helps, saves you a pile of waiting, or you simply want to support this kind of open-source
work:

- GitHub Sponsors: [GITHUB SPONSORS URL]
- Patreon: https://www.patreon.com/cw/teamleaderleo

Preflight is an independent, unofficial project. It is not affiliated with or endorsed by Fractal
Softworks.
