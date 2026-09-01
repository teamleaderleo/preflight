# Combat listener range snapshot candidate

Date: 2026-08-27

Install: Starsector 0.98a-RC8, current heavily modded profile, macOS on Apple M5,
bundled x86-64 Zulu 17 under Rosetta, Preflight fast preset

Status: accepted with limit as an opt-in; exact tests and a clean live 1,040-DP route pass

## Profile signal

The accepted 1,040-DP AI Tweaks arc-sizing run exposed a larger vanilla allocation family. In its
clean 30.006-second combat window, stacks containing `CombatListenerUtil` carried 487 JFR
allocation samples and 813.4 MiB of statistical weight. Six weapon-range query methods accounted
for virtually all of that population. Their explicit `new ArrayList(collection)` snapshots
produced 195.4 MiB of sampled `ArrayList` objects and 614.0 MiB of sampled backing `Object[]`
copies; iterator objects added another 4.0 MiB.

The backing snapshot is semantically necessary. Starsector's exact listener manager returns the
live `ObjectRepository` list, and a range modifier is allowed to add or remove listeners while its
callback runs. Iterating that live list would risk concurrent modification and would change which
callbacks run in the current query.

## Narrow replacement

`vanilla-combat-listener-range-snapshot-v1` replaces only the six exact range-query method bodies.
Each method calls the same `ShipAPI.getListeners(Class)`, immediately obtains one private array
snapshot, and walks that stable array by index. The runtime helper delegates directly to
`List.toArray()` unless the separately gated empty-snapshot shortcut is enabled. It preserves:

- one pre-callback snapshot and the original listener order;
- callback arguments and invocation count;
- additive identity and order for flat and percent modifiers;
- multiplicative identity and order for multiplier modifiers;
- the original null-ship and null-listener-manager exits;
- changes to the live listener repository for the next query, but not the in-progress query.

The expected structural saving is the sampled 195.4 MiB of `ArrayList` objects plus 4.0 MiB of
iterators. The required 614.0 MiB snapshot-array population remains; this candidate does not claim
to remove it. JFR weights are statistical samples, not an allocation census.

## Exact boundary

The opt-in property is `preflight.combat.listenerRangeSnapshotArray`. Admission requires Java 17,
the exact `CombatListenerUtil` class SHA-256
`2cffd915a76555a002fde4f717a5fad4fd72e093f948cb3e9eb801da48ec2dbc`, the exact
`starfarer.api.jar` SHA-256
`6ac6c78c6116946d487376426340d019938f986ceae1391ae1fa599e890e3185`, the application classloader,
all six reviewed descriptors, and one exact list-copy/iterator/callback shape in every method. Any
class, archive, loader, method, or instruction drift retains original bytecode. The candidate adds
no fields, retains no listeners or game objects, crosses no frame boundary, and touches no save
state.

The original four fixture tests and exact installed-archive test pass on Java 17. They verify explicit
opt-in, all-or-nothing shape admission, wrong-hash and second-rewrite fallback, pinned provenance,
one array snapshot per method, one array element load per loop, and absence of `ArrayList`
allocation and iterator calls after transformation.

## Live acceptance

One fresh Preflight-only `campaign-simulation-combat-1000dp` run enabled this candidate together
with the independent accepted AI Tweaks arc-capacity plan. The driver prepared 24 mirrored ships
and 520 DP per side, enabled 2x simulation speed, zoomed the viewport from 1,800 to 6,120 world
units, and completed all 34 steps. The exact listener plan registered one target, applied without
an evaluation problem, and the 30.003-second combat window completed without a gameplay fatal.
The run exited zero after publishing an exact PID/start-instant controller-stop receipt.

Within that window, stacks containing `CombatListenerUtil` carried 313 JFR allocation samples and
644.3 MiB of statistical weight. Every filtered sample was a required `Object[]` snapshot allocated
through `Arrays.copyOf`; sampled `ArrayList` objects and iterators were both zero. All six rewritten
range-query methods remained represented. This is the expected structural result, not evidence that
the necessary snapshot array disappeared.

The focusless route dropped 1,989 inactive intervals and retained zero active combat frames in its
declared measurement window. Its frame report therefore cannot support an FPS, percentile, or
smoothness comparison. The machine reported no macOS thermal or performance warning immediately
before launch, but its physical warmth is still a confounder. A later foreground knob-off/knob-on
pair may estimate user-visible magnitude; it is not required to retain this bounded allocation
reduction as an opt-in.

Two earlier development observations were not used as clean acceptance runs. One exposed the now
retired AI Tweaks select-target boundary; another exposed controller-stop receipt ordering in the
automation harness. The unsafe AI Tweaks target was removed rather than masked, and the harness now
publishes a strict process-lifetime receipt immediately before terminal shutdown. The final run
contains neither confounder.

The compact measurements and hashes are retained in
[`data/2026-08-27-combat-listener-range-snapshot.json`](data/2026-08-27-combat-listener-range-snapshot.json).
The raw JFR and copied megabyte log tail are intentionally not committed and are pruned after this
checkpoint.

## Claim boundary

Acceptance rests on the exact semantic boundary, direct disappearance of the redundant sampled
allocation classes, clean live execution, and fail-closed tests. The plan remains explicitly opt-in.
No FPS, percentile, startup-time, or cross-platform uplift is claimed.
