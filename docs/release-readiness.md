# Release readiness

**Status:** release-candidate execution; four candidate/platform blockers remain

**Updated:** 2026-08-20

This page mirrors the current beta gate. The live owner for blocker status, merge order, collision
control, and any promotion of a newly demonstrated candidate failure is
[#652](https://github.com/teamleaderleo/preflight/issues/652). The publication-policy decision is
recorded in [#950](https://github.com/teamleaderleo/preflight/issues/950).

Avoid pinning a moving `main` SHA in this document. Query the branch directly when an exact revision
is needed; #652 owns any temporary current-main note used for active coordination.

## Active beta blockers

These are the four candidate/platform tasks carried by #652:

- [ ] Exercise real-game installations on Windows and Linux.
- [ ] Freeze and exercise the complete hosted Windows, macOS, and Linux candidate from one accepted
  source revision.
- [ ] Run the startup benchmark on the **exact packaged candidate bytes** and retain the candidate
  identity with the result.
- [ ] Complete the packaged report-intake canary cancel/retry/delete sequence on the candidate.

Post-RC hardening, prototypes, compatibility expansion, repository administration, and research stay
in their own owner issues. A concrete candidate failure can promote work through #652; an open issue
or implementable hardening idea does not expand this list by itself.

## Publication policy — decided

- [x] **Preflight** is the public product, repository, and application name.
- [x] The Fractal Softworks permission request sent on 2026-08-07 is retained as courtesy
  correspondence. Waiting for a response is outside the publication gate.
- [x] Preflight remains an independent, unofficial project and redistributes no Starsector content.
- [x] Retain the existing descriptive-use attribution and unofficial-project disclaimer as the
  maintainer's publication position recorded in #950.
- [x] Keep paid Apple signing/notarization and Windows Authenticode outside the first-beta gate. The
  beta can publish checksums and accurate operating-system warning instructions.

The correspondence itself remains in
[fractal-permission-request.md](fractal-permission-request.md) for historical context.

## Candidate preparation already established

The detailed evidence remains in the linked documents and dated `docs/evidence/` records. In brief:

- the desktop first-run, preparation, profile, settings, update, diagnostics, cleanup, removal, and
  benchmark flows are implemented;
- the package pipeline assembles the reviewed Windows, macOS, and Linux artifacts and verifies their
  embedded engine, legal files, checksums, SBOM/dependency inventory, updater metadata, and packaged
  capability receipts;
- hosted package lifecycle rehearsal has exercised install, upgrade, rollback, and removal on all
  three platforms; the remaining lifecycle work is tied to the complete candidate bytes;
- the startup benchmark harness accepts a packaged engine with adjacent identity metadata and refuses
  checkout fallback in candidate mode;
- the production report-intake service and packaged upload path have completed earlier canaries; the
  remaining canary is the final candidate cancel/retry/delete sequence;
- the controlled development comparison remains 89.00 seconds ordinary versus 15.53 seconds with
  Preflight on the reviewed 83-mod profile, with a 15.25-second low. The packaged candidate result is
  still pending and will sit beside that development record.

Useful current references:

- [Startup benchmark](startup-benchmark.md)
- [Cross-platform evidence plan](cross-platform-evidence-plan.md)
- [Package lifecycle rehearsal](package-lifecycle-rehearsal.md)
- [Packaged report canary](evidence/2026-08-08-packaged-report-canary.md)
- [Optimization history](optimization-history.md)

## Supporting work outside the four-item blocker list

Repository and release-account administration has separate live owners. Use
[#607](https://github.com/teamleaderleo/preflight/issues/607) for branch/tag protection verification
and [#720](https://github.com/teamleaderleo/preflight/issues/720) for the `release-signing`
Environment, release-tag admission, and signing-secret migration. Their issue bodies own current
operator state; dated runbooks should not be used to infer live repository settings.

Compatibility work beyond the Windows/Linux real-game exercise continues during beta. Broader mod,
audio, visual, simulation, save/reload, frame-time, and display-server coverage expands supported
claims as evidence arrives. Keep those results scoped to the platform/profile actually exercised.

Release notes, checksums, SBOMs, notices, privacy/install/removal text, package-content verification,
and public posts are finalized against the accepted candidate generation. Existing pipeline and
verification work is retained as preparation evidence; candidate-specific receipts should name the
exact bytes they accepted.

## Historical checklist

The longer pre-cleanup checklist is preserved in Git history for audit context. It mixed completed
preparation, beta-expansion work, repository administration, and final-byte tasks under several open
checkbox headings, which made the apparent blocker count drift away from #652.

[Historical 2026-08-20 pre-cleanup snapshot](https://github.com/teamleaderleo/preflight/blob/6bee58e44264d222fded7ad51c04caa013d360be/docs/release-readiness.md)
