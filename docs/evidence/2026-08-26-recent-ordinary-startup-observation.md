# Recent ordinary startup observation — 2026-08-26

## Result

The maintainer reported a recently observed ordinary startup time of **112.17s** and selected it to replace the older roughly 101-second value in the current development/public headline.

The retained accelerated endpoint remains **13.69s**. Together, the selected headline is **112.17s → 13.69s**, an approximately **8.19×** startup speedup.

## Scope

This observation inherits the scope of the development headline it replaces: the current 83-mod M5 MacBook Air development installation running Starsector 0.98a-RC8 with the game's bundled x86-64 Java runtime through Rosetta.

The startup clock remains the development record's game-log clock, from the `Running with the following mods...` marker through GraphicsLib's `VRAM after unload/preload` marker.

## Provenance and limits

This record captures explicit maintainer direction on 2026-08-26. The raw run log/run ID for the 112.17-second observation was not present in the remote repository when this record was added, so this is retained as a single maintainer-reported development observation rather than a packaged-candidate benchmark result.

It supersedes the older ~101-second quantity only as the selected public/development baseline. Historical A/B campaigns, medians, and older chronology points remain unchanged for the comparison questions they were collected to answer.