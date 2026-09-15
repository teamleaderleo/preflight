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
