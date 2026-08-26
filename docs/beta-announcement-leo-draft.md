# Leo's beta announcement draft

Short forum/Reddit post. Keep [beta-announcement-draft.md](beta-announcement-draft.md) as the longer
source when a venue needs the release story, evidence, package details, or more explanation.

This draft is based on the current #1128 product surface, including custom battle sizes through the
positive Java `int` range. Keep candidate-specific fields bracketed until the retained release
candidate produces them. Convert this Markdown to BBCode before posting on the Starsector forum.

## Title

> Preflight — a free, open-source fast launcher for Starsector (~8.19× startup speedup on my 83-mod MacBook Air)

---

## Post

This is **Preflight**, a free, open-source fast launcher for Starsector.

On my 83-mod M5 MacBook Air, startup went from **112.17 seconds to 13.69 seconds**, an **8.19×
speedup**. That's Starsector's x86-64 Java running through Rosetta.

**Dramatically faster launch times** (and you can measure it yourself!).

**Download:** [RELEASE URL]

Features:

- **Tracked playtime!!!!!**
- Faster campaign-map movement *(on my setup)*.
- Battle size up to **2,147,483,647 deployment points** (`INT32_MAX`).
- Resolution, fullscreen, sound, antialiasing, UI scale, RAM, and battle size right beside Launch.
- Checks for missing/broken mod dependencies and other setup weirdness.
- Mod linting.
- Storage planning, repair, cleanup, and recovery stuff.
- A wireframe Hangar made from your installed ships.
- Windows, macOS, and Linux. It's a React/Tauri desktop app around the same Java engine as the CLI,
  and the desktop brings its own Java runtime.

Give first setup a few GB of free space. My current 83-mod setup settles around **1.1 GB** of prepared
data. Preflight calculates the number for your installation before it starts.

Compatibility should be pretty boring. Preflight doesn't permanently patch Starsector or mod JARs.
If a runtime shortcut doesn't recognize the code it expects, it steps aside and the normal path runs.

The public package's own retained benchmark goes here before posting:
**[PACKAGED CANDIDATE BENCHMARK RESULT]**.

Yes, I used ChatGPT/Codex and Claude Code throughout development. The source, tests, measurements, and
failed experiments are all in the repository.

Please try it. Please tell me if anything is slow, broken, confusing, or cursed.

**Download:** [RELEASE URL]

Preflight is an independent, unofficial project. It isn't affiliated with or endorsed by Fractal
Softworks.
