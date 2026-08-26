# Deterministic campaign Continue no longer depends on a macOS click

**Status:** live 0.98a-RC8 proof on the current 83-mod development profile

The campaign harness now routes `main-menu.continue` through a closed action request inside the
already-running game JVM. The request is create-once, names one sequence, exact PID and process-start
instant, expected live state, action, and deadline. The exact-reviewed title class polls only during
`--desktop-smoke`, invokes the normal title callback from `advanceImpl`, and writes a create-once
receipt. The controller then requires `campaign-ready` from the same process lifetime; an executed
receipt alone is not a pass.

The accepted run launched with Preflight Recommended (`--fast`), without System Events or native
input. It recorded:

- `main-menu-interactive` on the exact runtime;
- an executed `main-menu.continue` receipt at `title.advanceImpl`;
- `campaign-ready` from the same PID/start identity;
- 51 applied exact-target transformations, zero declines, and zero contained failures; and
- controller-owned shutdown of that exact runtime.

The campaign and descriptor hashes remained the same as the preceding paused frame-pacing runs:

- campaign: `f716de34cf38b717134bee0d6233824ce76c0374624b0f644c82d61beaad07d1`
- descriptor: `45388feaf1a3fc279b3a4e3b56fa88ad51a06e8b6e5a2b0f04d29e6db760a2cc`

The first internal-control proof correctly failed before writing a request. Starsector advances a
decorative combat engine behind the title screen, and the old semantic waiter treated a historical
interactive-menu timestamp as though it were the current state. Runtime state now keeps the
interactive title authoritative over decorative combat, and Continue requires the current
`main-menu-interactive` state. That rejected observation is not performance data.

The first accepted scenario then exposed a presentation mistake in the controller rather than the
action: it stopped the exact runtime immediately after the first `campaign-ready` observation, so a
campaign frame did not remain visible long enough for an operator to distinguish the transition.
`wait-duration` is now a driver-neutral, PID-checked scenario step. The checked Continue proof holds
the exact process for ten seconds after `campaign-ready`; longer local routes can use the same step
without acquiring macOS input permission. A second Preflight-only run executed Continue against its
one recorded PID, remained alive in campaign during the dwell, and produced the retained paused FPS
snapshot in
[data/2026-08-26-paused-campaign-preflight.json](data/2026-08-26-paused-campaign-preflight.json).

The bounded accepted receipt is retained in
[data/2026-08-26-deterministic-campaign-continue.json](data/2026-08-26-deterministic-campaign-continue.json).
The ignored raw run remains operator-local only until ordinary artifact pruning.
