# FPS counter product audit

Date: 2026-08-26

Status: installed game bytecode, current Preflight telemetry, and product boundary reviewed

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

The probe is currently explicit development/benchmark instrumentation. Normal launches do not start
its smoke-only one-second report publisher.

## Product direction

Do not inject a second counter into Starsector as the first feature. It would duplicate a display
the game already owns while adding render-hook, fullscreen, scaling, and compatibility risk.

The useful Preflight feature is an optional **Frame pacing** result: explain where Starsector's own
counter appears, then let a player explicitly record a session and show average FPS, one-percent
low, and p95/p99 frame time in Preflight after play. Keep the recording local, bounded, visibly
enabled, and out of campaign saves. A live desktop readout can follow if players use a second
display; it should reuse the same low-overhead frame boundary rather than add another clock to the
render loop.

An advanced paired campaign mode can reuse the existing measurement-only/optimized coordinator, but
it needs a deliberately selected disposable save, a representative route longer than the current
three-second movement smoke, and clear automation-permission review before it becomes a player
benchmark.
