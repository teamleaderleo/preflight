# Getting started with Preflight

## TL;DR

Preflight is still in release-candidate work, so public desktop downloads aren't live yet.

When the beta is published, the normal path is:

```text
install Preflight
      ↓
let it find Starsector
      ↓
Prepare and launch once
      ↓
Launch Starsector normally after that
```

Preflight prepares reusable work under its own data directory. It doesn't rewrite game/mod JARs or saves. If a runtime shortcut doesn't recognize the code it expects, it steps aside and the normal game path runs.

For the current release state, use [Release readiness](release-readiness.md). For the explanation of what all this means, read [How Preflight works](how-preflight-works.md).

## Before the public beta

There isn't a supported public-download path yet. Contributors can run the Java/desktop development versions from source; the root [README](../README.md) has the short development commands.

Once the first public package is accepted, this page can become the ordinary install guide without pretending that package already exists.

## What the normal desktop flow will be

### 1. Open Preflight

Preflight looks in the usual installation locations. If it finds Starsector, you're done with discovery.

If it doesn't, choose the folder containing `Starsector.app`, `starsector.exe`, or `starsector.sh`.

Preflight starts from the mods you already have enabled. Setup doesn't install or remove mods.

![Preflight asking for a Starsector installation](images/walkthrough-setup.png)

### 2. Prepare and launch

Use **Prepare and launch** the first time.

Before writing prepared data, Preflight calculates the current profile's disk requirement. If the normal preparation doesn't fit with its reserve, the app can offer a smaller storage option.

Preparation does reusable work such as texture/data/generated-code processing ahead of launch. The result belongs to the exact game and ordered-mod inputs that produced it.

You can stop preparation. Completed immutable data stays reusable; incomplete temporary work doesn't become a published cache entry.

### 3. Launch normally after that

Once the current profile is prepared, **Launch Starsector** is the routine action.

Resolution, battle size, RAM, antialiasing, UI scale, fullscreen, and sound are available beside Launch, so the common settings don't require a separate launcher ritual.

![Preflight ready to launch an 83-mod profile](images/walkthrough-ready.png)

### 4. Optional: benchmark your own setup

The built-in benchmark compares a normal launch with a Preflight launch on the current installation.

That's more useful than assuming the development machine's **112.17s → 13.69s** result will match yours.

![Preflight startup benchmark](images/walkthrough-benchmark.png)

## If something goes wrong

A changed or unknown runtime target can lose an optimization while keeping the original game behavior available.

Preflight also has an **Off / troubleshooting** preset that disables runtime acceleration while retaining the launch wrapper and bounded outcome reporting.

Help can create a small setup summary or a separate allowlisted diagnostics ZIP. Sending a report is an explicit action; ordinary launches don't act as an ambient telemetry channel.

## Storage and removal

Prepared data lives under Preflight-owned storage and can be rebuilt.

Cleanup is preview-first. Removing Preflight-owned data doesn't remove Starsector, mods, saves, or game-owned content.

For the exact storage modes and current measurements, see [Performance and storage tradeoffs](performance-storage-tradeoffs.md). For exact product/write boundaries, see [Product contract](product-contract.md).
