# Release readiness

**Status:** operational candidate execution; source and rendered-UI convergence complete

## TL;DR: what's actually left?

The product/source/UI work is converged enough for the first beta. The remaining gate is about **one exact package generation**:

1. select/freeze the release source and authorize one tagged candidate;
2. exercise that exact package with a licensed Starsector install on native Windows;
3. exercise the same candidate on native x86-64 Linux;
4. collect the package-bound startup/lifecycle/update/report evidence;
5. run the hands-on support-upload cancel/retry/delete canary;
6. explicitly authorize publication.

That's the beta gate.

[#652](https://github.com/teamleaderleo/preflight/issues/652) owns moving coordination. This document is the readable checklist/mirror, so it intentionally doesn't try to become a second live issue tracker.

## Active beta work

- [x] **Release-signing setup and private rehearsals.** The signing environment/private package machinery has been exercised. Those rehearsals prove the machinery, not final release bytes.
- [ ] **Select and freeze one release source.** The maintainer separately chooses the accepted source and authorizes the immutable tagged candidate generation.
- [ ] **Native Windows real-game acceptance.** Exercise discovery/setup, preparation, repeated launch, campaign/combat, adapter health/fallback, and removal with the frozen package.
- [ ] **Native x86-64 Linux real-game acceptance.** Run the corresponding path with that same frozen candidate generation.
- [ ] **Package-bound evidence.** Benchmark the engine extracted from the accepted package and retain the package/bundle identity with the result. Complete the tagged lifecycle/update/report receipts against the same generation.
- [ ] **Hands-on packaged report-intake canary.** Review the disclosed support ZIP, cancel a partial upload, prove server cleanup/local retention, retry the same ZIP, verify the accepted size/SHA/receipt, then delete it and prove cleanup.
- [ ] **Publication authorization.** A successful candidate still needs the maintainer's explicit decision to make the first public release/downloads live.

A demonstrated candidate failure or explicit maintainer decision can create new source bytes. If that happens, package-dependent evidence follows the new candidate generation.

## Publication decisions already made

- [x] **Preflight** is the public product/repository/application name.
- [x] Preflight remains independent/unofficial and redistributes no Starsector content.
- [x] The 2026-08-07 Fractal Softworks request is retained as courtesy correspondence; waiting for a reply isn't part of the publication gate. See [#950](https://github.com/teamleaderleo/preflight/issues/950).
- [x] Paid Apple Developer ID/notarization and Windows Authenticode are outside the first-beta gate. The beta can publish checksums and accurate OS-warning instructions.
- [x] Repository rulesets/branch protection aren't part of the current release gate. The exact candidate identity is verified at the release-signing/tag boundary instead.
- [x] Failed-run support stays manual for the first beta. The player reviews and explicitly sends a bounded support ZIP; there's no automatic failed-run upload path.

The retained correspondence is in [fractal-permission-request.md](fractal-permission-request.md).

## What is already established?

The detailed evidence lives in the linked docs/archive. At a high level:

- desktop setup/preparation/profile/settings/update/diagnostics/cleanup/removal/benchmark flows exist;
- rendered UI acceptance has covered the supported desktop sizes and important failure/recovery states;
- hosted package jobs create/verify the reviewed Windows/macOS/Linux artifact families and their embedded engine/legal/checksum/metadata/capability material;
- hosted package lifecycle rehearsal has covered install/upgrade/rollback/removal across the three platforms;
- the startup benchmark can operate in package-bound candidate mode and refuses checkout fallback there;
- the report-intake service/canary path is implemented;
- current development startup uses the selected **112.17s → 13.69s** headline on the documented 83-mod development setup;
- the historical same-profile A/B campaign remains **89.00s ordinary → 15.53s accelerated median** for the separate attribution question it measured.

The exact public package still needs its own retained package-bound result.

## Useful release references

Use the smallest document that answers the question:

- **moving gate / ownership:** [#652](https://github.com/teamleaderleo/preflight/issues/652)
- **startup measurement:** [Startup benchmark](startup-benchmark.md)
- **native-platform claim boundary:** [Cross-platform evidence plan](cross-platform-evidence-plan.md)
- **package install/update/removal:** [Package lifecycle rehearsal](package-lifecycle-rehearsal.md)
- **report upload:** [Packaged report canary](evidence/2026-08-08-packaged-report-canary.md)
- **performance chronology:** [Optimization history](optimization-history.md)

## Outside the first-beta gate

Post-RC hardening, prototypes, broader compatibility expansion, research, and nice-to-have coverage don't become blockers merely because they're implementable.

A concrete candidate failure or explicit maintainer decision can promote work through #652.

Broader mod/audio/visual/simulation/save/reload/frame-time/display-server coverage can continue during beta. Keep each claim scoped to the platform/profile actually exercised.

Release notes, checksums, SBOM/notices, privacy/install/removal text, and public posts are finalized against the accepted package generation rather than against a moving development checkout.

## Historical detail

Older versions of this page carried the long pre-cleanup checklist, private rehearsal IDs, and several completed/parked workstreams inline. That history remains available through Git when an audit needs it; it doesn't need to stay in the current front layer.

[Historical 2026-08-20 pre-cleanup snapshot](https://github.com/teamleaderleo/preflight/blob/6bee58e44264d222fded7ad51c04caa013d360be/docs/release-readiness.md)
