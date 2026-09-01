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
- slow-frame frequency, excess slow-frame time per second, and repeated slow-frame clusters;
- separate campaign, campaign warm-up, settled campaign, and combat distributions;
- focus-loss exclusion and measurement-overhead accounting.

The probe remains explicit and opt-in. A normal optimized launch can now enable the same bounded
frame boundary without enabling desktop-smoke automation or its one-second live report publisher.

## Product implementation

Do not inject a second counter into Starsector as the first feature. It would duplicate a display
the game already owns while adding render-hook, fullscreen, scaling, and compatibility risk.

The desktop now has an optional **Record frame pacing** preference. Recommended and Conservative
launches reuse the existing exact-pinned frame boundary. The latest run snapshot leads with stutter
burden, repeated slow-frame exposure, and slow frames per active minute, then retains one-percent
low, p99, and average FPS as context. Each row also states its measured frame count and accumulated
active time, so a short sample cannot look identical to a representative route.
Older summaries without the duration field remain readable and show only their frame count. The
desktop prefers settled campaign frames when available, separates complete paused and unpaused
active windows, labels that warm-up and transition exclusion, shows campaign and combat separately,
and does not expose raw worst-frame details. Reading the adapter report is size-bounded and fails
closed. Older reports without the stutter profile retain their compact percentile display.

The exact rendered matrix now carries a dedicated frame-pacing scenario. It checks that all three
preview distributions expose active duration and captures the result at the default and minimum
window sizes as part of the ordinary 480–1440 pixel sweep.

The result card's recorder-cost number is deliberately narrower than a slowdown claim. It times the
work inside the injected `Display.update` boundary and reports that average in microseconds per
frame. Recorded frame intervals already include the previous boundary call, so the FPS distribution
does not subtract the recorder from itself. The self-time does not cover every state/focus observer,
however, and it is not a counterfactual recorder-off FPS result. A true on/off slowdown claim would
need the same repeated route measured by an independent frame clock in both conditions; no such
retained pair exists yet.

The separate developer A/B path now enforces the same coverage truth before it can seal campaign
metrics. Both conditions launch through Preflight with identical steps: measurement-only keeps the
state/frame hooks but no reviewed fixes, while optimized enables them. Each phase allows 30 seconds
for warm-up, a 5-second transition cushion, and 30 seconds for settled collection; only the
post-30-second distribution is compared, and fewer than 100 frames or 30 active seconds is a failed
phase rather than an FPS result.

The sealed comparison carries the existing bounded stutter profile and an explicit ranking. Excess
slow-frame time and repeated clusters outrank isolated transitions or one favorable percentile; the
desktop off→on result shows those leading smoothness measures alongside startup time. The detailed
policy and projection tests are retained in the
[smoothness reporting note](2026-08-27-smoothness-reporting.md).

The preference stays visibly enabled but collection pauses under **Off / troubleshooting**, because
that preset promises not to install the runtime adapter. Results remain in Preflight's run directory;
the feature neither reads nor writes campaign saves. A live desktop readout can follow if players
use a second display; it should reuse the same low-overhead frame boundary rather than add another
clock to the render loop.

A fresh retained pair still requires an operator-selected disposable save and operating-system
automation permission. Until that run exists, Preflight should not present the same-session
**9.15 → 20.45 FPS** values as the result of turning its fixes on.
