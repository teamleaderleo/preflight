# Release readiness

**Status:** operational candidate execution; source and rendered-UI convergence complete

**Updated:** 2026-08-22

This page mirrors the current beta gate. The live owner for blocker status, freeze order, collision
control, and any promotion caused by a demonstrated candidate failure or explicit maintainer decision
is [#652](https://github.com/teamleaderleo/preflight/issues/652). The publication-policy decision is
recorded in [#950](https://github.com/teamleaderleo/preflight/issues/950).

Avoid pinning a moving `main` SHA in this document. Query the branch directly when an exact revision
is needed; #652 owns temporary current-main notes used for active coordination.

## Active beta work

Source and rendered-UI convergence are complete. The remaining first-beta work is operational and
must stay tied to one accepted candidate generation:

- [x] **Complete release-signing setup and private rehearsals (#720).** The Environment is
  configured and restricted to `main`. Distribution/lifecycle pairs `32527940046` + `32529367040`
  and post-cleanup `32530512574` + `32532048780` succeeded on Linux, macOS, and Windows. The two
  legacy repository updater-key secrets are removed while the Environment copies remain. These runs
  prove the private signing and package machinery only; they are not final release evidence.
- [ ] **Select the release source and authorize one immutable tagged candidate generation.** The
  maintainer must separately choose the exact accepted source and authorize the tag. Once source is
  frozen, unrelated source work stops. A demonstrated candidate failure or explicit maintainer
  decision can still create new bytes; affected package-dependent evidence must then follow the new
  candidate generation.
- [ ] **Exercise the frozen package on native Windows with a licensed game installation.** Cover
  detection/setup, preparation, repeated launch, campaign/combat, adapter health/fallback, and
  removal.
- [ ] **Exercise the same frozen package on native x86-64 Linux with a licensed game installation.**
  Run the corresponding acceptance path there.
- [ ] **Collect package-dependent evidence.** Run the #418 startup benchmark against the engine
  extracted from the accepted package bytes and retain the package/bundle identity with the result.
  Complete the tagged lifecycle/update receipts and the exact-tag production report canary required
  by #974/#818 against that same generation.
- [ ] **Complete the hands-on packaged report-intake canary (#965).** Review the disclosed support
  ZIP, cancel after a partial upload, prove remote cleanup while the local ZIP remains, retry the same
  ZIP, verify the accepted size/SHA and retained receipt, then delete it and prove cleanup.

A private rehearsal does not authorize a release tag, and a successful tagged candidate does not by
itself authorize making the first public beta GitHub release and downloadable packages live. Final
candidate creation and public release remain explicit maintainer decisions.

Post-RC hardening, prototypes, compatibility expansion, and research stay in their own owner issues.
A concrete candidate failure or explicit maintainer decision can promote work through #652; an open
issue or implementable idea does not expand the beta gate by itself.

## Publication policy — decided

- [x] **Preflight** is the public product, repository, and application name.
- [x] The Fractal Softworks permission request sent on 2026-08-07 is retained as courtesy
  correspondence. Waiting for a response is outside the publication gate.
- [x] Preflight remains an independent, unofficial project and redistributes no Starsector content.
- [x] Retain the existing descriptive-use attribution and unofficial-project disclaimer as the
  maintainer's publication position recorded in #950.
- [x] Keep paid Apple Developer ID/notarization and Windows Authenticode outside the first-beta gate.
  The beta can publish checksums and accurate operating-system warning instructions.
- [x] Repository rulesets are intentionally not part of the current release gate. The owner retired
  #607 on 2026-08-21. Before approving a tagged deployment, verify at the `release-signing`
  Environment boundary that the tag/commit is the intended frozen accepted `main` identity.

The Fractal correspondence itself remains in
[fractal-permission-request.md](fractal-permission-request.md) for historical context.

## Candidate preparation already established

The detailed evidence remains in the linked documents and dated `docs/evidence/` records. In brief:

- the desktop first-run, preparation, profile, settings, update, diagnostics, cleanup, removal, and
  benchmark flows are implemented;
- release-owner rendered acceptance of Home/Hangar, minimum-window workspaces, keyboard scrolling,
  failed-run attention layout, and the final custom Hangar hull selector/instrument treatment has
  completed against the supported desktop sizes;
- the package pipeline assembles and verifies the reviewed Windows, macOS, and Linux artifacts and
  checks their embedded engine, legal files, checksums, SBOM/dependency inventory, updater metadata,
  and packaged capability receipts;
- hosted package lifecycle rehearsal has exercised install, upgrade, rollback, and removal on all
  three platforms; the remaining lifecycle evidence belongs to the accepted candidate bytes;
- the startup benchmark harness accepts a packaged engine with adjacent identity metadata and refuses
  checkout fallback in candidate mode;
- the production report-intake service and tagged canary producer/consumer path are implemented; the
  remaining work is the final candidate evidence above, including the hands-on cancel/retry/delete
  sequence;
- the controlled development comparison remains 89.00 seconds ordinary versus 15.53 seconds with
  Preflight on the reviewed 83-mod profile, with a 15.25-second low. The packaged candidate result is
  still pending and will sit beside that development record.

Useful current references:

- [Startup benchmark](startup-benchmark.md)
- [Cross-platform evidence plan](cross-platform-evidence-plan.md)
- [Package lifecycle rehearsal](package-lifecycle-rehearsal.md)
- [Packaged report canary](evidence/2026-08-08-packaged-report-canary.md)
- [Optimization history](optimization-history.md)

## Work outside the candidate gate

[#720](https://github.com/teamleaderleo/preflight/issues/720) records the completed release-signing
administration and private rehearsals. #607 is closed `not planned` under the owner-selected
repository policy; do not recreate a ruleset unless that policy changes.

Compatibility work beyond the required native Windows/Linux exercise can continue during beta.
Broader mod, audio, visual, simulation, save/reload, frame-time, and display-server coverage expands
supported claims as evidence arrives. Keep those results scoped to the platform/profile actually
exercised.

Release notes, checksums, SBOMs, notices, privacy/install/removal text, package-content verification,
and public posts are finalized against the accepted candidate generation. Existing pipeline and
verification work remains useful preparation evidence; candidate-specific receipts should name the
package bytes they accepted.

## Historical checklist

The longer pre-cleanup checklist is preserved in Git history for audit context. It mixed completed
preparation, beta-expansion work, repository administration, and final-byte tasks under several open
checkbox headings, which made the apparent blocker count drift away from #652.

[Historical 2026-08-20 pre-cleanup snapshot](https://github.com/teamleaderleo/preflight/blob/6bee58e44264d222fded7ad51c04caa013d360be/docs/release-readiness.md)
