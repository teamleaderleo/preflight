# Leo's Preflight talking points

This is the crib sheet to read before a forum post, video, stream, interview, or release conversation.
The announcement drafts are still the source for finished prose. This page is here so the useful
parts of the product do not disappear from memory when the conversation moves quickly.

## Lead with this

Preflight is a performance launcher for Starsector. It prepares work that the game and mods would
otherwise repeat on each launch, then reuses that work only while the exact inputs still match.

On the 83-mod development installation, the same-session controlled medians were **89.00 seconds
normally and 15.53 seconds with Preflight**. The lowest recorded launch in that comparison was
**15.25 seconds**. Hardware and mod lists vary, so the app includes its own benchmark for measuring
an installation directly.

## Things that are easy to forget to mention

- **The benchmark is built in.** People can compare a normal launch and a Preflight launch on their
  own machine instead of taking the development numbers on faith.
- **It tracks Starsector playtime.** The local launch ledger survives the desktop minimizing or
  closing after the game starts. The history can also be exported as versioned JSON or CSV.
- **Named mod profiles are a real product feature.** Profile activation previews the exact
  `enabled_mods.json` change and saves a backup. A saved profile can also be duplicated before an
  experiment without activating the copy or duplicating mods, saves, or cache bytes.
- **Common game settings live beside Launch.** Resolution, fullscreen, sound, antialiasing, UI
  scale, RAM, and battle size are available without opening a separate launcher/settings ritual.
- **Preparation plans disk use before writing.** The app calculates the current profile's bound and
  offers a minimal-disk preparation path when the default cache does not fit.
- **Help has Copy setup.** It produces a bounded privacy-safe support summary with the useful setup
  facts while excluding paths, credentials, raw logs, save contents, and arbitrary private text.
- **Diagnostics are disclosed before sharing.** The support ZIP is allowlisted and contains its own
  disclosure. Report sending uses that same reviewed ZIP. Automatic failed-run reporting stays off
  until the player explicitly enables it.
- **Ordinary game launches do not upload logs or telemetry.** Update checks and explicitly enabled
  support reporting are separate product actions.
- **The update path is signed and checked.** Release packages use the project updater key. The
  private-candidate lifecycle rehearses update, rollback, and package boundaries; #818 tracks the
  remaining requirement that final publication evidence be bound to the exact tagged package bytes.
- **Every package carries a capability receipt.** It records the native commands, writes, child
  processes, links, and network endpoints available to that exact build.
- **There is a mod linter too.** It has found progressive JPEGs, wasteful texture/audio allocation,
  shadowed resources, extension mismatches, unused files, and configuration that the game never
  reads.
- **Preflight is free and open source.** Patreon supports development; features stay available to
  everyone.

## The trust explanation

Preflight does not rewrite Starsector or mod JARs, executables, assets, or saves. Runtime
optimizations live in the launched game JVM and disappear when the game exits. If installed code or
prepared evidence differs from what Preflight recognizes, that optimization declines and the
original game path handles the work.

Two explicit backed-up features can change game-owned configuration: named-profile activation writes
the reviewed enabled-mod selection, and the launch-settings editor writes the reviewed launch/game
settings. Both have reviewed boundaries instead of hidden background mutation.

The support path follows the same rule. Ordinary game launches send no logs or telemetry. A support
report is a disclosed bounded ZIP, and automatic failed-run reporting requires explicit opt-in.

## A short spoken version

"Preflight is a performance launcher for Starsector. On my 83-mod setup, the controlled median went
from 89 seconds to 15.53. It prepares repeated startup work once, verifies that the game and mod
inputs still match, and reuses it. It also has a built-in benchmark, a playtime counter, named mod
profiles, launch settings, disk planning, and privacy-safe support tools. It does not rewrite saves
or mod files, and if it does not recognize something it leaves the original game path alone."

## Questions people will ask

**"Will I get the same launch time?"**

Say the exact measured comparison first, then say results depend on hardware and mods. Point them at
the built-in benchmark.

**"Does this modify my saves or mods?"**

No. Runtime changes stay in the child game JVM. Profile activation and launch settings are the two
explicit backed-up game-owned preference changes.

**"Is this a mod manager?"**

It is primarily a performance launcher. Named profiles, duplication, setup analysis, storage
planning, support tools, and game settings grew around that job because they make launching a large
modded installation safer and easier to understand.

**"What gets sent anywhere?"**

Ordinary game launches upload no logs or telemetry. The app can check for updates. Support-report
sending is a separate disclosed action, and automatic failed-run reporting is opt-in.

**"Was this written with AI?"**

Yes. ChatGPT/Codex and Claude/Code were used throughout development. The repository keeps the
experiments, failures, fixes, review notes, and regression tests. The claim is the tested product and
its evidence, not who typed each line.

## Claims to qualify every time

- The 89.00s to 15.53s comparison is one machine, one 83-mod setup, one controlled session.
- The 15.25s result is a recorded run from that comparison, not a universal expectation.
- Gameplay improvements are more workload-dependent than startup improvements.
- Real-game testing has been deepest on Apple silicon macOS; Windows and Linux have strong package
  and automated coverage and need broader beta-machine evidence.
- Keep "as far as I can find" around any claim that Preflight is the first dedicated Starsector
  playtime tracker unless stronger external evidence is collected.
- Call it a beta until the release evidence says otherwise.

## Current small-product opportunities

These are useful follow-ups with unusually high leverage because much of the underlying work already
exists:

- Add a desktop **Export play history...** action over the existing JSON/CSV exporter.
- Add **Save setup summary...** beside **Copy setup**, generated from the exact same privacy-safe
  projection.
- Add **Copy benchmark result** for a compact forum/Discord-ready comparison from the retained
  benchmark result.
- Finish the safe remembered-UI subset from #562: useful filters/search, immediate Launch/Prepare
  busy feedback, and session-local Help/Recovery position.
- Review and disposition the already-built #738 hull motion/direction preference and #747
  Hangar/Compact + playtime visibility preference before the exact candidate is frozen.
- Add the #578 **Run self-check** desktop action by composing checks Preflight already performs.
- Follow with #573's player-facing explanation of why preparation is stale and how much data will be
  reused before rebuilding.
- Later, #585 can turn the existing profile evidence into **Check before switching** without changing
  the installation.
