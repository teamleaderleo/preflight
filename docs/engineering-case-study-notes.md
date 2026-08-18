# Engineering case-study notes

**Status:** working source material; retained on `main` during RC hardening on 2026-08-18  
**Purpose:** preserve engineering stories that are easy to lose inside issues, reviews, evidence files, and release work. This document is intentionally allowed to lag live issue/PR status. Treat status lines inside individual stories as historical snapshots unless they explicitly say otherwise; use #652 and current GitHub state for release truth. This is source material for a later portfolio/case-study page, interviews, README compression, release retrospectives, and project handoffs. It is **not** a public performance claim and is not a substitute for exact release-candidate evidence.

Preflight has accumulated enough technical depth that the difficult part is no longer finding impressive work. The difficult part is compressing it into a few stories that another engineer can understand quickly and then drill into.

## Retained story index

The original draft PR #681 and its review thread contain longer versions and fact-check history. Preserve these stories when this note is expanded from settled implementation.

### 1. The launch was waiting for two audio threads

**Useful for:** performance engineering, profiling, concurrency judgment, exact-input caching, knowing when *not* to parallelize.

Profiling showed the main launch path spending a multi-second interval waiting while two worker threads decoded audio. The tempting patch was to widen Starsector's executor. That was rejected because completion fed shared structures/OpenAL state whose concurrency contract was not owned by Preflight. Instead, Preflight moved the deterministic expensive decode work out of the launch path, keyed reuse to exact encoded bytes and decoder identity, and retained equivalence evidence against the installed game classes.

This is a good story because the performance win came from changing **where deterministic work happened**, rather than increasing concurrency at an unsafe boundary.

### 2. `stat` before and after hashing did not prove which file was hashed

**Useful for:** filesystem semantics, TOCTOU/ABA races, cache correctness, adversarial testing, proof lifecycle.

The original direct-provider proof inspected pathname metadata, hashed the pathname, then inspected it again. An A → B → A replacement can make both endpoint inspections describe A while the bytes consumed during the interval belong to B.

#650 moved hashing onto a stable file-object proof instead of assuming two pathname observations bound the read. Review then found several further problems in succession:

- the first proof location could be on the wrong filesystem for hard links;
- reusable evidence could become visible before proof cleanup completed;
- a fake cleanup-failure test did not leave real residue;
- real leaked proof residue could itself enter a recursive resource scan.

The final implementation keeps proof residue outside every indexed resource root, including nested-root cases, and a failed proof lifecycle cannot seed reusable exact evidence. #608 is closed after #642 + #650.

This is one of the strongest examples in the project of a plausible correctness proof becoming stronger under adversarial review.

### 3. Atomic publication was not create-if-absent profile semantics

**Useful for:** transactional filesystem semantics, reviewed mutations, optimistic concurrency, commit points, external writers.

Profile duplication exposed three separate questions that an ordinary "atomic write" does not answer:

- Is the source still the exact record the player reviewed?
- Is the destination name still free at the actual commit point?
- If publication committed but staging cleanup failed, what outcome should the caller be told?

#649 bound confirmation to the reviewed persisted record, made destination publication create-if-absent/no-overwrite, and separated public commit truth from post-commit cleanup. An external writer that claims the destination first wins and is preserved; cleanup failure after successful publication does not falsely report that the duplicate failed.

The broader lesson is that atomicity, ownership, optimistic concurrency, and committed-outcome semantics are different properties.

### 4. Destructive actions need ownership, not familiar paths

**Useful for:** uninstallers, symlink/junction safety, destructive authority, filesystem ownership, release engineering.

As Preflight gained install/update/removal behavior, "this is the path where our file normally lives" stopped being acceptable evidence for destructive actions. The codebase progressively moved toward exact Preflight-owned generation proof, symlink/alias refusal, create-if-absent publication, quarantine, re-proof, and rollback that preserves newer external winners.

#694 settled launcher-install publication ownership. #733 is the canonical launcher-removal continuation, using the shared `IntegrationMutation` authority rather than a second removal-only model.

This story is useful because it shows the shift from ordinary filesystem code to **destructive authority**: consequence determines how much evidence the program needs before acting.

### 5. Benchmark evidence and a personal-best trophy are different products

**Useful for:** measurement semantics, product design around evidence, avoiding misleading performance dashboards.

A historical best startup can remain enjoyable and numerically true even after the current setup changes. It simply stops being evidence about the current setup.

#657/#700 therefore separate:

- durable personal-best/history;
- latest/current controlled benchmark evidence;
- comparison identity strong enough to support any aggregate claim.

The earlier profile-only cumulative savings claim was retired because ordinary launches did not carry the controlled benchmark's full comparison identity. Faster / About the same / Slower are explicit states, so a regression cannot become `0.8× faster` or `-25% better` copy.

The useful lesson is not "delete stale numbers." It is to label the epistemic role of a number correctly.

### 6. The important UI bugs lived between states

**Useful for:** frontend/product engineering, state machines, progressive disclosure, cross-page authority, recovery UX.

#653/#654–#656 changed review from "does this screen look clean?" to following player errands through state transitions. That exposed issues that were hard to see component-by-component:

