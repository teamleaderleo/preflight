# GUI metadata readiness and silent Windows tests

The native Home bridge previously shared cache inspection, saved profiles, launch settings, and
mod readiness through one JVM response. That response waited for all four reads. Cache inspection
walks the enabled asset tree and checks prepared-data identity, so a slow scan delayed even the
small settings response; a process-level failure discarded all four results.

Home now requests `desktop home-state --game <path> --metadata-only` for profiles, settings, and
mod metadata, while cache inspection uses its existing independent request. The ordinary full
home-state command remains compatible. Cache validation and launch requirements are unchanged;
this is earlier availability of controls and isolation of scan failures, not permission to trust
stale prepared data. A second short-lived JVM is the tradeoff for separating those deadlines.
The intermittent Windows error's original cause remains unproven (#1323).

## Real-data observations

On the active Mac with `/Applications/Starsector.app`, using the installed package's Java runtime
and candidate engine SHA-256 `36f2bb578101f4a71ea91af22b0be9c444ba09b553727a554ec6374ac0214af1`:

| Sequential request | Elapsed seconds | Result |
| --- | --- | --- |
| Full home-state | 7.933 | All families returned, no errors |
| Metadata-only | 1.314 | Settings/profiles/mod metadata returned, no asset-cache result |
| Full home-state | 4.918 | All families returned, no errors |
| Metadata-only | 1.150 | Settings/profiles/mod metadata returned, no asset-cache result |

These are process invocation-to-JSON-return observations, not GUI window-to-Ready timings, game
startup timings, or a quiet-machine campaign. The full frontend suite was active for part of this
work. Raw outputs and summary are in `benchmark-results/gui-readiness-20260915/`. No game was
launched for these reads. Unit coverage holds the scan pending and rejects it independently to
verify that settings and mod readiness remain available.

## Big Red resource and audio checks

Windows and the owned viewer were already stopped. Fresh inspection found about 7.1 GiB RAM used,
23 GiB available, and 2.3 GiB swap occupied. Two interval samples showed zero swap-in/out. High CPU
load belonged to active browser tests in `actions-runner-party-protocol.service`. No leftover
Preflight game or viewer process was found. Other work was left running; swap occupancy alone was
not treated as current memory pressure, and no cache-drop or swap-reset operation was performed.

At the maintainer's request, changed the powered-off shared QXL domain's audio backend from
`spice` to `none`, preserving its sound card and original XML. `virt-xml-validate` passed and the
inactive domain readback confirmed `none`; Windows remained shut off. This discards output on the
current QEMU/SPICE route, including audio that could otherwise reach the Linux viewer and Mac RDP
playback. Game sound-processing settings were preserved. This is configuration verification, not
an acoustic playback test or a verification of a separate Sunshine capture path. See the native
operator access guide for the backup location and scope.

## Combined-read follow-up and checks

A subsequent full request took 4.689 s. With metadata and cache requests started together,
metadata returned in 1.928 s, cache in 3.852 s, and both finished in 3.866 s. All exited 0.
This single busy-Mac pair checks the complete backend dependency set, including the cost of the
second JVM; it is still not a rendered-window startup measurement. The raw comparison is
`benchmark-results/gui-readiness-20260915/combined-comparison.json`.

[PR #1325](https://github.com/teamleaderleo/preflight/pull/1325) merged as `828d8c0b` after all
checks passed, including all three native package jobs in
[Desktop CI 34958095845](https://github.com/teamleaderleo/preflight/actions/runs/34958095845).
Locally, 519 frontend tests passed; the focused Java unit tests and CLI integration reactor passed
(57 CLI integration tests, 5 platform/opt-in skips). Rust formatting and the reviewed capability
source digest passed. No timeout was extended and #1323 remains open for its original cause.

Moonlight's existing “Mute host PC speakers while streaming” setting was already enabled. Enabled
“Mute audio stream when Moonlight is not the active window”; reopening its settings confirmed the
checked value. This is a global Moonlight preference, including its Linux tile. It does not promise
silence for a focused Moonlight stream; the disabled QEMU backend governs the current shared
Windows/SPICE route. No Moonlight stream was started, and Moonlight was closed afterward.

## Redundant startup preparation plan

The first updated Mac app reached Ready, but its process tree exposed an unnecessary
`prepare --plan --texture-storage balanced --workers 4` child. The installed profile was already
ready with `textureStorage=balanced, textureScope=learned` (Compact). Cache arrival rendered once
with the default Balanced mode; the storage-inference effect queued Compact, while the planning
effect from that same render still started a Balanced estimate.

A regression test failed on the old hook with exactly one unwanted Balanced plan call. Storage
inference now has explicit state, and the planning/readiness paths wait until that state update
has settled. Fastest, Compact, and Minimal restoration are covered; actual cold-profile planning
and delayed Speed-page planning retain their existing tests. The first app was closed before
rebuilding. No game was launched.
