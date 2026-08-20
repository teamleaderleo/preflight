# Historical implementation handoff

**Superseded:** 2026-08-20

This document used to be the single living implementation handoff. Its top-level release direction
was written on 2026-08-07 and later accumulated completed work, superseded priorities, old operator
advice, and a Fractal-response publication gate. It is now a historical engineering ledger rather
than a dispatch surface.

Use these current owners instead:

- [#652](https://github.com/teamleaderleo/preflight/issues/652) — live RC/release convergence,
  current blocker status, merge order, collision control, and moving `main` coordination;
- [Release readiness](release-readiness.md) — documentation mirror of #652's four active
  candidate/platform blockers;
- [Product contract](product-contract.md) — current product, compatibility, cache, update, and
  support-upload boundary;
- [#950](https://github.com/teamleaderleo/preflight/issues/950) — 2026-08-20 publication decision,
  including the Fractal Softworks request as courtesy correspondence rather than a release gate.

Future agents should query live issue/branch state before acting. Do not copy blocker lists, moving
SHAs, merge queues, or “next” work from the historical handoff.

The complete pre-cleanup ledger remains available at the immutable snapshot below. Its measurements,
implementation notes, rejected paths, and chronology remain useful evidence when read with their
recorded dates.

[Historical full handoff at `main@6bee58e`](https://github.com/teamleaderleo/preflight/blob/6bee58e44264d222fded7ad51c04caa013d360be/docs/next-llm-handoff.md)

Dated evidence remains under [`docs/evidence/`](evidence/). Keep new durable measurements and
one-time decisions there; keep live dispatch in the current owner issues.
