# Historical release-gate closeout plan

**Superseded:** 2026-08-20

The 2026-08-19 closeout plan mixed release-candidate tasks, repository administration, signing
configuration, and moving repository state in one runbook. It also pinned old `main` SHAs and carried
the Fractal Softworks response as an owner-only blocker. Those live-state instructions have been
superseded.

This file now preserves the old plan as historical context and points operators to the current
owners.

## Current owners

- [#652](https://github.com/teamleaderleo/preflight/issues/652) owns live release convergence,
  blocker status, merge order, collision control, and moving `main` coordination.
- [Release readiness](release-readiness.md) mirrors #652's four active candidate/platform blockers.
- [#950](https://github.com/teamleaderleo/preflight/issues/950) records the maintainer's publication
  decision. The 2026-08-07 Fractal Softworks request is courtesy correspondence and a reply is outside
  the publication gate.
- [#607](https://github.com/teamleaderleo/preflight/issues/607) owns current `main` and release-tag
  protection verification. Its 2026-08-20 state already supersedes the old blanket statement that
  `main` is unprotected.
- [#720](https://github.com/teamleaderleo/preflight/issues/720) owns the `release-signing`
  Environment, release-tag admission, and updater-signing secret migration.

Use the live issue bodies for repository settings and operator steps. They can change independently
of documentation commits.

## Current beta gate

The release coordination board currently carries four candidate/platform tasks:

1. real-game Windows and Linux installation exercise;
2. complete hosted Windows/macOS/Linux candidate freeze and exercise;
3. startup benchmark on the exact packaged candidate bytes; and
4. packaged report-intake cancel/retry/delete canary.

Repository administration, signing setup, post-RC hardening, and research remain in their own owner
lanes. #652 decides whether a concrete candidate failure changes release priority.

## Historical runbook

The complete 2026-08-19 plan is retained at the immutable snapshot below. It includes the old
release-signing migration sequence, ruleset bootstrap proposal, singleton closeout, candidate-byte
workflow, source/package audit steps, and the superseded blocker wording. Read it as a dated planning
record rather than current operator instructions.

[Historical 2026-08-19 closeout snapshot](https://github.com/teamleaderleo/preflight/blob/6bee58e44264d222fded7ad51c04caa013d360be/docs/release-gate-closeout.md)
