# Commodity event-mod empty-map fast path

**Status:** accepted with a bounded claim; exact structural and installed-class tests, a live
Preflight-only campaign run, and the complete Java 17 verification gate passed

## What changed

The existing event-mod wrapper had already proved the common case where all three clean trade
inputs are exactly zero and the exact `eMod` entry is absent. That path still performed a keyed
`LinkedHashMap.get("eMod")` on every hit. The transformed exact `MutableStat` now exposes its
`flatMods` backing map, and the wrapper returns immediately when that map is empty. A nonempty map
continues through the prior exact-key check; dirty or nonzero inputs, cancellation, a present
`eMod`, first use, and every changed-state case continue through the complete slow fingerprint or
retained vanilla method.

The additional proof is deliberately narrow: an empty map cannot contain `eMod`. It does not infer
anything from map size when the map is nonempty, and it does not weaken the three clean exact-zero
checks that establish vanilla's result is an unsuccessful removal.

## Live branch coverage

One profiling-only `campaign-profile-paused-unpaused` run passed all ten semantic steps in one
owned Starsector process. It left the initial pause state untouched for three seconds, verified the
paused state, collected the paused window, explicitly requested and verified unpaused state, and
collected the active window. There were zero inactive or invalid frame intervals, 69 exact
transforms, zero contained failures, and no game process remained after cleanup.

The event-mod wrapper observed 38,546,278 calls:

- 38,352,454 exact fast hits (99.4972%);
- 33,350,500 empty-map returns (86.9579% of fast hits and 86.5207% of all calls);
- 5,001,954 nonempty-map exact-zero hits that still required the keyed lookup;
- 193,824 delegations (0.5028%);
- zero fast-validation linkage failures.

This establishes that the new instruction boundary removes a keyed lookup from most observed
calls on this save and route. It does not establish how many milliseconds that lookup costs after
JIT compilation.

Campaign timing probes were enabled to count the branches, so the same run's frame distributions
are context only. Paused frames averaged 55.65 FPS with a 35.71 FPS 1% low and 2.92 ms/s stutter
burden. Active campaign averaged 50.18 FPS with a 12.35 FPS 1% low, 81.0 ms p99, 3.49% repeated
slow-frame exposure, and 72.53 ms/s stutter burden. Those figures are not an A/B result and support
no FPS-uplift claim.

## Safety and verification

The transform remains pinned to the reviewed `CommodityOnMarket` and `MutableStat` class hashes,
Java 17 bytecode, exact method/field shapes, and the app loader. All memo fields remain private and
transient. `LinkageError` disables the memo and invokes retained vanilla bytecode. The change adds
no save fields, does not open or write a save, and can be disabled with
`PREFLIGHT_DISABLE_ADAPTER_PLANS=commodity-event-mod-memo-v1`.

The installed Starsector 0.98a-RC8 integration test executed the exact local classes through both
the empty and nonempty zero paths and passed 2/2 with no skips. Java 17 `./mvnw verify` passed all
five modules: 2,224 tests, zero failures or errors, and nine intentional skips.

The bounded record is
[`data/2026-08-27-event-mod-empty-map-fast-path.json`](data/2026-08-27-event-mod-empty-map-fast-path.json).
Raw frame reports, adapter reports, logs, and generated binaries remain disposable local artifacts.

