# Codebase improvement review, 2026-09-06

This is a broad engineering review of the desktop, CLI, core data formats, runtime agent,
report intake and build/operator tooling. It is not a claim that every line has been reviewed,
nor an exhaustive security assessment. Source began at `22e28d92`; native observations and
layout fixes are recorded separately. Existing research PRs and #1251 remain separate work.

## Highest-priority findings

1. **Settings can become unscrollable because of hidden page content. Confirmed and fixed in
   the layout slice.** The generic settled-Speed `:has()` selector matched a hidden React Activity
   inside the Game settings viewport. Both native Linux and browser observations exposed the
   unreachable footer. Keep page-level layout rules scoped to the active page class.
2. **Large-window layout and ship clipping. Confirmed and fixed in the layout slice.** The main
   width cap wasted available space; the camera multiplier did not bound the entire rotating hull.
   The fixes use the available width and a cached orientation-independent bound, retaining zoom
   and avoiding per-frame size changes.
3. **Stop and other mutations can still block native UI dispatch. Confirmed source path; follow-up
   requires coordinated design and native verification.** `src-tauri/src/lib.rs::stop_game` uses a
   synchronous Tauri command while waiting for the engine's 20-second graceful-stop operation.
   `engine.rs::update_launch_settings` and other mutation handlers hold the operation mutex across
   child-process work. Moving annotations alone is insufficient: quit and launch must not block
   behind that mutex or race a write. Use explicit operation reservations, short lock sections,
   background work, and bounded quit behavior, with delayed-child and concurrent-action tests.
4. **Focus refresh repeats expensive inspection. Confirmed call graph; benefit needs measurement.**
   `useProfiles.ts` starts profile, mod-readiness and cache refreshes on every window focus.
   After initial Home sharing, these use separate engine requests. `DesktopHomeStateCommand`
   waits for cache inspection alongside lightweight settings/profile reads; the previous single
   Mac Home read took 5.923 s. Coalesce focus refreshes, avoid overlapping generations, and measure
   each section before choosing an invalidation policy. Never trust stale prepared-data identity
   merely to reduce this delay.

## Reliability and maintenance follow-ups

- **Preference storage failures:** `useTheme.ts` reads/writes WebView storage without the fallback
  handling already used by `useInstrumentView.ts` and installation persistence. A thrown read
  during initial render can prevent the app from opening. Centralize best-effort cosmetic
  preferences while retaining explicit failure reporting for consequential data. Validate denied
  reads and full-store writes; do not silently claim a preference was persisted.
- **Animation recovery lifecycle:** `FlightInstrument.tsx` requests an extra focus-recovery frame
  outside the tracked animation frame, and handles visibility changes without checking whether the
  document became hidden. The delayed timers guard disposal, but that extra frame does not.
  Track/cancel it and test hidden/unmounted callbacks before adding more recovery timers.
- **Test process cleanup:** timeout fixtures using a shell followed by `sleep 600` can leave the
  sleep descendant after the shell is killed. One such process was observed and explicitly stopped
  in the previous verification. Direct stand-ins should use `exec`; tests that intentionally cover
  inherited pipes should own and clean their descendant explicitly.
- **CSS ownership:** the main stylesheet and successive override layers contain page behavior,
  visual tokens and instrument composition together. The hidden-Activity defect is concrete
  evidence for consolidating one page family at a time. Keep rendered states and keyboard
  reachability as the acceptance gate; avoid a repository-wide cosmetic rewrite.
- **Bridge ownership:** `bridge.ts` combines native transport, development fixtures and initial-read
  coordination. Separating a typed preview backend from native transport would make stale-result
  and request-sharing rules easier to maintain. Preserve the existing ordering tests and add no
  persistent cache without an explicit validity model.

## Areas reviewed without an immediate change

- Core hashing streams file content; directory fingerprints intentionally include names, sizes
  and content rather than timestamps. Broadly replacing this with metadata caching would weaken
  correctness. Resource-index workers are bounded; profile sizes and traversal costs should drive
  any tuning, not simply adding more threads.
- Prepared texture/audio runtime paths already have exact compatibility gates and lifetime guards.
  Large agent registries are not by themselves evidence for a risky refactor. Keep changes isolated
  behind equivalent behavior and real installed-game evidence.
- Report intake has a configured 6 MiB upload cap, archive validation, authenticated operation
  grants and quota state. It materializes a bounded archive for validation; no memory or latency
  problem was measured here, so streaming is a candidate only after profiling.
- Packaging and operator flows must retain exact source/artifact identity and distinguish
  automated installation smoke checks from actual GUI interaction. A full package rebuild is
  unnecessary for documentation, and repeat game benchmarks need a concrete experimental reason.

The next improvement should address native mutation responsiveness, followed by coalesced focus
refresh and the small lifecycle/storage defects. Each requires its own acceptance evidence;
the list above does not claim those follow-ups are implemented.
