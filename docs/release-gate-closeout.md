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
  blocker status, merge order, collision control, moving `main` coordination, and explicit maintainer
  changes to the pre-freeze product state.
- [Release readiness](release-readiness.md) mirrors the current candidate gate.
- [Public beta roadmap](beta-roadmap.md) explains the current operational sequence without relying on
  this historical runbook.
- [#950](https://github.com/teamleaderleo/preflight/issues/950) records the maintainer's publication
  decision. The 2026-08-07 Fractal Softworks request is courtesy correspondence and a reply is outside
  the publication gate.
- [#720](https://github.com/teamleaderleo/preflight/issues/720) records the completed
  `release-signing` Environment setup, private signed rehearsals, and updater-signing secret
  migration.
- [#607](https://github.com/teamleaderleo/preflight/issues/607) is closed `not planned`: the owner
  intentionally removed the repository rulesets/branch protection on 2026-08-21. It is retained as
  the decision record, not as a current release gate.

Use the live issue bodies for repository settings and operator steps. They can change independently
of documentation commits.

## Current beta gate

Source and rendered-UI convergence are complete for the first beta. The remaining work is the
operational candidate sequence owned by #652: select and freeze one immutable tagged candidate
generation, exercise that exact package on native Windows and x86-64 Linux, retain package-bound
benchmark/lifecycle/exact-tag canary evidence, and complete #965's hands-on report-intake
cancel/retry/delete sequence.

A private rehearsal, green source tree, or complete candidate does not itself authorize making the
first public beta GitHub release and downloadable packages live. Final candidate creation and public
release remain explicit maintainer decisions. After freeze, a demonstrated candidate failure or
explicit maintainer decision can create new bytes; affected package-dependent evidence must then be
regenerated for the new candidate generation.

Post-RC hardening, research, routine dependency work, and prototypes stay outside the candidate unless
#652 promotes them through a concrete failure or maintainer decision.

## Historical runbook

The complete 2026-08-19 plan is retained at the immutable snapshot below. It includes the old
release-signing migration sequence, ruleset bootstrap proposal, singleton closeout, candidate-byte
workflow, source/package audit steps, and the superseded blocker wording. Read it as a dated planning
record rather than current operator instructions.

[Historical 2026-08-19 closeout snapshot](https://github.com/teamleaderleo/preflight/blob/6bee58e44264d222fded7ad51c04caa013d360be/docs/release-gate-closeout.md)
