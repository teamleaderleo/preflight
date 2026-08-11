# Public beta roadmap

**Updated:** 2026-08-09

This is the working sequence from the current development build to a public beta. The detailed
pass/fail ledger remains [Release readiness](release-readiness.md); this page explains the order and
the product each phase is meant to leave behind.

## Product contract

The ordinary path has four steps: open Preflight, select Starsector if discovery didn't find it,
choose **Prepare and launch** once, then use **Launch Starsector** on later runs. Matching prepared
data is reused automatically. Profiles, storage controls, support export, updates, and removal stay
available without becoming setup requirements.

Preflight's main promise is simple: make the installed game faster and leave its files, mods, saves,
and settings recoverable. Every runtime change keeps an exact identity gate and the original
behavior as its fallback.

## 1. Finish the desktop state model

Exercise the complete interface against fixture states for first discovery, a cold profile, a ready
profile, low disk space, damaged prepared data, an unsupported update, a failed launch, and an
interrupted operation. Each disabled primary action needs a visible reason. Recovery should state
what happened, what remained unchanged, and the useful next action.

Finish the drafting-paper spacecraft icon, responsive light and dark layouts, keyboard behavior,
and the remaining copy pass. Home must fit the normal desktop window without scrolling. Longer
advanced work stays inside its workspace.

**Exit:** someone unfamiliar with the project can prepare, launch, change a profile, export support
data, update, and remove Preflight without reading the manual.

## 2. Ship a permission-free startup benchmark

The desktop owns an identity-checked normal then optimized pair through one coordinator. It waits
for the main-menu marker emitted by Preflight's runtime agent, closes only its exact process
lifetimes, checks the sealed installation/profile/launcher/runtime/settings identity, and emits one
versioned result. The benchmark doesn't use desktop input or Accessibility permission. It reports
startup before and after plus prepared-data disk cost. The packaged result accompanies the established
development record; optional campaign and combat measurement can follow as a separate advanced tool.

The result should show startup before and after, seconds saved, percentage change, exact identities,
and prepared-data disk cost. Alternating pairs, cooldowns, campaign/combat selection, frame-time
telemetry, and raw evidence remain optional diagnostic tools.

**Exit:** **Run benchmark** produces an honest, repeatable baseline-versus-Preflight result or a
specific refusal.

## 3. Close correctness and lifecycle gaps

Keep testing truncated, stale, missing, and incompatible cache artifacts; symlinked or unusual
filesystems; `ENOSPC`; killed preparation; restarts; stale PIDs; profile drift between preview and
apply; and every conflicting pair of launch, preparation, cleanup, profile, update, report, and
removal operations. Build outputs must carry enough provenance to reject a stale embedded engine.

The game scenario covers Fast Rendering, GraphicsLib, BoxUtil, a large mod profile, title and audio
transitions, campaign notifications, simulation, retreat, save/reload, and clean exit. Unknown game
or mod versions decline their affected transformations and continue with the original code.

**Exit:** failures remain scoped, explainable, and recoverable without touching the game install.

## 4. Freeze an immutable release candidate

Freeze one source revision and adapter catalog, then build macOS, Windows, and Linux packages from
it. Verify the embedded engine, update signature and origin, report origin, checksums, SBOM,
licenses, notices, privacy disclosure, install/removal instructions, and absence of proprietary game
or mod content. Exercise clean install, signed update, rejected signature, rollback, app-only removal,
and full Preflight-data removal. Any code change creates a new candidate.

**Exit:** each published byte maps to a reviewed source revision and a completed lifecycle result.

## 5. Gather platform evidence

macOS gets the complete local game and package lifecycle. VMware Fusion can establish Windows x64
package behavior under ARM emulation, including paths, discovery, preparation, launch construction,
update, and removal; it isn't native performance evidence. Hosted CI proves package and synthetic
contracts. Native Windows and Linux testers establish game, driver, display-server, and performance
claims. Linux starts with X11; Wayland limitations remain explicit.

**Exit:** each platform claim says whether its evidence came from hosted, emulated, or native work.

## 6. Benchmark the release candidate

Run the built-in normal-versus-Preflight benchmark on the exact candidate and retain its receipt.
Publish that result beside the established **101 seconds → 15.88 seconds** development progression.
An alternating multi-run campaign remains available when it answers a useful follow-up question; it
isn't required to make the existing progression real.

**Exit:** the exact distributed package has a retained benchmark result and the development record
keeps its stated machine and profile context.

## 7. Finish presentation and distribution

Capture final screenshots after the interface stops moving. Add platform download buttons, release
download counts, checksum and OS-warning instructions, update/rollback/removal/privacy/support
pages, and a readable optimization history. Forum, Reddit, README, and release notes use the same
reviewed claims and state that Preflight is unofficial, needs a legitimate Starsector installation,
falls back on uncertainty, and never uploads automatically.

Fractal Softworks' reply remains the external publication gate. It doesn't block the engineering
and packaging work above.

## After the first beta

Further startup work continues only when measurement shows a useful target. Gameplay work starts
with frame spikes and throughput evidence, then exact mod-specific plans. Additional storage modes,
signed compatibility advisories, community fixtures, and upstream patches follow the same identity,
fallback, and evidence rules.

Another startup record, perfect 60 FPS on every mod profile, paid platform signing, a complete mod
manager, a native game rewrite, Wayland automation, automatic telemetry, and advance support for
unknown future releases aren't first-beta requirements.
