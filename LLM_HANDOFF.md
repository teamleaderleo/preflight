# Preflight — LLM handoff

Read these in order before changing code:

1. [Release readiness](docs/release-readiness.md) — the maintained public-release blocker list.
2. [The latest maintenance checkpoint](docs/evidence/2026-08-16-maintenance-checkpoint.md) — a point-in-time summary after #488 and the August backlog cleanup.
3. [The engineering handoff/chronology](docs/next-llm-handoff.md) — detailed implementation history and evidence. Older priority language in this long ledger may have been completed or superseded; check current `main` and the latest issue status before treating it as work.
4. The open issues and newest reports under [docs/evidence/](docs/evidence/).

For the current prepared-pixel operator gate, use the copy-paste-safe [contract-check command](docs/prepared-pixel-contract-check-now.md). It forces a clean rebuild, verifies the checker is packaged, validates the real Starsector path, and preserves the checker exit status through `tee`.

Do not maintain project state in this pointer file. Dated point-in-time snapshots belong under `docs/evidence/`; keeping multiple live copies of project state caused the documents to drift apart and disagree about the merged baseline.
