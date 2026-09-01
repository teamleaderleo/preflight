# Empty combat listener range snapshots

Date: 2026-08-27

Install: Starsector 0.98a-RC8, current heavily modded profile, macOS on Apple M5,
bundled x86-64 Zulu 17 under Rosetta, Preflight fast preset

Status: accepted with limit as an opt-in; exact tests and a clean live 1,040-DP route pass

## The larger category was emptiness

The accepted array-walk transform removed the redundant `ArrayList` wrapper and iterator from six
weapon-range listener queries. Its clean 30.003-second 1,040-DP follow-up still attributed 313 JFR
allocation samples and 644.3 MiB of statistical weight to required `Object[]` snapshots.

The first follow-up design conservatively cached non-empty arrays after full size, order, and
element-identity validation. Live telemetry disproved the need for that complexity. Through the
capture it observed 46,364,004 exact empty-list snapshots, zero validated cache hits, zero non-empty
rebuilds, zero failures, and zero retained owners. The battle did not need a general snapshot cache;
it was repeatedly allocating `Object[0]` for ships with no matching range listeners.

## Retained shortcut

`preflight.combat.listenerRangeEmptySnapshot=true` implies the accepted array-walk transform. Its
invariant runtime helper returns one private shared zero-length array only when the source is the
exact JDK `ArrayList` used by the reviewed `ObjectRepository` and is currently empty. Non-empty
`ArrayList`s and every unknown list implementation delegate to a fresh `toArray()` on every query.
Adding a listener therefore takes the fresh-snapshot path on the next call, and callback-safe
snapshot behavior for every non-empty list is unchanged.

The shared array never escapes the six transformed private loops, which only read its length and
elements. It contains no game object, retains no listener or list, adds no field or table, crosses
no frame or serialization boundary, and touches no save state. Production sessions do not update
the diagnostic counters unless frame telemetry is enabled.

The transformed `CombatListenerUtil` bytecode is identical whether the empty shortcut or the
array-only property enabled the plan. Runtime configuration selects behavior, so a transformation
cache entry cannot replay the wrong mode.

## Live result

The fresh Preflight-only `campaign-simulation-combat-1000dp` observation prepared 24 mirrored ships
and 520 DP per side, enabled 2x simulation speed, zoomed to 6,120 world units, passed all 34 steps,
completed the 30.004-second combat window, and exited zero. The exact transform applied with no
contained failure. The strict controller-stop receipt correctly classified two known OpenAL cleanup
messages and did not hide any gameplay fatal.

In the combat window, both `getWeaponRange` and `getWeaponBaseRange` allocation filters moved from
the preceding 313 samples / 644.3 MiB to zero samples. A broader `CombatListenerUtil` filter still
found five samples / 28.0 MiB, but every one belonged to separate damage-listener paths rather than
the six transformed range methods.

The observation initially exposed the broader experiment under the temporary property name
`preflight.combat.listenerRangeSnapshotReuse`. Because its telemetry proved that only the empty
branch executed, the retained implementation deletes the unused identity cache, validation scans,
synchronization, and game-object retention, and renames the property to the narrower contract above.
No additional game run is needed to exercise an unobserved branch: the final shortcut is the same
empty-array branch used 46.36 million times in the clean observation, while every unused branch was
removed.

One preceding launch stopped before the main menu with an early native SIGSEGV, a zero-byte JFR,
and no adapter report. No target class or candidate path executed, so that attempt is excluded from
the optimization result. It exposed a native-crash banner classification gap in the smoke harness;
the scanner now treats anchored HotSpot signal and access-violation lines as fatal even when an
exact controller-stop receipt exists.

The focusless route dropped 2,006 inactive intervals and retained zero eligible combat frames. It
therefore supports no FPS, percentile, or smoothness claim. The machine reported no macOS thermal
or performance warning immediately before both attempts, though physical warmth remains a run
confounder.

## Verification and claim boundary

Twelve focused Java 17 tests cover opt-in implication, byte-for-byte transform-cache invariance,
wrong-hash and second-rewrite fallback, exact installed-archive provenance, disabled behavior,
exact empty reuse, non-empty mutation, unknown-list fallback, and telemetry-off behavior. Full
Java 17 `mvn verify` passes.

The compact measurements and hashes are retained in
[`data/2026-08-27-combat-listener-range-empty-snapshot.json`](data/2026-08-27-combat-listener-range-empty-snapshot.json).
The raw JFR and copied megabyte log tail are intentionally not committed and are pruned after this
checkpoint.

Acceptance rests on the exact empty-list contract, direct disappearance of the targeted sampled
allocation family, clean live execution, and fail-closed tests. The shortcut remains opt-in. No
FPS, percentile, startup-time, or cross-platform uplift is claimed.
