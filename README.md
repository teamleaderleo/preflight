# Preflight

**A free, open-source cross-platform performance launcher and mod-analysis toolkit for Starsector. On my 83-mod development setup, startup moved from 112.17 seconds to 13.69 seconds.**

> Preflight is an independent, unofficial project. It isn't affiliated with or endorsed by Fractal Softworks.
>
> **Release candidate.** Public downloads follow acceptance of one immutable candidate and the remaining native/package evidence. [Release readiness](docs/release-readiness.md) tracks that work.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/desktop-home-dark.png">
  <img alt="Preflight ready to launch Starsector" src="docs/images/desktop-home-light.png">
</picture>

## 64 KB version

Heavily modded Starsector repeats a lot of expensive work every launch.

Preflight does reusable work once, saves the checked answers under its own data directory, and reuses them while the exact game/mod inputs still match.

```text
same expensive work again?
        ↓
Preflight already has a checked answer?
     ↙                              ↘
   yes                              no
    ↓                                ↓
reuse it                       normal game code runs
```

Runtime shortcuts only apply to code they recognize. If a game/mod update changes the thing a shortcut expects, that shortcut steps aside and the original path stays available.

That's the main idea. The rest of the project makes it useful on a real computer.

For the standalone ultra-simple guide, read [Preflight in 256 KB](docs/how-preflight-works-256kb.md). For the normal-human version, read [How Preflight works](docs/how-preflight-works.md).

## What you get

- **Much quicker launches.** The current 83-mod development setup moved from **112.17s to 13.69s**.
- **A benchmark for your own installation.** Compare a normal launch with a Preflight launch and copy the result.
- **Tracked playtime.** Sessions launched through Preflight feed a local play-history ledger.
- **Named mod profiles and useful launch settings.** Switch profiles with a preview/backup and keep resolution, fullscreen, sound, antialiasing, UI scale, RAM, and battle size beside Launch.
- **Setup analysis and mod linting.** Find broken dependencies, duplicate IDs, suspicious assets, configuration problems, and other measurable issues without editing the mods.
- **Storage and recovery tools.** See the expected preparation cost before writing, repair damaged prepared data, and preview cleanup before deletion.
- **Deliberate diagnostics.** Copy a small privacy-safe setup summary or create a separate allowlisted support ZIP when you choose to.
- **Windows, macOS, and Linux desktop packages.** The app brings its own small Java runtime, so ordinary desktop users don't need a system JDK.

The normal routine is short: open Preflight, let it find Starsector, run setup once, then use **Launch Starsector**.

## What it actually does

```text
You
 ↓
React desktop UI
 ↓
small Rust/Tauri native host
 ↓
shared Java command engine
 ↓
Starsector child JVM + session-local Preflight Java agent

       ↘ Preflight-owned prepared data, profiles, history, reports
```

Preparation handles reusable work such as textures, merged data, generated mod bytecode, and eligible audio. The saved result is tied to the inputs that produced it.

When Preflight launches Starsector, its Java agent lives only inside that game process. Reviewed runtime shortcuts can use prepared data or avoid repeated work. Game and mod JARs aren't rewritten on disk.

Most Preflight writes stay under Preflight's own directories. The explicit exceptions are player-requested features such as activating a named mod profile and applying supported Starsector launch settings; those paths preview, recheck, and back up what they change.

## What happens when something changes?

| Situation | What Preflight does |
| --- | --- |
| Game/mod inputs changed | Uses a different content identity |
| Prepared entry is missing or damaged | Rejects it and lets the original loader run |
| A reviewed runtime target changed | That shortcut declines |
| Preparation gets interrupted | Keeps completed immutable data and cleans up incomplete temporary work later |
| Cleanup/removal is requested | Shows the Preflight-owned targets first |

Unknown code doesn't receive an old optimization just because a class or filename looks familiar.

## Current status

Preflight is still in the release-candidate phase. The source and desktop UI are converged; public downloads wait on the exact accepted package/native evidence and release authorization.

If you want the moving release details, use [Release readiness](docs/release-readiness.md). The README intentionally doesn't duplicate that whole checklist.

## Development quick start

The Java reactor uses JDK 17+ and Maven 3.9+:

```bash
./mvnw verify
```

For routine edits, declare the smaller feedback scope instead of memorizing Maven flags:

```bash
./scripts/java-dev.py test core ContentFingerprintTest
./scripts/java-dev.py deps agent
./scripts/java-dev.py full
```

The command prints the exact Maven inventory before running it. Focused modes do not certify
unrelated modules; `full` remains the Java integration oracle. See [`scripts/README.md`](scripts/README.md)
for every mode and the opt-in parallel form.

When the exact same focused JUnit result is requested repeatedly, opt in explicitly:

```bash
./scripts/java-dev.py test core ContentFingerprintTest --reuse
```

The receipt says `executed` when the requested Surefire report was produced and `reused` when Maven
restored the matching content result without running Surefire. Ordinary focused commands do not
load the build-cache extension.

Inspect cache shape without running Maven or writing, repairing, or deleting cache bytes:

```bash
./scripts/java-dev-cache.py inspect
```

The inventory is deliberately read-only; links, unknown formats, active writers, and incomplete
scans refuse instead of proposing cleanup.

The resulting self-contained launcher is `preflight-cli/target/preflight.jar`.

```bash
# Inspect discovery without launching
java -jar preflight-cli/target/preflight.jar doctor

# Prepare the detected profile
java -jar preflight-cli/target/preflight.jar install --prepare

# Launch with the recommended optimization preset
java -jar preflight-cli/target/preflight.jar run --optimization-preset recommended
```

The desktop app uses the same Java engine and safety checks. See [`preflight-desktop/`](preflight-desktop/) for local desktop development and packaging.

## Where to go next

- **I have 64–256 KB of brain available:** [256 KB explanation](docs/how-preflight-works-256kb.md)
- **Explain the whole thing without assuming I know the repo:** [How Preflight works](docs/how-preflight-works.md)
- **Show me the performance/engineering story:** [Engineering overview](docs/engineering-overview.md)
- **I need exact behavior, evidence, release docs, or specialist internals:** [Documentation map](docs/README.md)

Preflight is MIT-licensed. Contributions and bug reports are welcome.
