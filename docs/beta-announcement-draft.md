# Beta announcement draft

For the forum, Reddit, and Discord. Plain voice, first person, no marketing register.
The formal version with the full claim scaffolding is in [release-post-draft.md](release-post-draft.md).

Replace every bracketed field before posting.

**The Starsector forum takes BBCode, not Markdown.** Pasting this there renders literal asterisks
and broken link syntax. Ready-to-paste download blocks for the forum, Reddit and the README are in
[downloads.md](downloads.md#release-day-link-kit), and the prose needs the same conversion:
`**bold**` becomes `[b]bold[/b]`, `## Heading` becomes `[size=14pt][b]Heading[/b][/size]`, and
`[text](url)` becomes `[url=url]text[/url]`.

---

## Preflight — a launcher that makes heavily modded Starsector start a lot faster

Hello! I've been working on this for a while and it's ready for other people to try.

**Preflight** is a free, open-source launcher for Starsector. If you run a lot of mods and have
made peace with staring at the loading screen for a minute or two, this is for you. It does the
slow repetitive startup work once, keeps the result, and reuses it every launch after that.

On my development install, startup went from a **~101-second worst case to a 15.88-second warm
launch**. The five-run median before any of this started was 88.13 seconds, on a 77-mod profile;
the 15.88 is the 83-mod profile I run now. That's my Mac, my mods, my hardware, and a mod list that
grew along the way — yours will differ, which is why the app includes a benchmark that runs one
normal launch and one Preflight launch on your machine and shows you both numbers. Trust that one
over mine.

**Download:** https://github.com/teamleaderleo/preflight/releases/latest

### It's a beta, and here's exactly why

I only have access to macOS. That's the entire reason.

The Windows and Linux packages are built and tested by CI on every change, and the code paths run
on all three systems in automated tests — but I have never watched Starsector actually launch
through Preflight on a Windows machine, because I don't have one. That's not a thing I can fix by
being more careful. I need people to try it.

If you hit something, tell me and I'll do my best to fix it. Genuinely — that's what this phase is
for.

### What it does

- Prepares textures, merged mod data, generated mod bytecode, and audio ahead of launch, then
  reuses it for the same game and mod profile.
- Launches the game, with the settings you'd normally use the vanilla launcher for — resolution,
  fullscreen, battle size, RAM, antialiasing, UI scale, sound — sitting next to the launch button.
- Shows what the prepared cache is using on disk, with preview-first cleanup.
- Has a built-in benchmark so you can see your own before/after.
- Uninstalls cleanly, with a separate choice for "app only" and "everything Preflight made".

### Will it break when Starsector updates?

It won't break. It may stop helping until I update it.

Every optimization is pinned to the exact game and mod code it was checked against. If Preflight
looks at your installation and doesn't recognize something, that optimization declines and the
original code runs instead. A new Starsector release means fewer shortcuts apply, not a broken
game. Same for mods it hasn't seen — "not on the list" means "no speedup claimed", not
"incompatible".

There's also a single switch that turns every runtime change off, and the game still launches
normally.

### It does not touch your game

No permanent changes to Starsector, mod JARs, executables, assets, activation data, or saves. The
runtime changes exist only inside the launched game process and are gone when you quit. Preflight
writes its own cache in its own folder.

The two exceptions are explicit and both make a backup first: if you change a game setting or the
RAM allocation through Preflight, it edits the corresponding game-owned file, because that's the
file the game reads.

### Yes, I used a lot of AI assistance

I'd rather say it than have someone find it in the commit history and wonder what else I wasn't
saying.

I also understand what it's doing, and I'll happily go through any part of it. The honest summary
is that the big wins are not clever — they're caching. Starsector spends a lot of startup decoding
the same textures, parsing the same JSON, and generating the same bytecode it decoded and parsed
and generated the last time you played, because it has no reason to assume any of it stayed the
same. Preflight does the work once, checks that the inputs really are identical, and hands over the
stored answer.

Most of the difficulty wasn't making it fast. It was making it safe to be wrong — making sure that
when something doesn't match, it notices and steps aside instead of handing the game a stale or
mismatched result. That's where the identity checks, the version gates and the fallbacks come from.

If you want the longer version, the technical writeup is at [TECHNICAL WRITEUP URL] and the whole
repository is public.

### Privacy, safety, and what it talks to

Short version: it's a desktop app that mostly talks to nothing.

- **Nothing is sent anywhere unless you choose to send it.** There's no telemetry, no analytics,
  and no automatic crash reporting.
- **Update check.** It asks a fixed GitHub release feed whether a newer version exists. Updates are
  signature-verified before install, and it never installs one without you saying so.
- **Optional support reports.** If you hit a bug and want to send diagnostics, Preflight builds a
  ZIP, then shows you exactly what's inside, what it excluded, the size, and the checksum *before*
  you decide. Sending is a separate explicit action. It never includes saves, game or mod assets,
  screenshots, audio, or arbitrary logs. You get a receipt with a deletion link, and reports expire
  automatically.
- **Where those go.** A private Cloudflare bucket I control, reachable only through a small
  Cloudflare Worker. The Worker's source is in the repository along with everything else.
- **It's a Tauri app** — a Rust host with a web UI, plus a Java engine that does the actual game
  work. No bundled browser, no Electron.
- **The packages are unsigned**, because Apple and Microsoft signing costs money I haven't spent
  yet. That's why you'll see a warning on first open, and it's why every download has a published
  SHA-256 checksum. The source is public and you can build it yourself.

If any of that sounds wrong or you want more detail, ask me. I'd rather answer than have you guess.

### Known limits

- Real-game testing so far: **macOS only** — and **Apple silicon only**. There is no Intel Mac
  package in this beta.
- Reviewed game version: **0.98a-RC8**. Other versions get fewer shortcuts, not a broken game.
- Disk use: **about 4.5 GB** for a large profile. That's one measured profile (83 mods) on the
  default Balanced texture storage, so treat it as the ballpark rather than a number for your
  install. The Fastest setting stores textures uncompressed and took about 3 GB more on that same
  profile.
- First preparation takes a couple of minutes on a big mod list. After that it's reused.

---

**Shorter version for Discord / a Reddit comment:**

> Preflight is a free open-source launcher that makes heavily modded Starsector start much faster —
> it prepares the slow startup work once and reuses it. On my development install that took startup
> from ~101s at worst to 15.88s warm; the app has a benchmark so you can measure your own.
>
> It doesn't modify the game, mods, or saves, and anything it doesn't recognize it just skips, so an
> unknown version means less speedup rather than a broken game.
>
> It's beta because I only have a Mac and can't test the Windows/Linux builds on real hardware.
> Windows, Linux, and Apple silicon Macs only for now — no Intel Mac build yet.
> Bug reports very welcome. https://github.com/teamleaderleo/preflight/releases/latest