- hidden dirty launch settings changed what the visible Launch action would do;
- launch identity disappeared precisely when preparation made identity more consequential;
- cached saved-profile naming could survive external mod changes;
- prior-run/recovery actions could lose visual priority to idle presentation.

#651 merged with `Options · changed`, launch identity through preparation/recovery/retry, conservative `Current mod setup` naming when unique saved-profile identity cannot be justified, and deterministic recovery/focus priority.

The broader lesson is that local component correctness does not imply journey correctness.

### 7. A background support feature accidentally became a foreground blocker

**Useful for:** lifecycle policy, background work, native authority, privacy/credential durability.

Automatic failed-run reporting had an explicit product contract: it should never delay the player's recovery/launch work. Reusing a global `reportUploading` workflow state violated that contract.

The fix grew into a deeper lifecycle redesign under #703:

- automatic upload becomes background-owned rather than app-wide foreground ownership;
- deletion authority moves native-side per case;
- multiple live report cases remain independently actionable;
- exact-run dedup becomes atomic rather than renderer-local read/modify/write;
- upload consumes verified immutable ZIP bytes;
- accepted authority must be durable before renderer success;
- expiration is native-owned.

This story remains **active**. Current review is still closing secret-bearing path ancestry, clear/publication lifetime, and all-data-removal/deletion-authority boundaries. Do not turn it into a finished success story until #703 settles.

### 8. Exact packaged-candidate evidence is a different claim from checkout evidence

**Useful for:** release engineering, reproducible claims, benchmark provenance, package boundaries.

Preflight has strong development benchmark results, but the release process deliberately refuses to treat a checkout JAR as proof for the distributed package. The public startup claim must be tied to exact packaged candidate bytes, adjacent bundle identity, the selected profile/settings/runtime, and a retained paired receipt.

The same discipline appears in package lifecycle tests, capability/source locks, report-intake canaries, checksums/SBOMs, and source/package-content audits.

This is the story that turns "I benchmarked my code" into "I can justify what the shipped artifact actually does."

## Current settled corrections worth remembering

- #650 merged; provider proof cleanup/indexing is no longer an open blocker.
- #649 merged; duplication's source binding, no-overwrite publication, and committed outcome are settled.
- #651 merged; its important journey corrections are now product behavior rather than review hypotheses.
- #700 merged; personal-best and latest controlled evidence are distinct.
- #712 merged for stale Home run-recovery applicability; #731 records the remaining no-prepared-fingerprint edge.
- #694 merged for launcher-install publication ownership; #733 owns exact-generation removal.
- #709 merged for exact-generation destructive named-profile mutation; #725/#726 continue create/update and durable pre-marker commit evidence.
- #724 merged the RC global launch-settings Apply contract after #690's external-writer/current-value work.
- #735 merged the selected prepared-texture-pack integrity design: index-authenticated per-entry CRC32C plus loose-SPFT verification during packing, instead of the competing whole-pack/per-hit SHA approaches.

## System snapshot

The desktop product is split across three main layers:

- React/Vite renderer for the player-facing application;
- Tauri/Rust host for native process/filesystem/lifecycle boundaries;
- bundled Java engine and Java agent for Starsector inspection, preparation, launch, evidence, and runtime optimization.

The repository also contains benchmark/probe tooling, synthetic workloads, package/release verification, report-intake code, and a substantial deterministic regression suite.

## Public case-study direction

A useful portfolio-level thesis is roughly:

> Preflight started as an attempt to reduce heavily modded Starsector startup time. It became a cross-platform performance launcher whose optimizations, caches, mutations, process lifecycle, measurements, removal behavior, and UI repeatedly have to answer the same question: what can the program actually prove, and what should it do when that proof is unavailable?

The final public case study probably needs only four or five lead stories, with the rest available for drill-down. Strong candidates:

- audio profiling → rejected unsafe parallelism → exact prepared work;
- provider A → B → A evidence race → progressively stronger proof under review;
- profile/launcher commit semantics → atomicity, create-if-absent, external writers, exact-generation destructive authority;
- benchmark/UI evidence semantics → truthful measurement as a product problem;
- release engineering → development measurements converted into claims bound to exact packaged candidate bytes.

## Interview questions worth preparing

- Why did you prepare decoded audio instead of widening Starsector's executor?
- Why does `stat -> hash -> stat` fail to prove which bytes were hashed?
- What is the difference between atomic replacement and create-if-absent publication?
- How do you define the commit point of a filesystem mutation when cleanup can fail afterward?
- Why is a process-local operation lease insufficient protection against external writers?
- How can an uninstaller prove it owns what it is about to delete?
- Why can a benchmark result remain a valid personal best but become invalid evidence for the current setup?
- Give an example where every UI component was locally reasonable but the end-to-end transition was wrong.
- Why is checkout benchmark evidence insufficient for the release claim?
- What did adversarial review find after you thought a problem was already solved?

## Maintenance rule

Do not rewrite these stories into a frictionless success narrative. Preserve:

1. the initial plausible model;
2. the concrete counterexample that broke it;
3. why a tempting fix was rejected;
4. the revised invariant;
5. the regression/evidence that now supports it;
6. any remaining limitation.

That sequence is the useful engineering evidence.
