# Leo's beta announcement draft

This is the shorter technical release post for the Starsector forum and subreddit. It states the
result, explains the useful parts, leaves the implementation details in the repository, and puts
the instructions at the bottom. Keep
[beta-announcement-draft.md](beta-announcement-draft.md) as the longer source copy.

Replace every bracketed field before posting. Convert the Markdown to BBCode for the Starsector
forum.

## Title

> Preflight, a performance launcher for Starsector (101s to 15.25s on 83 mods)

---

## Post

I have 83 mods installed. At its worst, Starsector took 101 seconds to reach the main menu. The
fastest launch I have recorded with Preflight is 15.25 seconds.

So, here it is.

**Download Preflight:** [RELEASE URL]

Preflight is a free, open-source performance launcher for Starsector. On the development
installation, the result was roughly a sixfold change. The same-session controlled medians were
89.00 seconds normally and 15.53 seconds with Preflight. Results depend on your hardware and mods.
The app includes the benchmark, so it can measure the difference on your own installation.

Somehow, Starsector has never had a playtime counter. The usual workaround has been adding it to
Steam as a non-Steam game. As far as I can find, Preflight is the first dedicated playtime tracker
for Starsector. It keeps a durable total of the sessions launched through Preflight, including when
the desktop minimizes or quits after starting the game.

Preflight also removes repeated work from some campaign and combat paths, which can improve 1%
lows. Startup is the part with the big controlled result; gameplay will depend much more heavily on
what your mods are doing.

How? Lots and lots of caching. Starsector and its mods redo a surprising amount of identical work
every time the game starts. Preflight does that work once, checks whether the inputs are still the
same, and reuses the answer. I will spare you the details here. They are in the
[README](https://github.com/teamleaderleo/preflight#readme), the technical writeup at [TECHNICAL
WRITEUP URL], and the [development history](https://github.com/teamleaderleo/preflight/blob/main/docs/optimization-history.md)
if you want them.

It works with your existing installation and mods. Preflight doesn't rewrite the game's JARs, mod
JARs, assets, or saves. Runtime optimizations are checked against the installed code before they
run. If Preflight doesn't recognize something, it leaves it alone and the original code runs.

Yes. I used ChatGPT (Codex) and Claude (Code) throughout development. Yes, I stand by the code and
its rigour. The repository includes the experiments, failures, fixes, and tests that got it here.
This is still a beta. If you find a problem, please report it. I will investigate.

Real-game testing has been deepest on Apple silicon macOS. Windows and Linux packages pass their
automated installation and lifecycle checks, and this beta is where they get tested on more actual
machines. The reviewed game version is 0.98a-RC8. Unknown game or mod code uses its original path.

**Download:** [RELEASE URL]

If Preflight helps and you want to support it:
https://www.patreon.com/cw/teamleaderleo

## Playtime claim note

This note is not part of the post. Searches found Starsector players asking how to see their hours
in [2021](https://www.reddit.com/r/starsector/comments/l20mae/) and again in
[2026](https://www.reddit.com/r/starsector/comments/1q73sh7/is_there_a_way_to_check_how_many_hours_ive_played/).
The recurring answer is to add Starsector to Steam as a non-Steam game. No dedicated tracker or mod
turned up. Keep “as far as I can find” unless stronger evidence appears.
