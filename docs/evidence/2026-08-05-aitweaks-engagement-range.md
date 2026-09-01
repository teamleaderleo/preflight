# AI Tweaks recomputes and repeatedly boxes fixed ranges during target selection

**Date:** 2026-08-05

**Install:** Starsector 0.98a-RC8, AI Tweaks 2.2.10, current mod profile

**Status:** retired after v4 reproduced the same null-receiver failure at the original
target-search field read

## Runtime lead

The `graphicslib-audio-v2-20260805-041804` recording contains 776 combat game-thread samples.
AI Tweaks' `AutofireAI` is on 51 samples. Six of those samples include
`WeaponHandle.getEngagementRange-impl`; the getter in turn performs derived-stat work including
`StatBonus.computeEffective`.

The exact AI Tweaks 2.2.10 source and installed bytecode show a narrow redundant boundary.
`AutofireAI.updateTarget` creates one short-lived `SelectTarget` and immediately calls `target()`.
That object computes `weapon.engagementRange * 1.5` in its constructor, then recomputes the same
engagement range four more times during the synchronous `target()` call. It has no reuse across
frames or target-selection events.

## Exact snapshot boundary

`aitweaks-select-target-engagement-range-v1` adds one final float to only the exact reviewed
`SelectTarget` class from the exact AI Tweaks 2.2.10 archive and mod URL classloader. It records the
constructor's first engagement-range result and substitutes that value at the four remaining call
sites. Changes between selection objects remain visible; only redundant recomputation inside one
selection is removed.

The transform requires all five reviewed calls, exactly one in the reviewed constructor and four in
non-static instance methods. Every call must consume the exact object's `weapon` field immediately
beforehand. Class hash, archive hash, bytecode version, loader, method, call count, field access, or
instruction-shape drift retains the original class.

AI Tweaks does not let its `URLClassLoader` define core classes normally. It reads each class,
applies two internal rewrite passes, then calls `defineClass` without a protection domain. The JVM
therefore reports no code source even though the custom loader contains exactly one URL. Preflight
now uses a sole `URLClassLoader` URL only when protection-domain source is absent, then applies the
ordinary mod path and archive-hash gates to that URL. Zero or multiple loader URLs remain unknown
and fail closed. The exact loader subclass is also pinned to AI Tweaks' `CoreLoader`.

## Verification before launch

- an executable woven fixture changes the backing weapon range after construction and proves that
  all four later uses retain the single constructor snapshot;
- the fixture observes one underlying range call and one runtime snapshot rather than five calls;
- changed hashes, changed call counts, and a second rewrite fail closed;
- the installed-archive integration test transforms the exact local AI Tweaks 2.2.10 class and
  confirms one original range call plus one snapshot hook remain;
- full `mvn verify` passes with the exact installed Starsector core, sound, and AI Tweaks archives.

The first live pilot `aitweaks-range-v1-20260805-043049` exited normally and transformed 31 other
targets with no decline or contained failure. This target reported `source kind UNKNOWN`, absent
archive hash, and the exact custom `CoreLoader`; `installed=false` and zero snapshots prove original
AI Tweaks code ran. That observation supplied the source-recovery boundary above rather than being
treated as permission to weaken source identity.

The shutdown report records installation and the number of target-selection snapshots. JFR provides
the independent call/allocation evidence below; the non-identical combat still does not support a
frame-time speed claim.

## Live v1 result and the next exposed cost

`aitweaks-audio-v2-20260805-044117` applied the exact v1 target and completed with **13,405**
selection snapshots. The derived engagement-range getter was reduced to its constructor call as
designed. This validates application and use, but the combat was not a controlled frame-time A/B.

The recording's JFR allocation samples exposed a separate Kotlin/JVM cost in the same exact class.
`SelectTarget.selectTarget` accepts a `Function2<CombatEntityAPI, Float, Boolean>`, so it executes
`Float.valueOf` for the fixed search range on every candidate ship or missile. Twelve allocation
samples rooted at those three `selectTarget` boxing sites carried **27,255,424 bytes of sampled
allocation weight**. This is sampled weight, not an exact byte census, but all twelve stacks identify
the same bytecode boundary.

`aitweaks-select-target-range-snapshot-v2` retains v1's primitive snapshot and adds two final boxed
fields: the selected weapon's engagement range and its already-fixed `targetSearchRange`. The
constructor boxes each once. The two primary/current checks and the candidate loop then load those
same `Float` objects instead of allocating at their `Function2` call boundary. Predicate order,
candidate order, primitive bit values, range changes between selection objects, and every downstream
AI calculation remain unchanged.

The v2 transform additionally requires exactly two engagement-range boxing sites and one
target-search boxing site with the reviewed instruction shapes. It still pins the exact class,
archive, loader, bytecode version, constructor, fields, and all five derived-range calls. The
executable fixture proves a post-construction backing-range change remains isolated to the next
selection object, verifies one getter call and two constructor boxes, and exact installed-JAR
verification confirms all three cached fields. Full `mvn verify` passes. A live follow-up should
confirm the `SelectTarget.selectTarget -> Float.valueOf` allocation stack disappears and collect a
settled combat FPS distribution without JFR if Rosetta profiling remains unstable.

## Live v2 result

`aitweaks-boxing-fps-v3-20260805-062901` completed normally and installed the exact v2 adapter. It
served **30,989** `SelectTarget` construction snapshots during campaign/combat play. All 33 reviewed
transforms applied, with zero declines and zero contained failures. This is strong compatibility and
use evidence: the transformed class linked, constructed, selected targets repeatedly, completed
combat, and shut down cleanly.

The run deliberately omitted JFR after the preceding Rosetta/HotSpot safepoint crash, so it does not
directly prove that the sampled `Float.valueOf` stack disappeared or support a controlled FPS speed
claim. The allocation removal remains structurally exact and executable-fixture verified; passive
frame telemetry is retained for future controlled A/B work.

## 2026-08-27 correction

A later, heavier deterministic combat run disproved the boxed target-search field's retained safety:
after its measurement window, the exact transformed class failed while reading
`preflight$targetSearchRangeBoxed`. The v4 plan removes that field and restores the original
`targetSearchRange -> Float.valueOf` boundary. It retains the primitive engagement-range and
weapon-location snapshots. The exact failure, narrowed correction, installed-class gate, clean
61,413-snapshot follow-up, and later v4 recurrence are recorded in
[the correction report](2026-08-27-aitweaks-boxed-search-range-correction.md). That recurrence
supersedes the v2 target-search boxing acceptance above and retires the entire target.
