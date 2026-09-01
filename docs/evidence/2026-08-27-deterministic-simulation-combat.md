# Deterministic simulation combat route

**Status:** live Starsector 0.98a-RC8 proof on the current 83-mod macOS development profile

The desktop-smoke controller completed an unattended Fleet → Refit → Simulation route through one
Preflight-launched PID. It prepared 24 fixed player ships plus exactly the 4,898 missing minimum crew
in memory, waited 1.5 seconds, verified all 24 ships deployable at maximum CR, deployed 8 allied and
25 opposing ships, engaged, enabled autopilot, closed the tactical command map, enabled the explicit
SpeedUp 2× condition, and kept combat unpaused. The fixture was never saved.

Several verifier failures improved the route rather than becoming evidence. An unconditional Space
press paused combat because the simulator had already entered unpaused. It was replaced by the
state-setting `combat.unpause` action. A negative CoreGraphics wheel sign and a later pure game-batch
experiment both left the viewport at `viewMult` 1.250 and visible width 1800; the semantic verifier
rejected both before performance sampling. The pure game events were inserted at
`CombatEngine.advance`, after `CombatState` had already consumed that frame's input, so that approach
was removed. The surviving macOS player-equivalent sign was then proved by the game-side viewport:
12 bounded scroll clicks changed `viewMult` 1.250 → 4.250 and visible width 1800 → 6120.

The accepted run lasted from `2026-08-26T21:13:14.632589Z` through
`2026-08-26T21:14:38.308240Z`, used PID 60748, and ended with controller-owned exact-process cleanup.
The camera settled before the controller reset a separate frame distribution, so setup/menu hitches
are excluded from the following 30-second 2× window:

- 1,391 frames; 46.39 average FPS; 54.35 median FPS;
- 12.48 FPS 1% low and 8.50 FPS 0.1% low;
- 18.4 ms p50, 38.6 ms p95, 80.1 ms p99, and 137.219 ms maximum frame time;
- 93.39% of frames meeting 30 FPS; and
- 65.78 ms/s stutter burden, 23 repeated slow-frame clusters containing 68 frames, 4.89% repeated
  slow-frame exposure, and a longest cluster of 6 frames / 386.5 ms.

These numbers describe one heavy, modded 8-v-25 battle at 2× simulation speed on the tested machine.
They are a stress observation, not a release performance claim. Repeated >33.33 ms clusters, frames
inside those clusters, and excess slow-frame time rank ahead of isolated hitches; percentiles remain
context rather than the sole smoothness score.

The compact receipt and metric slice are retained in
[`data/2026-08-27-simulation-combat-speedup.json`](data/2026-08-27-simulation-combat-speedup.json).
The raw local screenshot SHA-256 was
`48fdeeba27363f7ff54cbba0e3ca543887c0d15a03e5da8e47c989c8f6c477d6`; the raw screenshot,
single-chunk JFR, full frame report, and megabyte log tail remain disposable run artifacts rather
than long-lived repository binaries.
