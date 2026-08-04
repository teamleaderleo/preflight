# AI Tweaks recomputes one engagement range five times per target selection

**Date:** 2026-08-05

**Install:** Starsector 0.98a-RC8, AI Tweaks 2.2.10, current mod profile

**Status:** exact adapter, executable behavior test, installed-archive test, and full repository
verification pass. Live combat counters and sampling remain pending.

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

## Verification before launch

- an executable woven fixture changes the backing weapon range after construction and proves that
  all four later uses retain the single constructor snapshot;
- the fixture observes one underlying range call and one runtime snapshot rather than five calls;
- changed hashes, changed call counts, and a second rewrite fail closed;
- the installed-archive integration test transforms the exact local AI Tweaks 2.2.10 class and
  confirms one original range call plus one snapshot hook remain;
- full `mvn verify` passes with the exact installed Starsector core, sound, and AI Tweaks archives.

No frame-time or speed claim is made before the live combat pilot. The shutdown report records
installation and the number of target-selection snapshots, and JFR can independently confirm
whether the four removed call sites disappear from `SelectTarget` stacks.
