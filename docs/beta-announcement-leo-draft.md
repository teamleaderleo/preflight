# Leo's beta announcement draft

Short forum/Reddit post. Keep [beta-announcement-draft.md](beta-announcement-draft.md) as the longer
source when a venue needs the release story, evidence, package details, or more explanation.

This draft is based on the current #1128 product surface, including custom battle sizes through the
positive Java `int` range. Keep candidate-specific fields bracketed until the retained release
candidate produces them. Convert this Markdown to BBCode before posting on the Starsector forum.

## Title

> Preflight — a free, open-source fast launcher for Starsector (13.69s best on my 83-mod setup)

---

## Post

This is **Preflight**, a free, open-source fast launcher for Starsector.

On my 83-mod M5 MacBook Air development setup, launches started around the ugly **~101-second** end
and eventually reached a **13.69-second best run**, about **7.4× faster**. The clean same-session
comparison was **89.00s median normally → 15.53s with Preflight**. That's Starsector's x86-64 Java
running through Rosetta, too.

The app has its own normal-vs-Preflight benchmark. **You can measure yours yourself!**

**Download:** [RELEASE URL]

Features:

- **Tracked playtime!!!!!**
- Faster campaign-map runtime on my setup too. I don't have one clean universal FPS number for that,
  so I'm not making one up.
- Battle size up to **2,147,483,647 deployment points**. That's `INT32_MAX`. This is not a
  recommendation.
- Resolution, fullscreen, sound, antialiasing, UI scale, RAM, and battle size right beside Launch.
- Saved mod setups if you want them.
- Checks for missing/broken mod dependencies and other setup weirdness.
- Mod linting.
- Storage planning, repair, cleanup, and recovery stuff.
- A little wireframe Hangar made from your installed ships, because I got carried away.
- Windows, macOS, and Linux. It's a React/Tauri desktop app around the same Java engine as the CLI,
  and the desktop brings its own Java runtime.

It can remember mod setups, but **this is not a mod manager**. It doesn't install or update mods. It
is mostly a fast launcher that accumulated a lot of useful Starsector-launcher things because I kept
wanting them.

Disk-wise, my current giant setup eventually settles around **1.1 GB** of prepared data. First setup
can need a couple of gigabytes plus temporary working room. Preflight calculates the actual number
for your install before it starts writing.

Compatibility should be pretty boring. Preflight doesn't permanently patch Starsector or mod JARs.
If a runtime shortcut doesn't recognize the code it expects, it steps aside and the normal path runs.
A sufficiently unusual future game or launcher change can still need a Preflight update, but an
unrecognized shortcut is supposed to decline rather than force itself through.

The public package's own retained benchmark goes here before posting:
**[PACKAGED CANDIDATE BENCHMARK RESULT]**.

Yes, I used ChatGPT/Codex and Claude Code throughout development. The source, tests, measurements, and
failed experiments are all in the repository.

Please try it. Please tell me if anything is slow, broken, confusing, or cursed.

**Download:** [RELEASE URL]

Preflight is an independent, unofficial project. It isn't affiliated with or endorsed by Fractal
Softworks.

## Optional mod-author / developer note

Use this separately when the venue or conversation actually calls for it; don't make every player
read ecosystem philosophy before the download link.

> Profiling Preflight has also turned up specific hot paths in the game and in mods. If I bring one
> upstream, I'll try to bring the measurement or reproduction with it, not a vague "this is slow."
> Preflight already handles what it can on its side; nobody needs to reorganize around the launcher.
