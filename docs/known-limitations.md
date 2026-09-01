# Known limitations

## TL;DR

The important ones:

- **The public beta isn't released yet.** Preflight is still finishing exact package/native acceptance.
- **The first macOS/Windows packages won't have paid platform identities.** Expect Gatekeeper/SmartScreen friction.
- **The strongest real-game performance evidence is from the documented Apple-silicon development setup.** Don't assume another machine gets the same number.
- **Runtime shortcuts only apply to code Preflight recognizes.** A game/mod update can temporarily mean fewer optimizations.
- **Preparation uses disk space.** Preflight calculates the plan first and keeps a reserve.
- **Support uploads are explicit.** Ordinary launches aren't usage telemetry.

That's the practical list. The rest of this page gives the important qualifiers.

## Release status

Preflight is in release-candidate work. Source/UI convergence and private signing rehearsals are complete; public packages wait on the exact retained candidate's remaining native/package evidence and release authorization.

For the moving checklist, use [Release readiness](release-readiness.md) or [#652](https://github.com/teamleaderleo/preflight/issues/652). This page doesn't duplicate the whole release program.

## Platform signing

The first beta's macOS and Windows packages won't carry paid Apple Developer ID / Windows Authenticode identities.

That means:

- macOS can require **Open Anyway** approval;
- Windows can show SmartScreen's unrecognized-app warning, and stricter managed policies can refuse the app.

Release artifacts can still publish SHA-256 manifests, and the in-app updater uses its separate project-owned update signature.

## Performance varies by installation

The current development headline is **112.17s → 13.69s** on the documented 83-mod M5 MacBook Air setup running Starsector 0.98a-RC8 through the game's x86-64 JVM under Rosetta.

Mod contents, CPU, storage, memory pressure, translation, temperature, and other machine state affect startup/runtime behavior. The built-in benchmark exists so each installation can measure its own normal and accelerated launch.

The exact public package will get package-bound evidence before release. Equal real-game activation and speed across every platform aren't claimed from the development Mac result.

## A game or mod update can reduce acceleration

Runtime optimizations are admitted for exact code/identities Preflight recognizes.

If a target changes, that optimization declines and the original code remains available. The game can therefore stay runnable while a newly changed target receives fewer shortcuts until its new code is reviewed.

Preflight also can't guarantee that Starsector or a third-party mod is free of its own defects.

## Preparation uses extra storage

Prepared data trades storage and one-time/repeated preparation work for cheaper launches.

The desktop calculates the current profile's temporary and finished requirements before writing and keeps a free-space reserve. Cleanup is preview-first.

See [Performance and storage tradeoffs](performance-storage-tradeoffs.md) for the current modes and measurements.

## Java, paths, and locale edge cases

The shipped Java code targets Java 17 bytecode, while the project also exercises newer supported JDKs during development.

There are extra compatibility paths for filenames/profile names that the selected process encoding can't represent, and Starsector itself has locale-sensitive behavior in a few case-insensitive lookups. Preflight aims to preserve the game's behavior at those boundaries rather than silently invent different semantics.

If you're investigating one of those edge cases, read [Java runtime support](java-runtime-support.md). Most users don't need that implementation detail.

## Diagnostics aren't ambient telemetry

Preflight has no user account or ordinary usage-telemetry system.

A configured release can send a bounded support ZIP after the user reviews and confirms it. Ordinary builds can save that ZIP locally. The first beta doesn't automatically send failed-run reports.

See [Diagnostics](diagnostics.md) and [Privacy](privacy.md) for the exact boundary.

## No remote runtime kill switch

There isn't a server-side switch that can silently change runtime behavior after installation.

If a reviewed adapter is implicated in a problem, use **Off / troubleshooting** and follow the [rollback path](rollback.md). Changing accepted fingerprints/default plans requires an updated Preflight package.
