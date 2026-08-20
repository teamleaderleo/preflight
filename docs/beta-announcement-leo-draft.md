# Leo's beta announcement draft

This is the shorter release post for the Starsector forum and subreddit. Keep
[beta-announcement-draft.md](beta-announcement-draft.md) as the longer source copy, and read
[Public writing style](public-writing-style.md) before doing the final venue-specific edit.

Replace every bracketed field before posting. Convert the Markdown to BBCode for the Starsector
forum.

## Title

> Preflight, a performance launcher for Starsector (89s to 15.53s median on my 83-mod setup)

---

## Post

I have 83 mods installed, which eventually turned launching Starsector into enough of an event that I
wanted to know where all that time was actually going.

In the latest controlled comparison on that profile, five ordinary Starsector launches had an
**89.00-second median** and five Preflight launches had a **15.53-second median**, with the lowest
Preflight run at **15.25 seconds**. Earlier in development the same installation had reached roughly
101 seconds, which is where the more dramatic 101 → 15.25 chronology comes from; the 89.00 → 15.53
pair is the controlled same-session comparison.

**Download Preflight:** [RELEASE URL]

Preflight is a free, open-source performance launcher for Starsector. It prepares work the game and
mods would otherwise repeat during startup, reuses it while the relevant inputs still match, and
lets Starsector handle the work normally when something changes. The desktop includes the same
normal-versus-Preflight benchmark used for the development comparison, so you can run the pair on
your own installation instead of assuming my 83-mod Mac tells you very much about yours; **Copy
result** turns the result into a compact forum/Discord-ready comparison without hauling private
paths, logs, or the full run record into the conversation.

That was supposed to be the project, more or less. Then the loading-time investigation started
accreting everything I kept wishing the launcher already did, so Preflight now tracks Starsector
playtime for sessions it launches, remembers named mod profiles, puts resolution/fullscreen/sound/
antialiasing/UI scale/RAM/battle size beside Launch, plans its disk use before preparation, has
recovery and cleanup tools, can inspect a suspicious mod stack without starting the game, can update
itself through a signed updater on the supported package paths, and has somehow acquired both a
locally generated wireframe Hangar and a read-only mod linter.

The profile/settings side is probably the part I use most when I am no longer thinking about startup
performance. Profiles can be created, searched, renamed, duplicated, switched, and deleted; a switch
shows which mods are about to change and saves a backup first, while duplicating a profile copies the
profile definition instead of making another copy of your mods or saves. Battle-size presets can go
through **2,000 deployment points** on a standard installation while still writing Starsector's own
preference, because if I was already putting the useful launcher settings beside the launch button I
was apparently going to finish the thought.

The playtime tracker follows a Starsector session even when Preflight minimizes or exits after
launch, the Speed page can copy a useful total/session summary, and the engine can export the history
as versioned JSON or spreadsheet-safe CSV. Starsector itself does not expose a lifetime playtime
total, so this started as a side feature and rather quickly became one of those things that feels
obvious after it exists.

Preparation has a similar "please tell me before you ruin my afternoon" philosophy. Preflight
calculates the current profile, reusable data, free space, and its safety margin before writing; when
the normal preparation will not fit it can offer a much smaller minimal-disk route, an active
preparation can be stopped safely, damaged prepared data has a repair path, and Home can still offer
**Launch at normal speed** when acceleration data cannot be used. The deeper setup checker lives in
the same general territory: without launching Starsector it can report missing enabled mods, invalid
metadata, duplicate mod IDs, missing or disabled required dependencies, and variants that point at
hulls absent from the active profile.

The linter came out of the profiling work because once I had tools for measuring absurd amounts of
asset loading it was hard to avoid pointing them at the mods themselves. On the reviewed set it found
progressive JPEGs that decode about **8.75 times slower** through the game's ImageIO path, large
texture and audio costs, shadowed and duplicate assets, editor source files the game never reads, and
released config containing data the game never applies; it was calibrated over 86 installed mod
directories, the median was zero findings, and **44 of 86 were completely clean**, which I like
because the useful answer to "are mods secretly terrible?" turned out to be "mostly, no."

The performance story itself wandered through several parts of startup. The loading thread could
spend roughly 27 seconds behind a one-thread texture prefetch queue and then repeat a lot of image
work; once that was cheap, the visible 0-percent pause exposed repeated JSON/CSV-derived `SpecStore`
work, and once *that* was cheap the remaining tail became a more eclectic collection of AshLib,
GraphicsLib, Janino, and audio-decoding work. Some of the best-looking ideas were wrong along the
way: early texture-cache pilots had healthy hit counters and broken visuals, a timing split turned
out to be a stale benchmark anchor, Java Flight Recorder's clock was off by about 2.49 times under
one runtime setting, and both a GraphicsLib traversal replay and AppCDS were removed after they failed
to earn their keep. The repository keeps those experiments and corrections alongside the accepted
work because pretending this was a straight line would be much less useful and, frankly, much less
interesting.

As for what the launcher touches: Preflight keeps Starsector JARs, mod JARs, executables, assets, and
saves outside the acceleration path, while runtime optimizations live inside the launched game
process and disappear when it exits. Profile switching and the launch-settings editor are the two
backed-up features that can change game-owned preferences. Ordinary game launches upload no logs or
telemetry; **Copy setup** makes a small support summary, Help can build a deeper support ZIP and show
what will be sent before sending it, and automatic failed-run reporting is a separate setting that
starts off.

The native desktop packages bring their own minimal Java runtime, so desktop users do not need to
install a JDK. The standalone JAR and CLI remain available for people who want them, with `doctor`
for launcher discovery, `scan` for an enabled-profile inventory, `--dry-run` for launch-command
inspection, the direct-launch path, deeper setup analysis, and the linter.

This is a beta. Real-game testing has been deepest on Apple silicon macOS; Windows and Linux already
have substantial automated package and lifecycle coverage, with broader real-machine Starsector
exercise continuing through the beta. The reviewed game version is 0.98a-RC8, and game or mod updates
can reduce the available speedups until the changed code has been reviewed. The final packaged beta
candidate also gets its own retained startup benchmark before these placeholders turn into release
copy.

**Download:** [RELEASE URL]

If Preflight helps, saves you a pile of waiting, or you simply like this kind of overgrown open-source
project and want to support it:

- GitHub Sponsors: [GITHUB SPONSORS URL]
- Patreon: https://www.patreon.com/cw/teamleaderleo

Preflight is an independent, unofficial project and isn't affiliated with or endorsed by Fractal
Softworks.

## Playtime claim note

This note is not part of the post. Searches found Starsector players asking how to see their hours
in [2021](https://www.reddit.com/r/starsector/comments/l20mae/) and again in
[2026](https://www.reddit.com/r/starsector/comments/1q73sh7/is_there_a_way_to_check_how_many_hours_ive_played/).
The recurring answer is to add Starsector to Steam as a non-Steam game. No dedicated tracker or mod
turned up. Keep "as far as I can find" around any first-dedicated-tracker claim unless stronger
evidence appears.
