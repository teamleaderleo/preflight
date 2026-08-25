# FPS counter product audit

Date: 2026-08-26

Status: installed game bytecode, current Preflight telemetry, product boundary, and desktop result reviewed

## Starsector already owns the on-screen counter

The installed Starsector 0.98a-RC8 bytecode already constructs an FPS display. `BaseGameState`
creates `com.fs.graphics.util.A`, whose initial text is `FPS: 0` and whose render text is
`FPS: %3d, Idle: %3d%%`. `CampaignState.render(float)` positions that object and renders it beside
the version. `CombatState` renders the same FPS/version area through its combat UI.

Both render paths check the game setting `renderVersionAndIdle`. The installed
`data/config/settings.json` sets that value to `true`. An ordinary installation therefore does not
need an FPS-counter mod or a Preflight-drawn overlay to show current FPS.

This inspection read the installed JAR entries in memory. It did not extract or modify game
bytecode.

## What Preflight already measures

Preflight's exact-pinned `Display.update` frame boundary records more useful diagnostic information
than the instantaneous game counter:

- average and median FPS;
- one-percent and 0.1-percent low FPS;
- p95 and p99 frame time;
- frames meeting 60- and 30-FPS budgets;
- separate campaign, campaign warm-up, settled campaign, and combat distributions;
- focus-loss exclusion and measurement-overhead accounting.

The probe remains explicit and opt-in. A normal optimized launch can now enable the same bounded
frame boundary without enabling desktop-smoke automation or its one-second live report publisher.

## Product implementation

Do not inject a second counter into Starsector as the first feature. It would duplicate a display
the game already owns while adding render-hook, fullscreen, scaling, and compatibility risk.

The desktop now has an optional **Record frame pacing** preference. Recommended and Conservative
launches reuse the existing exact-pinned frame boundary, then the latest run snapshot exposes only
a compact average-FPS, one-percent-low, and p95/p99 summary. Each row also states its measured frame
count and accumulated active time, so a short sample cannot look identical to a representative route.
Older summaries without the duration field remain readable and show only their frame count. The
desktop prefers settled campaign frames when available, labels that warm-up exclusion, shows
campaign and combat separately, and does not expose raw worst-frame details. Reading the adapter
report is size-bounded and fails closed.

The exact rendered matrix now carries a dedicated frame-pacing scenario. It checks that all three
preview distributions expose active duration and captures the result at the default and minimum
window sizes as part of the ordinary 480–1440 pixel sweep.

The preference stays visibly enabled but collection pauses under **Off / troubleshooting**, because
that preset promises not to install the runtime adapter. Results remain in Preflight's run directory;
the feature neither reads nor writes campaign saves. A live desktop readout can follow if players
use a second display; it should reuse the same low-overhead frame boundary rather than add another
clock to the render loop.

An advanced paired campaign mode can reuse the existing measurement-only/optimized coordinator.
Both conditions launch through Preflight; the first retains measurement while withholding the
reviewed fixes, and the second enables them. The pair still needs a deliberately selected disposable
save, a representative route longer than the current three-second movement smoke, and clear
automation-permission review before it becomes a player benchmark.
